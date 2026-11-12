package com.dshgo.app.notify

import com.dshgo.app.Prefs
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import com.dshgo.app.data.DshApi
import com.dshgo.app.data.WidgetState
import com.dshgo.app.notify.DshWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 盯着 DSH 的会话，谁跑完了就记一条。
 *
 * 完成信号来自事件流的 `api-session/status(sessionId, running)` —— `running` 由 true
 * 变 false 就是「这一轮回答结束」。这个判断只在**我们亲眼看见它从跑变成不跑**时才成立，
 * 启动时已经在跑的会话会先记进 [running] 基线，所以不会有「一启动就补发一堆通知」。
 *
 * 是个单例：Activity 和前台服务都读同一份，不会各推一条。
 */
object SessionWatcher {

    enum class Kind { Done, Error }

    data class Notice(
        val id: String,
        val sessionId: String,
        val title: String,
        val at: Long,
        val kind: Kind,
        val detail: String? = null,

        /**
         * 哪个工作区的会话 —— 两个工作区同时开着时，通知只说会话名分不清是哪边。
         */
        val space: String? = null,
    )

    private const val PREFS = "dsh_notices"
    private const val KEY_LIST = "list"
    private const val MAX_KEPT = 50

    /** 同一会话完成通知的去重窗口：WS 事件和快照差集几乎会同时报同一件事。 */
    private const val DONE_DEDUPE_MS = 15_000L

    private const val RECONNECT_MS = 5_000L

    /** 连续失败时的退避上限：太短会把服务器打醒，太长用户看不到更新。 */
    private const val RECONNECT_MAX_MS = 60_000L

    /** 事件流静默这么久就主动重连 —— 半开连接不会给 onDown，只能靠时间判断。 */
    private const val IDLE_RECONNECT_MS = 90_000L

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    /** 未读数，用来给铃铛画角标。 */
    private val _unseen = MutableStateFlow(0)
    val unseen: StateFlow<Int> = _unseen.asStateFlow()

    /** 事件流是否连着 —— 状态条上要如实显示。 */
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live.asStateFlow()

    /** 进程级作用域：不跟 Activity 生命周期绑定，否则界面一销毁事件流就断。 */
    /** WS 连续失败几次就退到轮询。1 次可能是网络抖动，2 次基本可以判定不支持。 */
    private const val WS_FAILURES_BEFORE_POLL = 2

    /** 轮询间隔。通知不比聊天，3 秒的延迟完全可接受。 */
    private const val POLL_MS = 3000L

    /** 收到会话事件后等这么久再校准 —— 一轮 turn 会连发好几个事件，逐個拉列表太浪费。 */
    private const val RESYNC_DEBOUNCE_MS = 1500L

    /** 轮询模式下每这么多次回头试一次 WS（× POLL_MS ≈ 1 分钟）。 */
    private const val WS_RETRY_EVERY = 20

    /** 单次 WS 连接的最长等待，超时也算失败。 */
    private const val OPEN_TIMEOUT_MS = 20000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var api: DshApi? = null
    private var stream: DshApi.EventStream? = null
    private var loop: Job? = null

    /**
     * 小组件的低频脉冲：WS 模式下 summary 只在「开跑/跑完」时变化，
     * 上下文占比和余额变的时候它不会动 —— 靠这条 15s 心跳把快照拉新。
     * 刷新本身有单飞+3s 节流，这里不需要再判断。
     */
    private var pulse: Job? = null

    /** 小组件快照心跳间隔：上下文占比/余额变化不产生 summary 事件，靠它兜。 */
    private const val WIDGET_PULSE_MS = 8_000L
    private var appContext: Context? = null

    /** id → 标题。事件里不带标题，靠启动快照 + `api-session/added` 维持。 */
    private val titles = HashMap<String, String>()

    /** 当前在跑的会话。 */
    private val running = HashSet<String>()

    /**
     * 上一次 widget 快照里「每条会话是否在跑」。
     *
     * 为什么另起一份：`session/list` 只返回**当前工作区**的会话；两个工作区
     * 同时开着时，另一个工作区跑完的会话根本不在差集里 —— 所以以前只收到
     * 一个工作区的完成通知（bug 2）。`/__dshgo__/widget/state` 覆盖全部工作区，
     * 每条会话还自带 title 与「待完成」（bug 3 也要靠它）。
     * null = 还没有基线，这时不能判「跑完了」，否则一启动就补发一堆通知。
     */
    private var snapRunning: Map<String, Boolean>? = null

