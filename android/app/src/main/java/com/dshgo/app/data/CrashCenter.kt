package com.dshgo.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程退出原因采集。
 *
 * `CrashLog` 只能抓 Java/Kotlin 的未捕获异常；libtailscale 崩在 native 时
 * JVM 的 handler 根本不会触发。Android 30 起系统自己记录了每次进程退出：
 *
 * - [ActivityManager.getHistoricalProcessExitReasons] 拿到最近几次退出；
 * - [android.app.ApplicationExitInfo.getReason] 区分 Java 崩溃 / native 崩溃 /
 *   ANR / 被系统杀；
 * - native 崩溃时 [android.app.ApplicationExitInfo.getTraceInputStream]
 *   就是 tombstone 文本（寄存器、backtrace、崩溃地址）。
 *
 * 这条路不需要 root，也不需要 adb —— 对「没有实机调试」的场景是唯一的证据来源。
 */
object CrashCenter {

    private const val TAG = "DshCrash"
    private const val DIR = "crash"
    private const val KEEP = 10

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    /**
     * 把当前可见的历史退出原因落盘（去重，按时间戳命名）。
     *
     * 每次冷启动调一次即可：新退出会自然出现在列表里，旧的因为文件名已存在被跳过。
     */
    fun capture(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // 0 = 只取本包，0 = 不做过滤，最多 10 条
            val list = am.getHistoricalProcessExitReasons(null, 0, KEEP)
            for (info in list) {
                val stamp = info.timestamp
                val f = File(dir(ctx), "exit-$stamp.txt")
                if (f.exists()) continue
                val text = describe(info)
                f.writeText(text)
                Log.w(TAG, "exit reason captured: $stamp")
            }
            prune(ctx)
        }.onFailure { Log.w(TAG, "capture: ${it.message}") }
    }

    private fun describe(info: android.app.ApplicationExitInfo): String {
        val sb = StringBuilder()
        sb.appendLine("Fnh Go 进程退出记录")
        sb.appendLine("时间: ${format(info.timestamp)}")
        sb.appendLine("原因: ${reasonName(info.reason)} (${info.reason})")
        sb.appendLine("状态: ${info.status}")
        sb.appendLine("重要性: ${info.importance}")
        info.description?.takeIf { it.isNotBlank() }?.let { sb.appendLine("描述: $it") }
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("版本: ${com.dshgo.app.BuildConfig.VERSION_NAME} (${com.dshgo.app.BuildConfig.VERSION_CODE})")
        sb.appendLine("─────────────────────────────────────────")
        // native 崩溃时这里就是 tombstone
        runCatching {
            info.traceInputStream?.use { input ->
                val bytes = input.readBytes()
                if (bytes.isNotEmpty()) {
                    sb.appendLine(bytes.toString(Charsets.UTF_8).take(48 * 1024))
                }
            }
        }.onFailure { sb.appendLine("（读取 tombstone 失败: ${it.message}）") }
        return sb.toString()
    }

    private fun reasonName(r: Int): String = when (r) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native 崩溃"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR 无响应"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "主动退出"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被杀"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死"
        android.app.ApplicationExitInfo.REASON_OTHER -> "其他"
        else -> "未知"
    }

    /** 最近 5 条退出记录（新的在前），给设置页展示。 */
    fun recent(ctx: Context, limit: Int = 5): List<File> = runCatching {
        val f = dir(ctx).listFiles()?.filter { it.name.startsWith("exit-") } ?: emptyList()
        f.sortedByDescending { stampOf(it) }.take(limit)
    }.getOrDefault(emptyList())

    fun read(ctx: Context, f: File): String = runCatching { f.readText() }.getOrDefault("")

    fun clear(ctx: Context) {
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }

    /** 导出用：退出记录 + 事件流水 + 上次 Java 崩溃。 */
    fun bundle(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== Fnh Go 诊断包 ===")
        sb.appendLine("生成时间: ${format(System.currentTimeMillis())}")
        sb.appendLine()
        sb.appendLine("=== 上次 Java 崩溃 ===")
        sb.appendLine(com.dshgo.app.CrashLog.read(ctx) ?: "（无）")
        sb.appendLine()
        sb.appendLine("=== 进程退出记录（含 native tombstone）===")
        val files = recent(ctx, 5)
        if (files.isEmpty()) sb.appendLine("（无）")
        for (f in files) {
            sb.appendLine(read(ctx, f))
            sb.appendLine()
        }
        sb.appendLine("=== 事件流水（最近 200 条）===")
        sb.appendLine(EventLog.tail(ctx).ifBlank { "（无）" })
        return sb.toString()
    }

    private fun stampOf(f: File): Long =
        f.name.removePrefix("exit-").removeSuffix(".txt").toLongOrNull() ?: 0L

    private fun prune(ctx: Context) {
        val all = runCatching {
            dir(ctx).listFiles()?.filter { it.name.startsWith("exit-") } ?: return
        }.getOrDefault(return)
        if (all.size <= KEEP) return
        all.sortedByDescending { stampOf(it) }.drop(KEEP).forEach { it.delete() }
    }

    private fun format(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
