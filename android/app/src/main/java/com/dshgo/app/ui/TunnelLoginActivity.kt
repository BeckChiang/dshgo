package com.dshgo.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.remote.Tunnel
import com.dshgo.app.data.CrashCenter
import com.dshgo.app.data.EventLog
import com.dshgo.app.tunnel.TsTunnel
import kotlinx.coroutines.delay

/**
 * 独立的 Tailscale 页面。
 *
 * 以前它挤在设置页里，导致「登录态 / 隧道开关 / 连接地址」三件事混在一起：
 * 连接页会因为你切地址而重连，隧道页只关心引擎，互不影响。这个页面只做三件事：
 * 显示引擎状态、发起登录、开/关隧道。
 */
class TunnelLoginActivity : ComponentActivity() {

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // 无论同意与否都回写一条事件，便于事后判断「崩在授权前还是授权后」
        EventLog.event(this, "vpn.consent", "result=${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            TsTunnel.start(this)
            Toast.makeText(this, "隧道已启动", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "没有授予 VPN 权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.event(this, "ui.tunnel", "open")
        setContent {
            MaterialTheme {
                TunnelScreen(
                    onStart = { startTunnel() },
                    onStop = { TsTunnel.stop(this); EventLog.event(this, "tunnel.stop", "user") },
                    onLogin = { openLogin() },
                    onLogout = { logout() },
                    onDiagnostics = { shareDiagnostics() },
                )
            }
        }
    }

    private fun startTunnel() {
        EventLog.event(this, "tunnel.start", "user")
        // ★ 关键：先要授权再启动服务。没授权直接 startForegroundService，
        // 引擎起来后 establish 会返回 null，部分 ROM 上表现为闪退。
        val prepare = TsTunnel.needsConsent(this)
        if (prepare != null) {
            EventLog.event(this, "vpn.consent", "required")
            consent.launch(prepare)
            return
        }
        TsTunnel.start(this)
        Toast.makeText(this, "隧道启动中…", Toast.LENGTH_SHORT).show()
    }

    private fun openLogin() {
        val url = TsTunnel.startLogin(this)
        EventLog.event(this, "tunnel.login", if (url.isBlank()) "no-url" else "url")
        if (url.isBlank()) {
            Toast.makeText(this, "登录地址还没准备好，稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun logout() {
        runCatching { Tunnel.get(this).logout() }
        TsTunnel.stop(this)
        EventLog.event(this, "tunnel.logout", "user")
    }

    private fun shareDiagnostics() {
        val text = CrashCenter.bundle(this)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Fnh Go 诊断")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, "分享诊断日志")) }
    }
}

@Composable
private fun TunnelScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var loginUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            state = runCatching { Tunnel.get(ctx).backendState() }.getOrDefault("")
            address = TsTunnel.selfAddress(ctx)
            loginUrl = TsTunnel.loginUrl(ctx)
            delay(1200)
        }
    }
    DisposableEffect(Unit) { onDispose { } }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Tailscale", style = MaterialTheme.typography.headlineSmall)
            Text(
                "隧道只把本应用放进去，手机其它 App 不走它。",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(4.dp))
            Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("引擎状态：${state.ifBlank { "未知" }}", fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("本机地址：${address.ifBlank { "尚未分配" }}", fontSize = 12.sp)
                    if (loginUrl.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("待登录：$loginUrl", fontSize = 11.sp, maxLines = 2)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("启用隧道") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停用") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onLogin, modifier = Modifier.weight(1f)) { Text("登录 Tailscale") }
                OutlinedButton(onClick = onLogout, modifier = Modifier.weight(1f)) { Text("退出登录") }
            }
            OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("分享诊断日志")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "登录状态存在应用私有目录，升级不会清；卸载才会清。",
                fontSize = 11.sp,
            )
        }
    }
}
