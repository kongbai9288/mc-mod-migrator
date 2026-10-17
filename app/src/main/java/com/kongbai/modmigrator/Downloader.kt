package com.kongbai.modmigrator

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载器：单文件多线程分块下载 + 断点续传 + 弱网退避重试。
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
 * 断点续传（本轮补上）：
 *   以前每次进来先 `part.delete()`，注释写着"断点续传尝试"，
 *   实际上一次都没续上——几十 MB 的模组在 4G 上下到 90% 断一次
 *   就全白费。现在 part 与每个分块都留在 cache 里，
 *   下次进来从已有长度接着要（Range: bytes=<已下>-）。
 *
 * 弱网退避（本轮补上）：
 *   以前一次超时就判失败、直接返回 null。现在交给 [Retry.loop]，
 *   对超时/连接重置/429/5xx 退避重试，已下的字节不丢。
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

    /** 续传残片保留多久。超过就清，避免长期占着 cache */
    private const val STALE_MS = 3L * 24 * 60 * 60 * 1000

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
        // ── 流量统计：下载才是这个应用最大的流量来源 ──────────────
        // 之前只在 `Http.get`（小 API 响应）里计数，而下载走的是
        // `Http.call` + 直接读 body 流，**从来没被统计过**。
        // 结果：下了几百 MB 模组，流量提醒纹丝不动，
        // 用户提的"用流量时提醒"对下载完全失效。
        // 这里按实际落盘体积补记一次。
        try {
            val bytes = tmp.length()
            if (bytes > 0) Traffic.record(ctx, bytes)
        } catch (t: Throwable) {
            Err.ignore(t, "记录下载流量")
        }
        val out = try {
            place(ctx, tmp, dir, name)
        } catch (t: Throwable) {
            null
        }
        if (out == null) {
            // 写入目标目录失败时 tmp 留在 cache 里，每次失败残留一个完整文件，
            // 长期下来会吃掉几百 MB。这里必须清掉。
            //（下一轮重下会走续传，从头再来也不亏）
            try { tmp.delete() } catch (_: Throwable) { }
            return null
        }
        tmp.delete()
        return out
    }

    /**
     * 把已下好的缓存文件放进目标目录：先写副名，再删旧的，最后改名。
     *
     * 以前的顺序是「先删旧的 → 再建新的」。中间任何一步失败
     *（createFile 返回 null、openOutputStream 返回 null、写到一半存储满了），
     * 用户的旧模组已经没了、新的也没装上 —— 迁移前还能进的存档直接变坏包。
     * 这正是"先复制后删除"要解决的：新内容完整落盘之前，不动旧文件。
     *
     * SAF 没有通用的"原子替换"，所以退而求其次：
     *   1. 完整写一份 `<名字>.mmpart`（这一步失败，旧文件毫发无损）
     *   2. 确认写完之后才删旧文件
     *   3. 用 renameDocument 改回正式名（外部存储这类 provider 普遍支持）
     *   4. 不支持改名时，再复制一份到正式名并清掉副名
     * 风险窗口只剩 2 到 3 之间，此时副名文件还在，下次进来会被清掉重来。
     */
    private fun place(ctx: Context, tmp: File, dir: DocumentFile, name: String): DocumentFile? {
        val partName = name + MigGuard.PART_SUFFIX
        // 上次残留的副名文件先清掉，否则 createFile 会另起一个 "(1)" 名字
        runCatching { dir.findFile(partName)?.delete() }

        val part = dir.createFile(Fs.mimeOf(name), partName) ?: return null
        // ⚠️ openOutputStream 返回 null 时，`?.use {}` 整块被跳过，
        // 但下面的 return part **照常执行**。调用方拿到一个非 null 的
        // DocumentFile，以为下载成功，实际是个 0 字节空 jar ——
        // 装进 mods 目录直接坏包。必须把 null 当成失败返回。
        val os = ctx.contentResolver.openOutputStream(part.uri, "wt")
        if (os == null) {
            part.delete()
            return null
        }
        os.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }

        // 走到这里，新内容已经在目标目录里完整存在了，才动旧文件
        runCatching { dir.findFile(name)?.delete() }

        val renamed = runCatching {
            DocumentsContract.renameDocument(ctx.contentResolver, part.uri, name)
        }.getOrNull()
        if (renamed != null) return DocumentFile.fromSingleUri(ctx, renamed)

        // provider 不支持改名：复制一份到正式名
        val out = dir.createFile(Fs.mimeOf(name), name) ?: return null
        val os2 = ctx.contentResolver.openOutputStream(out.uri, "wt")
        if (os2 == null) {
            out.delete()
            return null
        }
        os2.use { o ->
            ctx.contentResolver.openInputStream(part.uri)?.use { it.copyTo(o, 1 shl 16) }
        }
        runCatching { part.delete() }
        return out
    }

    /** 下载到 cache，内部自动判断是否分块 */
    /**
     * 按目标文件名加细粒度锁。
     *
     * 批量下载时线程池是并发的，而同名模组（比如两个源都给出
     * `sodium-1.20.1.jar`）会指向**同一个缓存文件**。
     * 两个线程同时 `out.exists() → delete()` 再 `outputStream()` 写，
     * 会出现一个刚写完、另一个把它删了重写的交错 ——
     * 最终文件是 0 字节、半截、或者两份内容拼接的损坏文件。
     * 用户看到的正是"批量下载后有几个模组装不上"。
     *
     * 按文件名加锁：不同文件完全并行不受影响，
     * 只有真正同名才串行，代价极小。
     */
    private val fileLocks = ConcurrentHashMap<String, Any>()

    private fun downloadToCache(
        ctx: Context, url: String, name: String,
        headers: Map<String, String>, onProgress: ((Long, Long) -> Unit)?
    ): File? {
        trimCache(ctx)
        val cache = File(ctx.cacheDir, "dl").apply { if (!exists()) mkdirs() }
        val safe = guessName(name).ifBlank { "mod.jar" }
        val out = File(cache, safe)
        val lock = fileLocks.computeIfAbsent(safe) { Any() }
        // ⚠️ 用完**不能**把锁从 map 里删掉。
        // 假如此时正有另一个线程阻塞在这把锁上等待：
        // 删掉之后，第三个线程进来会 computeIfAbsent 出一个**新的**锁对象，
        // 于是等待中的线程和它各自持有不同的锁 —— 互斥彻底失效，
        // 反而制造出我们本来要防止的并发写冲突。
        // 锁对象只是个空 Any()，一个模组名一个，常驻也没有内存压力。
        return synchronized(lock) {
            downloadToCacheLocked(ctx, url, out, headers, onProgress)
        }
    }

    /**
     * 清掉过期的续传残片。
     *
     * 续传要求残片留在 cache 里，否则"下次接着下"无从谈起——
     * 但放之任之会越积越多。折中：保留 3 天。
     * 还在用的文件每次续写都会更新 mtime，不会被误删。
     */
    private fun trimCache(ctx: Context) {
        runCatching {
            val dir = File(ctx.cacheDir, "dl")
            if (!dir.exists()) return@runCatching
            val cut = System.currentTimeMillis() - STALE_MS
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cut) f.delete()
            }
        }
    }

    private fun downloadToCacheLocked(
        ctx: Context, url: String, out: File,
        headers: Map<String, String>, onProgress: ((Long, Long) -> Unit)?
    ): File? {
        val safe = out.name
        val cache = out.parentFile ?: return null
        // 分块/续传文件带上 url 指纹：同名但不同地址的模组
        //（比如两个源给出同名文件）不会互相续到对方的残片上。
        val token = (url.hashCode() and 0x7fffffff).toString(36)
        val part = File(cache, "$safe.$token.part")

        // ⚠️ 这里**不能**再像以前那样一上来就 `part.delete()`。
        // 那一行让"断点续传"从类注释上的承诺变成了一句空话：
        // 每次失败都从 0 重来，弱网上大文件几乎永远下不完。
        // part 现在是有状态的续传进度，只有确认无法续传才清掉重来。
        runCatching { if (out.exists()) out.delete() }

        val parallel = Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3).coerceIn(1, MAX_CHUNKS)
        val info = try {
            Retry.loop("probe") { probeOnce(url, headers) }
        } catch (t: Throwable) {
            null
        }

        val got: File? = if (
            info == null || !info.acceptRange ||
            info.length < MIN_CHUNK_TOTAL || parallel <= 1
        ) {
            singleWithRetry(url, headers, part, info?.length ?: -1L, info?.acceptRange == true, onProgress)
        } else {
            val chunks = chunkCount(info.length, parallel)
            val files = chunked(url, headers, info.length, chunks, cache, safe, token, onProgress)
            if (files == null) {
                // 分块失败就退回单线程，不能因为追求速度导致下不下来。
                // part 里可能留有上次单线程的进度，续传会接着用，不会白下。
                singleWithRetry(url, headers, part, info.length, true, onProgress)
            } else {
                mergeParts(files, part)
            }
        }

        // 校验实际大小：拿得到总长度就必须对得上，对不上视为失败
        if (got == null || !got.exists()) {
            runCatching { part.delete() }
            return null
        }
        if (info != null && info.length > 0 && got.length() != info.length) {
            // 大小对不上说明服务端内容变了，续传的残片是旧的，一并清掉
            runCatching { got.delete() }
            clearChunks(cache, safe, token)
            return null
        }
        // 成功：分块文件已经合并进 part，留着没用，清掉
        clearChunks(cache, safe, token)
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

    /**
     * 探测文件长度与是否支持 Range。
     *
     * 同样优先 HEAD：只需要响应头。
     * 拿不到长度（部分服务器 HEAD 不给 Content-Length）时
     * 才用 1 字节 Range 请求再确认一次。
     *
     * 返回 null 只表示"通了但拿不到长度"，不是网络失败；
     * 网络失败抛异常，交给 [Retry.loop] 退避重试。
     */
    private fun probeOnce(url: String, headers: Map<String, String>): Probe? {
        // 1) HEAD
        try {
            val r = Http.head(url, headers, Http.SHORT)
            r.use {
                val len = it.header("Content-Length")?.toLongOrNull()
                    ?: it.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                val ar = it.header("Accept-Ranges")?.equals("bytes", true) == true
                if (len != null && len > 0) return Probe(len, ar)
                // 通了但没长度：再问一次也是一样，没必要重试
                if (it.isSuccessful) return null
                if (Retry.retryableCode(it.code)) throw IOException("HTTP ${it.code}")
                // 405/501 之类：服务端不支持 HEAD，落到下面的回退
            }
        } catch (t: Throwable) {
            // 不可重试的（证书问题、URL 非法等）不再退避，但仍可试试回退
            if (Retry.retryable(t)) throw t
        }
        // 2) 回退：GET 只取 1 字节，同时能确认是否支持续传
        val (code, len, ar) = Http
            .call(url, headers + mapOf("Range" to "bytes=0-0"), Http.SHORT)
            .use {
                val l = it.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                    ?: it.header("Content-Length")?.toLongOrNull()
                    ?: it.body?.contentLength()?.takeIf { v -> v > 0 }
                val a = it.header("Accept-Ranges")?.equals("bytes", true) == true ||
                    it.header("Content-Range")?.startsWith("bytes", true) == true
                Triple(it.code, l, a)
            }
        if (len == null || len <= 0) {
            if (Retry.retryableCode(code)) throw IOException("HTTP $code")
            return null
        }
        return Probe(len, ar)
    }

    /** 计算分几块 */
    private fun chunkCount(total: Long, parallel: Int): Int {
        val bySize = (total / MIN_CHUNK_SIZE).toInt().coerceAtLeast(1)
        return minOf(parallel, bySize, MAX_CHUNKS).coerceAtLeast(1)
    }

    /**
     * 分块并行下载，返回各块的临时文件（按序），失败返回 null。
     *
     * 每块的文件名是确定的（`<名字>.<url指纹>.c<序号>`），
     * 所以上一次下了一半的块这次能接着要——这是分块路径上的续传。
     */
    private fun chunked(
        url: String, headers: Map<String, String>, total: Long, chunks: Int,
        cache: File, base: String, token: String,
        onProgress: ((Long, Long) -> Unit)?
    ): List<File>? {
        val plan = ByteRange.plan(total, chunks)
        if (plan.isEmpty()) return null

        // 以 plan 的实际段数为准：total 太小时 plan 会合并成一段，
        // 若仍按 chunks 取 plan[i] 会越界
        val n = plan.size
        val parts = ConcurrentHashMap<Int, File>()
        val done = AtomicLong(0)
        val failed = AtomicBoolean(false)
        val latch = CountDownLatch(n)

        for (i in 0 until n) {
            val start = plan[i].first
            val end = plan[i].last
            val want = end - start + 1
            val f = File(cache, "$base.$token.c$i")

            // ⚠️ 只有"长度正好等于本块预期"的残片才能续用。
            // 比预期长，说明总长变了（服务端换了文件）或块数变了，
            // 续上去会拼出一个体积对、内容坏的 jar——比重新下还糟。
            if (f.exists() && f.length() > want) f.delete()
            if (f.exists() && f.length() == want) {
                parts[i] = f
                done.addAndGet(want)
                latch.countDown()
                continue
            }

            pool.submit {
                try {
                    Retry.loop("chunk$i") { _ ->
                        if (failed.get()) throw Retry.Abort("同批次的另一块已失败")

                        val have = if (f.exists()) f.length().coerceAtMost(want) else 0L
                        val from = start + have
                        val h = headers.toMutableMap()
                        h["Range"] = "bytes=$from-$end"
                        val r = Http.call(url, h)
                        try {
                            when {
                                // 本地这份比服务端还长，或内容已换，清掉重来
                                r.code == 416 -> {
                                    f.delete()
                                    throw IOException("HTTP 416")
                                }
                                // 要了区间却回 200：服务端根本不支持 Range，
                                // 分块这条路走不通，交给单线程兜底
                                r.code == 200 -> throw Retry.Abort("服务端忽略 Range")
                                !r.isSuccessful -> throw IOException("HTTP ${r.code}")
                                else -> {
                                    r.body?.byteStream()?.use { input ->
                                        FileOutputStream(f, have > 0).use { o ->
                                            val buf = ByteArray(1 shl 16)
                                            var got: Int
                                            while (input.read(buf).also { got = it } > 0) {
                                                // 别的块已经失败就立刻停手。
                                                // 只在提交时检查一次 failed 是不够的：
                                                // 那之后已启动的块仍会把整块下完才收尾，
                                                // 一个失败导致其余几块白白把流量跑满。
                                                if (failed.get()) throw Retry.Abort("同批次的另一块已失败")
                                                o.write(buf, 0, got)
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            runCatching { r.close() }
                        }
                        if (!f.exists() || f.length() != want) {
                            throw IOException("块 $i 长度不符：${if (f.exists()) f.length() else -1} != $want")
                        }
                    }
                    parts[i] = f
                    done.addAndGet(want)
                    onProgress?.invoke(done.get(), total)
                } catch (t: Throwable) {
                    failed.set(true)
                    runCatching { f.delete() }
                } finally {
                    latch.countDown()
                }
            }
        }

        return try {
            latch.await(10, TimeUnit.MINUTES)
            if (failed.get() || parts.size != n) {
                // ⚠️ 失败的那一块必须删掉（catch 里已删）。
                // 但**成功的块要留着**——它们正是下次续传的进度，
                // 全删等于把断点续传又变回从零开始。
                // 因此只删本次标记为失败的块，缓存总量由 trimCache 按时间兜底。
                null
            } else (0 until n).mapNotNull { parts[it] }
        } catch (t: Throwable) {
            null
        }
    }

    private fun clearChunks(cache: File, base: String, token: String) {
        runCatching {
            val pfx = "$base.$token.c"
            cache.listFiles { f -> f.isFile && f.name.startsWith(pfx) }?.forEach { it.delete() }
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

    /** 单线程下载，失败按 [Retry] 退避重试，已下字节不丢 */
    private fun singleWithRetry(
        url: String, headers: Map<String, String>, out: File,
        total: Long, acceptRange: Boolean, onProgress: ((Long, Long) -> Unit)?
    ): File? {
        return try {
            Retry.loop("download") {
                singleOnce(url, headers, out, total, acceptRange, onProgress)
            }
            if (out.exists() && out.length() > 0) out else null
        } catch (t: Throwable) {
            Err.ignore(t, "单线程下载")
            null
        }
    }

    /**
     * 单线程下载一次（带续传）。失败就抛，让 [Retry.loop] 决定要不要再来。
     *
     * 关键：抛异常时**不删** out。已下的字节就是续传的资本。
     */
    private fun singleOnce(
        url: String, headers: Map<String, String>, out: File,
        total: Long, acceptRange: Boolean, onProgress: ((Long, Long) -> Unit)?
    ) {
        var at = if (out.exists()) out.length() else 0L
        if (total > 0 && at > total) {
            // 本地比服务端还长：内容换了，旧的没有意义
            runCatching { out.delete() }
            at = 0L
        }
        val range = ByteRange.header(at, total, acceptRange)
        // ⚠️ range 为 null 意味着"不能续"，此时 at 未必是 0
        //（比如已经下完、或本地比服务端还长）。继续用 at 追加会把文件写成两倍，
        // 所以只要不续传就一律从 0 写。
        val writeAt = if (range != null) at else 0L
        if (range == null) runCatching { out.delete() }

        val h = headers.toMutableMap()
        if (range != null) h["Range"] = range

        val r = Http.call(url, h)
        try {
            when {
                r.code == 416 -> {
                    runCatching { out.delete() }
                    throw IOException("HTTP 416：本地已下字节超出服务端范围")
                }
                // 要了续传却回 200：服务端忽略 Range，会把整份从头发来。
                // 这时必须清空再写，否则拼出来是"旧的前半截 + 完整文件"的坏包。
                range != null && r.code == 200 -> {
                    runCatching { out.delete() }
                    copyBody(r, out, 0L, total, onProgress)
                }
                !r.isSuccessful -> throw IOException("HTTP ${r.code}")
                else -> copyBody(r, out, writeAt, total, onProgress)
            }
        } finally {
            runCatching { r.close() }
        }
    }

    /** 把响应体写入 [out]；[at] 大于 0 时追加 */
    private fun copyBody(
        r: Response, out: File, at: Long, total: Long,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val input = r.body?.byteStream()
        if (input == null) throw IOException("响应没有内容")
        input.use { i ->
            FileOutputStream(out, at > 0).use { o ->
                val buf = ByteArray(1 shl 16)
                var sum = at
                var n: Int
                while (i.read(buf).also { n = it } > 0) {
                    o.write(buf, 0, n)
                    sum += n
                    onProgress?.invoke(sum, total)
                }
            }
        }
    }

    /**
     * 只探测文件长度，不下载。服务里用来算进度。失败返回 -1。
     *
     * 之前用 `Http.call(url)` 发 **GET** —— 服务器会把整个文件发过来，
     * 而这里只需要一个 Content-Length。后台下载服务每次开下前都调它，
     * 等于**每个模组都先白下载一遍**，流量翻倍、还拖慢开始时间。
     * 现在优先 HEAD（只要响应头），服务器不支持 HEAD 才回退到
     * 只取 1 字节的 Range 请求。
     */
    fun probeLength(url: String): Long {
        // 1) HEAD：零流量拿到长度
        val head = try {
            Retry.loop("probeLength") {
                val r = Http.head(url, timeout = Http.SHORT)
                try {
                    val len = r.header("Content-Length")?.toLongOrNull()
                        ?: r.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                    if (len != null && len > 0) len
                    else if (!r.isSuccessful && Retry.retryableCode(r.code)) throw IOException("HTTP ${r.code}")
                    else 0L
                } finally {
                    r.close()
                }
            }
        } catch (t: Throwable) {
            // 服务器不支持 HEAD（405 等）或网络抖动，走下面的回退
            0L
        }
        if (head > 0) return head

        // 2) 回退：GET 但只请求第 1 个字节，不拉整个文件
        return try {
            Retry.loop("probeLengthRange") {
                val r = Http.call(url, mapOf("Range" to "bytes=0-0"), Http.SHORT)
                try {
                    val len = r.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                        ?: r.header("Content-Length")?.toLongOrNull()
                    if (len != null && len > 0) len
                    else if (Retry.retryableCode(r.code)) throw IOException("HTTP ${r.code}")
                    else -1L
                } finally {
                    r.close()
                }
            }
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
