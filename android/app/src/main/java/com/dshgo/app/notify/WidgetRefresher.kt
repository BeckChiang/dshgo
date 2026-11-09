package com.dshgo.app.notify

import android.content.Context
import com.dshgo.app.Prefs
import com.dshgo.app.data.WidgetStateClient
import com.dshgo.app.data.WidgetStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    private const val MIN_INTERVAL_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inFlight: Job? = null
    private var lastStartAt = 0L

    /** 最近一次拉取结果，供 provider 选择降级文案（「未连接」 vs「已锁定」）。 */
    @Volatile
    var lastOutcome: Outcome = Outcome.Unknown
        private set

    enum class Outcome { Unknown, Ok, Locked, Offline }

    /** 最近一次成功拉取的时间；0 = 还没成功过。 */
    @Volatile
    var lastOkAt: Long = 0L
        private set

    /**
     * 给小组件底部用的一行状态。
     *
     * 为什么需要它：拉取失败时小组件会保留上一次的缓存 —— 看起来「通知里 3 个在跑，
     * 小组件还是 1 个」，其实是一次 401/断网被静默吞掉了。把失败原因写出来，
     * 用户点一下就能判断是「没连上」还是「需要解锁」。
     */
    fun statusText(): String {
        val base = when (lastOutcome) {
            Outcome.Ok -> ""
            Outcome.Locked -> "需要解锁"
            Outcome.Offline -> "未连接"
            Outcome.Unknown -> "尚未刷新"
        }
        return if (lastOkAt > 0) {
            val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastOkAt))
            if (base.isEmpty()) "更新于 $t" else "$base · 上次 $t"
        } else base
    }

    /**
     * 拉一次快照并重绘所有小组件实例。
     *
     * @param force true 时跳过节流（用户点了工作区胶囊，必须立刻看到结果）。
     */
    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        // 没配入口地址就没有可拉的东西：别发请求，直接留着降级文案。
        if (Prefs(app).entryUrl().isBlank()) {
            lastOutcome = Outcome.Offline
            return
        }
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (!force && (inFlight?.isActive == true || now - lastStartAt < MIN_INTERVAL_MS)) return
            lastStartAt = now
            inFlight = scope.launch {
                when (val r = WidgetStateClient.fetch(app)) {
                    is WidgetStateClient.Result.Ok -> {
                        WidgetStateStore.save(app, r.raw)
                        lastOutcome = Outcome.Ok
                        lastOkAt = System.currentTimeMillis()
                    }
                    WidgetStateClient.Result.Locked -> lastOutcome = Outcome.Locked
                    is WidgetStateClient.Result.Failed -> {
                        // 失败不删缓存：上一次的数据比「未连接」有用得多。
                        lastOutcome = Outcome.Offline
                    }
                }
                // 无论成败都重绘：成功换新数据，失败退回缓存/降级文案。
                DshWidgetProvider.update(app, SessionWatcher.summary.value)
                ScheduleWidgetProvider.update(app)
            }
        }
    }
}
