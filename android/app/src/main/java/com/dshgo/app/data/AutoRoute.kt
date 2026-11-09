package com.dshgo.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 自动选路：只有「确认自己在同一个局域网里」才用局域网地址，其余一律用 Tailscale 地址。
 *
 * 判定顺序（都无网络 IO，纯本地判断，可在网络回调里直接调）：
 * 1. 当前网络不是 Wi-Fi/以太网 → 一定走隧道地址。
 * 2. 是 Wi-Fi，但当前地址是 100.x（Tailscale）而列表里有局域网地址 → 换回局域网。
 * 3. 是 Wi-Fi，但当前地址是局域网地址 → 保持（真正的可达性由页面自己报错，探测交给用户点「检查」）。
 */
object AutoRoute {

    fun isTailscaleUrl(url: String): Boolean = host(url).startsWith("100.")

    fun isLanUrl(url: String): Boolean {
        val h = host(url)
        return h.startsWith("192.168.") || h.startsWith("10.") ||
            h.startsWith("172.16.") || h.startsWith("172.17.") || h.startsWith("172.18.") ||
            h.startsWith("172.19.") || h.startsWith("172.2") || h.startsWith("172.30.") ||
            h.startsWith("172.31.")
    }

    fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 返回应当使用的地址；null 表示保持不变。 */
    fun pick(context: Context, connections: List<Connection>, current: String?): String? {
        if (connections.isEmpty()) return null
        val lan = connections.firstOrNull { isLanUrl(it.url) }?.url
        val tail = connections.firstOrNull { isTailscaleUrl(it.url) }?.url
        return when {
            // 不在 Wi-Fi 上：一定走隧道；没有隧道地址就保持原样（让页面自己报错更诚实）
            !onWifi(context) -> tail
            // 在同一局域网：优先局域网地址，没有就用隧道
            lan != null && current != null && isTailscaleUrl(current) -> lan
            lan == null -> tail
            else -> null
        }
    }

    private fun host(url: String): String =
        url.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
}
