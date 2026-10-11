package com.kongbai.modmigrator

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 区域缩略图在**公有目录**里的读写。
 *
 * 为什么非得放公有目录：
 * 瓦片是渲染的产出，一个世界动辄几百上千张。之前它们只躺在应用私有目录，
 * 用户既看不见也拿不走，而应用自己却要一直把它们攥在内存里 ——
 * 内存不够就 OOM，够的话又挤掉别的。
 * 落进公有目录之后，磁盘才是真相，内存只留屏幕上那一圈。
 *
 * 放在 `Pictures/ModMigrator/地图缓存/<世界>/<维度>/<高度档>/` 下，
 * 文件名就是区域坐标（`r.0.-1.png`），用户拿文件管理器能直接看。
 *
 * Android 10 起写公有目录只能走 MediaStore，[File] 直写会被拒；
 * 10 以下没有相对路径这套，退回应用私有目录（那里卸载会被清掉，
 * 所以对外说明里要讲清楚）。
 */
object McaTileStore {

    private const val ROOT = "Pictures/ModMigrator/地图缓存"

    private fun useMedia(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 世界名、维度名可能带斜杠或冒号，必须洗一遍才能当目录名 */
    fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_\\-一-龥.+]"), "_")
            .trim().ifBlank { "x" }.take(48)

    private fun relOf(ns: String): String =
        if (ns.isBlank()) ROOT else "$ROOT/${sanitize(ns)}"

    @Suppress("DEPRECATION")
    private fun legacyDir(ctx: Context, ns: String): File =
        File(
            File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ModMigrator"),
            "地图缓存/${sanitize(ns)}"
        )

    // ------------------------------------------------------------------ 写

    /**
     * 写一张瓦片。同名已存在就覆盖，不存在就新建。
     *
     * 覆盖而不是先删再插：MediaStore 里同一个相对路径下同名文件
     * 可能被插出多份，越滚越多，去重成本高。
     */
    fun write(ctx: Context, ns: String, name: String, bmp: Bitmap): Boolean {
        return try {
            if (useMedia()) writeMedia(ctx, relOf(ns), name, bmp)
            else writeFile(legacyDir(ctx, ns), name, bmp)
        } catch (t: Throwable) {
            Err.ignore(t, "写区域缩略图到公有目录")
            false
        }
    }

    private fun writeMedia(ctx: Context, rel: String, name: String, bmp: Bitmap): Boolean {
        val exist = find(ctx, rel, name)
        if (exist != null) {
            ctx.contentResolver.openOutputStream(exist, "wt")?.use {
                return bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
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
        return ok
    }

    private fun writeFile(dir: File, name: String, bmp: Bitmap): Boolean {
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        f.outputStream().use { return bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ 查

    private fun find(ctx: Context, rel: String, name: String): Uri? = try {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH
        )
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj, sel, arrayOf(rel, name), null
        )?.use { c ->
            if (c.moveToFirst()) {
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                )
            } else null
        }
    } catch (t: Throwable) {
        // 有些 ROM 不允许按 RELATIVE_PATH 查。查不到就退回"插入新的"，
        // 最坏情况是同一张瓦片存了多份，不影响正确性。
        Err.ignore(t, "查询瓦片缓存")
        null
    }

    /** 瓦片最后写入时间（秒）。用来判断缓存是不是比源文件旧 */
    fun modifiedSec(ctx: Context, ns: String, name: String): Long {
        return try {
            if (useMedia()) {
                val proj = arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
                val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    proj, sel, arrayOf(relOf(ns), name), null
                )?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                } ?: 0L
            } else {
                File(legacyDir(ctx, ns), name).lastModified() / 1000
            }
        } catch (t: Throwable) {
            Err.ignore(t, "查询瓦片写入时间")
            0L
        }
    }

    /** 读一张瓦片。返回 null 表示没有（或上次写入被打断留下半截文件） */
    fun read(ctx: Context, ns: String, name: String): Bitmap? {
        return try {
            if (useMedia()) {
                val uri = find(ctx, relOf(ns), name) ?: return null
                ctx.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            } else {
                val f = File(legacyDir(ctx, ns), name)
                if (!f.isFile) null
                else android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            }
        } catch (t: Throwable) {
            Err.ignore(t, "读瓦片缓存")
            null
        }
    }

    // ------------------------------------------------------------------ 删

    fun remove(ctx: Context, ns: String, name: String) {
        runCatching {
            if (useMedia()) {
                find(ctx, relOf(ns), name)?.let {
                    ctx.contentResolver.delete(it, null, null)
                }
            } else {
                File(legacyDir(ctx, ns), name).delete()
            }
        }
    }

    /** 清掉一个命名空间（含子目录）下的全部瓦片 */
    fun clear(ctx: Context, ns: String) {
        runCatching {
            if (useMedia()) {
                val base = relOf(ns)
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                // 精确匹配这一层
                ctx.contentResolver.delete(
                    uri, "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(base)
                )
                // 以及它下面的各级子目录
                ctx.contentResolver.delete(
                    uri,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$base/%")
                )
            } else {
                legacyDir(ctx, ns).deleteRecursively()
            }
        }
    }

    /** 这个命名空间下有多少张、总共多大。给用户看占用用 */
    fun stats(ctx: Context, ns: String): Pair<Int, Long> {
        return try {
            if (useMedia()) {
                val base = relOf(ns)
                val proj = arrayOf(MediaStore.MediaColumns.SIZE)
                val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    proj, sel, arrayOf(base, "$base/%"), null
                )?.use { c ->
                    var n = 0
                    var bytes = 0L
                    val i = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        n++
                        if (i >= 0) bytes += c.getLong(i)
                    }
                    n to bytes
                } ?: (0 to 0L)
            } else {
                val fs = legacyDir(ctx, ns).walkTopDown().filter { it.isFile }.toList()
                fs.size to fs.sumOf { it.length() }
            }
        } catch (t: Throwable) {
            Err.ignore(t, "统计瓦片缓存")
            0 to 0L
        }
    }
}
