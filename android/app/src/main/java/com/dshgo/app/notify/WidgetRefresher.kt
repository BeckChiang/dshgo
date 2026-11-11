package com.dshgo.app.notify

import android.content.Context
import com.dshgo.app.Prefs
import com.dshgo.app.data.WidgetDevice
import com.dshgo.app.data.WidgetStateClient
import com.dshgo.app.data.WidgetStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小组件的「数据 → 界面」泵。
 *
 * 为什么要单独一层，而不是在 provider 里直接发请求：
 * 1. provider 的 `onUpdate` 跑在主线程且必须快速返回，不能在里面做网络；
 * 2. 触发点有多个（状态流变化 / App 回前台 / provider onUpdate / 点胶囊切工作区），
 *    统一收口后才能做「单飞 + 节流」，避免一次状态抖动打出多次请求；
 * 3. 小组件不开长连接 —— 只复用 [SessionWatcher] 已经维护的那条，
 *    这里只在它推送变化时顺手拉一次快照。
 */
object WidgetRefresher {

    /** 同一时刻只允许一个在飞；两次主动刷新至少隔 3s（切工作区是强制刷新，不受限）。 */
    // 事件流每来一条就会催一次刷新，1 秒的节流等于「一直在飞」——
    // 底栏于是永远停在「刷新中」（用户 22:19 报的）。小组件不需要秒级新鲜度，
    // 15 秒一次足够；用户主动点刷新走 force，不受这个限制。
    private const val MIN_INTERVAL_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inFlight: Job? = null

    /** 请求被节流/合并时记一笔，本轮结束后补跑一次，避免「最后一次状态变化丢了」。 */
    private var pending = false
    private var lastStartAt = 0L

    /** 最近一次拉取结果，供 provider 选择降级文案（「未连接」 vs「已锁定」）。 */
    @Volatile
    var lastOutcome: Outcome = Outcome.Unknown
        private set

    enum class Outcome { Unknown, Ok, Locked, Offline }

    /**
     * 最近一次成功拉到的快照。
     *
     * 通知正文要用会话里的「待完成」条目，而常驻通知的刷新只走 SessionWatcher 的状态流；
     * 把它留在这儿，才能在不额外发请求的情况下把「待完成」写进通知。
     */
    @Volatile
    var lastState: com.dshgo.app.data.WidgetState? = null
        private set

    /**
     * 某个会话自己的待完成条目。
     *
     * 以前通知正文用全局聚合的 [todoLines]，于是「A 会话跑完」的通知里
     * 写着 B 会话的待完成项 —— 用户一眼能看出对不上。
     */
    fun todoFor(sessionId: String, limit: Int = 2): List<String> =
        lastState?.workspaces.orEmpty()
            .flatMap { it.sessions }
            .filter { it.id == sessionId }
            .flatMap { it.todo }
            .distinct()
            .take(limit)

    /** 所有会话里待完成的前 [limit] 条，给常驻通知正文用。 */
    fun todoLines(limit: Int = 2): List<String> =
        lastState?.workspaces.orEmpty()
            .flatMap { it.sessions }
            .flatMap { it.todo }
            .distinct()
            .take(limit)

    /** 最近一次成功用的地址（底栏通道标签按它算，见 [WidgetStateClient.Result.Ok]）。 */
    @Volatile
    var lastOkBase: String = ""
        private set

    /** 最近一次成功拉取的时间；0 = 还没成功过。 */
    @Volatile
    var lastOkAt: Long = 0L
        private set

