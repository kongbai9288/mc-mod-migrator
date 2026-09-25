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

    /**
     * 打包用户数据目录（收藏、标记链接、回收站清单等），用于云盘备份。
     *
     * @param dir 可能为 null（没设工作目录时），此时会把应用私有目录里的
     *            同名文件也带上——备份不该因为目录没授权就什么都不做。
     * @return 是否打进了至少一个文件
     */
    fun zipData(ctx: Context, dir: DocumentFile?, out: File): Boolean {
        var count = 0
        try {
            if (out.exists()) out.delete()
            ZipOutputStream(out.outputStream()).use { zos ->
                // 工作目录里的用户数据
                if (dir != null) {
                    for (f in Fs.children(dir)) {
                        if (!f.isFile) continue
                        val n = f.name ?: continue
                        zos.putNextEntry(ZipEntry(n))
                        ctx.contentResolver.openInputStream(f.uri)?.use { it.copyTo(zos, 1 shl 16) }
                        zos.closeEntry()
                        count++
                    }
                }
                // 应用私有目录里的兜底数据（收藏等会在两边都存一份）
                for (n in PRIVATE_FILES) {
                    val f = File(ctx.filesDir, n)
                    if (!f.exists() || f.length() <= 0L) continue
                    val ze = ZipEntry(n)
                    // 工作目录那边已经打过同名文件就不重复放，避免解压时互相覆盖
                    zos.putNextEntry(ze)
                    f.inputStream().use { it.copyTo(zos, 1 shl 16) }
                    zos.closeEntry()
                    count++
                }
            }
        } catch (t: Throwable) {
            Err.ignore(t, "打包用户数据")
        }
        return count > 0
    }

    /** 应用私有目录里需要在备份时一并带上的用户数据文件 */
    private val PRIVATE_FILES = listOf(
        "favorites.json",
        "marked_links.json",
        "trash_index.json"
    )

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
