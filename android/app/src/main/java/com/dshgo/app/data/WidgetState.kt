package com.dshgo.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小组件一屏要显示的数据 —— `/__dshgo__/widget/state` 的本地模型。
 *
 * 为什么单独建模型而不是让 RemoteViews 直接读 JSON：
 * 1. 解析只做一次，几个实例共用同一份结果；
 * 2. 字段缺失/类型漂移（插件版本前后）在这里收口，渲染层不需要到处 `optXxx`；
 * 3. 宿主侧以后加字段不会把小组件搞崩。
 */
data class WidgetState(
    val workspaces: List<Workspace>,
    val schedules: List<Schedule>,
    val balance: Balance?,
    /** 各数据源的可用性（"ok" / 其它）。拿不到位的数据源宁可隐藏也不显示 0。 */
    val sources: Map<String, String>,
    val updatedAt: String?,
    /** 高峰/低峰小字，例如「低峰 · 00:30–08:30 · 5 折」；没有就隐藏。 */
    val pricingLabel: String? = null,
) {

    data class Workspace(
        val id: String,
        val title: String,
        val sessionCount: Int,
        val sessions: List<Session>,
    )

    /**
     * 一条会话。
     *
     * `status` 只有 idle/running/live 三态；审批状态端点里**没有**按会话给出，
     * 所以 [pendingApproval] 只是尽力读 —— 读不到时由渲染层用全局审批数兜底。
     */
    data class Session(
        val id: String,
        val title: String,
        val status: String,
        val contextPercent: Int?,
        val pendingApproval: Boolean,
        /** 最后一条可读消息里「摘要」那一节；端点上没有就为 null，渲染层隐藏这一行。 */
        val summary: String? = null,
        /** 最后一条消息里「待完成」那几条（最多 3 条）；通知正文用这个。 */
        val todo: List<String> = emptyList(),
        /** 是否被置顶（端点暂未稳定提供，缺省 false；有了就在运行组内加权）。 */
        val pinned: Boolean = false,
    ) {
        val isRunning: Boolean get() = status == "running" || status == "live"
    }

    data class Schedule(
        val id: String,
        val title: String,
        val status: String,
        val scheduledAt: String?,
        val workspaceTitle: String?,
        val sessionTitle: String?,
    )

    data class Balance(val amount: Double, val currency: String)

    /** 按 id 取工作区；id 找不到（工作区被删/改名）时退回第一个，避免卡片空白。 */
    fun workspace(id: String?): Workspace? =
        workspaces.firstOrNull { it.id == id } ?: workspaces.firstOrNull()

    companion object {

        /** 解析失败直接抛，由调用方静默降级 —— 半截数据比没有数据更误导人。 */
        fun parse(root: JSONObject): WidgetState {
            val workspaces = ArrayList<Workspace>()
            root.optJSONArray("workspaces")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    val id = w.optString("id")
                    if (id.isEmpty()) continue
                    workspaces.add(
                        Workspace(
                            id = id,
                            title = w.optString("title").ifBlank { "DSH" },
                            sessionCount = w.optInt("sessionCount", 0),
                            sessions = sessions(w.optJSONArray("sessions")),
                        ),
                    )
                }
            }

            val schedules = ArrayList<Schedule>()
            root.optJSONArray("schedules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    schedules.add(
                        Schedule(
                            id = s.optString("id"),
                            title = s.optString("title"),
                            status = s.optString("status", "active"),
                            scheduledAt = s.optString("scheduledAt").ifBlank { null },
                            workspaceTitle = s.optString("workspaceTitle").ifBlank { null },
                            sessionTitle = s.optString("sessionTitle").ifBlank { null },
                        ),
                    )
                }
            }

            val sources = HashMap<String, String>()
            root.optJSONObject("sources")?.let { obj ->
                obj.keys().forEach { key -> sources[key] = obj.optString(key) }
            }

            val balance = root.optJSONObject("balance")
                ?.takeIf { it.has("amount") }
                ?.let {
                    Balance(
                        amount = it.optDouble("amount", Double.NaN),
                        currency = it.optString("currency").ifBlank { "CNY" },
                    )
                }
                ?.takeIf { !it.amount.isNaN() }

            return WidgetState(
                workspaces = workspaces,
                schedules = schedules,
                balance = balance,
                sources = sources,
                updatedAt = root.optString("updatedAt").ifBlank { null },
                pricingLabel = root.optString("pricingLabel").ifBlank { null },
            )
        }

        private fun sessions(arr: JSONArray?): List<Session> {
            if (arr == null) return emptyList()
            val out = ArrayList<Session>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val id = s.optString("id")
                if (id.isEmpty()) continue
                out.add(
                    Session(
                        id = id,
                        title = s.optString("title"),
                        status = s.optString("status", "idle"),
                        // 端点给的可能是 double；取整到百分比，没有就给 null（渲染成 —）
                        contextPercent = if (s.has("contextPercent") && !s.isNull("contextPercent")) {
                            s.optDouble("contextPercent").takeIf { !it.isNaN() }?.toInt()
                        } else null,
                        pendingApproval = s.optBoolean("approval", false) ||
                            s.optBoolean("pendingApproval", false) ||
                            s.optInt("approvals", 0) > 0,
                        summary = if (s.isNull("summary")) null else scrubSummary(s.optString("summary")),
                        todo = s.optJSONArray("todo")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { t -> t.isNotBlank() } }
                        }.orEmpty(),
                        pinned = s.optBoolean("pinned", false),
                    ),
                )
            }
            return out
        }

    /**
     * 摘要清洗：去掉「起」「止」这两个纯标记与时间轴行。
     *
     * 标记是给提取用的，不该出现在卡片上。桌面端插件已经会剥掉，但插件更新要重启
     * DSH 才生效 —— App 这边再兜一层，两边任一没更新时用户也不会看到「起」。
     */
    private val MARK_ONLY = Regex("^(起|开始|止|结束)\\s*[:：]?$")
    private val TIME_BOUND = Regex("^(起|开始|止|结束)\\s*[:：]?\\s*\\d{1,2}[:：]\\d{2}.*$")
    private val MSG_REF = Regex("[（(]\\s*m\\d{3,}[^）)]*[）)]")

    internal fun scrubSummary(raw: String): String? {
        val text = MSG_REF.replace(raw, " ")
        val lines = text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { MARK_ONLY.matches(it) }
            .filterNot { TIME_BOUND.matches(it) }
        val joined = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return joined.takeIf { it.isNotEmpty() }?.let { if (it.length > 100) it.take(100) + "…" else it }
    }
    }
}

