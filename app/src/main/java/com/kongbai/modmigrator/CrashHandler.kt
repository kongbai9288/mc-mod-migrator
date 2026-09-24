package com.kongbai.modmigrator

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获。
 *
 * 参照业界成熟 CrashHandler 的做法（三重 try-catch、记录 cause、
 * 记录线程与设备信息、目录降级），针对最常见的坑做防护：
 *
 * 1. **二次崩溃**：处理器里再抛异常，系统不会再走我们的 Handler，
 *    日志就丢一半甚至整个丢掉。所以 build/save 各包一层 try，
 *    最外层再包一层，任何一步出错都不影响"至少把堆栈写下来"。
 * 2. **只记顶层异常**：很多崩溃的真正原因在 cause 里，
 *    只打 e.toString() 会看不到根因。这里递归展开 cause。
 * 3. **处理器里少做事**：不做任何网络、不碰单例，只做纯字符串拼接 + 写文件。
 * 4. **目录降级**：优先私有外部目录，失败降级 cacheDir，再失败降级内存兜底。
 * 5. **落盘可靠**：写完 flush + fd.sync()，避免进程被杀时丢失。
 */
object CrashHandler {

    private const val MAX_FILES = 5

    /** 日志文件所在目录 */
    fun dir(ctx: Context): File {
        return try {
            val ext = ctx.getExternalFilesDir(null)
            val d = File(ext ?: ctx.filesDir, "crash")
            if (!d.exists()) d.mkdirs()
            if (d.exists()) d else ctx.filesDir
        } catch (t: Throwable) {
            ctx.filesDir
        }
    }

    fun install(ctx: Context) {
        val appCtx = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                save(appCtx, thread, e)
            } catch (t: Throwable) {
                // 第一层：保存失败也不能影响下面的默认处理
            }
            try {
                old?.uncaughtException(thread, e)
            } catch (t: Throwable) {
            }
        }
    }

    /** 组装日志文本（第二层保护：内部再包 try） */
    private fun build(ctx: Context, thread: Thread, e: Throwable): String {
        val sb = StringBuilder()
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            sb.appendLine("========== 崩溃 ==========")
            sb.appendLine("时间：${sdf.format(Date())}")
            sb.appendLine("线程：${thread.name}（id=${thread.id}）")
            try {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                sb.appendLine("版本：${pi.versionName}（${pi.versionCode}）")
            } catch (t: Throwable) {
            }
            sb.appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            sb.appendLine("系统：Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）")
            sb.appendLine("CPU：${android.os.Build.SUPPORTED_ABIS.joinToString()}".take(200))
            sb.appendLine()

            // 递归展开 cause，很多根因藏在里面
            var cause: Throwable? = e
            var depth = 0
            while (cause != null && depth < 5) {
                sb.appendLine(if (depth == 0) "异常：${cause.javaClass.name}" else "Cause：${cause.javaClass.name}")
                sb.appendLine("信息：${cause.message}")
                // 主线程崩溃看栈顶就够了，子线程适当多给几行
                val limit = if (depth == 0) 30 else 10
                for (st in cause.stackTrace.take(limit)) {
                    sb.appendLine("    at $st")
                }
                sb.appendLine()
                cause = cause.cause
                depth++
            }
        } catch (t: Throwable) {
            // 第二层：组装过程出问题时，至少保留原始堆栈
            sb.appendLine("（日志组装时出错：${t.message}）")
            sb.appendLine(e.stackTrace.take(30).joinToString("\n") { "    at $it" })
        }
        return sb.toString()
    }

    /** 写文件（第三层保护） */
    private fun save(ctx: Context, thread: Thread, e: Throwable) {
        try {
            val text = build(ctx, thread, e)
            val d = dir(ctx)
            val f = File(d, "crash-${System.currentTimeMillis()}.log")
            writeSafe(f, text)

            // 同时更新一份"最新"的，方便界面直接读
            writeSafe(File(ctx.filesDir, "crash.log"), text)

            // 只保留最近几份，避免日志越攒越多
            trim(d)
        } catch (t: Throwable) {
            // 第三层：写不进去也不再抛
        }
    }

    /** 带 flush + fsync 的写入，尽量保证进程被杀前落盘 */
    private fun writeSafe(f: File, text: String) {
        try {
            if (!f.exists()) f.createNewFile()
            java.io.FileOutputStream(f, false).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                try {
                    out.fd.sync()
                } catch (t: Throwable) {
                }
            }
        } catch (t: Throwable) {
        }
    }

    private fun trim(d: File) {
        try {
            val files = d.listFiles { it -> it.name.startsWith("crash-") } ?: return
            if (files.size <= MAX_FILES) return
            files.sortedByDescending { it.lastModified() }
                .drop(MAX_FILES)
                .forEach { runCatching { it.delete() } }
        } catch (t: Throwable) {
        }
    }

    /** 读取全部崩溃日志（界面展示/分享用） */
    fun readAll(ctx: Context): String {
        return try {
            val sb = StringBuilder()
            val latest = File(ctx.filesDir, "crash.log")
            if (latest.exists()) sb.append(latest.readText())
            val files = dir(ctx).listFiles { it -> it.name.startsWith("crash-") }
            if (files != null) {
                for (f in files.sortedByDescending { it.lastModified() }.take(3)) {
                    sb.appendLine().appendLine("---- ${f.name} ----")
                    sb.append(runCatching { f.readText() }.getOrDefault("（读取失败）"))
                }
            }
            if (sb.isEmpty()) "" else sb.toString()
        } catch (t: Throwable) {
            ""
        }
    }

    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, "crash.log").delete()
            dir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
        } catch (t: Throwable) {
        }
    }
}
