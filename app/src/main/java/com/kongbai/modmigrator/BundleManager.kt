package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BundleManager {

    fun zipInto(ctx: Context, root: DocumentFile, names: List<String>, out: File, log: (String) -> Unit): Boolean {
        return try {
            if (out.exists()) out.delete()
            ZipOutputStream(out.outputStream()).use { zos ->
                for (n in names) {
                    val d = Fs.find(root, n)
                    if (d == null) {
                        log("未找到：$n")
                        continue
                    }
                    add(ctx, d, "", zos, log)
                }
            }
            true
        } catch (t: Throwable) {
            log("打包失败：${t.message}")
            false
        }
    }

    private fun add(ctx: Context, doc: DocumentFile, prefix: String, zos: ZipOutputStream, log: (String) -> Unit) {
        val name = doc.name ?: return
        val path = if (prefix.isEmpty()) name else "$prefix/$name"
        if (doc.isDirectory) {
            zos.putNextEntry(ZipEntry("$path/"))
            zos.closeEntry()
            for (c in Fs.children(doc)) add(ctx, c, path, zos, log)
        } else {
            zos.putNextEntry(ZipEntry(path))
            ctx.contentResolver.openInputStream(doc.uri)?.use { it.copyTo(zos, 1 shl 16) }
            zos.closeEntry()
            log("打包：$path")
        }
    }

    fun unzip(ctx: Context, zip: File, dst: DocumentFile, log: (String) -> Unit) {
        try {
            ZipInputStream(zip.inputStream()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val path = e.name
                    if (e.isDirectory) {
                        ensurePath(dst, path)
                        continue
                    }
                    val parent = path.substringBeforeLast('/', "")
                    val dir = if (parent.isEmpty()) dst else ensurePath(dst, parent)
                    val fn = path.substringAfterLast('/')
                    if (fn.isBlank()) continue
                    val f = dir.createFile(Fs.mimeOf(fn), fn) ?: continue
                    ctx.contentResolver.openOutputStream(f.uri)?.use { zis.copyTo(it, 1 shl 16) }
                    log("还原：$path")
                }
            }
        } catch (t: Throwable) {
            log("解压失败：${t.message}")
        }
    }

    private fun ensurePath(root: DocumentFile, path: String): DocumentFile {
        var cur = root
        for (seg in path.split('/')) {
            if (seg.isBlank()) continue
            val next = cur.findFile(seg) ?: cur.createDirectory(seg)
            if (next != null) cur = next
        }
        return cur
    }
}
