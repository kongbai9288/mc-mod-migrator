package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 崩溃日志导出。
 *
 * 安卓剪贴板对超长文本支持很差（很多 ROM 直接截断），
 * 而且崩溃堆栈动辄几千字符。所以这里走**系统分享**：
 *   - 把日志写成 .log 文件
 *   - 用 FileProvider 生成一个 content:// URI
 *   - 交给系统分享面板（可以发到微信、邮件、存到网盘……）
 * 同时也提供「保存到工作目录」的入口。
 */
object CrashShare {

    /** 把日志内容写到 cache 里的文件，返回 File */
    private fun toFile(ctx: Context, content: String, name: String): File? {
        return try {
            val dir = File(ctx.cacheDir, "logs").apply { if (!exists()) mkdirs() }
            val f = File(dir, name)
            val header = buildString {
                appendLine("ModMigrator 崩溃日志")
                appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
                try {
                    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    appendLine("版本：${pi.versionName} (${pi.versionCode})")
                } catch (t: Throwable) { Err.ignore(t, "appendLine(\"版本：{pi.versionName} ({pi.versionCode") }
                appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}")
                appendLine("=".repeat(40))
                appendLine()
            }
            f.writeText(header + content)
            f
        } catch (t: Throwable) {
            null
        }
    }

    /** 通过系统分享发出去 */
    fun share(ctx: Context, content: String, subject: String = "崩溃日志") {
        if (content.isBlank()) {
            Toast.makeText(ctx, "没有可分享的日志", Toast.LENGTH_SHORT).show()
            return
        }
        val f = toFile(ctx, content, "modmigrator-crash.log")
        if (f == null) {
            Toast.makeText(ctx, "写日志文件失败", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", f
            )
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(i, "分享日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (t: Throwable) {
            // FileProvider 没配好的话退回纯文本分享
            try {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content.take(100_000))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(Intent.createChooser(i, "分享日志").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Throwable) {
                Toast.makeText(ctx, "无法分享：${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 保存到工作目录（持久化，不像 cache 会被清） */
    fun save(ctx: Context, content: String): File? {
        return try {
            val dir = WorkDir.logs(ctx)
            val name = "crash-${System.currentTimeMillis()}.log"
            if (dir != null) {
                val df = dir.createFile("text/plain", name)
                if (df != null) {
                    ctx.contentResolver.openOutputStream(df.uri, "wt")?.use {
                        it.write(content.toByteArray())
                    }
                    Toast.makeText(ctx, "已保存到：logs/$name", Toast.LENGTH_LONG).show()
                    return File(DefaultDir.logs(ctx), name)
                }
            }
            // 兜底：写私有目录
            val f = File(DefaultDir.logs(ctx), name)
            f.writeText(content)
            Toast.makeText(ctx, "已保存到：${f.absolutePath}", Toast.LENGTH_LONG).show()
            f
        } catch (t: Throwable) {
            Toast.makeText(ctx, "保存失败：${t.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    /** 读取崩溃日志内容（统一走 CrashHandler，含 cause 与设备信息） */
    fun read(ctx: Context): String = CrashHandler.readAll(ctx)

    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, "crash.log").delete()
        } catch (t: Throwable) { Err.ignore(t, "File(ctx.filesDir, \"crash.log\").delete()") }
    }
}