/**
 * 小组件数据的本地缓存 + 当前选中的工作区。
 *
 * 为什么缓存原始 JSON 而不是模型：widget 的 `onUpdate` 是同步的、且可能发生在
 * 冷启动第一帧，那时没有网络也没有协程结果 —— 有缓存就能立刻画出上一次的界面，
 * 网络结果回来后再覆盖；没缓存才退到降级文案。
 */
object WidgetStateStore {

    private const val FILE = "dsh_widget_state"
    private const val KEY_JSON = "state_json"
    private const val KEY_SAVED_AT = "saved_at"
    private const val KEY_WORKSPACE = "workspace_id"
    private const val KEY_PAGE_PREFIX = "page_"

    /** 两页的名字：会话列表 / 工作区选择。 */
    const val PAGE_SESSIONS = "sessions"
    const val PAGE_WORKSPACES = "workspaces"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(ctx: Context, rawJson: String) {
        val now = System.currentTimeMillis()
        val previous = load(ctx)
        val previousAt = savedAt(ctx)
        val merged = runCatching {
            val fresh = WidgetState.parse(JSONObject(rawJson))
            // 宿主偶尔会回一份「少了几个会话」的快照（列表正在重建、limit 被截断）。
            // 直接覆盖会让桌面上正在跑的几条会话整行消失；5 分钟内的旧快照里
            // 还有这些 id，就把它们补回来 —— 宁可多显示一条已结束的，也别丢在跑的。
            keepMissing(fresh, previous, previousAt, now)
        }.getOrNull()
        val json = if (merged != null) toJson(merged).toString() else rawJson
        sp(ctx).edit()
            .putString(KEY_JSON, json)
            .putLong(KEY_SAVED_AT, now)
            .commit()
    }

