package com.dshgo.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * 网络切换看门狗。
 *
 * 手机从家里 Wi-Fi 走到 4G、或者从 4G 回到 Wi-Fi 时，页面里那条 TCP/WS 已经死了，
 * 但 WebView 自己不会重连 —— 表现为" App 开着但一直转圈"。
 * 这里只在**网络实际换了**之后回调一次，由 MainActivity 决定要不要 reload。
 */
object NetWatcher {
    private const val TAG = "dshgo-net"

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    /**
     * 上一次判定出的「物理链路」。**不把 VPN 算进去** ——
     *
     * 本应用自己会在任何网络之上挂一条 tun（内嵌 Tailscale）。隧道建起来/断掉的
     * 那一刻 `activeNetwork` 会切成 VPN，若按 activeNetwork 判 Wi-Fi，就会得出
     * 「刚才在家网络、现在不是」的假结论，于是页面自己重载一次 ——
     * 用户看到的就是「DSH 界面明明已经出来了，却又自己刷新了一次」。
     */
    private var lastPath: String? = null

    fun attach(context: Context, onSwitch: () -> Unit) {
        if (callback != null) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm = manager
        lastPath = pathOf(manager)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = handle(manager, onSwitch)
            override fun onLost(network: Network) = handle(manager, onSwitch)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                handle(manager, onSwitch)
        }
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
            callback = cb
        }.onFailure { Log.w(TAG, "register failed", it) }
    }

    fun detach() {
        val manager = cm
        val cb = callback
        if (manager != null && cb != null) runCatching { manager.unregisterNetworkCallback(cb) }
        callback = null
        cm = null
        lastPath = null
    }

    /** 只有"Wi-Fi ↔ 蜂窝"这种真正的路径变化才值得重连，避免同网段抖动反复刷新。 */
    private fun handle(manager: ConnectivityManager, onSwitch: () -> Unit) {
        val now = pathOf(manager)
        val before = lastPath
        lastPath = now
        if (before == null || before == now) return
        Log.i(TAG, "network path changed: $before -> $now")
        runCatching { onSwitch() }
    }

    /**
     * 当前「物理链路」的粗分类：wifi / cell / other / none。
     *
     * 只看非 VPN 网络，并且 Wi-Fi 优先 —— 同一时刻手机可能同时挂着蜂窝与 Wi-Fi，
     * 只有 wifi 与 cell 之间的切换才值得重连；VPN 的建立/消失一律忽略。
     */
    private fun pathOf(manager: ConnectivityManager): String {
        val networks = runCatching { manager.allNetworks }.getOrNull() ?: return "none"
        var hasWifi = false
        var hasCell = false
        var hasOther = false
        for (network in networks) {
            val caps = manager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            // 我们自己挂的 tun 不算「换了网络」
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> hasWifi = true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> hasCell = true
                else -> hasOther = true
            }
        }
        return when {
            hasWifi -> "wifi"
            hasCell -> "cell"
            hasOther -> "other"
            else -> "none"
        }
    }
}
