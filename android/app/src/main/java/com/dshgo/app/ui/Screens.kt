package com.dshgo.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalContext
import com.dshgo.app.tunnel.TsTunnel
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import com.dshgo.app.ui.theme.softAccent
import com.dshgo.app.data.displayName
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import com.dshgo.app.data.ConnectionProbe
import com.dshgo.app.data.ConnectionStore
import com.dshgo.app.data.WidgetDevice
import com.dshgo.app.notify.WidgetRefresher
import kotlinx.coroutines.launch
import com.dshgo.app.data.Connection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshgo.app.Prefs
import com.dshgo.app.data.UpdateCheck
import com.dshgo.app.R
import com.dshgo.app.ui.theme.DshColor
import kotlin.math.roundToInt


/** 三个面：连接配置 / 握手等待 / DSH 界面本身（没有首页）。 */
/** Locked = 宿主开了访问闸门，App 还没解锁。 */
enum class Screen { Connecting, Setup, Dsh, Locked, Connections }

/** 顶部原生状态条的内容高度（不含状态栏）。 */
val StripHeight = 44.dp

// ---------------------------------------------------------------------------
// 连接配置
// ---------------------------------------------------------------------------

@Composable
fun SetupScreen(
    initialUrl: String,
    /** 上次填过的 Tailscale 地址；「更改」进来时用它预填第二条，而不是把生效地址塞进局域网框。 */
    initialTail: String = "",
    error: String?,
    connecting: Boolean,
    onConnect: (String) -> Unit,
    /** 点「扫码」的回调；返回扫到的内容（App 侧负责解析与填回）。 */
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时在左上角显示返回箭头 —— 从会话里点「更改地址」进来才给退路。 */
    onCancel: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // 两条地址分开存：家里用局域网那句，出门用 Tailscale 那句，回来不用重填。
    val prefs = remember {
        context.getSharedPreferences("dshgo_connect", Context.MODE_PRIVATE)
    }

    // ★ 按角色预填，而不是把「当前生效的那条」塞进第一个框。
    //
    // 以前不管生效的是哪条，都直接进 initialUrl —— 用的是 Tailscale（100.x）时，
    // 第二个框「局域网地址」里显示的就是 100.75.57.100，
    // 用户看到的是「局域网地址错了」，其实错在这里。
    val startIsTail = initialUrl.isNotEmpty() && runCatching {
        java.net.URI(initialUrl).host.orEmpty().startsWith("100.")
    }.getOrDefault(false)
    var text by remember {
        mutableStateOf(
            if (startIsTail) prefs.getString(PREF_LAN, "") ?: "" else initialUrl,
        )
    }
    var tailText by remember {
        mutableStateOf(
            when {
                startIsTail -> initialUrl
                initialTail.isNotBlank() -> initialTail
                else -> prefs.getString(PREF_TAIL, "") ?: ""
            },
        )
    }
    var autoRoute by remember { mutableStateOf(prefs.getBoolean(PREF_AUTO, true)) }

    // 外网地址的连通性结论。自动选路必须建立在「这条地址真的能用」之上 ——
    // 否则出门在外会选到一条根本连不上的路，用户只看到「连不上」。
    val scope = rememberCoroutineScope()
    var tailOk by remember { mutableStateOf(false) }
    var tailChecking by remember { mutableStateOf(false) }
    var tailMsg by remember { mutableStateOf("") }

    fun verifyTail(then: (Boolean) -> Unit) {
        val raw = tailText.trim()
        if (raw.isEmpty()) {
            tailOk = false
            tailChecking = false
            tailMsg = ""
            then(false)
            return
        }
        tailChecking = true
        tailMsg = "正在确认外网地址…"
        scope.launch {
            val st = runCatching { ConnectionProbe.probe(Prefs.normalize(raw)) }
                .getOrNull()
            tailChecking = false
            val ok = st?.reachable == true
            tailOk = ok
            tailMsg = when {
                st == null -> "外网地址连不上：探测失败"
                ok && st.locked -> "外网地址可用 · 需要密码登录 · ${st.ms} ms"
                ok -> "外网地址可用 · ${st.ms} ms"
                else -> "外网地址连不上：${st.error.ifBlank { "检查手机流量下能否访问" }}"
            }
            if (!ok) {
                // 连不上就不许开着自动选路 —— 否则出门必踩空
                autoRoute = false
                prefs.edit().putBoolean(PREF_AUTO, false).apply()
            }
            then(ok)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onCancel != null) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Icon(painterResource(FnhIcons.Back), contentDescription = "返回")
                }
                Spacer(Modifier.height(6.dp))
            } else {
                Spacer(Modifier.height(18.dp))
            }
            BrandMark()
            Spacer(Modifier.height(16.dp))
            Text(
                "Fnh Go",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "DeepSeek Harness · 远程终端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(30.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outline,
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "连接你的电脑",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在电脑上打开 DSH 的「设置 → 局域网访问」，把地址填进来，或扫那一页的二维码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(18.dp))

                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            prefs.edit().putString(PREF_LAN, it.trim()).apply()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("局域网地址") },
                        placeholder = { Text("http://192.168.1.100:3081") },
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                        trailingIcon = {
                            TextButton(onClick = {
                                val clip = clipboard.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    text = Prefs.normalize(clip)
                                }
                            }) {
                                Text(
                                    "粘贴",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )

                    Spacer(Modifier.height(10.dp))

                    // 第二条地址专供外网：Tailscale 给的是 100.x.x.x，和在家的局域网 IP 是两套。
                    // 两个都填上，出门/回家不用回来改设置。
                    OutlinedTextField(
                        value = tailText,
                        onValueChange = {
                            tailText = it
                            prefs.edit().putString(PREF_TAIL, it.trim()).apply()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Tailscale 地址（可选）") },
                        placeholder = { Text("http://100.x.x.x:3081") },
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = {
                            val pick = if (autoRoute) autoPickUrl(context, text, tailText) else text
                            if (pick.isNotBlank()) onConnect(pick)
                        }),
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "自动选路",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                // ★ 以前写的是「Wi-Fi 走局域网」，等于说别的 Wi-Fi 也走局域网。
                                //   实际只有**同一个** Wi-Fi 才走局域网，换到别的 Wi-Fi 一样要出网。
                                if (tailText.isBlank()) "填了可用的外网地址才会自动切换"
                                else "只有同一个 Wi-Fi 走局域网，其它都走外网",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoRoute,
                            enabled = !tailChecking,
                            onCheckedChange = { want ->
                                if (!want) {
                                    autoRoute = false
                                    prefs.edit().putBoolean(PREF_AUTO, false).apply()
                                } else {
                                    // 打开之前先验证外网地址；验不过就不开，并说明原因
                                    verifyTail { ok ->
                                        if (ok) {
                                            autoRoute = true
                                            prefs.edit().putBoolean(PREF_AUTO, true).apply()
                                        } else if (tailText.isBlank()) {
                                            tailMsg = "先填外网地址，才能自动选路"
                                        }
                                    }
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // Tailscale 这里**只显示状态**，登录/开关隧道都去同一个管理页 ——
                    // 以前连接页给「登录」、设置里又给四个按钮，等于两套入口。
                    val tsState = remember { TsTunnel.state(context) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Tailscale",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                tsStateText(tsState),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(context, TunnelLoginActivity::class.java),
                                )
                            }
                        }) {
                            Text("管理", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "只有和电脑连同一个 Wi-Fi 才走局域网地址；换到别的 Wi-Fi 或移动网络" +
                            "都走外网地址（100.x.x.x）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (tailMsg.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tailMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tailOk) DshColor.Running else DshColor.Danger,
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // 扫码填地址：比手敲 IP 快，也比「先在电脑上复制再粘贴」少一步 ——
                    // 电脑上「设置 → 局域网访问」那一页就摆着二维码。
                    FilledTonalButton(
                        onClick = onScan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("扫描二维码", style = MaterialTheme.typography.labelLarge)
                    }

                    if (error != null) {
                        Spacer(Modifier.height(14.dp))
                        ErrorNote(error)
                    }

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = {
                            verifyTail { ok ->
                                val lan = text.trim()
                                // 外网验过且允许自动选路才可能选到外网；否则老老实实用局域网那条
                                val pick = when {
                                    !ok -> lan
                                    autoRoute -> autoPickUrl(context, lan, tailText)
                                    else -> lan
                                }
                                if (pick.isNotBlank()) {
                                    onConnect(pick)
                                } else {
                                    tailMsg = "至少填一条地址"
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !connecting,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(10.dp))
                        }
                        Text("连接", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "设备与电脑直连，不经过任何中转服务器",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorNote(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DshColor.DangerSoft)
            .border(1.dp, DshColor.Danger.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painterResource(FnhIcons.Warning),
            contentDescription = null,
            tint = DshColor.Danger,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(9.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 品牌标记：渐变圆角方块 + 终端提示符，和启动图标一致。 */
@Composable
private fun BrandMark(size: Int = 62) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.28f).dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // 品牌 F：由 logo 手描轮廓生成的三块矢量，颜色就是 logo 本身的灰 + 蓝，
        // 所以这里 tint 用 Color.Unspecified —— 不能让主题色把它盖掉。
        Icon(
            painter = painterResource(R.drawable.ic_brand_f),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(size.dp),
        )
    }
}

private fun Color8(v: Int) = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or v)

// ---------------------------------------------------------------------------
// 会话加载遮罩
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// 设置
// ---------------------------------------------------------------------------

/**
 * 设置面板里的分组标题。
 *
 * 面板里已经有连接、界面、安全、通知、关于五类东西，全都长得一样、
 * 靠 20dp 的间距分开 —— 扫一眼看不出层次。给每一类一个标题，
 * 比再加几个分割线有用。
 */
@Composable
internal fun SettingsGroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
        ),
        color = DshColor.Accent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
    )
}

