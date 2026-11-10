package com.dshgo.app.ui

import android.content.Intent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshgo.app.tunnel.TsTunnel
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * 设置里的「内嵌 Tailscale」—— **只有状态，没有开关**。
 *
 * 登录、启动/关闭隧道、刷新、看诊断，全部收进一个管理页（[TunnelLoginActivity]）。
 * 以前设置里是「状态 + 四个按钮」、连接页又给一个「登录」，同一件事三套入口，
 * 权限和状态在三个地方各自刷新，还互相不同步。
 *
 * 这里只做一件事：让你一眼看到隧道现在是什么状态，想去动它点「管理」。
 */
@Composable
fun TsTunnelSection() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(TsTunnel.state(ctx)) }
    var address by remember { mutableStateOf(TsTunnel.selfAddress(ctx)) }

    // 从管理页回来时状态可能变了；页面还在合成树里就轻量轮询一下，
    // 不做事件总线 —— 这条状态 2 秒刷一次的开销可以忽略。
    LaunchedEffect(Unit) {
        while (true) {
            state = TsTunnel.state(ctx)
            address = TsTunnel.selfAddress(ctx)
            delay(2000)
        }
    }

    SettingsGroupTitle("内嵌 Tailscale")
    Spacer(Modifier.height(8.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        buildString {
                            append(tsStateText(state))
                            if (state == "Running" && address.isNotBlank()) append(" · ").append(address)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "隧道只接入本应用，不影响其它 App；与系统 Tailscale 不能同时开。",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(ctx, TunnelLoginActivity::class.java)) }
                }) {
                    Text("管理", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    Spacer(Modifier.height(22.dp))
}
