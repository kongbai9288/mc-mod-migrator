package com.kongbai.modmigrator

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import java.util.Locale

object Fs {

    fun tree(ctx: Context, uriStr: String?): DocumentFile? {
        if (uriStr.isNullOrBlank()) return null
        return try {
            val u = Uri.parse(uriStr)
            // ⚠️ file:// URI 不能用 fromTreeUri 解析。
            // InstanceScanner 用 File API 扫实例时生成的是
            // "file://" + absolutePath，而 fromTreeUri 只认
            // content://.../tree/... 这种 SAF 树 URI，
            // 拿到 file:// 会直接返回 null。
            // 后果：授权"所有文件访问"后能扫到实例，
            // 但选中之后扫描 mods / 算 SHA-1 / 复制配置全部报
            // "目录不可访问" —— 因为下游统统走 Fs.tree。
            // 这里按 scheme 分流：file:// 走 fromFile。
            if ("file".equals(u.scheme, true)) {
                val p = u.path
                if (p.isNullOrBlank()) return null
                val f = java.io.File(p)
                if (!f.exists()) return null
                DocumentFile.fromFile(f)
            } else {
                DocumentFile.fromTreeUri(ctx, u)
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun children(dir: DocumentFile): List<DocumentFile> {
        return try {
            val arr = dir.listFiles()
            if (arr == null) emptyList() else arr.toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun find(root: DocumentFile, name: String, depth: Int = 2): DocumentFile? {
        val lower = name.lowercase(Locale.ROOT)
        for (c in children(root)) {
            if ((c.name ?: "").lowercase(Locale.ROOT) == lower) return c
        }
        if (depth <= 0) return null
        for (c in children(root)) {
            if (c.isDirectory) {
                val hit = find(c, name, depth - 1)
                if (hit != null) return hit
            }
        }
        return null
    }

    // ---- 子目录缓存 ----
    // DocumentFile.findFile() 在树授权下是**跨进程调用**，
    // 迁移几十个文件时会被反复调用上百次，既慢又让人觉得"一直在请求授权"。
    // 这里按 uri 缓存解析结果，同一进程内同一目录只解析一次。
    private val dirCache = java.util.Collections.synchronizedMap(
        LinkedHashMap<String, DocumentFile>(64, 0.75f, true)
    )

    /** 切换工作目录时必须调这个，否则会拿到旧目录的缓存 */
    fun clearCache() {
        synchronized(dirCache) { dirCache.clear() }
    }

    /**
     * 在 root 下确保存在名为 name 的子目录。
     * 创建失败时返回 **null**（调用方自行决定降级策略）。
     */
    fun ensureDir(root: DocumentFile, name: String): DocumentFile? {
        val key = root.uri.toString() + "#" + name
        dirCache[key]?.let { return it }
        val hit = find(root, name, 0)
        if (hit != null && hit.isDirectory) {
            synchronized(dirCache) {
                if (dirCache.size > 200) dirCache.clear()
                dirCache[key] = hit
            }
            return hit
        }
        // ⚠️ 原来是 `(root.createDirectory(name) ?: root)`：
        // 创建失败时返回**父目录本身**，还把它缓存成 name 的结果。
        // 之后每次取这个"子目录"都拿到父目录，
        // 往子目录里写文件实际写到了父目录根下 —— 文件位置全错。
        // 失败必须返回 null，且**不能**把失败结果写进缓存。
        val made = try { root.createDirectory(name) } catch (t: Throwable) { null }
        if (made == null) return null
        synchronized(dirCache) {
            if (dirCache.size > 200) dirCache.clear()
            dirCache[key] = made
        }
        return made
    }

    fun readText(ctx: Context, file: DocumentFile): String {
        return try {
            ctx.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    fun sha1(ctx: Context, file: DocumentFile): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val stream = ctx.contentResolver.openInputStream(file.uri)
            // 打不开时必须返回空串。
            // 之前写成 `?.use { ... }`：流为 null 时 lambda 不执行，
            // 但 md.digest() 照常跑，于是返回的是「空内容的 SHA-1」
            // (da39a3ee...)。这个哈希会被当成真实指纹提交给 Modrinth，
            // 表现就是"文件明明打不开，却显示识别成功或结果错乱"。
            if (stream == null) return ""
            stream.use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            Err.ignore(t, "计算 SHA-1：${file.name}")
            ""
        }
    }

    fun copyInto(ctx: Context, src: DocumentFile, dstDir: DocumentFile, log: (String) -> Unit) {
        val name = src.name ?: return
        if (src.isDirectory) {
            val dir = dstDir.findFile(name) ?: dstDir.createDirectory(name)
            if (dir == null) {
                log("创建目录失败：$name")
                return
            }
            for (c in children(src)) copyInto(ctx, c, dir, log)
            log("复制目录：$name")
        } else {
            val existing = dstDir.findFile(name)
            if (existing != null) existing.delete()
            val out = dstDir.createFile(mimeOf(name), name)
            if (out == null) {
                log("创建文件失败：$name")
                return
            }
            try {
                ctx.contentResolver.openInputStream(src.uri)?.use { i ->
                    ctx.contentResolver.openOutputStream(out.uri)?.use { o ->
                        i.copyTo(o, 1 shl 16)
                    }
                }
                log("复制文件：$name")
            } catch (t: Throwable) {
                log("复制失败：$name ${t.message}")
            }
        }
    }

    fun mimeOf(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".jar") -> "application/java-archive"
            n.endsWith(".zip") -> "application/zip"
            n.endsWith(".json") -> "application/json"
            n.endsWith(".txt") || n.endsWith(".cfg") || n.endsWith(".properties") -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
