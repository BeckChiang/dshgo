package com.dshgo.app.data

import android.content.Context
import com.dshgo.app.Prefs

/**
 * 小组件该看哪台电脑、走哪条地址。
 *
 * 为什么单独一层：小组件在后台刷新，手上只有 `Prefs.entryUrl()` 那一个地址 ——
 * 而这个地址是「App 上次连的那条」。用户切了网络、或者小组件被固定在另一台
 * 电脑上时，用旧地址去拉就会一直是「未连接」。
 *
 * 规则和 App 内的自动选路保持一致：
 * 1. 设置里选定了设备 → 只用那台；
 * 2. 没选定 → 用当前激活的那台（没有就退回入口地址）；
 * 3. 这台有多条通道时，同 Wi-Fi 优先局域网，否则优先 Tailscale，
 *    两条都探不到就按「上次用得最晚」的那条。
 */
object WidgetDevice {

    /**
     * 返回小组件要拉的 baseUrl（不带结尾斜杠）。空串表示还没配任何地址。
     */
    fun url(ctx: Context): String {
        val picked = Prefs(ctx).widgetDeviceId()
        val conns = ConnectionStore.all(ctx)
        val target = if (picked.isNotBlank()) {
            conns.firstOrNull { it.id == picked } ?: ConnectionStore.active(ctx)
        } else {
            ConnectionStore.active(ctx)
        } ?: return Prefs(ctx).entryUrl().trim().trimEnd('/')

        val rows = conns.filter { it.deviceKey() == target.deviceKey() }
        if (rows.isEmpty()) return target.url.trim().trimEnd('/')

        val wifi = AutoRoute.onWifi(ctx)
        val lan = rows.firstOrNull { AutoRoute.isLanUrl(it.url) }
        val tail = rows.firstOrNull { AutoRoute.isTailscaleUrl(it.url) }
        val best = when {
            wifi && lan != null -> lan
            !wifi && tail != null -> tail
            else -> lan ?: tail
        } ?: target
        return best.url.trim().trimEnd('/')
    }

    /**
     * 这台电脑的所有候选地址，按「当前网络下更可能能用」的顺序排列。
     *
     * 为什么需要列表而不只是 [url]：隧道没起来、或对端换了网段时，首选地址会失败；
     * 这时换另一条再试一次，比让小组件一直挂着「未连接」好。
     */
    fun candidates(ctx: Context): List<String> {
        val picked = Prefs(ctx).widgetDeviceId()
        val conns = ConnectionStore.all(ctx)
        val target = if (picked.isNotBlank()) {
            conns.firstOrNull { it.id == picked } ?: ConnectionStore.active(ctx)
        } else {
            ConnectionStore.active(ctx)
        } ?: return listOfNotNull(
            Prefs(ctx).entryUrl().trim().trimEnd('/').ifEmpty { null },
        )

        val rows = conns.filter { it.deviceKey() == target.deviceKey() }.ifEmpty { listOf(target) }
        val wifi = AutoRoute.onWifi(ctx)
        val lan = rows.filter { AutoRoute.isLanUrl(it.url) }
        val tail = rows.filter { AutoRoute.isTailscaleUrl(it.url) }
        val ordered = buildList {
            if (wifi) {
                addAll(lan); addAll(tail)
            } else {
                addAll(tail); addAll(lan)
            }
            add(target)
            addAll(rows)
        }
        return ordered.map { it.url.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 设置页里给人看的一句话：这台电脑现在会用哪条地址。
     */
    fun describe(ctx: Context): String = url(ctx).ifEmpty { "未配置" }

    /**
     * 同一台电脑的判定键：有 deviceId 用 deviceId，没有就用 id。
     * 老插件不上报 deviceId 时每条连接各算一台，行为和原来一致。
     */
    private fun Connection.deviceKey(): String =
        if (deviceId.isNotBlank()) "d:$deviceId" else "c:$id"
}
