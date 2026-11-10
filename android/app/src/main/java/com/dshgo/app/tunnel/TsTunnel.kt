package com.dshgo.app.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import com.dsh.remote.TailscaleVpnService
import com.dsh.remote.Tunnel
import java.net.NetworkInterface

/**
 * 内嵌 Tailscale 的薄封装。
 *
 * 引擎（libtailscale）跑在本进程里，隧道由我们自己的 [TailscaleVpnService]
 * 建立，并且只把本应用放进隧道（见它的 addAllowedApplication 调用）——
 * 手机其它 App 的路由不受影响，也不需要装官方 Tailscale。
 */
object TsTunnel {

    private const val TAG = "TsTunnel"

    /** 「用户想要隧道」的持久标记。引擎状态不等于隧道状态，重建靠它判断该不该动手。 */
    private const val PREF_WANT = "ts_want"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("dshgo_tunnel", Context.MODE_PRIVATE)

    /** 用户开过隧道、且没关过。只有它为 true，看门狗才允许重建。 */
    fun wantsTunnel(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_WANT, false)

    private fun setWants(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(PREF_WANT, on).apply()

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

    /**
     * 真的有一条 TUN 接口在跑。
     *
     * ★ 这个和 [state] 必须分开看：后端可以一直 Running，而 VpnService 早就没了
     * （切换网络、省电、系统回收都会把它干掉）。只看后端就会在「隧道根本不存在的
     * 时候」显示「已连接」，用户拿着手机一个地址也连不上。
     */
    fun tunUp(): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.any { nic ->
            nic.isUp && nic.name.orEmpty().startsWith("tun")
        } == true
    } catch (t: Throwable) {
        Log.w(TAG, "tunUp: ${t.message}")
        false
    }

    /**
     * 该有隧道却没有 → 重建一次。返回是否真的发起了重建。
     *
     * 只在「用户要过隧道 + 后端在跑 + TUN 不存在」时动手：后端没跑说明用户根本没要，
     * 这时候去 start 会把一个没登录过的引擎拉起来，白白耗电。
     */
    fun ensureTunnel(ctx: Context): Boolean {
        if (!wantsTunnel(ctx)) return false
        if (tunUp()) return false
        val st = state(ctx)
        if (!st.equals("Running", true) && !st.equals("Starting", true)) return false
        com.dshgo.app.data.EventLog.event(ctx, "tunnel", "rebuild: tun missing, state=$st")
        start(ctx)
        return true
    }

    /** 交互式登录地址（登录后引擎会自己拿到节点）。 */
    fun loginUrl(ctx: Context): String = try {
        val t = Tunnel.get(ctx)
        // 引擎有时会把空值序列化成字符串 "null"，别原样摆到界面上
        listOf(t.authURL().orEmpty(), t.browseURL().orEmpty())
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.equals("null", true) }
            ?: ""
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
        setWants(ctx, true)
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
        setWants(ctx, false)
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
