package com.dshgo.app.notify

import android.content.Context
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.dshgo.app.R
import com.dshgo.app.data.WidgetState
import com.dshgo.app.data.WidgetStateStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 定时任务小组件的集合视图数据源。
 *
 * 与会话小组件同构：解析、排序、时间格式化都在这里做，provider 只负责接线。
 * 时间文案在每次 `onDataSetChanged` 时重算，所以卡片被系统刷新一次就自动变新。
 */
class ScheduleListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: android.content.Intent): RemoteViewsFactory =
        ScheduleFactory(applicationContext)
}

class ScheduleFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<WidgetState.Schedule> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        items = runCatching {
            WidgetStateStore.load(ctx)?.schedules.orEmpty()
                // 已结束/已删除的任务不再显示：宿主编剧端可能还留了一条 status=ended 的记录。
                .filter { it.status != "ended" && it.status != "deleted" }
        }.getOrDefault(emptyList())
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_schedule_row)
        val item = items.getOrNull(position) ?: return views
        views.setTextViewText(R.id.widget_schedule_row_title, item.title.ifBlank { "未命名任务" })
        views.setTextViewText(R.id.widget_schedule_row_when, whenText(item))
        val meta = metaText(item)
        if (meta.isBlank()) {
            views.setViewVisibility(R.id.widget_schedule_row_meta, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_schedule_row_meta, android.view.View.VISIBLE)
            views.setTextViewText(R.id.widget_schedule_row_meta, meta)
        }
        val dot = when {
            item.status == "paused" -> R.drawable.widget_dot_idle
            item.status == "ended" -> R.drawable.widget_dot_idle
            else -> R.drawable.widget_dot_running
        }
        views.setImageViewResource(R.id.widget_schedule_row_dot, dot)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = false

    /** 右侧时间：暂停/结束优先，其余按「已过期 / 今天 15:30 / 明天 09:00 / 3 天后 / 11-12」给最短可读写法。 */
    private fun whenText(item: WidgetState.Schedule): String {
        if (item.status == "paused") return "已暂停"
        if (item.status == "ended") return "已结束"
        val whenUtc = parse(item.scheduledAt) ?: return ""
        val local = whenUtc.withZoneSameInstant(ZoneId.systemDefault())
        val now = ZonedDateTime.now()
        val minutes = ChronoUnit.MINUTES.between(now, local)
        if (minutes < 0) return "已过期"
        if (minutes < 60) return "${minutes.coerceAtLeast(1)} 分钟后"
        if (local.toLocalDate() == now.toLocalDate()) return local.format(TIME)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), local.toLocalDate())
        return when (days) {
            0L -> local.format(TIME)
            1L -> "明天 ${local.format(TIME)}"
            in 2L..6L -> "$days 天后"
            else -> local.format(MONTH_DAY)
        }
    }

    /** 第二行：工作区 · 会话。缺哪个就少哪个，都没有则整行隐藏。 */
    private fun metaText(item: WidgetState.Schedule): String {
        val parts = listOfNotNull(
            item.workspaceTitle?.takeIf { it.isNotBlank() },
            item.sessionTitle?.takeIf { it.isNotBlank() },
        )
        return parts.joinToString(" · ")
    }

    private fun parse(raw: String?): ZonedDateTime? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw).atZone(ZoneId.of("UTC")) }.getOrNull()
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M-d", Locale.getDefault())
    }
}
