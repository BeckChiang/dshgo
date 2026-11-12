package com.dshgo.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * 网段自动发现：电脑换了 IP 时，自己把它找回来。
 *
 * ## 为什么需要它
 *
 * 连接列表里存的局域网地址是「上次连上时那个」。路由器重启、DHCP 续租、
 * 换到另一台路由器之后这个地址就废了：手机会一直超时，而地址簿里没有任何
 * 一条能连 —— 用户能做的只有手敲一遍新 IP。
 *
 * ## 只在「全都连不上」时才扫
 *
 * 正常路径（隧道通、或局域网地址还有效）永远不该触发它：扫一个 /24 是
 * 254 个 TCP 连接，再便宜也不该白做。调用点只在 `WidgetRefresher`
 * 判定「所有候选连接都失败」之后，且内部还有 60 s 节流。
 *
 * ## 边界
 *
 * - 只在 **Wi-Fi** 上扫：蜂窝网段扫出来的是运营商内网，和电脑的局域网无关。
 * - 单探 350 ms、并发 8、总预算 3 s —— 最坏 3 秒就把结论交出去，
 *   绝不会让小组件刷新卡在「检查中」。
 * - 命中条件是 `/__dshgo__/info` 回 `name == "dshgo"`，而不是「端口开着」：
 *   3081 上跑别的东西很常见，认错机器比找不到更糟。
 */
object NetScan {

    private const val PORT = 3081

    /** 单个 IP 的探测上限。局域网内 ARP 都通的话 350 ms 足够。 */
    private const val TIMEOUT_MS = 350L

    /** 并发数：家用路由器同时接 8 条没必要更多，再多也是排队。 */
    private const val CONCURRENCY = 8

    /** 整轮扫描的时间预算。超了就放弃，等下一轮（60 s 后）。 */
    private const val BUDGET_MS = 3_000L

    /** 两次扫描的最小间隔。 */
    private const val MIN_INTERVAL_MS = 60_000L

    private const val PREF = "dshgo_netscan"
    private const val KEY_LAST = "last_scan"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 254 个必失败连接不值得重试
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 满足条件就扫一轮，命中后把地址上报给 [ConnectionStore]。
     *
     * 调用方只需在「所有候选都连不上」时调一次，节流在这里做。
     */
    suspend fun scanIfDue(ctx: Context) {
        val app = ctx.applicationContext
        if (!onWifi(app)) return
        val sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_LAST, 0L) < MIN_INTERVAL_MS) return
        sp.edit().putLong(KEY_LAST, now).apply()

        val prefix = localPrefix(app) ?: return
        val hit = probe(prefix, now + BUDGET_MS) ?: return
        adopt(app, hit.first, hit.second)
    }

    private fun onWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    /** 本机 Wi-Fi 的 IPv4 /24 前缀，如 `192.168.31`。 */
    private fun localPrefix(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val n = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(n) ?: return null
        lp.linkAddresses.asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.let { a ->
                val b = a.address
                "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}"
            }
    }.getOrNull()

    private suspend fun probe(
        prefix: String,
        deadline: Long,
    ): Pair<String, JSONObject>? = coroutineScope {
        var found: Pair<String, JSONObject>? = null
        var host = 1
        while (host <= 254 && found == null && System.currentTimeMillis() < deadline) {
            val batch = (host until minOf(host + CONCURRENCY, 255)).toList()
            host += CONCURRENCY
            val results = batch
                .map { last -> async(Dispatchers.IO) { fetch("http://$prefix.$last:$PORT") } }
                .awaitAll()
            found = results.firstOrNull { it != null }
        }
        found
    }

    /** 探一台机器：真的是 dshgo 才返回（`name` 字段是身份判据）。 */
    private fun fetch(base: String): Pair<String, JSONObject>? = runCatching {
        val req = Request.Builder()
            .url("$base/__dshgo__/info")
            .header("accept", "application/json")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optString("name") != "dshgo") return null
            base to json
        }
    }.getOrNull()

    /** 找到就用它把连接列表对齐 —— 和小组件刷新走的是同一条对账逻辑。 */
    private fun adopt(ctx: Context, base: String, json: JSONObject) {
        val id = json.optString("deviceId")
        if (id.isBlank()) return
        val addrs = json.optJSONObject("addrs")
        fun list(key: String): List<String> {
            val arr = addrs?.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        }
        val changed = runCatching {
            ConnectionStore.applyReported(ctx, id, list("lan"), list("tail"))
        }.getOrDefault(0)
        EventLog.event(
            ctx,
            "netscan",
            "found $base device=$id changed=$changed",
        )
    }
}
