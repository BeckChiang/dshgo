package com.dshgo.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 环形事件流水。
 *
 * 为什么需要它：手机端的 native 崩溃（libtailscale 里 Go 的 SIGSEGV）不会走
 * [com.dshgo.app.CrashLog]，栈也未必拿得到；但「崩之前走到了哪一步」几乎总能
 * 从事件流水里看出来。比如连续三条
 *
 *     tunnel.start → vpn.prepare.ok → vpn.service.create → engine.start.fail
 *
 * 就能确认崩在引擎启动，而不是前台服务或授权。
 *
 * 设计约束：
 * - 只追加、只保留最近 [KEEP_LINES] 行，写文件是同步的（崩之前必须落盘）；
 * - 任何异常都吞掉并转 Log —— 诊断代码本身绝不能成为新的崩溃源。
 */
object EventLog {

    private const val TAG = "DshEvent"
    private const val FILE_NAME = "events.log"
    private const val KEEP_LINES = 200
    private const val REWRITE_AT = 400

    @Volatile
    private var lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /** 记一条事件。tag 用点分层级，例如 `tunnel`、`vpn`、`engine`、`net`。 */
    @JvmStatic
    fun event(ctx: Context, tag: String, msg: String) {
        val line = "${ts()} | $tag | $msg"
        runCatching { Log.i(TAG, line) }
        runCatching {
            synchronized(lock) {
                val f = file(ctx)
                f.appendText(line + "\n")
                if (f.length() > 64 * 1024) trim(f)
            }
        }
    }

    /** 只刷日志、不需要 Context 的便捷入口（Context 由调用方自带时用上面的）。 */
    fun raw(ctx: Context, line: String) = event(ctx, "raw", line)

    private fun trim(f: File) {
        val lines = f.readLines().takeLast(KEEP_LINES)
        f.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** 最近若干行，给「分享日志」用。 */
    fun tail(ctx: Context, n: Int = KEEP_LINES): String = runCatching {
        val f = file(ctx)
        if (!f.exists()) "" else f.readLines().takeLast(n).joinToString("\n")
    }.getOrDefault("")

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    private fun ts(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
}
