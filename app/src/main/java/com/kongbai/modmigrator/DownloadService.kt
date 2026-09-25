package com.kongbai.modmigrator

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台下载服务。
 *
 * 要点：
 *   - 前台服务 + 常驻通知，切到后台不会被杀
 *   - **断点续传**：临时文件已下载的部分保留，下次从该位置继续（HTTP Range）
 *   - **可取消**：每个任务一个 cancel 标志，取消时立刻停，临时文件保留
 *     （这样再点下载就是续传，不用从头来）
 *   - 串行执行，避免同时下多个把带宽和内存打满
 */
class DownloadService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_ID = "id"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_DIR = "dir"

        /** 任务状态表，UI 可以查询 */
        val states = ConcurrentHashMap<String, Task>()

        data class Task(
            val id: String,
            val url: String,
            val name: String,
            val dir: String,
            var state: String = "排队",       // 排队/下载中/暂停/完成/失败/已取消
            var done: Long = 0,
            var total: Long = 0,
            val cancel: AtomicBoolean = AtomicBoolean(false)
        )

        fun start(ctx: Context, url: String, name: String, dir: String): String {
            val id = url.hashCode().toString() + "_" + name
            if (states[id]?.state.let { it == "下载中" || it == "排队" }) return id
            states[id] = Task(id, url, name, dir)
            val i = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_DIR, dir)
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (t: Throwable) {
                states[id]?.state = "失败"
            }
            return id
        }

        fun cancel(id: String) {
            states[id]?.cancel?.set(true)
            states[id]?.state = "已取消"
        }

        fun cancelAll() {
            for (t in states.values) {
                t.cancel.set(true)
                t.state = "已取消"
            }
        }

        fun get(id: String): Task? = states[id]

        /** 已完成的临时文件，供后续移动到目标目录 */
        fun tempFile(ctx: Context, task: Task): File =
            File(File(ctx.cacheDir, "bgdl").apply { if (!exists()) mkdirs() }, task.name)

        /** 断点信息文件：记录已确认写入的字节数 */
        private fun resumeFile(ctx: Context, task: Task): File =
            File(File(ctx.cacheDir, "bgdl").apply { if (!exists()) mkdirs() }, task.name + ".pos")

        fun savedBytes(ctx: Context, task: Task): Long {
            val f = tempFile(ctx, task)
            val p = resumeFile(ctx, task)
            val pos = try {
                if (p.exists()) p.readText().trim().toLongOrNull() ?: 0L else 0L
            } catch (t: Throwable) {
                0L
            }
            return minOf(f.length(), pos).coerceAtLeast(0L)
        }

        private fun savePos(ctx: Context, task: Task, pos: Long) {
            try {
                resumeFile(ctx, task).writeText(pos.toString())
            } catch (t: Throwable) { Err.ignore(t, "resumeFile(ctx, task).writeText(pos.toString())") }
        }

        fun clearPos(ctx: Context, task: Task) {
            try {
                resumeFile(ctx, task).delete()
                tempFile(ctx, task).delete()
            } catch (t: Throwable) { Err.ignore(t, "tempFile(ctx, task).delete()") }
        }
    }

    private val exec = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_ID)?.let { cancel(it) }
            }
            else -> {
                val id = intent?.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: return START_NOT_STICKY
                val dir = intent.getStringExtra(EXTRA_DIR) ?: ""
                val task = states[id] ?: Task(id, url, name, dir).also { states[id] = it }
                exec.submit { runTask(task) }
            }
        }
        return START_NOT_STICKY
    }

    private fun runTask(task: Task) {
        task.state = "下载中"
        notifyProgress(task)
        try {
            val out = tempFile(this, task)
            var start = savedBytes(this, task)
            val total = try {
                Downloader.probeLength(task.url)
            } catch (t: Throwable) {
                -1L
            }
            task.total = total
            task.done = start

            java.io.RandomAccessFile(out, "rw").use { raf ->
                raf.seek(start)
                val headers = if (start > 0) mapOf("Range" to "bytes=$start-") else emptyMap()
                val resp = Http.call(task.url, headers)

                // ── 续传必须校验服务端真的返回了 206 ──────────────
                // 这是个会**静默损坏文件**的 bug：
                // 发了 Range 头，但服务端不支持续传时会**忽略它并返回 200 + 完整内容**。
                // 200 也是 isSuccessful，于是代码会走到 copyLoop(resp, raf, start)，
                // 在 start 偏移处追加一份**完整文件** ——
                // 得到的 jar = 上次残留的半截 + 一整个新文件，直接变成坏包，
                // 装进 mods 目录就是"下载完成了但模组加载不了"。
                // 只有 206 Partial Content 才表示服务端真的从指定位置开始给。
                val canResume = start > 0 && resp.code == 206

                if (!canResume && start > 0) {
                    // 服务端不认 Range（返回 200），或者续传请求失败 → 从头下
                    resp.close()
                    start = 0
                    task.done = 0
                    raf.setLength(0)
                    raf.seek(0)
                    val r2 = Http.call(task.url)
                    if (!r2.isSuccessful) {
                        r2.close()
                        task.state = "失败"
                        notifyProgress(task)
                        return
                    }
                    copyLoop(r2, raf, task, 0L)
                } else if (!resp.isSuccessful) {
                    resp.close()
                    task.state = "失败"
                    notifyProgress(task)
                    return
                } else {
                    copyLoop(resp, raf, task, start)
                }
            }

            if (task.cancel.get()) {
                task.state = "已取消"
                savePos(this, task, task.done)
                notifyProgress(task)
                return
            }

            savePos(this, task, task.done)

            // ── 校验实际大小 ──────────────────────────────────────
            // copyLoop 是靠 read() 返回 -1 判断结束的。连接中途被掐断时
            // 也可能正常返回 -1，于是循环平顺结束、没有任何异常，
            // 但文件其实是**半截**的。之前不做校验就标"完成"，
            // 一个截断的 jar 会被装进 mods 目录 —— 表现正是
            // "下载显示完成了，模组却加载不了"。
            if (task.total > 0 && task.done < task.total) {
                task.state = "失败（文件不完整：${task.done}/${task.total}）"
                notifyProgress(task)
                return
            }

            // 落到目标目录
            val targetDir = if (task.dir.isBlank()) WorkDir.modsDir(this)
            else WorkDir.sub(this, task.dir)
            if (targetDir == null) {
                // 之前这里静默跳过、仍标"完成"：
                // 目标目录不可用时，临时文件被清掉、什么都没落地，
                // 用户却看到"下载完成"。这不是完成，必须如实报失败。
                task.state = "失败（目标目录不可用）"
                notifyProgress(task)
                return
            }
            targetDir.findFile(task.name)?.delete()
            val df = targetDir.createFile(Fs.mimeOf(task.name), task.name)
            if (df == null) {
                task.state = "失败（无法在目标目录创建文件）"
                notifyProgress(task)
                return
            }
            contentResolver.openOutputStream(df.uri, "wt")?.use { o ->
                out.inputStream().use { it.copyTo(o, 1 shl 16) }
            }
            clearPos(this, task)
            task.state = "完成"
        } catch (t: Throwable) {
            task.state = "失败"
        }
        notifyProgress(task)
        if (states.values.all { it.state !in listOf("下载中", "排队") }) {
            try {
                stopForeground(true)
                stopSelf()
            } catch (e: Throwable) { Err.ignore(e, "stopSelf()") }
        }
    }

    private fun copyLoop(resp: okhttp3.Response, raf: java.io.RandomAccessFile, task: Task, start: Long) {
        resp.body?.byteStream()?.use { input ->
            val buf = ByteArray(1 shl 16)
            var sum = start
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                if (task.cancel.get()) return
                raf.write(buf, 0, n)
                sum += n
                task.done = sum
                // 每 4MB 落一次断点，避免频繁写
                if (sum % (4 * 1024 * 1024) < 65536) savePos(this, task, sum)
                notifyProgress(task)
            }
        }
        resp.close()
    }

    private var lastNotify = 0L

    private fun notifyProgress(task: Task) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 800 && task.state == "下载中") return
        lastNotify = now
        try {
            val mb = { b: Long -> String.format("%.1f MB", b / 1048576.0) }
            val text = when (task.state) {
                "下载中" -> "${mb(task.done)} / ${if (task.total > 0) mb(task.total) else "?"}"
                else -> task.state
            }
            val nb = NotificationCompat.Builder(this, Notifier.CHANNEL)
                .setContentTitle("下载：${task.name}")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_bolt)
                .setOngoing(task.state == "下载中")
                .setProgress(100, if (task.total > 0) (task.done * 100 / task.total).toInt() else 0,
                    task.total <= 0)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            startForeground(9001, nb.build())
        } catch (t: Throwable) { Err.ignore(t, "startForeground(9001, nb.build())") }
    }

    override fun onDestroy() {
        try {
            exec.shutdownNow()
        } catch (t: Throwable) { Err.ignore(t, "exec.shutdownNow()") }
        super.onDestroy()
    }
}
