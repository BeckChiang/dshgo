package com.dshgo.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.webkit.CookieManager
import com.dshgo.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 小组件数据端点客户端。
 *
 * 为什么不复用 [DshApi]：DshApi 走的是 `/api/<ns>/<method>` 的 RPC 信封，
 * 而小组件端点是一个普通 GET，两者超时预算也不同（小组件要等得起桌面端
 * 那 5 秒的采样，又不能让 provider 挂太久）。这里单独一个精简客户端，
 * 只复用 cookie 与解锁链路。
 */
object WidgetStateClient {

    private const val PATH = "/__dshgo__/widget/state"

    /** 拉多少条会话：最多显示 5 条，多拉一点留给「切工作区」后免请求重绘。 */
    private const val SESSION_LIMIT = 20

    /** 小组件场景要快失败：整个调用 ≤ 9s（< 任务要求的 10s）。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookies)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 结果只区分「能用 / 锁着 / 暂时拿不到」，渲染层据此选降级文案。 */
    sealed interface Result {
        /**
         * 拿到并校验过的原始 JSON，交给 [WidgetStateStore] 落盘。
         *
         * `base` 是**真正成功的那条地址**：小组件底栏的通道标签必须按它算，
         * 不能按「首选地址」猜 —— 在外网挑中隧道、在局域网挑中局域网，猜错就显示错通道。
         */
        data class Ok(val raw: String, val base: String = "") : Result

        /** 桌面端开了访问密码，且本机没有可用的解锁密码。 */
        data object Locked : Result

        /** 网络不通、超时、地址没配、返回体不认识 —— 一律静默降级。 */
        data class Failed(val message: String) : Result
    }

    suspend fun fetch(ctx: Context): Result = withContext(Dispatchers.IO) {
        // 地址不是「入口地址」那么简单：小组件可能在后台、App 没打开过、
        // 用户又刚切过网络 —— 交给 WidgetDevice 按「选定设备 + 当前网络」算。
        // 通道独立检查：小组件不等 App 的当前入口，自己按 TCP 可达性排一遍。
        // 用户在 App 里「明明连着」，小组件却因为挑了另一条（比如在外网挑局域网）
        // 报「未连接」—— 这是最常被吐槽的那个不一致。
        val preferred = WidgetDevice.candidates(ctx)
        if (preferred.isEmpty()) {
            EventLog.event(ctx, "widget", "no-candidates")
            return@withContext Result.Failed("未配置入口地址")
        }
        val bases = reorderByReachability(preferred)
        // 小组件失败以前完全静默：用户只看到「未连接」，日志里一个字都没有，
        // 根本分不清是地址没配、网络不通还是端点报错。每次尝试都落一条。
        EventLog.event(ctx, "widget", "try " + bases.joinToString(",") { short(it) })
        // 解锁 cookie 是按 host 存的：在隧道那条地址上解过锁，切到局域网这条就没有，
        // 小组件于是回「需要解锁」，而 App 里点进去是直通的（用户最早报的 R11 同源）。
        // 同一台电脑的两条通道本来就该共用一份凭据，这里先把它搬过去。
        runCatching { carryUnlockCookie(bases) }

        var lastFailure: Result = Result.Failed("未配置入口地址")
        for (base in bases) {
            when (val r = fetchOne(ctx, base)) {
                is Result.Ok -> {
                    EventLog.event(ctx, "widget", "ok ${short(base)} ${r.raw.length}b")
                    return@withContext r
                }
                // 锁着说明地址是通的，换一条也一样会被锁，直接返回
                is Result.Locked -> {
                    EventLog.event(ctx, "widget", "locked ${short(base)}")
                    return@withContext r
                }
                is Result.Failed -> {
                    lastFailure = r
                    EventLog.event(ctx, "widget", "fail ${short(base)} ${r.message.take(60)}")
                }
            }
        }
        return@withContext lastFailure
    }

    /**
     * 把 `dshgo-unlock` 在候选地址之间搬一份。
     *
     * WebView 的 cookie 按 host 隔离，而同一台电脑的局域网/隧道是两条 host ——
     * 不搬的话「在这条上解过锁、换到那条又要输密码」。
     */
    private fun carryUnlockCookie(bases: List<String>) {
        if (bases.size < 2) return
        val cm = CookieManager.getInstance()
        val source = bases.firstOrNull { (cm.getCookie(it) ?: "").contains(UNLOCK_COOKIE) } ?: return
        val hit = (cm.getCookie(source) ?: "")
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$UNLOCK_COOKIE=") } ?: return
        var changed = false
        for (dst in bases) {
            if (dst == source) continue
            if ((cm.getCookie(dst) ?: "").contains(UNLOCK_COOKIE)) continue
            cm.setCookie(dst, "$hit; path=/; max-age=${30 * 24 * 60 * 60}")
            changed = true
        }
        if (changed) cm.flush()
    }

    private const val UNLOCK_COOKIE = "dshgo-unlock"

