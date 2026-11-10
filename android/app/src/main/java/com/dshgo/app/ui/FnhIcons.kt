package com.dshgo.app.ui

import androidx.annotation.DrawableRes
import com.dshgo.app.R

/**
 * Fnh Go 图标集（43 枚）。
 *
 * 24dp 网格、线宽 1.9、圆头端点、右下 45° 切角 —— 与 logo 同族。
 * 矢量文件由 `design/android_icons.py` 从 `design/icons_manifest.json` 生成，
 * 颜色一律不写在矢量里，用 `Icon(painterResource(...), tint = ...)` 着色，
 * 深色主题下自然变成浅色。
 *
 * 为什么不直接用 Material 图标：Material 的圆角/端点/粗细跟我们的 logo 不是一族，
 * 混在一起能看出「拼装的」。这里只挑真正用得到的，按用途分组。
 */
object FnhIcons {

    // —— 连接与设备 ——
    @DrawableRes val Add = R.drawable.ic_fnh_add
    @DrawableRes val Device = R.drawable.ic_fnh_device
    @DrawableRes val Edit = R.drawable.ic_fnh_edit
    @DrawableRes val Delete = R.drawable.ic_fnh_delete
    @DrawableRes val Copy = R.drawable.ic_fnh_copy
    @DrawableRes val Qr = R.drawable.ic_fnh_qr
    @DrawableRes val More = R.drawable.ic_fnh_more
    @DrawableRes val External = R.drawable.ic_fnh_external

    // —— 隧道与安全 ——
    @DrawableRes val Play = R.drawable.ic_fnh_play
    @DrawableRes val Stop = R.drawable.ic_fnh_stop
    @DrawableRes val Lock = R.drawable.ic_fnh_lock
    @DrawableRes val Key = R.drawable.ic_fnh_key
    @DrawableRes val Shield = R.drawable.ic_fnh_shield
    @DrawableRes val Eye = R.drawable.ic_fnh_eye
    @DrawableRes val EyeOff = R.drawable.ic_fnh_eyeoff

    // —— 网络与通道 ——
    @DrawableRes val Swap = R.drawable.ic_fnh_swap
    @DrawableRes val Wifi = R.drawable.ic_fnh_wifi
    @DrawableRes val Cellular = R.drawable.ic_fnh_cellular
    @DrawableRes val Pulse = R.drawable.ic_fnh_pulse

    // —— 设置与通知 ——
    @DrawableRes val Settings = R.drawable.ic_fnh_settings
    @DrawableRes val Bell = R.drawable.ic_fnh_bell
    @DrawableRes val TextSize = R.drawable.ic_fnh_text
    @DrawableRes val Download = R.drawable.ic_fnh_download
    @DrawableRes val Refresh = R.drawable.ic_fnh_refresh
    @DrawableRes val Share = R.drawable.ic_fnh_share
    @DrawableRes val Mic = R.drawable.ic_fnh_mic

    // —— 状态与通用 ——
    @DrawableRes val Back = R.drawable.ic_fnh_back
    @DrawableRes val Close = R.drawable.ic_fnh_close
    @DrawableRes val Check = R.drawable.ic_fnh_check
    @DrawableRes val Warning = R.drawable.ic_fnh_warning
    @DrawableRes val Error = R.drawable.ic_fnh_error
    @DrawableRes val Info = R.drawable.ic_fnh_info
    @DrawableRes val Help = R.drawable.ic_fnh_help
    @DrawableRes val Clock = R.drawable.ic_fnh_clock
    @DrawableRes val File = R.drawable.ic_fnh_file
    @DrawableRes val User = R.drawable.ic_fnh_user
    @DrawableRes val SignOut = R.drawable.ic_fnh_signout
    @DrawableRes val Scan = R.drawable.ic_fnh_scan
    @DrawableRes val Filter = R.drawable.ic_fnh_filter
    @DrawableRes val Sort = R.drawable.ic_fnh_sort
    @DrawableRes val Fullscreen = R.drawable.ic_fnh_fullscreen
    @DrawableRes val Home = R.drawable.ic_fnh_home
    @DrawableRes val Message = R.drawable.ic_fnh_message
}