    /**
     * 最近一次失败的原因（截断到 40 字）。
     *
     * 为什么留这个：光写「未连接」无法区分「电脑没开」「地址过期」「插件没起」，
     * 用户只能靠猜；具体原因写进底栏，一眼就知道该去修哪一端。
     */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * 给小组件底部用的一行状态。
     *
     * 为什么需要它：拉取失败时小组件会保留上一次的缓存 —— 看起来「通知里 3 个在跑，
     * 小组件还是 1 个」，其实是一次 401/断网被静默吞掉了。把失败原因写出来，
     * 用户点一下就能判断是「没连上」还是「需要解锁」。
     */
    fun statusText(ctx: android.content.Context? = null): String {
        // 正在拉取时先说「检查中」：用户点了刷新，界面得马上有反应。
        // 但只认最近 20s 内的在飞请求 —— 隧道抖动时一次拉取可能拖很久，
        // 以前这里会永远停在「检查中…」（用户 21:02 报的第二个问题）。
        if (ctx != null && lastOkAt == 0L) {
            val sp = ctx.getSharedPreferences("dshgo_widget", android.content.Context.MODE_PRIVATE)
            val at = sp.getLong("last_ok_at", 0L)
            if (at > 0L) {
                lastOkAt = at
                lastOkBase = sp.getString("last_ok_base", "") ?: ""
            }
        }
        val flying = inFlight?.isActive == true
        // 「刷新中」只在前 3 秒里说：它是「我刚点了刷新」的反馈，不是常驻状态。
        val justStarted = flying && System.currentTimeMillis() - lastStartAt < 3_000L
        val fresh = flying && System.currentTimeMillis() - lastStartAt < 20_000L
        // 已经有上一次成功结果时，不要用「检查中」把时间戳顶掉（用户要的是「保持现有提示 + 刷新中」）。
        if (lastOkAt > 0) {
            val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastOkAt))
            return if (justStarted) "更新于 $t · 刷新中" else "更新于 $t"
        }
        // ★ 不在飞的时候也不要说「连接中…」：这句话会被当成常驻状态，
        //   用户看到的永远是「连接中」，而不是「到底连上了没有」。
        //   有结果就报结果，只在刚点刷新那 3 秒里补一句「刷新中」。
        val base = when (lastOutcome) {
            Outcome.Ok -> ""
            Outcome.Locked -> "需要解锁"
            // 失败时把原因带上：光一个「未连接」看不出是哪一端的问题。
            Outcome.Offline -> if (lastError.isNotBlank()) "未连接 · $lastError" else "未连接"
            Outcome.Unknown -> "尚未刷新"
        }
        // 只有「已经连上过」才加「刷新中」：失败状态下再挂一句刷新中，
        // 用户会以为它还在努力，其实是没连上。
        val suffix = if (justStarted && lastOutcome == Outcome.Ok) " · 刷新中" else ""
        return if (lastOkAt > 0) {
            val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastOkAt))
            (if (base.isEmpty()) "更新于 $t" else "$base · 上次 $t") + suffix
        } else base + suffix
    }

    /**
     * 把底层异常压成一句人话。
     *
     * OkHttp 的原话是 `failed to connect to /100.75.57.100 (port 3081) after 350ms`，
     * 底栏只有两行，长句会把「通道 · 状态 · 峰谷」挤没（用户 22:37 的截图就是这个）。
     */
    private fun shortReason(message: String): String {
        val m = message.lowercase()
        return when {
            m.contains("timeout") || m.contains("timed out") -> "超时"
            m.contains("failed to connect") || m.contains("refused") -> "连不上"
            m.contains("401") || m.contains("locked") -> "需要解锁"
            m.contains("unable to resolve") || m.contains("unknown host") -> "解析不了"
            message.isBlank() -> "未连接"
            else -> message.take(10)
        }
    }

    /**
     * 看门狗：防止小组件永久停在「检查中…」。
     *
     * 两个坑叠在一起才会永久停住：
     * 1) 隧道抖动时一次拉取可能挂很久，`inFlight` 一直活着；
     * 2) 只有「拉取结束」才会重绘，而它不结束就永远不重绘，于是底栏一直显示开始那一刻的
     *    「检查中…」——即便 [statusText] 早就能给出「超时」也不会自己刷。
     * 所以这里做两件事：超过 20 s 在飞就放弃，并且每一拍都重绘一次。
     */

    /**
     * 用电脑上报的 `addrs` 把本地存的地址对齐。
     *
     * 为什么不复用界面的探测：小组件刷新本来就刚和电脑通过话，再走一遍 2500 ms 的
     * 全量探测不值得；这里只要 `/__dshgo__/info` 那一个小 JSON。
     */
    private suspend fun syncAddressesFromPc(ctx: android.content.Context, base: String) =
        withContext(Dispatchers.IO) {
            val cookie = runCatching {
                android.webkit.CookieManager.getInstance().getCookie(base).orEmpty()
            }.getOrDefault("")
            val req = okhttp3.Request.Builder()
                .url(base.trimEnd('/') + "/__dshgo__/info")
                .header("accept", "application/json")
                .apply { if (cookie.isNotBlank()) header("cookie", cookie) }
                .build()
            val body = okhttp3.OkHttpClient.Builder()
                .connectTimeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
                .newCall(req).execute().use { res -> if (res.isSuccessful) res.body?.string() else null }
                ?: return@withContext
            val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return@withContext
            val id = json.optString("deviceId")
            if (id.isBlank()) return@withContext
            val addrs = json.optJSONObject("addrs")
            fun list(key: String): List<String> {
                val arr = addrs?.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.isNotBlank() }
                }
            }
            val changed = com.dshgo.app.data.ConnectionStore.applyReported(
                ctx, id, list("lan"), list("tail"),
            )
            if (changed > 0) {
                com.dshgo.app.data.EventLog.event(ctx, "widget", "address synced x$changed")
            }
        }

    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        val flying = inFlight?.isActive == true
        if (flying && System.currentTimeMillis() - lastStartAt > 20_000L) {
            runCatching { inFlight?.cancel() }
            synchronized(this) {
                inFlight = null
                pending = false
            }
            lastOutcome = Outcome.Offline
            lastError = "超时"
        }
        DshWidgetProvider.update(app, SessionWatcher.summary.value)
        ScheduleWidgetProvider.update(app)
    }

    /**
     * 拉一次快照并重绘所有小组件实例。
     *
     * @param force true 时跳过节流（用户点了工作区胶囊，必须立刻看到结果）。
     */
    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        // 没配入口地址就没有可拉的东西：别发请求，直接留着降级文案。
        if (WidgetDevice.url(app).isBlank()) {
            lastOutcome = Outcome.Offline
            lastError = "未配置地址"
            return
        }
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (!force && (inFlight?.isActive == true || now - lastStartAt < MIN_INTERVAL_MS)) {
                // 丢掉请求就等于丢掉一次状态变化（用户看到「在跑 1 个」但行还是旧的）。
                // 记下来，等这一轮结束后补一次；force 的请求不排队（本来就是为了立刻看）。
                if (inFlight?.isActive == true) pending = true
                // 被节流也要重绘一次：statusText() 会随时间从「检查中」变「超时」，
                // 不重绘就停在旧字上（用户 21:42 截图就是这个）。
                DshWidgetProvider.update(app, SessionWatcher.summary.value)
                return
            }
            lastStartAt = now
            inFlight = scope.launch {
                when (val r = WidgetStateClient.fetch(app)) {
                    is WidgetStateClient.Result.Ok -> {
                        WidgetStateStore.save(app, r.raw)
                        lastState = runCatching { com.dshgo.app.data.WidgetState.parse(org.json.JSONObject(r.raw)) }.getOrNull()
                        lastOutcome = Outcome.Ok
                        lastError = ""
                        lastOkBase = r.base
                        lastOkAt = System.currentTimeMillis()
                        // ★ 小组件自己顺手把地址对齐一次。
                        //   以前只有 App 打开过才会 reconcile：局域网地址过期 + 隧道没起时，
                        //   桌面上的小组件只能一直报超时，等用户想起来打开 App 才恢复。
                        runCatching { syncAddressesFromPc(app, r.base) }
                        // 落盘：进程被回收后小组件还开着，底栏不能退化成「尚未刷新」。
                        app.getSharedPreferences("dshgo_widget", android.content.Context.MODE_PRIVATE)
                            .edit()
                            .putLong("last_ok_at", lastOkAt)
                            .putString("last_ok_base", lastOkBase)
                            .apply()
                    }
                    WidgetStateClient.Result.Locked -> {
                        lastOutcome = Outcome.Locked
                        lastError = "需要解锁"
                    }
                    is WidgetStateClient.Result.Failed -> {
                        // 失败不删缓存：上一次的数据比「未连接」有用得多。
                        lastOutcome = Outcome.Offline
                        lastError = shortReason(r.message)
                    }
                }
                // 无论成败都重绘：成功换新数据，失败退回缓存/降级文案。
                DshWidgetProvider.update(app, SessionWatcher.summary.value)
                ScheduleWidgetProvider.update(app)
                if (pending) {
                    pending = false
                    // 补跑一次：这一轮拿到的可能是变化之前的状态。
                    refresh(app, force = true)
                }
            }
        }
    }
}