    /** 最近一次成功快照里的工作区概况（常驻通知按它分行显示在跑数）。 */
    @Volatile
    private var snapSpaces: List<Summary.Space> = emptyList()

    /** 每条会话上次推完成通知的时间，防止两条通路重复弹。 */
    private val lastDoneAt = HashMap<String, Long>()

    /** 校准任务（去抖后用）。 */
    private var resyncJob: Job? = null

    /** 事件流当前连着的地址。地址一变就必须重开，见 [start]。 */
    private var currentUrl: String? = null

    /** 给通知显示用的主机名。 */
    private val currentHost: String
        get() = currentUrl?.removePrefix("http://")?.removePrefix("https://")?.trimEnd('/') ?: "DSH"

    /**
     * 最近一次事件流断开的原因，null 表示没出过错。
     *
     * 为什么要暴露出来：以前断了只把状态点变灰，**具体原因哪儿都看不到** ——
     * 用户只知道「没通知」，我只能猜。现在设置页直接显示。
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 最近收到会话事件的时间（0 = 一条都没收到过）。 */
    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt.asStateFlow()

    /**
     * 现在靠什么获知会话状态。
     *
     * DSH 的 WebSocket 事件流（`/api/remote.mux`）是 **0.1.2 之后**才有的；
     * 0.1.1 那一代根本没注册这个升级路由，WS 握手会被直接掐断。
     * 而 `session/list` 是各版本都有的普通 HTTP 接口 —— 所以 WS 连不上时
     * 退到轮询它，老版本也能收到通知。
     */
    private val _mode = MutableStateFlow(Mode.Stream)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    enum class Mode { Stream, Poll, Down }

