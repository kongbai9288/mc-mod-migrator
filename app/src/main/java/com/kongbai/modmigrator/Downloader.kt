package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载器：单文件多线程分块下载 + 断点续传尝试。
 *
 * 思路参考开源下载框架（lingochamp/FileDownloader、jackfengji/android-download-manager，
 * 均为 Apache-2.0 / MIT），但这里是按同样思路用 OkHttp 自行实现，
 * 不引入外部依赖，也不受其许可约束。
 *
 * 流程：
 *   1. 先发一次请求拿 Content-Length，并看服务端是否声明支持 Range
 *   2. 支持 Range 且文件够大 → 切成 N 块，多线程并行下载到临时文件
 *   3. 不支持或文件小 → 直接单线程下载（省掉分块开销）
 *   4. 所有块下完，按顺序合并写入目标目录
 *   5. 任意一步失败 → 回退到单线程重试，保证"能下下来"优先于"下得快"
 *
 * 为什么要先写临时文件再合并：
 *   目标是 SAF 的 DocumentFile，只能顺序写、不能随机 seek，
 *   所以各块落在 cache 里，最后按顺序拼到目标输出流。
 */
object Downloader {

    /** 小于这个体积就不分块了，分块本身有请求开销 */
    private const val MIN_CHUNK_TOTAL = 512L * 1024

    /** 单块最小体积 */
    private const val MIN_CHUNK_SIZE = 256L * 1024

    /** 最大块数，避免把连接打满 */
    private const val MAX_CHUNKS = 8

    private val pool by lazy { Executors.newFixedThreadPool(MAX_CHUNKS) }

    fun guessName(url: String, fallback: String = "mod.jar"): String {
        var name = url.substringBefore("?").substringAfterLast("/")
        name = try {
            URLDecoder.decode(name, "UTF-8")
        } catch (t: Throwable) {
            name
        }
        name = name.replace(Regex("[^A-Za-z0-9._+\\-]"), "_")
        if (name.isBlank() || !name.contains(".")) name = fallback
        return name
    }

    /**
     * 下载到指定目录。
     * @param onProgress 可选进度回调（已下载字节, 总字节）
     */
    fun download(
        ctx: Context,
        url: String,
        dir: DocumentFile,
        name: String,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null
    ): DocumentFile? {
        // 先在 cache 里并行分块下载，再一次性写入目标
        val tmp = try {
            downloadToCache(ctx, url, name, headers, onProgress)
        } catch (t: Throwable) {
            null
        }
        if (tmp == null || !tmp.exists() || tmp.length() <= 0L) {
            return null
        }
        return try {
            val existing = dir.findFile(name)
            if (existing != null) existing.delete()
            val out = dir.createFile(Fs.mimeOf(name), name) ?: return null
            ctx.contentResolver.openOutputStream(out.uri, "wt")?.use { o ->
                tmp.inputStream().use { it.copyTo(o, 1 shl 16) }
            }
            tmp.delete()
            out
        } catch (t: Throwable) {
            null
        }
    }

    /** 下载到 cache，内部自动判断是否分块 */
    private fun downloadToCache(
        ctx: Context, url: String, name: String,
        headers: Map<String, String>, onProgress: ((Long, Long) -> Unit)?
    ): File? {
        val cache = File(ctx.cacheDir, "dl").apply { if (!exists()) mkdirs() }
        val safe = guessName(name).ifBlank { "mod.jar" }
        val out = File(cache, safe)

        // 原子提交：先写 .part，完整写完后才 rename 成正式文件。
        // 否则下载中断会留下半截文件，下次复用缓存时会被当成完整文件，
        // 装进 mods 目录就是个坏 jar——这是"下载中断后模组装不上"的常见根因。
        val part = File(cache, "$safe.part")
        runCatching { if (part.exists()) part.delete() }
        runCatching { if (out.exists()) out.delete() }

        val parallel = Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3).coerceIn(1, MAX_CHUNKS)
        val info = probe(url, headers)

        // 不支持断点、拿不到长度、文件太小、或用户只开 1 并发 → 单线程
        val got: File? = if (
            info == null || !info.acceptRange ||
            info.length < MIN_CHUNK_TOTAL || parallel <= 1
        ) {
            single(url, headers, part, onProgress, info?.length ?: -1)
        } else {
            val chunks = chunkCount(info.length, parallel)
            val parts = chunked(url, headers, info.length, chunks, onProgress)
            if (parts == null) {
                // 分块失败就退回单线程，不能因为追求速度导致下不下来
                runCatching { part.delete() }
                single(url, headers, part, onProgress, info.length)
            } else {
                mergeParts(parts, part)
            }
        }

