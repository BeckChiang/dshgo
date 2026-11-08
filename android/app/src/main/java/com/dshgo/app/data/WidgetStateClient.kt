package com.dshgo.app.data

import android.content.Context
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
        /** 拿到并校验过的原始 JSON，交给 [WidgetStateStore] 落盘。 */
        data class Ok(val raw: String) : Result

        /** 桌面端开了访问密码，且本机没有可用的解锁密码。 */
        data object Locked : Result

        /** 网络不通、超时、地址没配、返回体不认识 —— 一律静默降级。 */
        data class Failed(val message: String) : Result
    }

    suspend fun fetch(ctx: Context): Result = withContext(Dispatchers.IO) {
        val base = Prefs(ctx).entryUrl().trim().trimEnd('/')
        if (base.isEmpty()) return@withContext Result.Failed("未配置入口地址")

        try {
            var (code, body) = get("$base$PATH?sessions=$SESSION_LIMIT")
            // 未解锁：401 + {"error":{"code":"locked"},"needAuth":true}。
            // 复用 App 里已保存的访问密码解锁一次再重试；拿不到密码就算 locked。
            if (code == 401 || (code == 403 && body.contains("needAuth"))) {
                if (!tryUnlock(ctx, base)) return@withContext Result.Locked
                val retry = get("$base$PATH?sessions=$SESSION_LIMIT")
                code = retry.first
                body = retry.second
            }
            if (code != 200) return@withContext Result.Failed("HTTP $code")
            if (!body.trimStart().startsWith("{")) return@withContext Result.Failed("响应不是 JSON")
            // 只做结构门禁，真正的解析在 WidgetStateStore.load 里；坏数据不落盘。
            if (!body.contains("\"workspaces\"")) return@withContext Result.Failed("响应缺少 workspaces")
            Result.Ok(body)
        } catch (t: Throwable) {
            Result.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun get(url: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // 桌面端代理会看 UA 区分「dshgo 客户端」，沿用 UnlockClient 的写法
            .header("User-Agent", UnlockClient.UA)
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
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