    /**
     * 常驻通知要显示的东西 —— "撇一眼手机能知道什么"。
     *
     * 数据全都来自事件流，本来就有；以前只是没展示出来。
     */
    data class Summary(
        val running: Int = 0,
        val approvals: Int = 0,
        /** 会话里「待完成」的条目（最多 2 条）；有就优先显示在常驻通知上。 */
        val todo: List<String> = emptyList(),
        val lastDoneTitle: String? = null,
        val lastDoneAt: Long = 0L,
        /**
         * 最早那条待批准的**完整信息**。
         *
         * 为什么把它整个带出来，而不是只带个数量：小组件和常驻通知都要在上面
         * 放「允许/拒绝」按钮，而按钮的 PendingIntent 必须带上 eventId/clientId
         * 才回执得回去。只给数量的话，界面能显示"等你批准"却点不了 ——
         * 那正是用户第一次测试时碰到的问题。
         */
        val firstApproval: DshApi.ApprovalAsk? = null,

        /**
         * 每个工作区各自的在跑数。
         *
         * 原来只报总数：「2 个会话在跑」—— 两个工作区同时开时，用户分不清哪边在跑。
         */
        val spaces: List<Space> = emptyList(),
    ) {

        /** 一个工作区的在跑概况。 */
        data class Space(val title: String, val running: Int, val total: Int)
        /** 有没有可以就地处理的审批。 */
        val actionable: Boolean get() = firstApproval != null

        fun title(): String = when {
            approvals > 1 -> "$approvals 个操作等你批准"
            approvals == 1 -> "等你批准：${firstApproval?.toolName ?: "一个操作"}"
            running > 0 -> runningLabel()
            else -> "DSH 空闲"
        }

        /**
         * 「DSH 2 · second 1」—— 只列真有会话在跑的工作区。
         *
         * 为什么不写「3 个会话在跑」：两个工作区一起用时，用户想知道的是
         * 「是哪边在跑」，而不是总数。
         */
        private fun runningLabel(): String {
            val live = spaces.filter { it.running > 0 }
            if (live.isEmpty()) return "$running 个会话在跑"
            return live.joinToString(" · ") { "${it.title} ${it.running}" }
        }

        /** 常驻通知专用：不带「待完成」——待完成属于每条会话的完成通知。 */
        fun detailForForeground(host: String): String = buildString {
            firstApproval?.reason?.takeIf { it.isNotBlank() }?.let { append(it.take(60)) }
            if (isEmpty() && running > 0) append(runningLabel())
            if (lastDoneTitle != null && lastDoneAt > 0) {
                if (isNotEmpty()) append(" · ")
                append("刚完成「").append(lastDoneTitle.take(18)).append("」")
            }
            // host 自己就带着「已连接 192.168.31.221（局域网）」/「未连接 · 隧道没启动」，
            // 这里只负责连接符 —— 再套一层「已连接」会变成「已连接 未连接…」。
            append(if (isEmpty()) "" else " · ").append(host)
        }

        fun detail(host: String): String = buildString {
            // 待完成优先：用户要的是「接下来还要做什么」，而不是「刚做完了什么」。
            if (todo.isNotEmpty()) {
                append("待完成：")
                todo.forEachIndexed { i, t -> if (i > 0) append(" · "); append(t.take(40)) }
            }
            firstApproval?.reason?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append(" · ")
                append(it.take(60))
            }
            if (isEmpty() && running > 0) append("正在执行…")
            if (lastDoneTitle != null && lastDoneAt > 0) {
                if (isNotEmpty()) append(" · ")
                append("刚完成「").append(lastDoneTitle.take(18)).append("」")
            }
            if (isEmpty()) append(host).append(" · 会话跑完会通知你")
        }
    }

    /**
     * 正在等用户点头的审批。
     *
     * 只收**退到后台之后**来的那些 —— 前台时页面自己会把审批处理掉，
     * 原生这边抢答只会跟页面打架（两边都回执，谁生效看谁快，用户会看到
     * "我明明点了允许"却什么也没发生）。
     */
    private val _approvals = MutableStateFlow<List<DshApi.ApprovalAsk>>(emptyList())
    val approvals: StateFlow<List<DshApi.ApprovalAsk>> = _approvals.asStateFlow()

    /**
     * 当前在跑的会话 id 快照。
     *
     * 给小组件行用：事件流一收到「开始跑」，行就应该变绿，
     * 不该等到下一次 widget 快照拉取（最多 8 秒）才更新。
     */
    fun liveRunningIds(): Set<String> = synchronized(this) { running.toSet() }

    /** 常驻通知显示的内容。running / approvals / notices 任一变化都会重算。 */
    val summary: StateFlow<Summary> get() = _summary
    private val _summary = MutableStateFlow(Summary())

    /** App 是否在前台。MainActivity 的 onResume/onPause 维护。 */
    @Volatile
    var appForeground: Boolean = true

    @Synchronized
    fun start(context: Context, baseUrl: String) {
        // 已经在跑、且地址没变 → 什么都不用做。
        //
        // ★ 但**地址变了必须重开**：客户端是 `DshApi { baseUrl }` 把 URL 闭包
        // 捕获下来的，光改 prefs 不会影响已经在跑的那条流。原来的实现直接
        // `if (loop?.isActive == true) return`，于是用户改了入口地址之后，
        // 界面显示新地址、事件流却还连着旧的那台 —— 实测踩过：
        // 入口写 100.100.190.107，诊断里报的却是 192.168.0.21。
        if (loop?.isActive == true) {
            if (currentUrl == baseUrl) return
            stop()
        }
        currentUrl = baseUrl

        appContext = context.applicationContext
        load()

        val client = DshApi { baseUrl }
        api = client

        pulse = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WIDGET_PULSE_MS)
                appContext?.let { WidgetRefresher.refresh(it) }
            }
        }

        loop = scope.launch(Dispatchers.IO) {
            // 先拿一次全量：标题对照表 + 「已经在跑」的基线
            runCatching { client.snapshot() }.onSuccess { snap ->
                synchronized(this@SessionWatcher) {
                    titles.clear()
                    titles.putAll(snap.titles)
                    running.clear()
                    running.addAll(snap.running)
                }
                // ★ 必须刷新，否则这次基线等于白拿。
                //
                // 实测踩过：App 启动时那个会话已经在跑了，基线把它记进 running，
                // 但没有重算 summary —— 于是小组件一直显示默认值「DSH 空闲」，
                // 而一轮 turn 中间不会再发 api-session/status，整个 turn 卡片都是错的。
                refreshSummary()
            }

            var wsFailures = 0
            var pollTicks = 0

            while (isActive) {
                // ── 轮询模式 ──
                // 上游是 0.1.1 那一代时没有 WS 路由，硬连只会不停被掐断。
                // 退到轮询 session/list —— 这个接口各版本都有。
                if (wsFailures >= WS_FAILURES_BEFORE_POLL) {
                    _mode.value = Mode.Poll
                    pollTick(client)
                    pollTicks++
                    // 轮询模式下顺手刷小组件快照（≈每 30s 一次）。
                    // summary 没变化时不会走 refreshSummary，但上下文占比/余额会变，
                    // 靠这个低频节拍兜住；WidgetRefresher 自己还有单飞+3s 节流。
                    if (pollTicks % 10 == 0) appContext?.let { WidgetRefresher.refresh(it) }
                    // 每 POLL_MS × WS_RETRY_EVERY ≈ 1 分钟再试一次 WS：
                    // 上游升级之后能自动切回实时流。
                    if (pollTicks % WS_RETRY_EVERY != 0) {
                        delay(POLL_MS)
                        continue
                    }
                    wsFailures = 0
                }

                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                stream = client.openEvents(object : DshApi.EventSink {
                    override fun onReady() {
                        _live.value = true
                        _lastError.value = null
                        _mode.value = Mode.Stream
                    }

                    override fun onApproval(ask: DshApi.ApprovalAsk): Boolean {
                        // 无论前后台都要记账：小组件和常驻通知靠 _approvals 显示「N 个待审批」。
                        // 之前这里在前台直接 return false，结果前台出现的审批连计数都没有，
                        // 小组件自然一直是「N 个在跑」。
                        synchronized(this@SessionWatcher) {
                            // 已经在列表里：如果我们在后台（已接管），这次是重复投递，回 true 免得页面也弹
                            if (_approvals.value.any { it.eventId == ask.eventId }) return !appForeground
                            _approvals.value = _approvals.value + ask
                        }
                        _lastEventAt.value = System.currentTimeMillis()
                        // 只有后台才接管（发通知 + 允许/拒绝按钮）；
                        // 前台把决定权留给页面，抢答会和网页打架。
                        if (!appForeground) {
                            appContext?.let { NotificationCenter.postApproval(it, ask) }
                        }
                        refreshSummary()
                        return !appForeground
                    }

                    override fun onEmit(event: String, args: JSONArray) {
                        _lastEventAt.value = System.currentTimeMillis()
                        handle(event, args)
                        // 会话事件之后校准一次 running 集合（去抖：一轮 turn 会连发好几个事件）
                        if (event.startsWith("api-session/")) {
                            resyncJob?.cancel()
                            resyncJob = scope.launch {
                                delay(RESYNC_DEBOUNCE_MS)
                                resync(client)
                            }
                        }
                    }

                    override fun onDown(reason: String) {
                        _live.value = false
                        _lastError.value = buildString {
                            append(reason)
                            val extra = api?.lastCookieInfo
                            if (!extra.isNullOrEmpty()) append("｜").append(extra)
                        }
                        done.complete(Unit)
                    }
                })
                // 半开连接探测：连上后长时间没有任何事件（也没 onDown）时，主动断一次。
                // 实测移动网络切 Wi-Fi 后，旧 socket 会一直活着但永远不来数据。
                val watchdog = scope.launch {
                    while (isActive) {
                        delay(15_000)
                        val idle = System.currentTimeMillis() - _lastEventAt.value
                        if (_live.value && idle > IDLE_RECONNECT_MS) {
                            _lastError.value = "事件流 ${idle / 1000}s 没有数据，重连"
                            runCatching { stream?.close() }
                            done.complete(Unit)
                            break
                        }
                    }
                }
                val opened = withTimeoutOrNull(OPEN_TIMEOUT_MS) { done.await() } != null
                watchdog.cancel()
                runCatching { stream?.close() }
                stream = null
                // 连上过又断了 → 计入失败；一直连不上更要记。
                wsFailures++
                if (isActive) {
                    // 5s、10s、20s……封顶 60s，再加 0–1s 抖动：多台设备同时重连时不会齐步走。
                    val backoff = (RECONNECT_MS shl (wsFailures - 1).coerceIn(0, 4)).coerceAtMost(RECONNECT_MAX_MS)
                    delay(backoff + (0..1000L).random())
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        pulse?.cancel()
        pulse = null
        loop?.cancel()
        loop = null
        currentUrl = null
        runCatching { stream?.close() }
        stream = null
        api = null
        _live.value = false
    }

    /**
     * 替用户回答一条审批（从通知的动作按钮来）。
     *
     * 无论后端回执成不成功都要把它从待办里摘掉并撤掉通知 —— 否则会出现
     * "点过了但通知还在"，用户会反复点。
     */
    fun answer(ask: DshApi.ApprovalAsk, allow: Boolean) {
        synchronized(this) {
            _approvals.value = _approvals.value.filterNot { it.eventId == ask.eventId }
        }
        appContext?.let { NotificationCenter.cancelApproval(it, ask) }
        refreshSummary()
        runCatching { api?.answerApproval(ask, allow) }
    }

    /**
     * 用宿主上报的整份快照判定「谁跑完了」。
     *
     * 这是完成通知的**唯一权威来源**：快照覆盖全部工作区（session/list 只有
     * 当前工作区），并且每条会话自带标题与「待完成」—— 所以另一个工作区的
     * 会话跑完会通知，通知正文里也有待完成。
     */
    fun onSnapshot(state: WidgetState) {
        val prev = synchronized(this) { snapRunning }
        val cur = HashMap<String, Boolean>(32)
        val spaces = ArrayList<Summary.Space>(4)
        // (会话 id, 工作区名, 这条会话的待完成)
        val done = ArrayList<Triple<String, String, List<String>>>(2)
        for (ws in state.workspaces) {
            var live = 0
            for (s in ws.sessions) {
                cur[s.id] = s.isRunning
                if (s.isRunning) live++
                if (prev?.get(s.id) == true && !s.isRunning) {
                    done.add(Triple(s.id, ws.title, s.todo))
                }
            }
            spaces.add(Summary.Space(ws.title, live, ws.sessionCount))
        }
        synchronized(this) {
            snapRunning = cur
            snapSpaces = spaces
            for (ws in state.workspaces) for (s in ws.sessions) {
                if (s.title.isNotBlank()) titles[s.id] = s.title
            }
            if (lastDoneAt.size > 200) {
                val now = System.currentTimeMillis()
                lastDoneAt.entries.removeIf { now - it.value > 600_000L }
            }
        }
        for ((id, space, todo) in done) push(id, Kind.Done, null, space = space, todo = todo)
        refreshSummary()
    }

    /** 把三处状态汇总成常驻通知要的一行字，并原地刷新通知。 */
    private fun refreshSummary() {
        val ctx = appContext ?: return
        val s = synchronized(this) {
            val spaces = snapSpaces
            Summary(
                // 有快照就用快照的总数（覆盖全部工作区）；还没有快照时退回事件流。
                running = if (spaces.isEmpty()) running.size else spaces.sumOf { it.running },
                spaces = spaces,
                approvals = _approvals.value.size,
                lastDoneTitle = _notices.value.firstOrNull { it.kind == Kind.Done }?.title,
                lastDoneAt = _notices.value.firstOrNull { it.kind == Kind.Done }?.at ?: 0L,
                todo = WidgetRefresher.todoLines(),
                firstApproval = _approvals.value.firstOrNull(),
            )
        }
        if (_summary.value == s) return          // 没变就别刷，省电
        _summary.value = s
        // ★ 通道标签每次现算：服务 onCreate 时闭包下来的 host 会过时 ——
        //   清掉通知后重贴的那条就写着错的通道（bug 1）。
        NotificationCenter.updateForeground(ctx, WidgetRefresher.channelLabel(ctx), s)
        // 桌面上放着小组件的话，一起刷新 —— 那是最常被瞟一眼的地方
        DshWidgetProvider.update(ctx, s)
        // 会话版的卡片还要会话列表/上下文/余额，这些只有 widget 端点有；
        // 状态流变了就顺手拉一次（WidgetRefresher 内部有单飞+3s 节流，不会打爆）。
        // 不在这里开长连接：网络复用这条 WS/轮询已有的节奏。
        WidgetRefresher.refresh(ctx)
    }

    /** 退出前台时把已有审批挂进通知，进来时撤掉。 */
    fun onForegroundChanged(foreground: Boolean) {
        appForeground = foreground
        val ctx = appContext ?: return
        if (foreground) {
            _approvals.value.forEach { NotificationCenter.cancelApproval(ctx, it) }
            _approvals.value = emptyList()
        }
    }

    /** 确保监听在跑（小组件刷新时用；已经在跑就是空操作）。 */
    fun ensureRunning(ctx: Context) {
        val url = currentUrl ?: Prefs(ctx).entryUrl()
        if (url.isEmpty()) return
        start(ctx, url)
    }

    fun markAllSeen() {
        _unseen.value = 0
        save()
    }

    fun clear() {
        _notices.value = emptyList()
        _unseen.value = 0
        save()
    }

    // ------------------------------------------------------------------

    /**
     * 用 `session/list` **校准** `running` 集合。
     *
     * 为什么不只依赖 `api-session/status` 事件：那个事件的参数形状我是从压缩过的
     * 产物里推的，而实测中「有会话在跑、卡片却显示空闲」—— 说明靠不住。
     * `session/list` 的 `running` 字段是权威数据（App 本来就用它拿启动快照），
     * 用它校准，事件流只负责「尽快知道（跑完了）」这件事。
     *
     * 这是**校准**不是替换：差集照样会触发完成通知，语义与轮询模式一致。
     */
    private suspend fun resync(client: DshApi) {
        val snap = runCatching { client.snapshot() }.getOrNull() ?: return
        val finished = synchronized(this) {
            titles.putAll(snap.titles)
            val gone = running.filter { it !in snap.running }
            running.clear()
            running.addAll(snap.running)
            gone
        }
        appContext?.let { ctx -> for (id in finished) hydrateAndPush(ctx, id) }
        refreshSummary()
    }

    /**
     * 轮询一次会话列表，靠 `running` 集合的变化判断「跑完了」。
     *
     * 这是 WS 的降级方案，语义上等价：原来靠 `api-session/status(sessionId, running)`
     * 事件，现在靠两次快照的差集。代价是最多 [POLL_MS] 的延迟。
     */
    private suspend fun pollTick(client: DshApi) {
        var failure: String? = null
        val snap = runCatching { client.snapshot() }.onFailure {
            failure = it.message?.take(80) ?: it.toString().take(80)
        }.getOrNull()
        if (snap == null) {
            _live.value = false
            _mode.value = Mode.Down
            // 轮询失败也要留下原因 —— 否则界面只显示「未连接」，无从判断是
            // 认证问题、网络问题，还是这个接口在宿主上不存在。
            val f = failure ?: "未知"
            _lastError.value = if (f.contains("404")) {
                // 404 = 这台 DSH 根本没有这个接口。0.1.1 那一代连
                // dsh-api-session-controller 都还没有，session/* 的 RPC 端点
                // 是后来才加的 —— 属于「服务端没提供」，不是能适配的差异。
                "这台 DSH 太老了（最低需要 0.1.2-rc.1）—— 它没有 App 需要的接口，" +
                    "session/list 返回 404。升级即可：npm i -g @deepseek-ai/dsh@latest"
            } else {
                "轮询 session/list 失败：$f"
            }
            return
        }
        _live.value = true
        _lastEventAt.value = System.currentTimeMillis()
        val finished = synchronized(this) {
            titles.putAll(snap.titles)
            val gone = running.filter { it !in snap.running }
            running.clear()
            running.addAll(snap.running)
            gone
        }
        appContext?.let { ctx -> for (id in finished) hydrateAndPush(ctx, id) }
        refreshSummary()
    }

    private fun handle(event: String, args: JSONArray) {
        val sessionId = args.optString(0).orEmpty()
        if (sessionId.isEmpty()) return

        when (event) {
            // (sessionId, running)
            "api-session/status" -> {
                val isRunning = args.optBoolean(1, false)
                val wasRunning = synchronized(this) {
                    if (isRunning) {
                        running.add(sessionId); true
                    } else {
                        running.remove(sessionId)
                    }
                }
                if (!isRunning && wasRunning) {
                    // 事件比快照早到：这一刻还没有标题和「待完成」，
                    // 先拉一次快照再弹，否则通知正文是空的。
                    val ctx = appContext
                    if (ctx == null) push(sessionId, Kind.Done, null)
                    else hydrateAndPush(ctx, sessionId)
                }
                refreshSummary()
            }

            // (summary) —— 借机补标题
            "api-session/added" -> {
                val summary = args.optJSONObject(0) ?: return
                val title = summary.optJSONObject("projections")
                    ?.optJSONObject("values")
                    ?.let { if (it.isNull("title")) "" else it.optString("title") }
                    .orEmpty()
                if (title.isNotEmpty()) synchronized(this) { titles[sessionId] = title }
            }

            // (sessionId, message)
            "api-session/error" -> {
                val message = args.optString(1).orEmpty()
                push(sessionId, Kind.Error, message.take(200).ifEmpty { null })
            }
        }
    }

    /**
     * 收到「跑完了」但手上没有完整数据：先拉一次 widget 快照，
     * 拿到这条会话的标题 / 待完成 / 工作区名再弹通知。
     *
     * 拉不到（离线、锁定）也不能丢通知 —— 退回当前已知信息。
     */
    private fun hydrateAndPush(ctx: Context, sessionId: String) {
        scope.launch(Dispatchers.IO) {
            val st = runCatching { WidgetRefresher.fetchNow(ctx) }.getOrNull()
            if (st != null) onSnapshot(st)   // 快照差集里若包含这条，它自己会弹
            val already = synchronized(this@SessionWatcher) {
                lastDoneAt[sessionId]?.let { System.currentTimeMillis() - it < DONE_DEDUPE_MS }
            } == true
            if (already) return@launch
            var space: String? = null
            var todo: List<String>? = null
            st?.workspaces?.forEach { ws ->
                ws.sessions.firstOrNull { it.id == sessionId }?.let {
                    space = ws.title
                    todo = it.todo
                }
            }
            push(sessionId, Kind.Done, null, space = space, todo = todo)
        }
    }

    private fun push(
        sessionId: String,
        kind: Kind,
        detail: String?,
        space: String? = null,
        todo: List<String>? = null,
    ) {
        val now = System.currentTimeMillis()
        // 两条通路（WS 事件 / 快照差集）都会报同一件事，15 s 内只弹一条。
        if (kind == Kind.Done) {
            val last = synchronized(this) { lastDoneAt[sessionId] }
            if (last != null && now - last < DONE_DEDUPE_MS) return
            synchronized(this) { lastDoneAt[sessionId] = now }
        }
        val base = synchronized(this) { titles[sessionId] }
            ?: "会话 ${sessionId.removePrefix("session-").take(8)}"
        // 两个工作区同时用时，「second · xxx」比光一个会话名有用得多
        val title = if (space.isNullOrBlank()) base else "$space · $base"

        val notice = Notice(
            id = "$sessionId-$now",
            sessionId = sessionId,
            title = title,
            at = now,
            kind = kind,
            space = space,
            // 按会话取「待完成」：全局聚合会把别的会话的待办写进这条通知。
            detail = run {
                // 优先用快照里这条会话自己的「待完成」；没有才退回缓存按会话取。
                val mine = todo?.takeIf { it.isNotEmpty() }
                    ?: WidgetRefresher.todoFor(sessionId)
                if (mine.isEmpty()) detail
                else buildString {
                    append("待完成：")
                    append(mine.joinToString(" · ") { it.take(40) })
                    if (!detail.isNullOrBlank()) append(" · ").append(detail.take(60))
                }
            },
        )
        _notices.value = (listOf(notice) + _notices.value).take(MAX_KEPT)
        _unseen.value += 1
        save()

        appContext?.let { NotificationCenter.post(it, notice) }
    }

    // ------------------------------------------------------------------
    // 持久化：重启 app 后通知列表还在，不然悬浮窗一开就是空的
    // ------------------------------------------------------------------

    private fun file(ctx: Context) = File(ctx.filesDir, "notices.json")

    private fun save() {
        val ctx = appContext ?: return
        runCatching {
            val arr = JSONArray()
            _notices.value.forEach { n ->
                arr.put(
                    JSONObject()
                        .put("id", n.id)
                        .put("sessionId", n.sessionId)
                        .put("title", n.title)
                        .put("at", n.at)
                        .put("kind", n.kind.name)
                        .put("detail", n.detail ?: JSONObject.NULL),
                )
            }
            val root = JSONObject()
                .put("unseen", _unseen.value)
                .put("list", arr)
            file(ctx).writeText(root.toString())
        }
    }

    private fun load() {
        val ctx = appContext ?: return
        runCatching {
            val f = file(ctx)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("list") ?: JSONArray()
            val list = ArrayList<Notice>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    Notice(
                        id = o.optString("id"),
                        sessionId = o.optString("sessionId"),
                        title = o.optString("title"),
                        at = o.optLong("at"),
                        kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.Done),
                        detail = if (o.isNull("detail")) null else o.optString("detail"),
                    ),
                )
            }
            _notices.value = list
            _unseen.value = root.optInt("unseen", 0)
        }
    }
}
