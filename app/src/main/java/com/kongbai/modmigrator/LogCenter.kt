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

    /**
     * 内存里的日志（最新的在前，最多保留 N 条用于顶部展示）。
     *
     * 这三个集合会被**多个线程同时访问**：日志可能从任意后台线程写入，
     * 而监听器由 UI 线程注册/反注册。
     * 之前用的是普通 ArrayList，并发下会 ConcurrentModificationException，
     * 或者读到半改状态——这正是"崩溃日志莫名其妙丢内容"的原因。
     * 现在统一换成线程安全的 CopyOnWriteArrayList
     * （读远多于写，COW 在这个场景下开销可以接受）。
     */
    private val lines = java.util.concurrent.CopyOnWriteArrayList<LogLine>()

    /** 监听器：页面用来刷新顶部条数与预览 */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<LogLine>) -> Unit>()

    /** 严重错误监听：页面用来弹窗 */
    private val errorHooks = java.util.concurrent.CopyOnWriteArrayList<(LogLine) -> Unit>()

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
            } catch (t: Throwable) { Err.ignore(t, "l(ArrayList(lines))") }
        }
        // 失败弹窗
        if (level == "E") {
            for (h in errorHooks) {
                try {
                    h(line)
                } catch (t: Throwable) { Err.ignore(t, "h(line)") }
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
            } catch (t: Throwable) { Err.ignore(t, "l(ArrayList(lines))") }
        }
    }

    // ---------- 落盘 ----------

    /**
     * 磁盘日志大小上限（字节）。
     *
     * ⚠️ 之前用 "wa"（追加）模式写，**只增不减、从不清空也不截断**。
     * 用久了 app.log 会涨到几十 MB，而「设置 → 日志」是把它
     * **整个读进内存**再显示的 —— 读一次就卡死，甚至 OOM。
     * 现在超过上限就保留后半段（新的），丢掉最早的那部分。
     */
    private const val MAX_DISK = 512 * 1024

    /**
     * 磁盘写入锁。
     *
     * persist 是 **追加写（"wa"）**，trimDisk 是 **覆盖写（"wt"）**。
     * 两者操作同一个文件却没有任何同步：
     * 日志本身就可能从任意后台线程打出来（网络回调、下载线程、协程），
     * 所以完全可能出现"一个线程正在追加、另一个线程正在整体覆盖重写"。
     *
     * 后果不是丢一行日志那么简单 ——
     * 覆盖写会先把文件截断成 0，此时追加写正好落进去，
     * 最终留下的是"半条旧日志 + 半条新日志"的混合体，
     * 文件直接变成乱码。而日志是我们排查问题的唯一线索，它坏了就什么都查不了。
     *
     * 所以整个"追加 + 必要时裁剪"必须作为一个不可分割的整体来加锁。
     */
    private val logLock = Any()

    private fun persist(line: LogLine) = synchronized(logLock) {
        try {
            val ctx = Prefs.appCtx() ?: return
            val dir = WorkDir.logs(ctx) ?: return
            val f = dir.findFile("app.log") ?: dir.createFile("text/plain", "app.log") ?: return
            val text = line.toString() + "\n"
            ctx.contentResolver.openOutputStream(f.uri, "wa")?.use {
                it.write(text.toByteArray())
            }
            trimDisk(ctx, f)
        } catch (t: Throwable) {
            // 落盘失败不能影响主流程
            Err.ignore(t, "写入磁盘日志失败")
        }
    }

    /**
     * 磁盘日志超过上限时，只保留**后半段**（较新的内容）。
     * 前半段按行切，避免把一个汉字/一条日志截成两半。
     */
    private fun trimDisk(ctx: Context, f: androidx.documentfile.provider.DocumentFile) {
        try {
            val len = f.length()
            if (len <= MAX_DISK) return
            val all = ctx.contentResolver.openInputStream(f.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return
            // 留 3/4 的空间，不至于每次写一行就重写一次
            val keepBytes = MAX_DISK * 3 / 4
            var cut = all.length - keepBytes
            if (cut < 0) return
            // 往后找到第一个换行，从那里开始保留，保证不截半行
            val nl = all.indexOf('\n', cut)
            if (nl >= 0) cut = nl + 1
            val kept = all.substring(cut)
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use {
                it.write(kept.toByteArray())
            }
        } catch (t: Throwable) {
            Err.ignore(t, "截断磁盘日志")
        }
    }

    /**
     * 读出磁盘上的完整日志，供「设置 → 日志」查看。
     *
     * 同样要加锁：trimDisk 的覆盖写会先把文件截断成 0 再重写，
     * 如果读取正好发生在这个窗口里，读到的会是空内容或半截内容 ——
     * 表现为"日志页偶尔一片空白"，过一会儿再打开又有了，极像随机 bug。
     */
    fun readAll(ctx: Context): String = synchronized(logLock) {
        try {
            val dir = WorkDir.logs(ctx) ?: return@synchronized ""
            val f = dir.findFile("app.log") ?: return@synchronized ""
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
        } catch (t: Throwable) { Err.ignore(t, "WorkDir.logs(ctx)?.findFile(\"app.log\")?.delete()") }
        clear()
    }
}
