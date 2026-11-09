package com.dshgo.app.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import com.dsh.remote.TailscaleVpnService
import com.dsh.remote.Tunnel

/**
 * 内嵌 Tailscale 的薄封装。
 *
 * 引擎（libtailscale）跑在本进程里，隧道由我们自己的 [TailscaleVpnService]
 * 建立，并且只把本应用放进隧道（见它的 addAllowedApplication 调用）——
 * 手机其它 App 的路由不受影响，也不需要装官方 Tailscale。
 */
object TsTunnel {

    private const val TAG = "TsTunnel"

    /** VPN 授权是否已经给过；没给过时要走 [VpnService.prepare]。 */
    fun needsConsent(ctx: Context): Intent? = try {
        VpnService.prepare(ctx)
    } catch (t: Throwable) {
        Log.w(TAG, "prepare: ${t.message}")
        null
    }

    /** 引擎有没有起来（不代表隧道已连上）。 */
    fun engineUp(ctx: Context): Boolean = try {
        Tunnel.get(ctx).isEngineUp
    } catch (t: Throwable) {
        false
    }

    /** Tailscale 后端状态：NoState / NeedsLogin / Starting / Running / Stopped … */
    fun state(ctx: Context): String = try {
        Tunnel.get(ctx).backendState().ifBlank { "" }
    } catch (t: Throwable) {
        ""
    }

    fun isRunning(ctx: Context): Boolean = state(ctx) == "Running"

    /** 交互式登录地址（登录后引擎会自己拿到节点）。 */
    fun loginUrl(ctx: Context): String = try {
        val t = Tunnel.get(ctx)
        val direct = t.authURL()
        if (direct.isNotBlank()) direct else t.browseURL().ifBlank { "" }
    } catch (t: Throwable) {
        ""
    }

    /** 发起一次交互式登录，返回跳转地址。 */
    fun startLogin(ctx: Context): String = try {
        Tunnel.get(ctx).startLogin().ifBlank { loginUrl(ctx) }
    } catch (t: Throwable) {
        ""
    }

    fun start(ctx: Context) {
        // 事件流水：崩在哪一步全靠它 —— 服务启动、引擎启动、VPN 建立是三个不同的位置。
        com.dshgo.app.data.EventLog.event(ctx, "tunnel", "start requested")
        val intent = Intent(ctx, TailscaleVpnService::class.java)
            .setAction(TailscaleVpnService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(ctx, intent) }
            .onFailure {
                com.dshgo.app.data.EventLog.event(ctx, "tunnel", "start failed: ${it.javaClass.simpleName}")
            }
    }

    /** 只把引擎拉起来（登录期间不建隧道，控制通道靠前台保活）。 */
    fun startForegroundOnly(ctx: Context) {
        val intent = Intent(ctx, TailscaleVpnService::class.java)
            .setAction(TailscaleVpnService.ACTION_START_FOREGROUND_ONLY)
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun stop(ctx: Context) {
        try {
            ctx.startService(
                Intent(ctx, TailscaleVpnService::class.java)
                    .setAction(TailscaleVpnService.ACTION_STOP),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "stop: ${t.message}")
        }
    }

    /** 本节点的 tailnet 地址（100.x.y.z），拿不到时返回空串。 */
    fun selfAddress(ctx: Context): String = try {
        val t = Tunnel.get(ctx)
        val self = t.status().optJSONObject("Self")
        val ips = self?.optJSONArray("TailscaleIPs")
        if (ips != null && ips.length() > 0) ips.optString(0, "") else ""
    } catch (t: Throwable) {
        ""
    }
}