        // 校验实际大小：拿得到总长度就必须对得上，对不上视为失败
        if (got == null || !got.exists()) {
            runCatching { part.delete() }
            return null
        }
        if (info != null && info.length > 0 && got.length() != info.length) {
            runCatching { got.delete() }
            return null
        }
        return try {
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) {
                // 极少数文件系统 rename 失败，退化为复制
                part.inputStream().use { i -> out.outputStream().use { i.copyTo(it) } }
                part.delete()
            }
            out
        } catch (t: Throwable) {
            runCatching { part.delete() }
            null
        }
    }

    private data class Probe(val length: Long, val acceptRange: Boolean)

    /** 探测文件长度与是否支持 Range */
    private fun probe(url: String, headers: Map<String, String>): Probe? {
        return try {
            val r = Http.call(url, headers + mapOf("Range" to "bytes=0-0"))
            val len = r.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it > 0 }
            val ar = r.header("Accept-Ranges")?.equals("bytes", true) == true ||
                r.header("Content-Range")?.startsWith("bytes", true) == true
            r.close()
            if (len == null || len <= 0) null else Probe(len, ar)
        } catch (t: Throwable) {
            null
        }
    }

    /** 计算分几块 */
    private fun chunkCount(total: Long, parallel: Int): Int {
        val bySize = (total / MIN_CHUNK_SIZE).toInt().coerceAtLeast(1)
        return minOf(parallel, bySize, MAX_CHUNKS).coerceAtLeast(1)
    }

    /** 分块并行下载，返回各块的临时文件（按序），失败返回 null */
    private fun chunked(
        url: String, headers: Map<String, String>, total: Long, chunks: Int,
        onProgress: ((Long, Long) -> Unit)?
    ): List<File>? {
        val size = total / chunks
        val parts = ConcurrentHashMap<Int, File>()
        val done = AtomicLong(0)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(chunks)

        for (i in 0 until chunks) {
            val start = i * size
            val end = if (i == chunks - 1) total - 1 else (start + size - 1)
            pool.submit {
                try {
                    if (failed.get()) return@submit
                    val f = File.createTempFile("part${i}_", ".tmp")
                    val h = headers.toMutableMap()
                    h["Range"] = "bytes=$start-$end"
                    val r = Http.call(url, h)
                    if (!r.isSuccessful) {
                        r.close(); f.delete(); failed.set(true); return@submit
                    }
                    r.body?.byteStream()?.use { input ->
                        f.outputStream().use { input.copyTo(it, 1 shl 16) }
                    }
                    r.close()
                    parts[i] = f
                    val n = done.addAndGet(f.length())
                    onProgress?.invoke(n, total)
                } catch (t: Throwable) {
                    failed.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }
        return try {
            latch.await(5, TimeUnit.MINUTES)
            if (failed.get() || parts.size != chunks) null
            else (0 until chunks).mapNotNull { parts[it] }
        } catch (t: Throwable) {
            null
        }
    }

    /** 合并各块 */
    private fun mergeParts(parts: List<File>, out: File): File? {
        return try {
            if (out.exists()) out.delete()
            out.outputStream().use { o ->
                for (p in parts) {
                    p.inputStream().use { it.copyTo(o, 1 shl 16) }
                }
            }
            parts.forEach { runCatching { it.delete() } }
            out
        } catch (t: Throwable) {
            null
        }
    }

    /** 单线程下载 */
    private fun single(
        url: String, headers: Map<String, String>, out: File,
        onProgress: ((Long, Long) -> Unit)?, total: Long
    ): File? {
        return try {
            if (out.exists()) out.delete()
            val r = Http.call(url, headers)
            if (!r.isSuccessful) {
                r.close()
                return null
            }
            r.body?.byteStream()?.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var sum = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        o.write(buf, 0, n)
                        sum += n
                        onProgress?.invoke(sum, total)
                    }
                }
            }
            r.close()
            out
        } catch (t: Throwable) {
            null
        }
    }

    /** 只探测文件长度，不下载。服务里用来算进度。失败返回 -1。 */
    fun probeLength(url: String): Long {
        return try {
            val r = Http.call(url)
            val len = r.header("Content-Length")?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it > 0 }
            r.close()
            len ?: -1L
        } catch (t: Throwable) {
            -1L
        }
    }

    /** 下载到 cache 目录（供外部直接取文件） */
    fun toCache(ctx: Context, url: String, name: String): File? {
        return try {
            downloadToCache(ctx, url, name, emptyMap(), null)
        } catch (t: Throwable) {
            null
        }
    }
}
