package com.dshgo.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 探测每条连接「活着吗、活到哪一层」。
 *
 * ## 四层状态，而不是「在线 / 离线」
 *
 * 手机上遇到的情况本来就有四种，混成两种一定会说错话：
 *
 * 1. **连不上** —— TCP 都到不了：电脑关机、不在同一网络、dshgo 没起。
 * 2. **要密码** —— 端点回了 401，说明电脑好好的，只是没解锁。
 * 3. **dsh web 没起来** —— 插件在应答，但它转发的上游（`dsh web`）没在监听。
 * 4. **在线** —— 上游也通，能直接进。
 *
 * 以前「3」被写成「连到了，但转发层没在监听（电脑上 dshgo 没起来）」，
 * 把两个不同的原因混在同一句里，谁也看不懂到底该去开哪个。
 *
 * ## 探针打什么
 *
 * `/__dshgo__/info` 和 `/__dshgo__/auth/status` 都**不需要先解锁**，
 * 所以从没登录过的电脑也能显示在线、延迟、版本、要不要密码。
 *
 * **「这台机器里有几个会话在跑」拿不到** —— 那要先登录它。所以不显示，
 * 而不是放一个永远空着的「0 个会话」。
 */
object ConnectionProbe {

    /** 单条探测的超时。列表是并发探的，最坏也就等这么久。 */
    private const val TIMEOUT_MS = 2500L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 状态的层级。界面只按这个着色、写文案，不再自己 if-else 拼。 */
    enum class Level { Offline, Locked, NoUpstream, NoProxy, Online }

    /** 一条连接的探测结果。 */
    data class Status(
        /** 插件端点应答了（含 401）。false 表示 TCP 都没通。 */
        val reachable: Boolean = false,
        /** 端点回了 401：电脑在线，只是要访问密码。 */
        val locked: Boolean = false,
        /** 插件在监听转发层。 */
        val listening: Boolean = false,
        /** 上游 `dsh web` 在监听。插件没报这个字段时按「未知」处理成 true。 */
        val upstreamReachable: Boolean = true,
        /** 往返耗时（毫秒）。越小越可能是局域网直连。 */
        val ms: Long = 0L,
        /** 插件的版本；探不到时为空。 */
        val version: String = "",
        /** npm 上的最新版本；插件查不到时为空。 */
        val latestVersion: String = "",
        /** 宿主开了访问密码 —— 连它要先验证。 */
        val needsPassword: Boolean = false,
        /** 上游 `dsh web` 的端口。 */
        val upstreamPort: Int = 0,
        /** 这台电脑的稳定标识（插件算的）；老版本插件为空。 */
        val deviceId: String = "",
        /** 电脑的主机名。 */
        val deviceName: String = "",
        /** 插件自报的局域网地址列表（可能不止一条网卡）。 */
        val lanUrls: List<String> = emptyList(),
        /** 插件自报的 Tailscale 地址列表。 */
        val tailUrls: List<String> = emptyList(),
        /** 失败原因；成功时为空。 */
        val error: String = "",
    ) {
        /** 真正能进（不是「要密码」，也不是「上游没起」）。 */
        val online: Boolean get() = reachable && !locked && upstreamReachable && listening

        val level: Level
            get() = when {
                !reachable -> Level.Offline
                locked -> Level.Locked
                !listening -> Level.NoProxy
                !upstreamReachable -> Level.NoUpstream
                else -> Level.Online
            }

        /** 有插件更新可装。 */
        val updateAvailable: Boolean
            get() = version.isNotEmpty() && latestVersion.isNotEmpty() &&
                UpdateCheck.isNewer(latestVersion, version)
    }

    /**
     * 并发探一批。返回 `url → Status`。
     *
     * 并发而不是串行：五条连接串行各等 2.5 秒就是 12 秒，列表转半天。
     */
    suspend fun probeAll(
        connections: List<Connection>,
        /** 这条地址在 WebView 里已有的 Cookie（主要是 30 天有效的解锁凭据）。 */
        cookieFor: (String) -> String? = { null },
    ): Map<String, Status> =
        withContext(Dispatchers.IO) {
            connections.map { c -> async { c.url to probe(c.url, cookieFor(c.url)) } }
                .awaitAll()
                .toMap()
        }

