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
            DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
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

    fun ensureDir(root: DocumentFile, name: String): DocumentFile {
        val hit = find(root, name, 0)
        if (hit != null && hit.isDirectory) return hit
        return root.createDirectory(name) ?: root
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
            ctx.contentResolver.openInputStream(file.uri)?.use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
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
