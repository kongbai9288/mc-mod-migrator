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

    // ── 内存写缓冲 ────────────────────────────────────────────
    // ⚠️ 之前是"每打一行日志就立刻写一次磁盘"，而且**每写一行都调一次 trimDisk**，
    // trimDisk 内部又要 f.length()（SAF 下是跨进程 IPC）+ 超限时 readBytes()
    // 把整个 512KB 读进内存、截取、再全量重写。
    // 下载进度、网络回调这类高频日志下，这就是
    // 「每写一行 = 1 次 IPC + 可能的全量读写 512KB」，
    // CPU 和 IO 带宽被瞬间吃光，主线程直接 ANR。
    //
    // 改成：先攒在内存里，攒够量或到时间才真正落盘一次。
    private val diskBuffer = StringBuilder(4096)
    private var lastFlushMs = 0L

    /** 缓冲区攒到这么多字符就落盘 */
    private const val FLUSH_CHARS = 8192
    /** 距上次落盘超过这么久也落盘（保证日志不会长时间停在内存里） */
    private const val FLUSH_INTERVAL_MS = 2000L

    // ⚠️ 用块体而不是 `= synchronized(logLock) { ... }` 表达式体。
    // 表达式体要求最后一句是表达式，而下面的 if 没有 else，
    // Kotlin 会报 "'if' must have both main and 'else' branches if used as an expression"。
    private fun persist(line: LogLine) {
        synchronized(logLock) {
            try {
                diskBuffer.append(line.toString()).append("\n")
                val now = System.currentTimeMillis()
                if (diskBuffer.length >= FLUSH_CHARS ||
                    now - lastFlushMs > FLUSH_INTERVAL_MS
                ) {
                    flushLocked()
                    lastFlushMs = now
                } else {
                    // 还没到阈值，继续攒在内存里。
                    // 必须有这个 else：`synchronized { }` 的 lambda 有返回值，
                    // 而这是它最后一句，Kotlin 会把 if 当表达式，
                    // 缺 else 直接编译不过。
                    Unit
                }
            } catch (t: Throwable) {
                // ⚠️ 这里绝不能调 Err.ignore()。
                // Err.ignore 会写日志，而日志系统正是此刻失败的那个 ——
                // persist 失败 → Err.ignore → 再写日志 → persist 再失败 → ……
                // 磁盘满时这条链会无限递归，几毫秒内撑爆栈。
                // 直接用原生 Log，它写内核缓冲区，不碰文件系统，不可能再失败。
                try {
                    android.util.Log.e("LogCenter", "日志落盘失败", t)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 把缓冲区真正写进磁盘。**调用前必须已持有 logLock。**
     *
     * 超限处理刻意做得"粗暴"：直接丢弃旧内容，只保留这一批新的。
     *
     * 之前的做法是"读全文件 → 内存截取 → 全量重写"，
     * 为了不截半个汉字还要做字符串扫描 ——
     * 每次都要把 512KB 读进来再写回去，是纯粹的 IO 灾难。
     * 而日志超过上限本来就是要丢掉最早的那些，
     * 逐行精确保留并没有实际价值（没人会去翻 512KB 之外的旧日志，
     * 界面上显示时也是整个读进内存，太大反而卡死）。
     * 用"丢旧的、留新的"换掉全量读写，是这个场景下正确的取舍。
     */
    private fun flushLocked() {
        if (diskBuffer.length == 0) return
        val ctx = Prefs.appCtx() ?: return
        try {
            val dir = WorkDir.logs(ctx) ?: return
            val f = dir.findFile("app.log") ?: dir.createFile("text/plain", "app.log") ?: return
            val text = diskBuffer.toString()
            // 只有这一次 length 查询，且只在落盘时发生，不再是每行一次
            val over = try { f.length() > MAX_DISK } catch (_: Throwable) { false }
            val mode = if (over) "wt" else "wa"
            ctx.contentResolver.openOutputStream(f.uri, mode)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
            diskBuffer.setLength(0)
        } catch (t: Throwable) {
            // 同上：不能用 Err.ignore，避免与日志写入互相触发造成递归
            try {
                android.util.Log.e("LogCenter", "日志刷盘失败", t)
            } catch (_: Throwable) {}
        }
    }

    /** 主动把缓冲区刷到磁盘（退出前、查看日志前调用） */
    fun flush() = synchronized(logLock) { flushLocked() }

    /**
     * 读出磁盘上的完整日志，供「设置 → 日志」查看。
     *
     * 两件事必须一起做：
     * 1. **先刷盘**。日志现在先攒在内存缓冲里，不刷就读不到最新的那部分，
     *    用户会看到"刚打的错误日志怎么没有"，以为是丢了。
     * 2. **加锁**。覆盖写会先把文件截断成 0 再重写，
     *    读取正好落在这个窗口里就会读到空内容或半截内容 ——
     *    表现为"日志页偶尔一片空白"，过一会儿再打开又有了，极像随机 bug。
     */
    fun readAll(ctx: Context): String = synchronized(logLock) {
        try {
            flushLocked()
            val dir = WorkDir.logs(ctx) ?: return@synchronized ""
            val f = dir.findFile("app.log") ?: return@synchronized ""
            ctx.contentResolver.openInputStream(f.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 清空日志。
     *
     * ⚠️ 必须和写入互斥，且**先清内存缓冲再删文件**。
     * 顺序反了的话：删完文件后，缓冲区里还没落盘的内容会被下一次 flush
     * 重新写进去 —— 用户点了"清空"，一刷新日志又冒出来了，像功能坏了。
     */
    fun clearFile(ctx: Context) {
        // ⚠️ clear() 是 @Synchronized（锁 LogCenter 实例），
        // 必须放在 logLock **外面**调用。
        // push() 的顺序是「锁 this → 锁 logLock」，
        // 如果这里写成「锁 logLock → 锁 this」，两个线程各持一把等对方，
        // 就是标准的 AB-BA 死锁 —— 界面直接卡死。
        try {
            synchronized(logLock) {
                diskBuffer.setLength(0)
                WorkDir.logs(ctx)?.findFile("app.log")?.delete()
            }
        } catch (t: Throwable) {
            try {
                android.util.Log.e("LogCenter", "删除日志文件失败", t)
            } catch (_: Throwable) {}
        }
        clear()
    }
}
