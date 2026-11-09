package com.dshgo.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.dshgo.app.data.CrashCenter
import com.dshgo.app.data.EventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页的「诊断」分组。
 *
 * 手机端崩溃很难现场抓：这里把三种证据集中到一处 ——
 * Java 崩溃（`CrashLog`）、进程退出原因与 native tombstone（`CrashCenter`）、
 * 事件流水（`EventLog`），一键打包分享。
 */
@Composable
fun DiagnosticsSection() {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(CrashCenter.recent(ctx, 3)) }

    SettingsGroupTitle("诊断")
    Spacer(Modifier.height(8.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (items.isEmpty()) "还没有采集到崩溃记录" else "最近 ${items.size} 次进程退出",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "包含 native 崩溃（tailscale 引擎）的 tombstone；升级后仍可查看。",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
            )

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
                for (f in items) {
                    val stamp = f.name.removePrefix("exit-").removeSuffix(".txt").toLongOrNull() ?: 0L
                    Text("· ${fmt.format(Date(stamp))}  ${f.name}", fontSize = 11.sp)
                    val body = CrashCenter.read(ctx, f)
                    val reason = body.lineSequence().firstOrNull { it.startsWith("原因:") } ?: ""
                    if (reason.isNotBlank()) {
                        Text("    $reason", fontSize = 11.sp)
                    }
                }
                val events = EventLog.tail(ctx, 12)
                if (events.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("最近事件", fontSize = 11.sp)
                    for (line in events.lines().takeLast(8)) {
                        Text(line, fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { items = CrashCenter.recent(ctx, 3); expanded = !expanded },
                    modifier = Modifier.weight(1f),
                ) { Text(if (expanded) "收起" else "查看") }

                OutlinedButton(
                    onClick = {
                        val text = CrashCenter.bundle(ctx)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Fnh Go 诊断")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        runCatching { ctx.startActivity(Intent.createChooser(send, "分享诊断日志")) }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("分享日志") }
            }

            TextButton(
                onClick = {
                    CrashCenter.capture(ctx)
                    items = CrashCenter.recent(ctx, 3)
                    Toast.makeText(ctx, "已重新采集", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重新采集退出原因", fontSize = 12.sp) }
        }
    }
}
