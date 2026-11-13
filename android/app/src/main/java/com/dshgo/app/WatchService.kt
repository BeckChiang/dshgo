package com.dshgo.app

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
        // 不再在这里算一次 host 闭包进循环：清掉通知后重贴时必须按**当下**
        // 实际连上的通道重算，否则会写着「隧道」而其实走局域网（bug 1）。

        runCatching {
            startForegroundTyped(
                NotificationCenter.foreground(this, WidgetRefresher.channelLabel(this), SessionWatcher.summary.value),
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
                    // 切网络（家里 → 蜂窝 / 先开了别的 VPN）后隧道最容易掉，
                    // 这里立刻补一次自动拉起，别等 15 s 那一拍。
                    runCatching { TsTunnel.autostart(this@WatchService) }
                    runCatching { TsTunnel.ensureTunnel(this@WatchService) }
                    com.dshgo.app.data.EventLog.event(this@WatchService, "net", "default network changed")
                    runCatching { WidgetRefresher.refresh(this@WatchService, force = true) }
                }
            })
        }

        scope.launch {
            var tick = 0
            while (isActive) {
                delay(15_000)
                // bug 4：登录过隧道但 TUN 不见了 → 自己拉起来（60 s 节流）。
                runCatching { TsTunnel.autostart(this@WatchService) }
                runCatching { TsTunnel.ensureTunnel(this@WatchService) }
                // 常驻通知可能被「清除全部」或系统清理掉，但服务还活着 —— 补回去。
                runCatching { reassertForeground() }
                runCatching { WidgetRefresher.tick(this@WatchService) }
                if (++tick % 4 == 0) {
                    runCatching { WidgetRefresher.refresh(this@WatchService) }
                }
            }
        }
    }

    /**
     * API 34 起 startForeground 必须显式带类型。这里固定传 specialUse：
     * 清单里声明的也是 specialUse，显式传一次是为了不让系统或后续改动
     * 把它推断回 dataSync —— 那个类型有 24 小时累计 6 小时的配额。
     */
    private fun startForegroundTyped(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * 前台服务被系统判定超时时的回调（API 34+ 单参、API 35+ 带类型）。
     *
     * dataSync 超时会走到这里；不自己停就抛
     * ForegroundServiceDidNotStopInTimeException 直接崩进程。
     * 现在服务用的是 specialUse，正常不该触发，留着是为了万一被判定超时
     * 也能体面退出（下次打开 App 或开机由 BootReceiver 再拉起），而不是崩掉。
     */
    override fun onTimeout(startId: Int) {
        shutdownAfterSystemTimeout("single-arg timeout")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        shutdownAfterSystemTimeout("timeout fgsType=$fgsType")
    }

    private fun shutdownAfterSystemTimeout(reason: String) {
        runCatching {
            com.dshgo.app.data.EventLog.event(this, "service", "watch service stopped by system: $reason")
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** 常驻通知不在通知栏里就重新贴一条（同 id 原地更新，不会重复）。 */
    private fun reassertForeground() {
        val live = runCatching {
            NotificationManagerCompat.from(this).activeNotifications.any { it.id == NOTIF_ID }
        }.getOrDefault(true)
        if (live) return
        runCatching {
            startForegroundTyped(
                NotificationCenter.foreground(
                    this, WidgetRefresher.channelLabel(this), SessionWatcher.summary.value,
                ),
            )
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
