package com.dshgo.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.dshgo.app.notify.NotificationCenter
import com.dshgo.app.notify.SessionWatcher
import com.dshgo.app.notify.WidgetRefresher
import com.dshgo.app.tunnel.TsTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 保活用的前台服务。
 *
 * 纯粹是为了「**手机锁屏、你不在看 app 的时候，agent 跑完了也能收到通知**」。
 * 没有它的话，app 退到后台几分钟就会被系统冻结，WebSocket 断掉，
 * 完成通知只能等你重新打开 app 才补上 —— 那就失去意义了。
 *
 * 代价是一条常驻通知（最低优先级，不响不震）。关掉通知开关时这个服务也会停。
 *
 * 服务本身不干活：它只是让进程活着，真正的事件流由 [SessionWatcher] 这个单例持有，
 * Activity 和本服务读的是同一份状态，不会各推一条。
 */
class WatchService : Service() {

    companion object {
        /** 和 NotificationCenter.FOREGROUND_ID 必须是同一个值 —— 否则原地更新会变成新增一条。 */
        private const val NOTIF_ID = NotificationCenter.FOREGROUND_ID

        /** 幂等启动；系统不允许时静默失败（比如从后台启动前台服务）。 */
        fun start(ctx: Context) {
            val intent = Intent(ctx, WatchService::class.java)
            runCatching { ctx.startForegroundService(intent) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WatchService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val prefs = Prefs(this)
        val host = runCatching {
            java.net.URI(prefs.entryUrl()).host
        }.getOrNull() ?: prefs.entryUrl()

        runCatching {
            startForeground(
                NOTIF_ID,
                NotificationCenter.foreground(this, host, SessionWatcher.summary.value),
            )
        }

        // 系统可能在 Activity 不在时把服务拉起来，这里兜一次底
        val url = prefs.entryUrl()
        if (url.isNotEmpty()) {
            SessionWatcher.start(this, url)
        }

        // 隧道看门狗：VpnService 会因为切网络、省电、系统回收而单独死掉，
        // 而后端引擎还活着 —— 表现就是界面说「已连接」、地址却一个也连不上。
        // 这个进程本来就是常驻的，借它每 15 秒体检一次，缺了就重建。
        //
        // 顺带每 4 拍（约 1 分钟）催一次小组件刷新：桌面小组件的系统周期更新
        // 是 0（见 widget_info.xml），不催的话它只会停在放上去那一刻的数据上，
        // 换网之后就一直写「未连接」。
        // 网络一变就让小组件重新取数：系统周期更新是 0，光靠下面的节拍会一直停在旧数据上。
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    com.dshgo.app.data.EventLog.event(this@WatchService, "net", "default network changed")
                    runCatching { WidgetRefresher.refresh(this@WatchService, force = true) }
                }
            })
        }

        scope.launch {
            var tick = 0
            while (isActive) {
                delay(15_000)
                runCatching { TsTunnel.ensureTunnel(this@WatchService) }
                // 常驻通知可能被「清除全部」或系统清理掉，但服务还活着 —— 补回去。
                runCatching { reassertForeground(host) }
                if (++tick % 4 == 0) {
                    runCatching { WidgetRefresher.refresh(this@WatchService) }
                }
            }
        }
    }

    /** 常驻通知不在通知栏里就重新贴一条（同 id 原地更新，不会重复）。 */
    private fun reassertForeground(host: String) {
        val live = runCatching {
            NotificationManagerCompat.from(this).activeNotifications.any { it.id == NOTIF_ID }
        }.getOrDefault(true)
        if (live) return
        runCatching {
            startForeground(NOTIF_ID, NotificationCenter.foreground(this, host, SessionWatcher.summary.value))
            com.dshgo.app.data.EventLog.event(this, "notify", "foreground notification restored")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
