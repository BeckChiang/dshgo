package com.dshgo.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshgo.app.tunnel.TsTunnel

/**
 * 设置里的「内嵌 Tailscale」分组。
 *
 * 设计取舍：
 * - 隧道只在【启动隧道】被点过一次之后才建，平时不占用系统 VPN 槽位；
 * - 登录走 Tailscale 官方交互页（引擎没有 auth key 接口，见 PLAN.md 的阶段 4 说明）；
 * - 只把本应用加进隧道（addAllowedApplication），别的 App 不受影响。
 */
@Composable
fun TsTunnelSection() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(TsTunnel.state(ctx)) }
    var address by remember { mutableStateOf(TsTunnel.selfAddress(ctx)) }
    var loginUrl by remember { mutableStateOf(TsTunnel.loginUrl(ctx)) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        state = TsTunnel.state(ctx)
        address = TsTunnel.selfAddress(ctx)
        loginUrl = TsTunnel.loginUrl(ctx)
    }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            TsTunnel.start(ctx)
        }
        busy = false
        refresh()
    }

    SettingsGroupTitle("内嵌 Tailscale")
    Spacer(Modifier.height(8.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "状态：" + when {
                    busy -> "处理中…"
                    state.isBlank() -> "未启动"
                    state == "Running" -> "已连接" + if (address.isBlank()) "" else " · $address"
                    state == "NeedsLogin" -> "等待登录"
                    state == "Starting" -> "启动中"
                    else -> state
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "隧道只接入本应用，不影响手机其它应用的网络；与系统里的 Tailscale 不能同时开启。",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    busy = true
                    val intent = TsTunnel.needsConsent(ctx)
                    if (intent != null) {
                        consent.launch(intent)
                    } else {
                        TsTunnel.start(ctx)
                        busy = false
                        refresh()
                    }
                }) { Text("启动隧道") }

                TextButton(onClick = {
                    TsTunnel.stop(ctx)
                    refresh()
                }) { Text("关闭隧道") }

                TextButton(onClick = {
                    val url = TsTunnel.startLogin(ctx)
                    loginUrl = url
                    if (url.isNotBlank()) {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }) { Text("登录") }

                TextButton(onClick = { refresh() }) { Text("刷新") }
            }
            if (loginUrl.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    loginUrl,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                )
            }
        }
    }
    Spacer(Modifier.height(22.dp))
}