    suspend fun probe(url: String, cookie: String? = null): Status = withContext(Dispatchers.IO) {
        val base = url.trimEnd('/')
        val started = System.currentTimeMillis()

        // 带上解锁 Cookie 再探：手机里已经有 30 天的解锁凭据时，
        // /info 会正常返回 200 —— 列表就不该再写「需要密码登录」。
        // 以前这里裸探，于是「点进去直接能开」与「列表说要密码」长期自相矛盾。
        val info = runCatching { getJson("$base/__dshgo__/info", cookie) }
            .getOrElse { t ->
                // 开了访问密码时，非导航请求一律 401 —— 那是「这台电脑在线，只是要密码」，
                // 不是「连不上」。以前这里直接判 offline 并把 HTTP 401 标红，把人劝退了。
                //
                // 401 的响应体里带着设备身份（插件从 0.2.0 起会带）：解锁前拿不到
                // /info，只能靠它把这条「要密码」并回同一台电脑，否则一台电脑会有两张卡。
                if (t is HttpError && t.code == 401) {
                    return@withContext Status(
                        reachable = true,
                        locked = true,
                        ms = System.currentTimeMillis() - started,
                        needsPassword = true,
                        deviceId = t.json?.optString("deviceId").orEmpty(),
                        deviceName = t.json?.optString("deviceName").orEmpty(),
                        error = "",
                    )
                }
                return@withContext Status(
                    reachable = false,
                    ms = System.currentTimeMillis() - started,
                    error = friendly(t),
                )
            }

        // 确认对面确实是 dshgo 的转发层，而不是同网段某个碰巧开了端口的东西
        if (info.optString("name") != "dshgo") {
            return@withContext Status(
                reachable = false,
                ms = System.currentTimeMillis() - started,
                error = "这个地址上不是 dshgo",
            )
        }

        val ms = System.currentTimeMillis() - started
        // 密码状态是另一个端点。它失败不影响「在线」这个判断 —— 少一个字段而已
        val needsPassword = runCatching { getJson("$base/__dshgo__/auth/status") }
            .getOrNull()?.optBoolean("hasPassword", false) ?: false

        Status(
            reachable = true,
            listening = info.optBoolean("listening", false),
            // 老版本插件没有这个字段：缺省按 true，免得升级前所有人一起报「上游没起」
            upstreamReachable = info.optBoolean("upstreamReachable", true),
            ms = ms,
            version = info.optString("version"),
            latestVersion = info.optString("latestVersion"),
            needsPassword = needsPassword,
            upstreamPort = info.optInt("upstreamPort", 0),
            deviceId = info.optString("deviceId"),
            deviceName = info.optString("deviceName"),
            lanUrls = info.optJSONObject("addrs")?.optJSONArray("lan").toStringList(),
            tailUrls = info.optJSONObject("addrs")?.optJSONArray("tail").toStringList(),
        )
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }
    }

    /** 非 2xx 的响应。带上状态码与已解析的 body —— 401 里也有东西要看。 */
    private class HttpError(val code: Int, val json: JSONObject?) : Exception("HTTP $code")

    private fun getJson(url: String, cookie: String? = null): JSONObject {
        val parsed = url.toHttpUrlOrNull() ?: error("地址格式不对")
        val builder = Request.Builder().url(parsed).header("Accept", "application/json")
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        val req = builder.build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            // 失败也要把 body 带出去：401 时设备身份就在里面
            if (!resp.isSuccessful) throw HttpError(resp.code, json)
            return json ?: JSONObject()
        }
    }

    /** 把异常翻成用户能懂的一句话。 */
    private fun friendly(t: Throwable): String {
        val m = (t.message ?: "").lowercase()
        return when {
            // 连接被拒：可能是电脑没开，也可能是电脑开着但 dshgo 没起。
            // 分不出来就别假装分得出来，两件事一起说。
            "failed to connect" in m || "econnrefused" in m ->
                "连不上 —— 电脑没开、dshgo 没启动，或不在同一网络"
            "timeout" in m || "timed out" in m ->
                "连不上 —— 不在同一网络？"
            "unable to resolve host" in m ->
                "地址解析不了"
            "地址格式不对" in m -> "地址格式不对"
            "http 401" in m -> "需要密码登录"
            "http 404" in m -> "这个地址上没有 dshgo"
            else -> t.message?.take(60) ?: "探测失败"
        }
    }
}
