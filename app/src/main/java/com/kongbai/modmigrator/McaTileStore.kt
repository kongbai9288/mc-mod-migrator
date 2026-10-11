package com.kongbai.modmigrator

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 区域缩略图（瓦片）的磁盘读写。
 *
 * ## 为什么不放公有图库了
 *
 * 上一版写进 `Pictures/ModMigrator/地图缓存/`。瓦片是渲染的中间产物，
 * 一个世界动辄几百上千张 —— 结果它们**全部出现在系统相册里**，
 * 把用户的照片流冲垮了。这是把缓存当成果给用户看，方向错了。
 *
 * 现在放**应用专属外部目录**（`Android/data/<包名>/files/ModMigrator/地图缓存/`）：
 *  · 不会被媒体扫描器收录，不进相册
 *  · 用文件管理器仍然看得到、能拷走
 *  · 卸载系统会自动清掉，不留垃圾
 *
 * 真要导出一张图给用户，走「导出图」按钮 —— 那是用户主动要的，
 * 单独一张进相册是合理的；缓存静默灌进相册不合理。
 *
 * 目录根放一个 `.nomedia`，双保险：万一有 ROM 把应用目录也扫一遍，
 * 看到这个文件就会跳过。
 *
 * 另外提供 [migrateAwayFromGallery]：把上一版已经写进相册的那些瓦片删掉，
 * 否则它们会永远留在用户的相册里。
 */
object McaTileStore {

    private const val ROOT = "ModMigrator/地图缓存"
    private const val NOMEDIA = ".nomedia"

    /** 上一版误写进相册的相对路径 */
    private const val LEGACY_REL = "Pictures/ModMigrator/地图缓存"

    @Volatile
    private var migrated = false

    /** 世界名、维度名可能带斜杠或冒号，必须洗一遍才能当目录名 */
    fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_\\-一-龥.+]"), "_")
            .trim().ifBlank { "x" }.take(48)

    private fun root(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val d = File(base, ROOT)
        if (!d.exists()) d.mkdirs()
        // .nomedia 对整个目录树生效，写一次就够
        runCatching { File(d, NOMEDIA).createNewFile() }
        return d
    }

    private fun dirOf(ctx: Context, ns: String): File {
        val d = if (ns.isBlank()) root(ctx) else File(root(ctx), sanitize(ns))
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileOf(ctx: Context, ns: String, name: String): File =
        File(dirOf(ctx, ns), name)

    // ------------------------------------------------------------------ 写

    /**
     * 写一张瓦片。同名已存在就覆盖。
     *
     * 先写临时文件再改名（原子替换）：之前直接往目标写，中途被杀
     * 会留下半截 PNG —— 它 size 不为 0、时间戳也新，之后一直被当成
     * 有效缓存，解码失败后又不删，那张图就永远黑着。
     */
    fun write(ctx: Context, ns: String, name: String, bmp: Bitmap): Boolean {
        return try {
            val dir = dirOf(ctx, ns)
            val dst = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            var ok = false
            tmp.outputStream().use {
                ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.fd.sync()
            }
            if (!ok) {
                tmp.delete()
                return false
            }
            if (dst.exists() && !dst.delete()) {
                tmp.delete()
                return false
            }
            if (tmp.renameTo(dst)) true
            else {
                // 少数 ROM 跨不过去，退回直接覆盖写
                dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (t: Throwable) {
            Err.ignore(t, "写区域缩略图")
            false
        }
    }

    // ------------------------------------------------------------------ 查

    /** 瓦片最后写入时间（秒）。用来判断缓存是不是比源文件旧 */
    fun modifiedSec(ctx: Context, ns: String, name: String): Long =
        try {
            fileOf(ctx, ns, name).lastModified() / 1000
        } catch (t: Throwable) {
            Err.ignore(t, "查询瓦片写入时间")
            0L
        }

    /** 读一张瓦片。返回 null 表示没有（或上次写入被打断留下半截文件） */
    fun read(ctx: Context, ns: String, name: String): Bitmap? {
        return try {
            val f = fileOf(ctx, ns, name)
            if (!f.isFile) return null
            val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            if (bmp == null) {
                // 半截文件：留着只会一直失败，删掉让它重新渲染
                f.delete()
            }
            bmp
        } catch (t: Throwable) {
            Err.ignore(t, "读瓦片缓存")
            null
        }
    }

    // ------------------------------------------------------------------ 删

    fun remove(ctx: Context, ns: String, name: String) {
        runCatching {
            fileOf(ctx, ns, name).delete()
            File(dirOf(ctx, ns), "$name.tmp").delete()
        }
    }

    /** 清掉一个命名空间下的全部瓦片 */
    fun clear(ctx: Context, ns: String) {
        runCatching { dirOf(ctx, ns).deleteRecursively() }
    }

    /** 这个命名空间下有多少张、总共多大。给用户看占用用 */
    fun stats(ctx: Context, ns: String): Pair<Int, Long> {
        return try {
            val d = dirOf(ctx, ns)
            if (!d.isDirectory) return 0 to 0L
            val fs = d.walkTopDown().filter {
                it.isFile && it.name != NOMEDIA && !it.name.endsWith(".tmp")
            }.toList()
            fs.size to fs.sumOf { it.length() }
        } catch (t: Throwable) {
            Err.ignore(t, "统计瓦片缓存")
            0 to 0L
        }
    }

    // ------------------------------------------------- 旧版遗留在相册里的清理

    /**
     * 删掉上一版误写进相册的瓦片。
     *
     * 不清理的话，它们会一直留在用户的相册里 —— 用户删了应用也删不掉。
     * 每个进程只跑一次；失败（比如 ROM 弹回收站确认）不影响使用。
     */
    fun migrateAwayFromGallery(ctx: Context) {
        if (migrated) return
        migrated = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                ctx.contentResolver.delete(uri, sel, arrayOf("$LEGACY_REL/", "$LEGACY_REL/%"))
            } else {
                @Suppress("DEPRECATION")
                val d = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES
                    ), "ModMigrator/地图缓存"
                )
                if (d.isDirectory) d.deleteRecursively()
            }
        } catch (t: Throwable) {
            Err.ignore(t, "清理旧版相册里的瓦片")
        }
        // 顺手把空壳目录去掉，省得相册里还留着一个空文件夹
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                File(ctx.getExternalFilesDir(null), ROOT).walkBottomUp()
                    .filter { it.isDirectory && it.list().isNullOrEmpty() }
                    .forEach { it.delete() }
            }
        }
    }

    /** 导出给用户时才用：主动插一张进相册。和缓存是两条路。 */
    fun publishToGallery(ctx: Context, name: String, bmp: Bitmap): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ModMigrator")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v
            ) ?: return false
            var ok = false
            ctx.contentResolver.openOutputStream(uri)?.use {
                ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, v, null, null)
            ok
        } catch (t: Throwable) {
            Err.ignore(t, "导出地图图片")
            false
        }
    }
}
