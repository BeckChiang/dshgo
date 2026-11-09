package com.dshgo.app.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
        ids.forEach { manager.updateAppWidget(it, buildViews(ctx, summary, state, it)) }
        // 列表数据在 RemoteViewsFactory 里，改完必须显式通知，否则滑动列表停在旧内容。
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)

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
        // 翻页：会话页 ↔ 工作区页。点「+N」或「返回」都走这里。
        if (intent.action == ACTION_SWITCH_PAGE) {
            val id = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val page = intent.getStringExtra(EXTRA_PAGE) ?: WidgetStateStore.PAGE_SESSIONS
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetStateStore.setPage(ctx, id, page)
                WidgetStateStore.load(ctx)?.let { pushAll(ctx, SessionWatcher.summary.value, it) }
            }
            return
        }
        // 工作区页点了某一项：记住选择、翻回会话页、再拉一次快照。
        if (intent.action == ACTION_PICK_WORKSPACE) {
            val id = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID)
            if (!workspaceId.isNullOrBlank()) {
                WidgetStateStore.setSelectedWorkspaceId(ctx, workspaceId)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetStateStore.setPage(ctx, id, WidgetStateStore.PAGE_SESSIONS)
                }
                WidgetStateStore.load(ctx)?.let { pushAll(ctx, SessionWatcher.summary.value, it) }
                WidgetRefresher.refresh(ctx, force = true)
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    override fun onDeleted(ctx: Context, appWidgetIds: IntArray) {
        // 实例被删掉后清掉它的翻页状态，避免 id 复用后一放上桌面就停在工作区页。
        appWidgetIds.forEach { WidgetStateStore.clearPage(ctx, it) }
        super.onDeleted(ctx, appWidgetIds)
    }

    companion object {

        /** 自定义广播：切工作区（顺序轮换，仅用于「+N」收口时）。显式 Intent 指向自己，不受 Android 8 隐式广播限制。 */
        const val ACTION_NEXT_WORKSPACE = "com.dshgo.app.WIDGET_NEXT_WORKSPACE"

        /** 自定义广播：切到指定工作区（点胶囊）。 */
        const val ACTION_SELECT_WORKSPACE = "com.dshgo.app.WIDGET_SELECT_WORKSPACE"

        /** 工作区 id 的 extra 名。 */
        const val EXTRA_WORKSPACE_ID = "workspace_id"

        /** 翻页：`sessions` ↔ `workspaces`。 */
        const val ACTION_SWITCH_PAGE = "com.dshgo.app.WIDGET_SWITCH_PAGE"

        /** 在工作区页选了某个工作区（集合视图的模板广播）。 */
        const val ACTION_PICK_WORKSPACE = "com.dshgo.app.WIDGET_PICK_WORKSPACE"

        /** 翻页传的参数：目标页名。 */
        const val EXTRA_PAGE = "page"

        /** 集合视图点击时回传的组件实例号，多实例下各自翻页互不影响。 */
        const val EXTRA_APPWIDGET_ID = "appwidget_id"

        /** 头部最多并排几枚工作区胶囊，多的收进「+N」。3 枚 3×2 宽度刚好。 */
        private const val MAX_CHIPS = 3

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
                ids.forEach { manager.updateAppWidget(it, buildViews(ctx, summary, state, it)) }
                // 与 onUpdate 同理：集合视图的数据要单独通知一次才会重建。
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_ws_list)
            }
        }

        private fun buildViews(
            ctx: Context,
            summary: SessionWatcher.Summary,
            state: WidgetState?,
            appWidgetId: Int,
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
                    more.setOnClickPendingIntent(
                        R.id.widget_chip_text,
                        pageTap(ctx, appWidgetId, WidgetStateStore.PAGE_WORKSPACES),
                    )
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

            // 会话列表交给 WidgetListService：只有集合视图（ListView）能滚动，
            // 显示几条由 Factory 按可用高度决定，这里只负责接线、空列表文案与点击模板。
            // 必须用带 appWidgetId 的重载：两参版本是 API 31 才加的，minSdk 26 上会崩。
            applyRowPadding(ctx, this, appWidgetId)
            val page = WidgetStateStore.page(ctx, appWidgetId)
            val onWorkspaces = page == WidgetStateStore.PAGE_WORKSPACES
            setViewVisibility(
                R.id.widget_page_sessions,
                if (onWorkspaces) View.GONE else View.VISIBLE,
            )
            setViewVisibility(
                R.id.widget_page_workspaces,
                if (onWorkspaces) View.VISIBLE else View.GONE,
            )
            // 工作区页不显示胶囊条：这一页本身就是选择器，再叠一排胶囊只会让人点错。
            if (onWorkspaces) setViewVisibility(R.id.widget_chips, View.GONE)
            // 鲸鱼图标兼作「工作区菜单」入口：胶囊放得下时直接点胶囊，放不下时点这里。
            setOnClickPendingIntent(
                R.id.widget_whale,
                pageTap(ctx, appWidgetId, WidgetStateStore.PAGE_WORKSPACES),
            )
            setOnClickPendingIntent(
                R.id.widget_ws_back,
                pageTap(ctx, appWidgetId, WidgetStateStore.PAGE_SESSIONS),
            )
            setTextViewText(R.id.widget_fallback, fallbackText(ctx, state, summary))
            if (onWorkspaces) {
                val wsIntent = Intent(ctx, WorkspaceListService::class.java)
                    .putExtra(WorkspaceListService.EXTRA_APPWIDGET_ID, appWidgetId)
                setRemoteAdapter(appWidgetId, R.id.widget_ws_list, wsIntent)
                setPendingIntentTemplate(R.id.widget_ws_list, pickWorkspaceTemplate(ctx, appWidgetId))
                setEmptyView(R.id.widget_ws_list, R.id.widget_fallback)
            } else {
                setRemoteAdapter(
                    appWidgetId,
                    R.id.widget_list,
                    Intent(ctx, WidgetListService::class.java),
                )
                setPendingIntentTemplate(R.id.widget_list, sessionTemplate(ctx))
                setEmptyView(R.id.widget_list, R.id.widget_fallback)
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
            val status = WidgetRefresher.statusText().trim()
            val footer = listOf(pricing, status).filter { it.isNotEmpty() }.joinToString(" · ")
            if (footer.isEmpty()) {
                setViewVisibility(R.id.widget_footer, View.GONE)
            } else {
                setViewVisibility(R.id.widget_footer, View.VISIBLE)
                setTextViewText(R.id.widget_footer, footer)
            }
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

        /**
         * 让列表停在整数行上：先按可用高度算出能放几整行，剩下的余量交给预置垫片。
         *
         * RemoteViews 在 minSdk 26 上不能动态改高度（`setViewLayoutHeight` 是 API 31 才有的），
         * 所以布局里预置了 0/8/16/24/32/40dp 六档垫片，这里只挑一档显示，其余隐藏。
         * 效果：卡片底边永远落在整行边界上，不会露出半截行。
         */
        private fun applyRowPadding(ctx: Context, views: RemoteViews, appWidgetId: Int) {
            val padIds = intArrayOf(
                R.id.widget_row_pad0, R.id.widget_row_pad1, R.id.widget_row_pad2,
                R.id.widget_row_pad3, R.id.widget_row_pad4, R.id.widget_row_pad5,
            )
            val density = ctx.resources.displayMetrics.density.toDouble()
            val options = runCatching {
                AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId)
            }.getOrNull()
            val heightPx = (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0)
            val heightDp = heightPx / density
            // 头部一行（鲸鱼/胶囊/余额）+ 上下内边距 + 底栏，约占 78dp；拿不到尺寸时按 3×2 的典型高度退算。
            val availableDp = (if (heightDp > 0.0) heightDp else 128.0) - HEADER_AND_FOOTER_DP
            val rows = (availableDp / ROW_HEIGHT_DP).toInt().coerceAtLeast(1)
            val remainderDp = (availableDp - rows * ROW_HEIGHT_DP).coerceIn(0.0, 40.0)
            val padIndex = (remainderDp / 8.0).toInt().coerceIn(0, padIds.size - 1)
            padIds.forEachIndexed { index, id ->
                views.setViewVisibility(id, if (index == padIndex) View.VISIBLE else View.GONE)
            }
        }

        /** 与 `widget_dsh_row.xml` 的根高度保持一致。 */
        private const val ROW_HEIGHT_DP = 46.0

        /** 头部 + 内外边距 + 底栏的估算高度。 */
        private const val HEADER_AND_FOOTER_DP = 78.0

        /**
         * 翻页广播：带上实例号与目标页，多实例互不干扰。
         *
         * PendingIntent 的身份只看 `(requestCode, action, data, type, component)` —— **extras 不参与**。
         * 所以固定 requestCode 会让「鲸鱼（去工作区页）」和「返回（回会话页）」复用同一个
         * PendingIntent，后创建的那次把 extras 覆盖掉，点上去就像没反应。这里用 data 区分
         * 实例与目标页，requestCode 再按实例号错开，两个方向各拿身份。
         */
        private fun pageTap(ctx: Context, appWidgetId: Int, page: String): PendingIntent {
            val i = Intent(ctx, DshWidgetProvider::class.java)
                .setAction(ACTION_SWITCH_PAGE)
                .setData(Uri.parse("dshgo://page/$appWidgetId/$page"))
                .putExtra(EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(EXTRA_PAGE, page)
            return PendingIntent.getBroadcast(
                ctx,
                22 + (appWidgetId and 0xFF),
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * 工作区页的点击模板。
         *
         * 集合视图只能用模板 + fillInIntent：模板负责动作，fillIn 负责带参数。
         * 必须可变（FLAG_MUTABLE），否则系统不让我们往里填 extras。
         */
        private fun pickWorkspaceTemplate(ctx: Context, appWidgetId: Int): PendingIntent {
            val i = Intent(ctx, DshWidgetProvider::class.java)
                .setAction(ACTION_PICK_WORKSPACE)
                // data 里带实例号：模板的身份要按实例区分，否则两个组件共用一份、extras 互相覆盖。
                .setData(Uri.parse("dshgo://pick/$appWidgetId"))
            return PendingIntent.getBroadcast(
                ctx,
                23 + (appWidgetId and 0xFF),
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
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

        /**
         * 集合视图的点击模板。
         *
         * ListView 里每一行不能各带一个 PendingIntent（RemoteViews 只允许模板），
         * 所以模板用可变 PendingIntent，行里用 setOnClickFillInIntent 塞会话 id。
         */
        private fun sessionTemplate(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                ctx,
                70,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
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
