package com.dshgo.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dshgo.app.Prefs
import com.dshgo.app.WatchService

/**
 * 开机后把保活服务拉起来。
 *
 * 之前只有在「用户打开过一次 App」之后，[WatchService] 才会跑起来，
 * 所以手机重启（或被系统清理）之后，「会话跑完了」的通知就再也不来了 ——
 * 用户看到的现象就是「通知功能坏了」。这个接收器专门补这一段：
 * 只要已经连过电脑（entryUrl 非空），开机就恢复监听。
 *
 * 注意：Android 10+ 不允许后台静默启动前台服务，但 BOOT_COMPLETED 是官方允许的场景之一；
 * 系统若仍拒绝，[WatchService.start] 内部会静默失败，不会崩溃。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        val app = context.applicationContext
        if (Prefs(app).entryUrl().isBlank()) return
        WatchService.start(app)
    }
}