/**
 * 小组件看哪台电脑。
 *
 * 为什么放在设置里而不是小组件上：RemoteViews 里没法放列表，多选一项就得
 * 开一个 Activity；而「这块图标看谁的会话」本来就是一次性决定，放设置里
 * 点两下定下来更省事。选完立刻强制刷一次，小组件会马上换数据。
 */
@Composable
private fun WidgetDeviceSection() {
    val context = LocalContext.current
    val connections = remember { ConnectionStore.all(context) }
    var picked by remember { mutableStateOf(Prefs(context).widgetDeviceId()) }
    val resolved = WidgetDevice.url(context)

    SettingsGroupTitle("小组件")
    Spacer(Modifier.height(8.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "会话小组件显示哪台电脑",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "现在用的是 ${if (resolved.isBlank()) "（还没配地址）" else resolved.removePrefix("http://").removePrefix("https://")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            WidgetDeviceChoice(
                label = "跟随当前连接",
                selected = picked.isBlank(),
                onClick = {
                    picked = ""
                    Prefs(context).setWidgetDeviceId("")
                    WidgetRefresher.refresh(context, force = true)
                },
            )
            // ★ 按「同一台电脑」归并：局域网与外网是两条通道，不是两台设备。
            //   以前直接遍历连接列表，于是同一台电脑会出现两次，用户选了其中一条
            //   另一条就点不上（实测反馈）。
            val groups = connections.groupBy {
                if (it.deviceId.isNotBlank()) "d:${it.deviceId}" else "c:${it.id}"
            }
            groups.forEach { (key, rows) ->
                val head = rows.first()
                val pickedKey = connections.firstOrNull { it.id == picked }?.let {
                    if (it.deviceId.isNotBlank()) "d:${it.deviceId}" else "c:${it.id}"
                }
                WidgetDeviceChoice(
                    label = if (rows.size > 1) "${head.displayName()} · ${rows.size} 条通道" else head.displayName(),
                    selected = picked.isNotBlank() && pickedKey == key,
                    onClick = {
                        picked = head.id
                        Prefs(context).setWidgetDeviceId(head.id)
                        WidgetRefresher.refresh(context, force = true)
                    },
                )
            }
            if (connections.isEmpty()) {
                Text(
                    "还没有添加过电脑，先在列表里连一台。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WidgetDeviceChoice(label: String, selected: Boolean, onClick: () -> Unit) {    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) DshColor.Accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = painterResource(FnhIcons.Check),
                contentDescription = "已选择",
                tint = DshColor.Accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    url: String,
    layout: String,
    awake: Boolean,
    notifyEnabled: Boolean,
    onToggleNotify: () -> Unit,
    /** 非 null 时说明通知现在有毛病（没授权 / 渠道被静音），直接显示给用户。 */
    notifyProblem: String?,
    /** 事件流是否连着；通知全靠它。 */
    streamLive: Boolean,
    /** 当前靠什么获知会话状态："stream" / "poll" / "down"。 */
    streamMode: String,
    /** 最近一次断开的原因（null = 没出过错）。 */
    streamError: String?,
    /** 最近收到会话事件的时间，0 = 一条都没收到过。 */
    lastEventAt: Long,
    onTestNotify: () -> Unit,
    onNotifySettings: () -> Unit,
    /** 本机版本号（BuildConfig.VERSION_NAME）。 */
    currentVersion: String,
    /** 远端最新版本号；null = 还不知道。 */
    updateLatest: String?,
    /** 远端是否比本机新。 */
    updateNewer: Boolean,
    /** 正在手动检查。 */
    updateChecking: Boolean,
    /** 本次结果建议的下载地址（镜像优先）。 */
    updateApkUrl: String,
    onCheckUpdate: () -> Unit,
    onDownload: (String) -> Unit,
    /** 把主屏小组件钉到桌面。 */
    onPinWidget: () -> Unit,
    /** 解锁方式（AppLock.Mode 的 id）。 */
    lockMode: String,
    /** 这台设备实际能用什么（给说明文字用）。 */
    lockDeviceMethod: String,
    onLockMode: (String) -> Unit,
    /** 打开连接管理。 */
    onOpenConnections: () -> Unit,
    /** 已有几条连接（入口上显示数量）。 */
    connectionCount: Int,
    /** 应用内下载/安装：idle / downloading / ready / failed。 */
    updatePhase: String,
    updateBytes: Long,
    updateTotal: Long,
    updateError: String?,
    /** 开始应用内下载并安装。 */
    onInstall: () -> Unit,
    scale: Float,
    onScale: (Float) -> Unit,
    onLayout: (String) -> Unit,
    onToggleAwake: () -> Unit,
    onClearSession: () -> Unit,
    onChangeUrl: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 必须能滚：内容比一屏高时，ModalBottomSheet 会把超出的部分直接
                // 裁掉，用户既看不到也滑不到（诊断块就这么够不着过）。
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(20.dp))

            SettingsGroupTitle("连接")
            Spacer(Modifier.height(8.dp))

            // 入口地址
            Text(
                "入口地址",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(7.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 13.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        url.ifEmpty { "未设置" },
                        modifier = Modifier.weight(1f).padding(vertical = 13.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = onChangeUrl) {
                        Text(
                            "更改",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 连接管理入口。放在入口地址下面 —— 它管的就是那个地址。
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenConnections,
                shape = RoundedCornerShape(14.dp),
                color = softAccent(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "连接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (connectionCount > 1) "$connectionCount 个 · 切换" else "管理",
                        style = MaterialTheme.typography.labelMedium,
                        color = DshColor.Accent,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SettingsGroupTitle("界面")
            Spacer(Modifier.height(8.dp))

            // 布局
            Text(
                "会话内界面布局",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(7.dp))
            SegmentedControl(
                options = listOf(
                    Prefs.LAYOUT_AUTO to "自动",
                    Prefs.LAYOUT_MOBILE to "手机",
                    Prefs.LAYOUT_DESKTOP to "宽屏",
                ),
                selected = layout,
                onSelect = onLayout,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (layout) {
                    Prefs.LAYOUT_MOBILE -> "始终用抽屉布局，单手操作最顺。"
                    Prefs.LAYOUT_DESKTOP -> "始终用电脑布局，平板横屏信息密度最高。"
                    else -> "窄屏用抽屉布局，≥1024px 用电脑布局，手机和平板各就各位。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))


            // 界面缩放
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "界面缩放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(scale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (scale != Prefs.SCALE_DEFAULT) {
                    TextButton(onClick = { onScale(Prefs.SCALE_DEFAULT) }) {
                        Text("重置", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Slider(
                value = scale,
                onValueChange = onScale,
                valueRange = Prefs.SCALE_MIN..Prefs.SCALE_MAX,
                steps = 16,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "小",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "大",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "等比缩放整个 DSH 界面（文字、按钮、间距一起变），拖动即时生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            // 屏幕常亮
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "屏幕常亮",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "看 agent 跑长任务时不让屏幕熄灭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = awake, onCheckedChange = { onToggleAwake() })
                }
            }

            Spacer(Modifier.height(20.dp))


            // 通知
            SettingsGroupTitle("通知")
            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                // 必须自己套一层 Column：Surface 的内容按 Box 排布，直接放多个
                // 子节点会**互相重叠**（诊断文字压在标题上，实测踩过）。
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                        Text(
                            "会话完成通知",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "某个会话回答完成后推送通知；开启后会有一条常驻通知保活",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = notifyEnabled, onCheckedChange = { onToggleNotify() })
                }

                // 开着却收不到，是最让人困惑的状态：给一条能自己验证的路，
                // 以及一个直接跳到系统通知设置的入口 —— 渠道的重要性归系统管，
                // 代码改不动用户已经选过的设置。
                if (notifyEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onTestNotify, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text("发条测试通知", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = onNotifySettings, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("系统设置", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    notifyProblem?.let { problem ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            problem,
                            style = MaterialTheme.typography.labelSmall,
                            color = DshColor.Danger,
                        )
                    }

                    // 事件流诊断。通知是从这条流里来的，它断了就什么都收不到 ——
                    // 而以前断了只把状态点变灰，原因哪儿都看不到。
                    Spacer(Modifier.height(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (streamLive) DshColor.Running else DshColor.TextFaintLight),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (streamMode) {
                                    "stream" -> "事件流已连接"
                                    "poll" -> "轮询模式（上游不支持事件流）"
                                    else -> "事件流未连接"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        val detail = when {
                            streamMode == "poll" ->
                                "这台电脑上的 DSH 没有 WebSocket 事件流（0.1.2 之前没有），" +
                                    "已自动改用轮询，通知延迟最多几秒。"
                            streamError != null -> "上次断开：$streamError"
                            streamLive && lastEventAt > 0 -> "最近一条会话事件：${relativeTime(lastEventAt)}"
                            streamLive -> "已连接，还没收到过会话事件"
                            else -> "正在重连…"
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (streamError != null) DshColor.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // 让用户能把完整诊断贴出来。截图会把长错误压成一行看不清，
                        // 而这条信息正是定位问题唯一需要的东西。
                        val clip = LocalClipboardManager.current
                        var copied by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = {
                                clip.setText(AnnotatedString(
                                    "dshgo 诊断\n" +
                                        "入口地址：$url\n" +
                                        "事件流：" + when (streamMode) {
                                            "stream" -> "已连接（实时）"
                                            "poll" -> "轮询模式"
                                            else -> "未连接"
                                        } + "\n" +
                                        "断开原因：" + (streamError ?: "（无）") + "\n" +
                                        "最近事件：" + (if (lastEventAt > 0) relativeTime(lastEventAt) else "从未收到"),
                                ))
                                copied = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                if (copied) "已复制，粘贴给我即可" else "复制诊断信息",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                }
            }

            Spacer(Modifier.height(14.dp))

            WidgetDeviceSection()

            Spacer(Modifier.height(14.dp))

            TsTunnelSection()

            Spacer(Modifier.height(18.dp))

            SettingsGroupTitle("安全")
            Spacer(Modifier.height(8.dp))

            // 解锁方式
            //
            // 放在这里而不是塞进通知那块：它和"怎么进这个 App"有关，
            // 和通知无关。设备实际支持什么写在下面 —— 用户选了"仅指纹/人脸"
            // 但这台设备只有人脸时，得让他看得见为什么弹的是人脸。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "解锁方式",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "打开 App 时怎么验证身份。访问密码始终是根凭据 —— 生物识别只是本机的一道门。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(10.dp))
                    SegmentedControl(
                        options = listOf(
                            "auto" to "自动",
                            "biometric" to "指纹/人脸",
                            "credential" to "设备密码",
                            "none" to "每次输密码",
                        ),
                        selected = lockMode,
                        onSelect = onLockMode,
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append(
                                when (lockMode) {
                                    "biometric" -> "只用指纹或人脸，不给设备密码回退。"
                                    "credential" -> "只用设备的 PIN / 图案 / 密码。"
                                    "none" -> "本机不存密码，每次打开都手输 —— 最严的一档。"
                                    else -> "指纹或人脸优先，没有就用设备密码。"
                                },
                            )
                            if (lockMode != "none") {
                                append("　这台设备可用：")
                                append(lockDeviceMethod.ifEmpty { "无" })
                                if (lockDeviceMethod == "无") {
                                    // 文案要跟着选项走 —— 选了「设备密码」却说"配好指纹或人脸"，
                                    // 用户会以为选错了
                                    append(
                                        if (lockMode == "credential") "（先去系统设置里设一个 PIN 或图案）"
                                        else "（配好指纹或人脸后再来）",
                                    )
                                }
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            DiagnosticsSection()

            SettingsGroupTitle("更新与版本")
            Spacer(Modifier.height(8.dp))

            // 版本与更新
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "版本",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (updateChecking) "正在检查…" else "当前 $currentVersion",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = onCheckUpdate,
                            enabled = !updateChecking,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Text("检查更新", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // 有新版本才占地方 —— 没查到（网络不通）时什么都不说，
                    // 检查更新失败不该打扰用户。
                    if (updateNewer && updateLatest != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (updatePhase == "ready") "已下载，等待安装" else "有新版本 $updateLatest",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = onInstall,
                                enabled = updatePhase != "downloading",
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                Text(
                                    when (updatePhase) {
                                        "downloading" -> "下载中"
                                        "ready" -> "去安装"
                                        "failed" -> "重试"
                                        else -> "下载并安装"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }

                        // 进度条。总长未知时（服务端没给 Content-Length）用不确定态 ——
                        // 硬凑一个百分比反而是在骗人。
                        if (updatePhase == "downloading") {
                            Spacer(Modifier.height(8.dp))
                            val pct = if (updateTotal > 0) {
                                ((updateBytes * 100) / updateTotal).toInt().coerceIn(0, 100)
                            } else {
                                null
                            }
                            if (pct == null) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(
                                    progress = { pct / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildString {
                                    append(fmtSize(updateBytes))
                                    if (updateTotal > 0) append(" / ").append(fmtSize(updateTotal))
                                    if (pct != null) append("　").append(pct).append("%")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        updateError?.let { err ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "下载失败：$err",
                                style = MaterialTheme.typography.labelSmall,
                                color = DshColor.Danger,
                            )
                        }

                        // 应用内下载只是方便，不是唯一的路 —— 装不上时还得能手工兜底
                        TextButton(
                            onClick = { onDownload(UpdateCheck.APK_URL) },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text("改用浏览器下载", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    TextButton(
                        onClick = onPinWidget,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text("把小组件放到桌面", style = MaterialTheme.typography.labelSmall)
                    }

                    // 兜底：查不到版本（GitHub API 在国内经常连不上）或下载装不上时，
                    // 至少还有一条「拿浏览器去 GitHub 自己看」的路。
                    TextButton(
                        onClick = { onDownload(UpdateCheck.RELEASES_PAGE) },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            "在浏览器打开 GitHub 更新页",
                            style = MaterialTheme.typography.labelSmall,
                            color = DshColor.Accent,
                        )
                    }

                    if (!updateChecking && updateLatest != null && !updateNewer) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            // 显示本机真实版本号。以前用 $updateLatest，而它是从宿主
                            // `/__dshgo__/info` 的 latestVersion 拿的 —— 宿主没重启就一直是
                            // 旧的 4.4.3，和 BuildConfig 对不上。
                            "已是最新版本（$currentVersion）",
                            style = MaterialTheme.typography.labelSmall,
                            color = DshColor.Running,
                        )
                        // 查得到版本、但你知道有更新的情况确实会存在：镜像有索引延迟，
                        // GitHub 国内又可能不通。所以给一条永远可用的手工路，
                        // 而不是让用户对着"已是最新"干瞪眼。
                        TextButton(
                            onClick = { onDownload("") },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text("手动下载最新版", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (updateChecking || updateLatest == null) {
                        // 一路都没查到 —— 那就别装"已是最新"，直接给手工入口
                        TextButton(
                            onClick = { onDownload("") },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text("直接打开下载页", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 破坏性操作放最后：以前它夹在「通知」和「小组件」中间，
            // 手滑点到的代价太高，也说不清属于哪一类。
            ResetSection(onClearSession = onClearSession)

            Spacer(Modifier.height(10.dp))
            Text(
                "Fnh Go · 直连你自己的电脑 · 界面为 DSH 原生",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OutlinedActionButton(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 14.dp),
            textAlign = TextAlign.Center,
            color = tint,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(4.dp)) {
            options.forEach { (value, label) ->
                val active = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .clickable { onSelect(value) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            Icon(
                                painterResource(FnhIcons.Check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 平板右栏：尚未选择会话时的占位
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// 崩溃报告
// ---------------------------------------------------------------------------

/**
 * 上次运行的崩溃栈。
 *
 * 不做自动上报（没有服务器），就地把栈显示出来、一键复制——手机端崩溃往往
 * 只在特定 ROM/系统版本上出现，能把现场带出来就省掉一整轮来回。
 */
@Composable
fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "上次运行崩溃了",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Text(
                    "把下面的内容发给我就能定位。也可以直接复制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                report,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                onDismiss()
            }) { Text("复制并关闭") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

// ---------------------------------------------------------------------------
// 连接中
// ---------------------------------------------------------------------------

@Composable
fun ConnectingScreen(host: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(size = 56)
            Spacer(Modifier.height(14.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "正在连接",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (host.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    host,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DSH 界面加载中 / 出错
// ---------------------------------------------------------------------------

@Composable
fun DshLoadingOverlay(
    host: String,
    error: String?,
    onReload: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (error == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "正在载入 DSH 界面…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (host.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        host,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Icon(
                    painterResource(FnhIcons.Warning),
                    contentDescription = null,
                    tint = DshColor.Danger,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "打不开 DSH 界面",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onReload, shape = RoundedCornerShape(14.dp)) {
                        Text("重新加载")
                    }
                    TextButton(onClick = onSettings) { Text("设置") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶部状态条
// ---------------------------------------------------------------------------

/**
 * DSH 界面顶部的原生状态条（44dp）。
 *
 * 这个 app 没有首页 —— 会话列表在 DSH 自己的侧栏里。所以这条是**唯一**的原生入口，
 * 必须常驻：左边显示连的是哪台机器，右边是「重新加载」和「设置」。
 *
 * 只占 44dp，且和 DSH 自己的顶栏同色，视觉上是一条连续的顶栏而不是两层。
 */
@Composable
fun DshStatusStrip(
    host: String,
    /** 当前这条链路的性质：「局域网」/「Tailscale」——换网时一眼能看出换过去没有。 */
    channel: String = "",
    live: Boolean,
    unseen: Int,
    onNotice: () -> Unit,
    onReload: () -> Unit,
    onSettings: () -> Unit,
    /** 回首页（连接列表）。 */
    onHome: () -> Unit,
    /** 语音输入：说话 → 写进 DSH 的输入框。 */
    onVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(StripHeight)
                // end 收到 0：设置是这一行的最后一个按钮，外面再留 4dp 就会显得「没靠到边」。
                // IconButton 自己有 12dp 内部留白，视觉上仍然不贴边。
                .padding(start = 16.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 回首页（连接列表）。放在最左 —— 它管的是「现在连着哪个」，
            // 比通知 / 麦克风这些更靠外一层。
            IconButton(onClick = onHome, modifier = Modifier.size(38.dp)) {
                Icon(
                    painterResource(FnhIcons.Back),
                    contentDescription = "返回连接列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }

            // ★ 绿点 + 地址 + 通道标签按内容宽度排在左边（不占权重），
            //   然后靠后面那个 Spacer(weight) 把按钮顶到最右。
            //   以前是「Text 自己 weight(1f, fill=false) + 后面再来一个 Spacer(weight(1f))」——
            //   两个权重平分剩余空间，地址只占一半、另一半留白，按钮就停在屏幕中间偏右。
            //   注意别反过来给这一整块加权：那样通道标签会被推到按钮旁边，离开地址。
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 绿 = 事件流通着；灰 = 断了（断了就收不到完成通知）
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (live) DshColor.Running else DshColor.TextFaintLight),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    host.ifEmpty { "DSH" },
                    // 不给权重：地址按内容宽度占位，太长的用 ellipsis 截断，
                    // 免得把通道标签和按钮挤走。
                    modifier = Modifier.widthIn(max = 168.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    // 通道标签：Wi-Fi ↔ 蜂窝切换后地址会换，这里跟着变，
                    // 不用再点进设置猜「现在走的是哪条」。
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = if (channel == "Tailscale") softAccent(0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            channel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                            color = if (channel == "Tailscale") DshColor.Accent
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }

            // 剩余空间全部吃掉 —— 这是把铃铛/话筒/刷新/设置顶到最右的那一下。
            Spacer(Modifier.weight(1f))

            // 铃铛 + 未读角标
            Box {
                IconButton(onClick = onNotice) {
                    Icon(
                        painterResource(FnhIcons.Bell),
                        contentDescription = "通知",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp),
                    )
                }
                if (unseen > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 2.dp)
                            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                            .clip(CircleShape)
                            .background(DshColor.Danger)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (unseen > 99) "99+" else unseen.toString(),
                            fontSize = 9.5.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
            }

            // 语音输入：手机上打字是最大的摩擦，而 prompt 恰恰适合用说的
            IconButton(onClick = onVoice) {
                Icon(
                    painterResource(FnhIcons.Mic),
                    contentDescription = "语音输入",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }
            IconButton(onClick = onReload) {
                Icon(
                    painterResource(FnhIcons.Refresh),
                    contentDescription = "重新加载",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    painterResource(FnhIcons.Settings),
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

/**
 * 主文档加载失败时的界面。
 *
 * 为什么自己做而不用 WebView 的原生错误页：那一页只有一句
 * `net::ERR_CONNECTION_REFUSED`，对用户毫无指导意义 —— 他不知道该去开 dsh web、
 * 还是该改地址、还是该检查是不是同一个网络。
 *
 * 这里做两件事：**把错误翻成人话**，以及**给出下一步动作**。
 */
@Composable
fun PageErrorOverlay(
    host: String,
    url: String,
    detail: String,
    onRetry: () -> Unit,
    onEditUrl: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(DshColor.DangerSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(FnhIcons.Warning),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = DshColor.Danger,
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "连不上 $host",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                explainLoadFailure(detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // 逐条排查。顺序按「最常见的先查」排 —— 电脑上 dsh web 停了是最常见的原因。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "按顺序查一遍",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "电脑上的 dsh web 还在运行吗？关掉那个终端窗口它就停了。",
                        "dshgo 插件装了吗、装完重启过 dsh web 吗？",
                        "手机和电脑在同一个 WiFi（或同一个 Tailscale 网络）吗？",
                        "地址对吗？电脑上「设置 → 局域网访问」里能一键复制。",
                    ).forEachIndexed { i, line ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(18.dp),
                            )
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("重试", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onEditUrl,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("改地址", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onSettings,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("设置", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(Modifier.height(18.dp))

            // 原始错误留在最后：普通用户用不上，但排查时是唯一线索
            Text(
                "$url\n$detail",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 把 WebView 的错误码翻成人话。 */
/** 字节数写成人看的量级。 */
private fun fmtSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun explainLoadFailure(detail: String): String = when {
    detail.contains("CONNECTION_REFUSED", ignoreCase = true) ->
        "电脑拒绝了这个连接 —— 最常见的原因是 dsh web 没在运行，或者 dshgo 插件没装上（装完要重启 dsh web）。"
    detail.contains("TIMED_OUT", ignoreCase = true) ||
        detail.contains("ADDRESS_UNREACHABLE", ignoreCase = true) ||
        detail.contains("HOST_LOOKUP", ignoreCase = true) ->
        "连不上这台电脑 —— 手机和它不在同一个网络，或者地址填错了。"
    detail.contains("NAME_NOT_RESOLVED", ignoreCase = true) ->
        "这个地址解析不了 —— 地址可能写错了，请对照电脑上显示的那一串。"
    detail.contains("CLEARTEXT", ignoreCase = true) ->
        "系统拦截了明文 HTTP 请求。这不该发生，麻烦反馈一下。"
    detail.startsWith("HTTP 4") ->
        "服务器返回了 $detail —— 多半是登录凭据失效了，点「重试」会重新握手。"
    detail.startsWith("HTTP 5") ->
        "服务器返回了 $detail —— 转发层或 dsh web 那边出了状况。"
    else -> "页面没能加载。"
}

/**
 * 访问闸门界面。
 *
 * 宿主（插件）在转发端口上加了一道密码闸门：浏览器看到密码页，App 看到 401。
 * 这是 App 那一侧的界面 —— 先过生物识别（或设备密码），再去宿主那边换解锁凭据。
 *
 * 设计上刻意**不做成"App 自己的锁"**：真正的安全边界在宿主那边，
 * 这一屏只是把"交密码"这一步做得舒服一点。所以即使有人绕过这一屏，
 * 他面对的仍然是宿主的闸门 —— 这也正是为什么两类客户端要的是同一个密码。
 */
@Composable
fun LockScreen(
    host: String,
    stage: String,
    error: String?,
    /** 这台设备支持什么（AppLock.describe）；没有生物识别时为空。 */
    deviceMethod: String,
    /** 是否已经存过密码 —— 决定了"下次能不能直接用人脸/指纹"。 */
    hasStoredPassword: Boolean,
    onUnlock: (String) -> Unit,
    onRetryBiometric: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(FnhIcons.Lock),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = DshColor.Accent,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "需要解锁",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$host 暴露在网络上，已开启访问验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(26.dp))

            if (stage == "busy") {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "正在验证…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (stage == "prompt") {
                // 生物识别由系统弹窗承载，这里只放一个重新拉起的入口
                Button(
                    onClick = onRetryBiometric,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("用指纹或面部解锁", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { input = "" }) {
                    Text("改用访问密码", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                // ★ 把「为什么这次要输密码」说清楚。
                //
                // 密码是根凭据，生物识别只是本地的一道门 —— 所以第一次必须先输一次，
                // 之后才能用人脸/指纹。不解释的话，用户配好了人脸解锁却发现还是密码框，
                // 会直接认为"生物识别没生效"。
                if (deviceMethod != "无" && !hasStoredPassword) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = softAccent(),
                    ) {
                        Text(
                            "这台设备支持$deviceMethod。先输一次密码，之后就能直接用$deviceMethod 解锁，不用再输。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("访问密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = {
                        if (input.isNotBlank()) onUnlock(input)
                    }),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { if (input.isNotBlank()) onUnlock(input) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("解锁", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "这个密码是在电脑上的 DSH「设置 → 局域网访问」里设的。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = DshColor.Danger,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onOpenSettings) {
                Text("设置", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 顶部的系统 inset —— **只在窗口真的贴着屏幕顶时才留**。
 *
 * ## 为什么不能直接用 statusBarsPadding()
 *
 * 分屏（以及部分自由窗口场景）时，系统已经把窗口放在状态栏下面了，
 * 但 Compose 仍然报一个非零的状态栏 inset —— 照补就是白留一条。
 * 实测：分屏下面板上方多出约 29dp 的空白，正好是状态栏的高度。
 *
 * ## 判据是窗口在屏幕上的位置
 *
 * 补 inset 的目的是「别让内容钻到状态栏图标底下」。窗口顶端已经在状态栏
 * 下沿之下时，这个目的已经由系统达成了，再补一次纯属浪费。
 * 全屏 edge-to-edge 时窗口从 y=0 开始，这个条件不成立，照常补。
 */
@Composable
private fun rememberTopInset(): androidx.compose.ui.unit.Dp {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // getTop 是 @Composable 的，只能在合成函数体里读 —— 不能塞进 DisposableEffect
    val rawTop = WindowInsets.statusBars.getTop(density)
    var pad by remember { mutableStateOf(0.dp) }

    DisposableEffect(view, rawTop) {
        val loc = IntArray(2)
        fun apply() {
            view.getLocationOnScreen(loc)
            // 窗口顶端已经在状态栏下沿之下 → 系统已经躲开了，不用再补
            pad = if (loc[1] >= rawTop) 0.dp else with(density) { rawTop.toDp() }
        }
        apply()
        val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    return pad
}

/**
 * 首页：选择要连接的电脑。
 *
 * ## 为什么连接列表是首页，而不是藏在设置里
 *
 * 一个人可能有好几台跑 DSH 的机器，也可能同一个 DSH 在不同网络下地址不同
 * （家里局域网、出门 Tailscale）。藏在设置里意味着每次换机器都要翻三层菜单。
 * 摆在首页就是一屏之遥，而且每个状态一眼可见。
 *
 * ## 状态能给什么、不能给什么
 *
 * 探针打插件自己的两个端点（`/info` 和 `/auth/status`），**都不需要先解锁** ——
 * 所以从没登录过的 DSH 也能显示在线、延迟、版本、要不要密码。
 *
 * **「这台机器里有几个会话在跑」拿不到** —— 那要先登录它，而列表里的连接你
 * 未必都存过密码。所以不显示，而不是放一个永远空着的「0 个会话」。
 */
@Composable
fun ConnectionsScreen(
    connections: List<Connection>,
    statuses: Map<String, ConnectionProbe.Status>,
    activeId: String?,
    probing: Boolean,
    onSwitch: (Connection) -> Unit,
    onProbeAll: () -> Unit,
    onEdit: (Connection) -> Unit,
    onDelete: (Connection) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 点齿轮管理的是**整台设备**（它可能有两三条地址），不是单条连接
    var editingRows by remember { mutableStateOf<List<Connection>?>(null) }
    val topPad = rememberTopInset()

    // ★ 背景铺满整屏、内边距只作用于内容。
    //
    // 以前把 inset 写在 Surface 的 modifier 上，等于把自己整体往下挪 ——
    // 状态栏那一条露出来的是**后面**的东西（黑底或上一屏），
    // 于是「通知栏透明、和内容重合」。
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = topPad)
                .navigationBarsPadding(),
        ) {

            // ── 品牌头 ────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    // top 只留 4dp：上面已经有 statusBarsPadding() 给的系统 inset，
                    // 再叠一层自己的内边距就是白留一条。
                    .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_brand_f),
                    contentDescription = null,
                    // 品牌标记自带 logo 的灰 + 蓝，交给主题着色就变成单色块了
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Fnh Go",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (probing) "正在检查各台电脑…" else "选择要连接的电脑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 设备页也给设置入口：以前要先随便进一台设备才能改设置，
                // 换机器/换密码的时候就得多点好几层。
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painterResource(FnhIcons.Settings),
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(2.dp))
                Surface(
                    onClick = onAdd,
                    shape = CircleShape,
                    color = softAccent(0.16f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(FnhIcons.Add),
                            contentDescription = "添加设备",
                            tint = DshColor.Accent,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                if (connections.isEmpty()) {
                    EmptyConnections()
                } else {
                    // ★ 一台电脑一张卡。
                    //
                    // 同一个 DSH 常常有两条地址（家里局域网、出门 Tailscale）。
                    // 以前它们就是两张卡，看起来像两台机器，还各探各的。
                    // 插件现在报 deviceId，这里按它归并；探不到 id 的老插件退回按地址各算一张。
                    groupDevices(connections, statuses).forEach { group ->
                        DeviceCard(
                            group = group,
                            statuses = statuses,
                            activeId = activeId,
                            probing = probing,
                            onEnter = { onSwitch(it) },
                            onEdit = { editingRows = it },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 刷新做成一整条，比右上角一个小图标好按得多
                TextButton(
                    onClick = onProbeAll,
                    enabled = !probing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (probing) "检查中…" else "刷新状态",
                        style = MaterialTheme.typography.labelMedium,
                        color = DshColor.Accent,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "状态是探测出来的：插件在不在监听、版本多少、要不要密码。" +
                        "「几个会话在跑」只对当前连着的那条有效 —— 其他连接没登录过，探不到。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    editingRows?.let { rows ->
        DeviceDialog(
            rows = rows,
            statuses = statuses,
            activeId = activeId,
            onDismiss = { editingRows = null },
            onSave = { updated ->
                updated.forEach { onEdit(it) }
                editingRows = null
            },
            onDeleteAll = {
                rows.forEach { onDelete(it) }
                editingRows = null
            },
        )
    }
}

/** 一条连接都没存过时的引导。 */
@Composable
private fun EmptyConnections() {
    Surface(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "还没有连接",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "地址在电脑上的 DSH「设置 → 局域网访问」里 —— 也可以扫那一页的二维码，" +
                    "不用手敲 IP。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
}

/** 一台电脑：可能有多条通道（家里局域网 / 出门 Tailscale）。 */
private data class DeviceGroup(
    val key: String,
    val name: String,
    /** 该设备的各条地址，局域网排前面。 */
    val rows: List<Connection>,
)

/**
 * 把「地址」归并成「设备」。
 *
 * 判据是插件自报的 `deviceId`（主机名+用户名+平台派生，稳定）。探不到就退回一条地址一张卡 ——
 * 老版本插件没有这个字段，不能因为升级 App 就把人家的连接拆没了。
 */
private fun groupDevices(
    connections: List<Connection>,
    statuses: Map<String, ConnectionProbe.Status>,
): List<DeviceGroup> {
    val buckets = LinkedHashMap<String, MutableList<Connection>>()
    for (c in connections) {
        // ★ 先看这次探测，再退回存储里的 deviceId。
        //   出门在外时局域网那条探不到，靠存下来的 id 才能和 Tailscale 那条并成一台。
        val byId = statuses[c.url]?.deviceId?.takeIf { it.isNotBlank() }
            ?: c.deviceId.takeIf { it.isNotBlank() }
        val key = if (byId != null) "d:$byId" else "u:${c.url}"
        buckets.getOrPut(key) { mutableListOf() }.add(c)
    }
    return buckets.map { (key, rows) ->
        val name = rows.firstNotNullOfOrNull { statuses[it.url]?.deviceName?.takeIf(String::isNotBlank) }
            ?: rows.first().displayName().substringBefore(" ·")
        DeviceGroup(key, name, rows.sortedBy { channelRank(it.url) })
    }
}

/** 局域网先、Tailscale 后：同 Wi-Fi 下先看到的应该是能直连的那条。 */
private fun channelRank(url: String): Int = if (channelLabel(url) == "Tailscale") 1 else 0

private fun channelLabel(url: String): String {
    val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
    return when {
        host.startsWith("100.") -> "Tailscale"
        host.startsWith("192.168.") || host.startsWith("10.") -> "局域网"
        host.matches(Regex("172\\.(1[6-9]|2\\d|3[01])\\..*")) -> "局域网"
        else -> "其它"
    }
}

/**
 * 设备卡上到底显示哪条通道。
 *
 * 判断顺序：当前网络下能用的那条 → 上次用的那条 → 顺序第一条。
 * 「能用」= 探测没过 Offline（能连、要密码、转发层没就绪都算能用，
 * 因为这些都能靠重连/输密码解决，只有 TCP 不通才是真的没网）。
 */
private fun visibleRow(
    rows: List<Connection>,
    statuses: Map<String, ConnectionProbe.Status>,
    activeId: String?,
    onWifi: Boolean,
): Connection {
    fun usable(c: Connection) = statuses[c.url]?.let { it.level != ConnectionProbe.Level.Offline } ?: false
    val lan = rows.firstOrNull { channelLabel(it.url) == "局域网" }
    val tail = rows.firstOrNull { channelLabel(it.url) == "Tailscale" }
    val ordered = if (onWifi) listOfNotNull(lan, tail) else listOfNotNull(tail, lan)
    return ordered.firstOrNull(::usable)
        ?: rows.firstOrNull { it.id == activeId }
        ?: ordered.firstOrNull()
        ?: rows.first()
}

/** 状态一句话 —— 列表里的每条通道共用这一份，不再各写一遍。 */private fun statusLine(status: ConnectionProbe.Status?, probing: Boolean): String = when {
    probing -> "检查中…"
    status == null -> "未检查"
    else -> when (status.level) {
        // TCP 都没通
        ConnectionProbe.Level.Offline -> status.error.ifEmpty { "连不上" }
        // 通了但被闸门挡住：可以连，只是要输访问密码
        ConnectionProbe.Level.Locked -> "可以连接 · 需要密码登录"
        // 插件在应答，但它背后的 dsh web 没起来：该去电脑上开 DSH，不是重装 dshgo
        ConnectionProbe.Level.NoUpstream -> "电脑上的 dsh web 没在运行"
        // 应答来自 DSH 本体，转发层没就绪（插件刚重启时会短暂出现）
        ConnectionProbe.Level.NoProxy -> "转发层还没就绪 —— 电脑上的 dshgo 没在监听"
        ConnectionProbe.Level.Online -> buildString {
            append(status.ms).append(" ms")
            if (status.version.isNotEmpty()) append(" · v").append(status.version)
            if (status.needsPassword) append(" · 需要解锁")
            if (status.updateAvailable) append(" · 插件有新版")
        }
    }
}

private fun statusColor(status: ConnectionProbe.Status?): androidx.compose.ui.graphics.Color =
    when (status?.level) {
        ConnectionProbe.Level.Offline -> DshColor.Danger
        ConnectionProbe.Level.Locked,
        ConnectionProbe.Level.NoUpstream,
        ConnectionProbe.Level.NoProxy,
        -> DshColor.Warning
        ConnectionProbe.Level.Online -> DshColor.Running
        null -> DshColor.TextFaintLight
    }

@Composable
private fun DeviceCard(
    group: DeviceGroup,
    statuses: Map<String, ConnectionProbe.Status>,
    activeId: String?,
    probing: Boolean,
    onEnter: (Connection) -> Unit,
    onEdit: (List<Connection>) -> Unit,
) {
    val anyActive = group.rows.any { it.id == activeId }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (anyActive) softAccent(0.10f) else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            width = if (anyActive) 1.5.dp else 1.dp,
            color = if (anyActive) DshColor.Accent.copy(alpha = 0.55f)
            else DshColor.OutlineDark.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 13.dp, bottom = 6.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(FnhIcons.Device),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (group.rows.size > 1) {
                    Text(
                        "${group.rows.size} 条通道",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = { onEdit(group.rows) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painterResource(FnhIcons.Settings),
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // ★ 只显示「现在这个网络下该走的那条」：两条都列出来用户没法判断点哪个。
            //   都不可用时显示一行「断联」，点它仍然按上次成功的那条重试。
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val onWifi = androidx.compose.runtime.remember(ctx) { com.dshgo.app.data.AutoRoute.onWifi(ctx) }
            val visible = visibleRow(group.rows, statuses, activeId, onWifi)
            ChannelRow(
                conn = visible,
                status = statuses[visible.url],
                active = visible.id == activeId,
                probing = probing && statuses[visible.url] == null,
                dead = statuses[visible.url]?.level == ConnectionProbe.Level.Offline,
                onClick = { onEnter(visible) },
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 一条通道：标签 + 地址 + 状态；点一下就走这条。 */
@Composable
private fun ChannelRow(
    conn: Connection,
    status: ConnectionProbe.Status?,
    active: Boolean,
    probing: Boolean,
    dead: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 2.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(status)),
            )
            Spacer(Modifier.width(9.dp))
            // 通道标签：一眼分清走的是哪条网
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = if (channelLabel(conn.url) == "Tailscale") softAccent(0.18f)
                else MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    channelLabel(conn.url),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = if (channelLabel(conn.url) == "Tailscale") DshColor.Accent
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    conn.url.removePrefix("http://"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (dead) "断联" else statusLine(status, probing),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = if (dead) DshColor.Danger else statusColor(status),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active) {
                Spacer(Modifier.width(6.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = DshColor.Accent) {
                    Text(
                        "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** 改名 / 改地址 / 删除。 */
@Composable
private fun DeviceDialog(
    rows: List<Connection>,
    statuses: Map<String, ConnectionProbe.Status>,
    activeId: String?,
    onDismiss: () -> Unit,
    onSave: (List<Connection>) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val first = rows.firstOrNull() ?: return
    var name by remember { mutableStateOf(first.name) }
    // 每条通道的地址都可改：换路由器、换端口不用删了重加。
    val urls = remember(rows) {
        mutableStateMapOf<String, String>().apply { rows.forEach { put(it.id, it.url) } }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    first.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${rows.size} 条通道 · 同一台电脑",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    val st = statuses[row.url]
                    val current = row.id == activeId
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (current) softAccent(0.10f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    channelLabel(row.url),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (channelLabel(row.url) == "Tailscale") DshColor.Accent
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.width(8.dp))
                                if (current) {
                                    Text(
                                        "当前使用",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DshColor.Accent,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${st?.ms ?: 0} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = urls[row.id] ?: row.url,
                                onValueChange = { urls[row.id] = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("地址") },
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                statusLine(st, false),
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor(st),
                            )
                            val extra = buildString {
                                if (!st?.version.isNullOrBlank()) append("插件 ${st?.version}")
                                if (st?.needsPassword == true) {
                                    if (isNotEmpty()) append(" · ")
                                    append("需要密码")
                                }
                                if (st?.upstreamReachable == false) {
                                    if (isNotEmpty()) append(" · ")
                                    append("dsh web 没起来")
                                }
                            }
                            if (extra.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    extra,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("设备名（可留空）") },
                    placeholder = { Text("例如：书房的台式机") },
                    shape = RoundedCornerShape(12.dp),
                )

                if (confirmDelete) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "再点一次「删除设备」确认：这台电脑的 ${rows.size} 条地址会一起删掉。",
                        style = MaterialTheme.typography.labelSmall,
                        color = DshColor.Danger,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        rows.map { row ->
                            row.copy(
                                name = name.trim(),
                                url = Prefs.normalize(urls[row.id] ?: row.url),
                            )
                        },
                    )
                },
                enabled = rows.all { !urls[it.id].isNullOrBlank() },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            // 两段式确认：一次点在折叠屏上很容易误触
            TextButton(onClick = { if (confirmDelete) onDeleteAll() else confirmDelete = true }) {
                Text(
                    if (confirmDelete) "确认删除" else "删除设备",
                    color = DshColor.Danger,
                )
            }
        },
    )
}

// ---------------- 连接页：两条地址 + 自动选路 ----------------

private const val PREF_LAN = "lan_url"
private const val PREF_TAIL = "tail_url"
private const val PREF_AUTO = "auto_route"

/** 引擎状态 → 一句人话（连接页与设置页共用）。 */
internal fun tsStateText(state: String): String = when {
    state.isBlank() -> "未启动"
    state == "Running" -> "已连接"
    state == "NeedsLogin" -> "等待登录"
    state == "Starting" -> "启动中"
    else -> state
}

/** 当前是否接在 Wi-Fi / 以太网上：家里局域网只有这两种网络能直连。 */
private fun isOnWifi(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

/** 自动选路：Wi-Fi 优先局域网地址，移动网络优先 Tailscale 地址；空的那条自然退到另一条。 */
private fun autoPickUrl(context: Context, lan: String, tail: String): String {
    val l = Prefs.normalize(lan)
    val t = Prefs.normalize(tail)
    return when {
        t.isBlank() -> l
        l.isBlank() -> t
        isOnWifi(context) -> l
        else -> t
    }
}

/**
 * 重置类操作：唯一会「把已有登录/缓存清掉」的地方。
 *
 * 为什么单独成组：这些按钮一旦误触代价很高，混在通知、主题、小组件中间
 * 会让人以为它也是普通开关。放最后一组、用危险色、二次确认，三件事一起做。
 */
@Composable
private fun ResetSection(onClearSession: () -> Unit) {
    SettingsGroupTitle("重置")
    Spacer(Modifier.height(8.dp))
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = DshColor.DangerSoft,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, DshColor.Danger.copy(alpha = 0.3f),
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "清除后会退回连接页，需要重新握手。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { confirming = false }) { Text("取消") }
                    TextButton(onClick = onClearSession) {
                        Text("确认清除", color = DshColor.Danger)
                    }
                }
            }
        }
    } else {
        OutlinedActionButton(
            label = "清除登录与缓存",
            tint = DshColor.Danger,
            onClick = { confirming = true },
        )
    }
}
