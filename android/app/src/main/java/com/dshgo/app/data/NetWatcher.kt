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
    private var lastWifi: Boolean? = null

    fun attach(context: Context, onSwitch: () -> Unit) {
        if (callback != null) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm = manager
        lastWifi = isWifi(manager)
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
        lastWifi = null
    }

    /** 只有"Wi-Fi ↔ 蜂窝"这种真正的路径变化才值得重连，避免同网段抖动反复刷新。 */
    private fun handle(manager: ConnectivityManager, onSwitch: () -> Unit) {
        val now = isWifi(manager)
        val before = lastWifi
        lastWifi = now
        if (before == null || before == now) return
        Log.i(TAG, "network path changed: wifi=$before -> $now")
        runCatching { onSwitch() }
    }

    private fun isWifi(manager: ConnectivityManager): Boolean {
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
