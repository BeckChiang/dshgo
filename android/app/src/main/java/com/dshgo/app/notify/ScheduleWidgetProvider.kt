package com.dshgo.app.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.dshgo.app.MainActivity
import com.dshgo.app.R
import com.dshgo.app.data.WidgetState
import com.dshgo.app.data.WidgetStateStore

/**
 * 定时任务小组件（3×2）。
 *
 * 与 [DshWidgetProvider] 共用同一份缓存（[WidgetStateStore]），只是取 `schedules` 那一段，
 * 所以刷新逻辑不需要复制：`WidgetRefresher` 拉完一次快照会把两个组件一起重绘。
 *
 * 空列表分两种文案：没缓存 = 还没连上；有缓存但没有任务 = 确实没任务。
 * 这两种情况混成一句「暂无」会让用户误以为数据坏了。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = WidgetStateStore.load(ctx)
        ids.forEach { manager.updateAppWidget(it, buildViews(ctx, state, it)) }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_schedule_list)
        // 卡片被放到桌面或系统刷新时顺手拉一次，别让新卡片停在「还没连上」。
        WidgetRefresher.refresh(ctx)
    }

    companion object {

        /** 把缓存里的定时任务推给桌面上所有实例。 */
        fun update(ctx: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(ctx) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(ctx, ScheduleWidgetProvider::class.java))
                if (ids.isEmpty()) return
                val state = WidgetStateStore.load(ctx)
                ids.forEach { manager.updateAppWidget(it, buildViews(ctx, state, it)) }
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_schedule_list)
            }
        }

        private fun buildViews(ctx: Context, state: WidgetState?, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_schedule)

            // 头部数量：只数没结束的，暂停的也算「还在」，因为它随时能恢复。
            val live = state?.schedules?.count { it.status != "ended" } ?: 0
            val count = viewsCount(ctx, live)
            if (count == null) {
                views.setViewVisibility(R.id.widget_schedule_count, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_schedule_count, View.VISIBLE)
                views.setTextViewText(R.id.widget_schedule_count, count)
            }

            views.setTextViewText(R.id.widget_schedule_empty, emptyText(state))
            views.setRemoteAdapter(
                appWidgetId,
                R.id.widget_schedule_list,
                Intent(ctx, ScheduleListService::class.java),
            )
            views.setEmptyView(R.id.widget_schedule_list, R.id.widget_schedule_empty)

            views.setOnClickPendingIntent(R.id.widget_schedule_root, appTap(ctx))
            views.setOnClickPendingIntent(R.id.widget_schedule_refresh, refreshTap(ctx))
            return views
        }

        private fun viewsCount(ctx: Context, live: Int): String? =
            if (live <= 0) null else ctx.getString(R.string.widget_schedule_count, live)

        private fun emptyText(state: WidgetState?): String =
            if (state == null) "未连接 —— 点一下重试" else "没有定时任务"

        private fun appTap(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_RECENT_SESSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                ctx,
                31,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** 刷新按钮：广播给自己，触发一次强制拉取（跳过节流）。 */
        private fun refreshTap(ctx: Context): PendingIntent {
            val i = Intent(ctx, ScheduleWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(
                ctx,
                32,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val ACTION_REFRESH = "com.dshgo.app.widget.SCHEDULE_REFRESH"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == "com.dshgo.app.widget.SCHEDULE_REFRESH") {
            // 点刷新必须有反馈：先按缓存重绘一次，再提示正在拉取，最后强制拉新数据。
            update(ctx)
            android.widget.Toast.makeText(ctx, "正在刷新定时任务…", android.widget.Toast.LENGTH_SHORT).show()
            WidgetRefresher.refresh(ctx, force = true)
            return
        }
        super.onReceive(ctx, intent)
    }
}