    /**
     * 按 TCP 可达性重排候选地址。
     *
     * 每条最多等 600ms：够局域网/隧道握手，又不至于让小组件刷新卡住。
     * 可达的排前面（保持原来的偏好顺序），全不可达就原样返回，让后面的 HTTP 请求给出具体原因。
     */
    private fun reorderByReachability(bases: List<String>): List<String> {
        if (bases.size < 2) return bases
        val reachable = ArrayList<String>(bases.size)
        val others = ArrayList<String>(bases.size)
        for (base in bases) {
            if (tcpReachable(base)) reachable.add(base) else others.add(base)
        }
        return if (reachable.isEmpty()) bases else reachable + others
    }

    /** 日志里只写 host:port，别把整条 URL 塞进事件流水。 */
    private fun short(base: String): String =
        base.removePrefix("http://").removePrefix("https://").trimEnd('/')

    private fun tcpReachable(base: String): Boolean = try {
        val uri = java.net.URI(base)
        val host = uri.host ?: return false
        val port = if (uri.port > 0) uri.port else 80
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), 600)
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** 单条地址的抓取 + 解锁重试。 */
    private suspend fun fetchOne(ctx: Context, base: String): Result = withContext(Dispatchers.IO) {
        try {
            var (code, body) = get(ctx, "$base$PATH?sessions=$SESSION_LIMIT")
            // 未解锁：401 + {"error":{"code":"locked"},"needAuth":true}。
            // 复用 App 里已保存的访问密码解锁一次再重试；拿不到密码就算 locked。
            if (code == 401 || (code == 403 && body.contains("needAuth"))) {
                if (!tryUnlock(ctx, base)) return@withContext Result.Locked
                val retry = get(ctx, "$base$PATH?sessions=$SESSION_LIMIT")
                code = retry.first
                body = retry.second
            }
            if (code != 200) {
                EventLog.event(ctx, "widget", "http $code ${body.take(60)}")
                return@withContext Result.Failed("HTTP $code")
            }
            if (!body.trimStart().startsWith("{")) {
                EventLog.event(ctx, "widget", "not-json ${body.take(60)}")
                return@withContext Result.Failed("响应不是 JSON")
            }
            // 只做结构门禁，真正的解析在 WidgetStateStore.load 里；坏数据不落盘。
            if (!body.contains("\"workspaces\"")) {
                EventLog.event(ctx, "widget", "no-workspaces ${body.take(60)}")
                return@withContext Result.Failed("响应缺少 workspaces")
            }
            // 宿主端点自己失败时会回 `{"error":{"code":"widget-state-failed",...}, "workspaces":[]}`
            // —— 这种「空壳」不能覆盖缓存，否则一次服务端 bug 就把卡片清成「这个工作区还没有会话」。
            if (body.contains("\"widget-state-failed\"")) {
                EventLog.event(ctx, "widget", "endpoint-error ${body.take(120)}")
                return@withContext Result.Failed("端点报错")
            }
            Result.Ok(body, base)
        } catch (t: Throwable) {
            EventLog.event(ctx, "widget", "exc ${(t.message ?: t.javaClass.simpleName).take(80)}")
            Result.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun get(ctx: Context, url: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // 桌面端代理会看 UA 区分「dshgo 客户端」，沿用 UnlockClient 的写法
            .header("User-Agent", UnlockClient.UA)
            .build()
        val http = if (AutoRoute.isLanUrl(url)) physicalClient(ctx) ?: client else client
        http.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    /**
     * 局域网目标要绑物理网卡再发。
     *
     * 内嵌 Tailscale 一开就是「全量接管」，局域网目的地址也会被塞进 tun；
     * 而 tailnet 不认识 192.168.31.0/24，SYN 直接沉底 —— 表现就是「连 3081 超时」。
     * 前台 WebView 因为有既存连接看着正常，后台小组件每次新建连接就全挂。
     * 绑定到 Wi-Fi/以太网（退而求其次蜂窝）的 socketFactory 可以绕开 VPN 直连。
     */
    private fun physicalClient(ctx: Context): OkHttpClient? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        fun caps(n: Network): NetworkCapabilities? = cm.getNetworkCapabilities(n)
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        val net = networks.firstOrNull { n ->
            val c = caps(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } ?: networks.firstOrNull { n ->
            val c = caps(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } ?: return null
        return client.newBuilder()
            .socketFactory(net.socketFactory)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 用 App 里存着的访问密码换解锁 cookie。
     *
     * 为什么要写回 CookieManager：WebViewCookies 这座桥读的就是系统的 CookieManager，
     * 写进去以后 WebView 里的页面和后续 OkHttp 请求都会自动带上，不用另存一份。
     */
    private suspend fun tryUnlock(ctx: Context, base: String): Boolean {
        val password = AppLock.loadPassword(ctx) ?: return false
        return when (val r = UnlockClient.unlock(base, password)) {
            is UnlockClient.Unlock.Ok -> {
                CookieManager.getInstance().setCookie(base, r.cookie)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    CookieManager.getInstance().flush()
                }
                true
            }
            // 桌面端没开密码：直接重试即可
            UnlockClient.Unlock.NotRequired -> true
            else -> false
        }
    }
}
