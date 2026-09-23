package com.kongbai.modmigrator

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志中心。
 *
 * 1. 所有页面只保留「条数 + 最新几条」在顶部，完整日志写到工作目录的 logs/ 里。
 * 2. 失败（error）除了记日志，还会弹出对话框，不让用户错过。
 * 3. 日志落盘到用户已授权的工作目录，卸载/清数据都不会丢。
 */
object LogCenter {

    /** 内存里最多保留多少条（用于顶部展示） */
    private const val MAX_MEM = 300

    /** 内存里的日志（最新的在前，最多保留 N 条用于顶部展示） */
    private val lines = ArrayList<LogLine>(MAX_MEM)

    /** 监听器：页面用来刷新顶部条数与预览 */
    private val listeners = ArrayList<(List<LogLine>) -> Unit>()

    /** 严重错误监听：页面用来弹窗 */
    private val errorHooks = ArrayList<(LogLine) -> Unit>()

    data class LogLine(
        val time: String,
        val level: String,
        val tag: String,
        val msg: String
    ) {
        val isError: Boolean get() = level == "E"
        override fun toString(): String =
            "[$time] ${if (level == "E") "错误" else if (level == "W") "警告" else "信息"} · $tag：$msg"
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())

    fun addListener(l: (List<LogLine>) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (List<LogLine>) -> Unit) {
        listeners.remove(l)
    }

    /** 注册失败弹窗钩子（通常由当前可见页面注册，detach 时反注册） */
    fun addErrorHook(h: (LogLine) -> Unit) {
        errorHooks.add(h)
    }

    fun removeErrorHook(h: (LogLine) -> Unit) {
        errorHooks.remove(h)
    }

    @Synchronized
    private fun push(level: String, tag: String, msg: String) {
        val line = LogLine(now(), level, tag, msg)
        lines.add(0, line)
        while (lines.size > MAX_MEM) lines.removeAt(lines.size - 1)
        // 落盘
        persist(line)
        // 通知刷新
        for (l in listeners) {
            try {
                l(ArrayList(lines))
            } catch (t: Throwable) {
            }
        }
        // 失败弹窗
        if (level == "E") {
            for (h in errorHooks) {
                try {
                    h(line)
                } catch (t: Throwable) {
                }
            }
        }
    }

    fun i(tag: String, msg: String) = push("I", tag, msg)
    fun w(tag: String, msg: String) = push("W", tag, msg)
    fun e(tag: String, msg: String) = push("E", tag, msg)

    /** 顶部展示用：只取最新若干条 */
    fun recent(n: Int = 5): List<LogLine> = lines.take(n)

    fun count(): Int = lines.size

    fun all(): List<LogLine> = ArrayList(lines)

    @Synchronized
    fun clear() {
        lines.clear()
        for (l in listeners) {
            try {
                l(ArrayList(lines))
            } catch (t: Throwable) {
            }
        }
    }

    // ---------- 落盘 ----------

    private fun persist(line: LogLine) {
        try {
            val ctx = Prefs.appCtx() ?: return
            val dir = WorkDir.logs(ctx) ?: return
            val f = dir.findFile("app.log") ?: dir.createFile("text/plain", "app.log") ?: return
            ctx.contentResolver.openOutputStream(f.uri, "wa")?.use {
                it.write((line.toString() + "\n").toByteArray())
            }
        } catch (t: Throwable) {
            // 落盘失败不能影响主流程
        }
    }

    /** 读出磁盘上的完整日志，供「设置 → 日志」查看 */
    fun readAll(ctx: Context): String {
        return try {
            val dir = WorkDir.logs(ctx) ?: return ""
            val f = dir.findFile("app.log") ?: return ""
            ctx.contentResolver.openInputStream(f.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    fun clearFile(ctx: Context) {
        try {
            WorkDir.logs(ctx)?.findFile("app.log")?.delete()
        } catch (t: Throwable) {
        }
        clear()
    }
}