    /** 新快照缺的会话，如果旧快照里有、且旧快照还新鲜，就补回去。 */
    private fun keepMissing(
        fresh: WidgetState,
        previous: WidgetState?,
        previousAt: Long,
        now: Long,
    ): WidgetState {
        if (previous == null) return fresh
        if (previousAt <= 0L || now - previousAt > 5 * 60 * 1000L) return fresh
        val have = HashMap<String, WidgetState.Session>()
        fresh.workspaces.forEach { ws -> ws.sessions.forEach { have[it.id] = it } }
        var added = 0
        val spaces = fresh.workspaces.map { ws ->
            val old = previous.workspaces.firstOrNull { it.id == ws.id } ?: return@map ws
            val missing = old.sessions.filter { it.id !in have }
            if (missing.isEmpty()) ws else { added += missing.size; ws.copy(sessions = ws.sessions + missing) }
        }
        return if (added == 0) fresh else fresh.copy(workspaces = spaces)
    }

    /** 把模型写回 JSON：只在 [save] 的合并路径上用，字段与 [WidgetState.parse] 对称。 */
    private fun toJson(s: WidgetState): JSONObject = JSONObject().apply {
        put("updatedAt", s.updatedAt ?: JSONObject.NULL)
        put("pricingLabel", s.pricingLabel ?: JSONObject.NULL)
        put("balance", s.balance?.let {
            JSONObject().put("amount", it.amount).put("currency", it.currency)
        } ?: JSONObject.NULL)
        put("workspaces", JSONArray().apply {
            s.workspaces.forEach { ws ->
                put(JSONObject().apply {
                    put("id", ws.id)
                    put("title", ws.title)
                    put("sessionCount", ws.sessionCount)
                    put("sessions", JSONArray().apply {
                        ws.sessions.forEach { ss ->
                            put(JSONObject().apply {
                                put("id", ss.id)
                                put("title", ss.title)
                                put("status", ss.status)
                                put("contextPercent", ss.contextPercent ?: JSONObject.NULL)
                                put("pendingApproval", ss.pendingApproval)
                                put("summary", ss.summary ?: JSONObject.NULL)
                                put("pinned", ss.pinned)
                                put("todo", JSONArray().apply { ss.todo.forEach { put(it) } })
                            })
                        }
                    })
                })
            }
        })
    }

    fun load(ctx: Context): WidgetState? {
        val raw = sp(ctx).getString(KEY_JSON, null) ?: return null
        return runCatching { WidgetState.parse(JSONObject(raw)) }.getOrNull()
    }

    fun savedAt(ctx: Context): Long = sp(ctx).getLong(KEY_SAVED_AT, 0L)

    /**
     * 当前显示的是哪一页：`sessions` 或 `workspaces`。
     *
     * 按组件实例 id 分开存：桌面上可能放了两个组件、各自停在不同页，
     * 共用一个值会让它们互相跳页。
     */
    fun page(ctx: Context, appWidgetId: Int): String =
        sp(ctx).getString(KEY_PAGE_PREFIX + appWidgetId, PAGE_SESSIONS) ?: PAGE_SESSIONS

    fun setPage(ctx: Context, appWidgetId: Int, page: String) {
        sp(ctx).edit().putString(KEY_PAGE_PREFIX + appWidgetId, page).commit()
    }

    fun clearPage(ctx: Context, appWidgetId: Int) {
        sp(ctx).edit().remove(KEY_PAGE_PREFIX + appWidgetId).commit()
    }

    fun selectedWorkspaceId(ctx: Context): String? =
        sp(ctx).getString(KEY_WORKSPACE, null)?.ifBlank { null }

    fun setSelectedWorkspaceId(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_WORKSPACE, id).commit()
    }

    /**
     * 切到下一个工作区并返回它。
     *
     * 选中项按 id 存（不是下标）：工作区增删之后下标会错位，
     * 而 id 不会 —— 用户选的那个工作区下次还认得出来。
     */
    fun cycleWorkspace(ctx: Context, state: WidgetState): WidgetState.Workspace? {
        val list = state.workspaces
        if (list.isEmpty()) return null
        val current = selectedWorkspaceId(ctx)
        val idx = list.indexOfFirst { it.id == current }.coerceAtLeast(0)
        val next = list[(idx + 1) % list.size]
        setSelectedWorkspaceId(ctx, next.id)
        return next
    }


}
