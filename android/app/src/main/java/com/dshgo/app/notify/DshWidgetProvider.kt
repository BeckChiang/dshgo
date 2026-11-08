package com.dshgo.app.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.dshgo.app.MainActivity
import com.dshgo.app.Prefs
import com.dshgo.app.R
import com.dshgo.app.data.WidgetState
import com.dshgo.app.data.WidgetStateStore
import java.util.Locale

/**
 * 主屏小组件（3×2 会话版）：一眼看到「哪个工作区、几条会话、各占多少上下文、余额」。
 *
 * ## 数据从哪来
 * 端点快照由 [WidgetRefresher] 拉取并缓存到 [WidgetStateStore]，这里只负责**画**。
 * 为什么不让 provider 自己发请求：`onUpdate` 在主线程、必须立刻返回；
 * 而且桌面上的每个实例都会触发一次，重复请求没有意义。
 *
 * 缓存里没有数据时才退回 [SessionWatcher.summary] 的计数（running/approvals），
 * 再没有就显示「未连接」。任何情况下都不显示 0 或占位的假百分比。
 *
 * ## 交互
 * - 整卡 → 打开 App（继续最近会话）；
 * - 头部一排工作区胶囊 → 点哪个切哪个（用户明确不要长按，也不要盲切）；
 * - 审批的「允许/拒绝」已移回通知栏，卡片上不再放按钮。
 */
class DshWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val summary = SessionWatcher.summary.value
        val state = WidgetStateStore.load(ctx)
        ids.forEach { manager.updateAppWidget(it, buildViews(ctx, summary, state)) }

        // 小组件刚被放到桌面（或系统刷新）时，确保状态是活的：
        // 用户可能刚重装、刚重启，SessionWatcher 还没起来。随后顺手拉一次快照
        // ——没网/没配地址时 refresh 内部会静默返回，留着缓存或降级文案。
        if (Prefs(ctx).notifyEnabled()) SessionWatcher.ensureRunning(ctx)
        WidgetRefresher.refresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        // 点工作区胶囊：本地切换 + 立刻重绘。不先等网络 —— 拉取慢一点也不能让点击没反应。
        if (intent.action == ACTION_NEXT_WORKSPACE) {
            val state = WidgetStateStore.load(ctx)
            if (state != null) {
                WidgetStateStore.cycleWorkspace(ctx, state)
                pushAll(ctx, SessionWatcher.summary.value, state)
            }
            WidgetRefresher.refresh(ctx, force = true)
            return
        }
        // 点右上角刷新：先把缓存画一遍（立刻有反应），再强制拉新数据。
        if (intent.action == ACTION_REFRESH) {
            Toast.makeText(ctx, "正在刷新小组件…", Toast.LENGTH_SHORT).show()
            val cached = WidgetStateStore.load(ctx)
            if (cached != null) pushAll(ctx, SessionWatcher.summary.value, cached)
            WidgetRefresher.refresh(ctx, force = true)
            return
        }
        // 点某个工作区胶囊：直接切到它，先本地重绘再拉一次快照。
        if (intent.action == ACTION_SELECT_WORKSPACE) {
            val id = intent.getStringExtra(EXTRA_WORKSPACE_ID)
            if (!id.isNullOrBlank()) {
                val state = WidgetStateStore.load(ctx)
                if (state != null) {
                    WidgetStateStore.setSelectedWorkspaceId(ctx, id)
                    pushAll(ctx, SessionWatcher.summary.value, state)
                }
                WidgetRefresher.refresh(ctx, force = true)
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {

        /** 自定义广播：切工作区（顺序轮换，仅用于「+N」收口时）。显式 Intent 指向自己，不受 Android 8 隐式广播限制。 */
        const val ACTION_NEXT_WORKSPACE = "com.dshgo.app.WIDGET_NEXT_WORKSPACE"

        /** 自定义广播：切到指定工作区（点胶囊）。 */
        const val ACTION_SELECT_WORKSPACE = "com.dshgo.app.WIDGET_SELECT_WORKSPACE"

        /** 工作区 id 的 extra 名。 */
        const val EXTRA_WORKSPACE_ID = "workspace_id"

        /** 头部最多并排几枚工作区胶囊，多的收进「+N」。3 枚 3×2 宽度刚好。 */
        private const val MAX_CHIPS = 3

        /** 卡片上最多显示几条会话。加了摘要行之后一行更占地方，3 条是 3×2 的舒适上限。 */
        private const val MAX_SESSIONS = 4

        /** 点右上角刷新：强制拉一次快照，跳过节流。 */
        private const val ACTION_REFRESH = "com.dshgo.app.widget.REFRESH"

        /**
         * 把状态推给桌面上所有实例。
         *
         * 用户没放过小组件时直接返回 —— 不做任何事，代价可忽略。
         */
        fun update(ctx: Context, summary: SessionWatcher.Summary) {
            runCatching {
                pushAll(ctx, summary, WidgetStateStore.load(ctx))
            }
        }

        private fun pushAll(ctx: Context, summary: SessionWatcher.Summary, state: WidgetState?) {
            runCatching {
                val manager = AppWidgetManager.getInstance(ctx) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(ctx, DshWidgetProvider::class.java),
                )
                if (ids.isEmpty()) return
                ids.forEach { manager.updateAppWidget(it, buildViews(ctx, summary, state)) }
            }
        }

        private fun buildViews(
            ctx: Context,
            summary: SessionWatcher.Summary,
            state: WidgetState?,
        ): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_dsh).apply {

            val workspaces = state?.workspaces.orEmpty()
            val workspace = state?.workspace(WidgetStateStore.selectedWorkspaceId(ctx))

            // 工作区切换：一排胶囊，点谁切谁。
            // 之前只有一个胶囊、点它顺序轮换 —— 工作区一多就变成盲切，用户明确说不要。
            removeAllViews(R.id.widget_chips)
            val chips = workspaces.take(MAX_CHIPS)
            if (chips.isEmpty()) {
                setViewVisibility(R.id.widget_chips, View.GONE)
                setViewVisibility(R.id.widget_workspace, View.VISIBLE)
                setTextViewText(R.id.widget_workspace, workspace?.title ?: "DSH")
                setOnClickPendingIntent(
                    R.id.widget_workspace,
                    appTap(ctx, MainActivity.ACTION_RECENT_SESSION, 1),
                )
            } else {
                setViewVisibility(R.id.widget_chips, View.VISIBLE)
                setViewVisibility(R.id.widget_workspace, View.GONE)
                chips.forEachIndexed { index, ws ->
                    val chip = RemoteViews(ctx.packageName, R.layout.widget_chip)
                    val selected = ws.id == workspace?.id
                    chip.setTextViewText(R.id.widget_chip_text, ws.title)
                    chip.setInt(
                        R.id.widget_chip_text,
                        "setBackgroundResource",
                        if (selected) R.drawable.widget_pill else R.drawable.widget_chip_plain,
                    )
                    chip.setTextColor(
                        R.id.widget_chip_text,
                        ctx.getColor(if (selected) R.color.wg_pill_text else R.color.wg_chip_text),
                    )
                    chip.setOnClickPendingIntent(
                        R.id.widget_chip_text,
                        selectWorkspace(ctx, ws.id, 30 + index),
                    )
                    addView(R.id.widget_chips, chip)
                }
                // 超过 3 个的用「+N」收口，点它顺序轮换（数量少时不会出现）
                if (workspaces.size > chips.size) {
                    val more = RemoteViews(ctx.packageName, R.layout.widget_chip)
                    more.setTextViewText(R.id.widget_chip_text, "+" + (workspaces.size - chips.size))
                    more.setInt(R.id.widget_chip_text, "setBackgroundResource", R.drawable.widget_chip_plain)
                    more.setTextColor(R.id.widget_chip_text, ctx.getColor(R.color.wg_chip_text))
                    more.setOnClickPendingIntent(R.id.widget_chip_text, nextWorkspace(ctx))
                    addView(R.id.widget_chips, more)
                }
            }
            setOnClickPendingIntent(
                R.id.widget_root,
                appTap(ctx, MainActivity.ACTION_RECENT_SESSION, 2),
            )

            // 余额：sources 说拿不到就整块隐藏。一个错的钱数比没有钱数更糟。
            val balance = state?.balance
            val balanceSource = state?.sources?.get("balance") ?: "ok"
            if (balance != null && balanceSource == "ok") {
                setViewVisibility(R.id.widget_balance_box, View.VISIBLE)
                setTextViewText(R.id.widget_balance_value, money(balance))
            } else {
                setViewVisibility(R.id.widget_balance_box, View.GONE)
            }

            setOnClickPendingIntent(R.id.widget_refresh, refreshTap(ctx, 60))

            val sessions = workspace?.sessions.orEmpty().take(MAX_SESSIONS)
            removeAllViews(R.id.widget_rows)
            if (sessions.isEmpty()) {
                setViewVisibility(R.id.widget_rows, View.GONE)
                setViewVisibility(R.id.widget_fallback, View.VISIBLE)
                setTextViewText(R.id.widget_fallback, fallbackText(ctx, state, summary))
            } else {
                setViewVisibility(R.id.widget_rows, View.VISIBLE)
                setViewVisibility(R.id.widget_fallback, View.GONE)
                val approvalRows = approvalRows(sessions, summary)
                sessions.forEachIndexed { index, session ->
                    val row = RemoteViews(ctx.packageName, R.layout.widget_dsh_row)
                    row.setImageViewResource(
                        R.id.widget_row_dot,
                        when {
                            approvalRows.contains(index) -> R.drawable.widget_dot_approval
                            session.isRunning -> R.drawable.widget_dot_running
                            else -> R.drawable.widget_dot_idle
                        },
                    )
                    row.setTextViewText(
                        R.id.widget_row_title,
                        session.title.ifBlank { ctx.getString(R.string.widget_untitled_session) },
                    )
                    // 摘要：端点没给就整行隐藏，不留一条空白。
                    val summary = session.summary?.trim().orEmpty()
                    if (summary.isEmpty()) {
                        row.setViewVisibility(R.id.widget_row_summary, View.GONE)
                    } else {
                        row.setViewVisibility(R.id.widget_row_summary, View.VISIBLE)
                        row.setTextViewText(R.id.widget_row_summary, summary)
                    }
                    // 没有上下文数据就显示「—」：0% 会被读成「几乎为空」，是错误信息。
                    row.setTextViewText(
                        R.id.widget_row_pct,
                        session.contextPercent?.let { "$it%" } ?: "—",
                    )
                    row.setViewVisibility(
                        R.id.widget_row_divider,
                        if (index == sessions.lastIndex) View.GONE else View.VISIBLE,
                    )
                    // 点这一行 → 直接进这条会话（写 localStorage + 重载，见 MainActivity）。
                    row.setOnClickPendingIntent(
                        R.id.widget_row_title,
                        sessionTap(ctx, session.id, 40 + index),
                    )
                    row.setOnClickPendingIntent(
                        R.id.widget_row_summary,
                        sessionTap(ctx, session.id, 40 + index),
                    )
                    row.setOnClickPendingIntent(
                        R.id.widget_row_dot,
                        sessionTap(ctx, session.id, 40 + index),
                    )
                    addView(R.id.widget_rows, row)
                }
            }

            // 左下角：待审批优先，其次正在跑的会话总数；右下角：DeepSeek 高峰/低峰。
            // 审批是要人动手的事，比"在跑几个"更该被看见，所以它抢占这一格。
            val approvals = summary.approvals
            val running = summary.running
            if (approvals > 0) {
                setViewVisibility(R.id.widget_running, View.VISIBLE)
                setTextViewText(R.id.widget_running, "$approvals 个待审批")
            } else if (running > 0) {
                setViewVisibility(R.id.widget_running, View.VISIBLE)
                setTextViewText(R.id.widget_running, "$running 个在跑")
            } else {
                setViewVisibility(R.id.widget_running, View.GONE)
            }
            val pricing = state?.pricingLabel?.trim().orEmpty()
            if (pricing.isEmpty()) {
                setViewVisibility(R.id.widget_footer, View.GONE)
            } else {
                setViewVisibility(R.id.widget_footer, View.VISIBLE)
                setTextViewText(R.id.widget_footer, pricing)
            }
        }

        /**
         * 哪几行标「有审批」。
         *
         * 端点目前没有按会话给出审批（`ApprovalAsk` 里也没有 sessionId），所以：
         * 会话自己带了 pendingApproval 就信它；否则把审批算到**第一条 running** 上
         * ——总比整卡没有橙点、用户不知道有东西在等要好。将来端点补字段后这里自动准确。
         */
        private fun approvalRows(
            sessions: List<WidgetState.Session>,
            summary: SessionWatcher.Summary,
        ): Set<Int> {
            val explicit = sessions.indices.filter { sessions[it].pendingApproval }
            if (explicit.isNotEmpty()) return explicit.toSet()
            if (summary.approvals <= 0) return emptySet()
            val firstRunning = sessions.indexOfFirst { it.isRunning }
            return if (firstRunning >= 0) setOf(firstRunning) else emptySet()
        }

        /** 没有会话可画时的文案。顺序：缓存缺失 → 全局计数 → 空工作区。 */
        private fun fallbackText(
            ctx: Context,
            state: WidgetState?,
            summary: SessionWatcher.Summary,
        ): String {
            if (state == null) {
                return when {
                    summary.approvals > 0 ->
                        ctx.getString(R.string.widget_fallback_approval, summary.approvals)
                    summary.running > 0 ->
                        ctx.getString(R.string.widget_fallback_running, summary.running)
                    WidgetRefresher.lastOutcome == WidgetRefresher.Outcome.Locked ->
                        ctx.getString(R.string.widget_fallback_locked)
                    else -> ctx.getString(R.string.widget_fallback_offline)
                }
            }
            if (summary.approvals > 0) {
                return ctx.getString(R.string.widget_fallback_approval, summary.approvals)
            }
            return ctx.getString(R.string.widget_fallback_empty)
        }

        /** 「¥ 146.59」：整数部分不补零，小数固定两位。 */
        private fun money(balance: WidgetState.Balance): String {
            // 只保留数字：货币符号与「余额」标签都在布局里拿掉了，多一个字符就少一分可读性。
            return String.format(Locale.US, "%.2f", balance.amount)
        }

        private fun nextWorkspace(ctx: Context): PendingIntent {
            // 显式 Intent：Android 8+ 对清单注册的隐式广播有限制，指向自己最稳。
            val i = Intent(ctx, DshWidgetProvider::class.java).setAction(ACTION_NEXT_WORKSPACE)
            return PendingIntent.getBroadcast(
                ctx,
                21,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun selectWorkspace(ctx: Context, workspaceId: String, code: Int): PendingIntent {
            val i = Intent(ctx, DshWidgetProvider::class.java)
                .setAction(ACTION_SELECT_WORKSPACE)
                .putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            return PendingIntent.getBroadcast(
                ctx,
                code,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** 点某一行 → 打开这条会话。id 走和通知同一条通道（MainActivity 的 consumeOpenSession）。 */
        /** 刷新按钮 → 广播回 provider 自己，再走 WidgetRefresher（网络只在那一条路上）。 */
        private fun refreshTap(ctx: Context, code: Int): PendingIntent {
            val intent = Intent(ctx, DshWidgetProvider::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(
                ctx,
                code,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun sessionTap(ctx: Context, sessionId: String, code: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .putExtra(NotificationCenter.EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                ctx,
                code,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun appTap(ctx: Context, action: String, code: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                ctx,
                code,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
