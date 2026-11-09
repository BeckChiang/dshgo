package com.dshgo.app.notify

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.dshgo.app.R
import com.dshgo.app.data.WidgetState
import com.dshgo.app.data.WidgetStateStore

/**
 * 工作区选择页的集合视图数据源。
 *
 * 为什么单独一个服务：小组件里一个 [RemoteViewsService] 对应一个集合视图，
 * 会话列表与工作区列表是两套数据，混在一起既难排序也难点击（点击要落到不同动作）。
 *
 * 数据同样只读缓存 —— 拉取统一由 [WidgetRefresher] 负责，列表本身不发请求。
 */
class WorkspaceListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return WorkspaceFactory(applicationContext, appWidgetId)
    }

    companion object {
        /** 由 provider 塞进来：多实例下要各自记住是哪个组件在选工作区。 */
        const val EXTRA_APPWIDGET_ID = "ws_appwidget_id"
    }
}

/** 工作区行：名字 + 「N 条会话 / 当前选择」。 */
class WorkspaceFactory(
    private val ctx: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<WidgetState.Workspace> = emptyList()
    private var selectedId: String? = null

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val state = WidgetStateStore.load(ctx)
        items = state?.workspaces.orEmpty()
        selectedId = WidgetStateStore.selectedWorkspaceId(ctx)
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_workspace_row)
        val ws = items.getOrNull(position) ?: return views
        views.setTextViewText(R.id.widget_ws_row_name, ws.title.ifBlank { ws.id })
        val count = ws.sessions.size
        views.setTextViewText(
            R.id.widget_ws_row_meta,
            if (ws.id == selectedId) {
                ctx.getString(R.string.widget_ws_meta_selected, count)
            } else {
                ctx.getString(R.string.widget_ws_meta, count)
            },
        )
        // 点一行 → 由 provider 的模板 PendingIntent 接住，把 id 与实例号带回去。
        val fill = Intent()
            .putExtra(DshWidgetProvider.EXTRA_WORKSPACE_ID, ws.id)
            .putExtra(WorkspaceListService.EXTRA_APPWIDGET_ID, appWidgetId)
        views.setOnClickFillInIntent(R.id.widget_ws_row_root, fill)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
