package com.dshgo.app.notify

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.dshgo.app.R
import com.dshgo.app.data.WidgetState
import com.dshgo.app.data.WidgetStateStore

/**
 * 会话列表的集合视图（[RemoteViewsService]）。
 *
 * 为什么要这个服务：RemoteViews 里**只有集合视图能滚动**（ListView/GridView），
 * 普通 LinearLayout 塞几行就是几行。用户要求「可以上下滑动查看多个对话」，
 * 所以行必须交给 ListView，而 ListView 的数据由 RemoteViewsFactory 提供。
 *
 * 数据来源仍是 [WidgetStateStore] 的缓存 —— Factory 不自己发网络请求：
 * 1. `onDataSetChanged()` 允许阻塞，但我们没有理由让它等网络；
 * 2. 拉取交给 [WidgetRefresher]，它拿到新数据后写缓存并调
 *    `notifyAppWidgetViewDataChanged`，界面自然跟着变；
 * 3. 这样滑动时永远不会因为一次超时把列表清空。
 */
class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        SessionFactory(applicationContext)
}

/** 会话行的 RemoteViewsFactory：把缓存里的会话列表画成一屏可滚动的行。 */
class SessionFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {

    /** 行数据：会话本体 + 是否高亮为「等审批」（高亮行换一套布局）。 */
    data class Row(val session: WidgetState.Session, val alert: Boolean)

    private var rows: List<Row> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        rows = load(ctx)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_dsh_row)
        val session = row.session
        val layout = if (row.alert) R.layout.widget_dsh_row_alert else R.layout.widget_dsh_row
        val views = RemoteViews(ctx.packageName, layout)

        views.setTextViewText(
            R.id.widget_row_title,
            session.title.ifBlank { ctx.getString(R.string.widget_untitled_session) },
        )

        // 摘要：端点上没有就整行隐藏，不留一条空白。
        val text = session.summary?.trim().orEmpty()
        if (text.isEmpty()) {
            views.setViewVisibility(R.id.widget_row_summary, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_row_summary, View.VISIBLE)
            views.setTextViewText(R.id.widget_row_summary, text)
        }

        // 状态以「事件流知道的实时状态」为准：widget 快照有节流，
        // 而底栏计数是实时的 —— 两者不一致时用户会觉得「明明在跑，行还是灰的」。
        val liveRunning = session.isRunning || SessionWatcher.liveRunningIds().contains(session.id)

        // 第三行：状态 + 是否在等审批。这一行是「加大文字、增多内容」后的信息主位。
        val status = when {
            row.alert -> ctx.getString(R.string.widget_row_status_approval)
            liveRunning -> ctx.getString(R.string.widget_row_status_running)
            else -> ctx.getString(R.string.widget_row_status_idle)
        }
        views.setTextViewText(R.id.widget_row_meta, status)

        // 状态点：橙=等审批，绿=在跑，灰=空闲。颜色写死在 drawable 里，RemoteViews 不能 tint。
        views.setImageViewResource(
            R.id.widget_row_dot,
            when {
                row.alert -> R.drawable.widget_dot_approval
                liveRunning -> R.drawable.widget_dot_running
                else -> R.drawable.widget_dot_idle
            },
        )

        // 没有上下文数据就显示「—」：0% 会被读成「几乎为空」，是错误信息。
        views.setTextViewText(
            R.id.widget_row_pct,
            session.contextPercent?.let { "$it%" } ?: "—",
        )

        // 行内点击：填进一个带 sessionId 的 Intent，由 provider 的模板 PendingIntent 承接。
        // 集合视图里每一行不能自己带 PendingIntent（会被忽略），必须走 fillInIntent。
        val fill = Intent().putExtra(NotificationCenter.EXTRA_SESSION_ID, session.id)
        views.setOnClickFillInIntent(R.id.widget_row_root, fill)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    companion object {
        /**
         * 读出当前工作区的会话，并把「等审批」的挑出来。
         *
         * 端点对审批只有尽力而为的字段（`approval` / `pendingApproval` / `approvals`），
         * 拿不到时只按 running 排序，不猜 —— 猜错会比没有橙标更糟。
         */
        fun load(ctx: Context): List<Row> {
            val state = WidgetStateStore.load(ctx) ?: return emptyList()
            val workspace = state.workspace(WidgetStateStore.selectedWorkspaceId(ctx)) ?: return emptyList()
            // 排序：审批 > 运行中 > 置顶 > 一般。运行中永远压在置顶前面 ——
            // 「置顶」表达的是关注度，「运行中」表达的是现在正在发生，后者更该先看到。
            return workspace.sessions
                .map { Row(it, it.pendingApproval) }
                .sortedWith(
                    compareByDescending<Row> { it.alert }
                        .thenByDescending { it.session.isRunning }
                        .thenByDescending { it.session.pinned },
                )
        }
    }
}
