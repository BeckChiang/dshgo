package com.dshgo.app.tunnel

import android.content.Context
import com.dsh.remote.Prefs
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
    private const val PREF_REBUILD = "ts_last_rebuild"

    /** 上一次「自动拉起」的时间（60 s 节流，避免和系统回收打架）。 */
    private const val PREF_AUTOSTART = "ts_last_autostart"

    /** 见过一次隧道真正建立 —— 之后才允许自动拉起。 */
    private const val PREF_EVER = "ts_ever"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("dshgo_tunnel", Context.MODE_PRIVATE)

    /** 用户开过隧道、且没关过。只有它为 true，看门狗才允许重建。 */
    fun wantsTunnel(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_WANT, false)

    /** 上一次建隧道失败的原因（由 TailscaleVpnService.TsBuilder.establish 写入，成功后自动清空）。 */
    fun lastError(ctx: Context): String {
        // 隧道已经建起来了就别再挂着上一次的失败原因 —— 用户看到「隧道建不起来」
        // 而设置里同时显示已连接，会以为是两个 bug（反馈里出现过）。
        if (runCatching { tunUp() }.getOrDefault(false)) return ""
        return runCatching { Prefs.get(ctx).getString("ts_error", "").orEmpty() }.getOrDefault("")
    }

    /** 隧道恢复正常后把失败的痕迹擦掉。 */
    fun clearError(ctx: Context) {
        runCatching { Prefs.get(ctx).edit().remove("ts_error").apply() }
    }

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
        val want = wantsTunnel(ctx)
        if (!want) return false
        if (runCatching { tunUp() }.getOrDefault(false)) {
            clearError(ctx)
            markEverConnected(ctx)
            return false
        }
        // 不能拿「引擎状态」当门槛：VpnService 被系统单独回收时引擎往往还活着，
        // 状态甚至是 Running —— 旧逻辑正是因此在 Running 下拒绝重建，隧道一死就一直死。
        // 现在只要「想连 + 没有 tun」就重建，只做 30 s 节流，避免和自身的启动流程抢。
        val now = System.currentTimeMillis()
        val last = prefs(ctx).getLong(PREF_REBUILD, 0L)
        if (now - last < 30_000L) return false
        prefs(ctx).edit().putLong(PREF_REBUILD, now).apply()
        val st = state(ctx)
        com.dshgo.app.data.EventLog.event(ctx, "tunnel", "rebuild: tun missing, state=$st")
        start(ctx)
        return true
    }

    /** 记下「这台手机上隧道真的连通过」——自动拉起只对这类设备生效。 */
    fun markEverConnected(ctx: Context) {
        if (!prefs(ctx).getBoolean(PREF_EVER, false)) {
            prefs(ctx).edit().putBoolean(PREF_EVER, true).apply()
        }
    }

    fun everConnected(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_EVER, false)

    /**
     * 该有隧道却没有 → 自己拉起来（60 s 节流）。
     *
     * 场景（用户 2026-11-12 报的第 4 条）：出门切到蜂窝，或者先用过别的 VPN，
     * 系统的 VpnService 被顶掉/回收；只靠 15 s 看门狗不够 —— 进程被杀后
     * 看门狗也不在，回到 App 时隧道还是「没启动」。
     *
     * 只对**曾经连通过**的设备生效：从没登录过就强行 start 会把一个空引擎
     * 拉起来白耗电，还会弹授权框打断用户。
     */
    fun autostart(ctx: Context): Boolean {
        if (runCatching { tunUp() }.getOrDefault(false)) {
            markEverConnected(ctx)
            return false
        }
        if (!wantsTunnel(ctx) && !everConnected(ctx)) return false
        val now = System.currentTimeMillis()
        if (now - prefs(ctx).getLong(PREF_AUTOSTART, 0L) < 60_000L) return false
        prefs(ctx).edit().putLong(PREF_AUTOSTART, now).apply()
        com.dshgo.app.data.EventLog.event(
            ctx, "tunnel", "autostart: tun missing (engine=${engineUp(ctx)})",
        )
        start(ctx)
        return true
    }

    /**
     * 隧道状态的人话版，**三分**：没启动 / 连不上 / 已连接。
     *
     * 以前设置里只把后端状态（Running/Stopped…）和 tunUp 混在一起说，
     * 用户看不出「隧道根本没起」和「起了但没连上」的区别（bug 4）。
     */
    fun statusText(ctx: Context): String {
        val st = state(ctx)
        val tun = runCatching { tunUp() }.getOrDefault(false)
        return when {
            tun && st == "Running" -> "已连接"
            tun && st == "Starting" -> "隧道已建立，正在连后端"
            tun -> "隧道已建立，后端未连接"
            needsConsent(ctx) != null -> "未连接 · 需要重新授权"
            !engineUp(ctx) -> "未启动"
            st == "NeedsLogin" -> "未连接 · 需要登录"
            st == "Starting" -> "未连接 · 正在启动"
            st.isNotBlank() -> "未连接 · $st"
            else -> "未连接"
        }
    }

    /** 设置里第二行的说明 —— 按当前卡在哪一步给下一步动作。 */
    fun hint(ctx: Context): String {
        val tun = runCatching { tunUp() }.getOrDefault(false)
        return when {
            tun -> "隧道只接入本应用，不影响其它 App；与系统 Tailscale 不能同时开。"
            needsConsent(ctx) != null ->
                "隧道没启动：Android 只允许一个 VPN，用过别的 VPN 后要把授权还回来。点「管理」授权，之后会自动重连。"
            !engineUp(ctx) -> "隧道没启动。出门（蜂窝 / 别人的 Wi-Fi）时点「管理」启动；同一网段可不用。"
            state(ctx) == "NeedsLogin" -> "隧道进程在，但还没登录 Tailscale。点「管理」登录一次即可。"
            else -> "隧道进程在，但没连上后端（登录过期或网络不通）。点「管理」重建。"
        }
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
        val started = runCatching { ContextCompat.startForegroundService(ctx, intent) }
        val err = started.exceptionOrNull()
        if (err != null) {
            com.dshgo.app.data.EventLog.event(ctx, "tunnel", "start failed: ${err.javaClass.simpleName}")
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
