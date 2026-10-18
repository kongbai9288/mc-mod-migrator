package com.kongbai.modmigrator

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.net.Uri
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
 * 旧版已经写进相册的那些，靠 [galleryLeftovers] 数出来、由用户在系统确认框里
 * 亲手删 —— 静默 delete 在国内 ROM 上会被"移入回收站"，等于没删。
 */
object McaTileStore {

    private const val ROOT = "ModMigrator/地图缓存"
    private const val NOMEDIA = ".nomedia"

    /** 上一版误写进相册的相对路径 */
    private const val LEGACY_REL = "Pictures/ModMigrator/地图缓存"

    /** 世界名、维度名可能带斜杠或冒号，必须洗一遍才能当目录名 */
    fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_\\-一-龥.+]"), "_")
            .trim().ifBlank { "x" }.take(48)

    private fun root(ctx: Context): File {
        // 走 WorkRoot：用户在设置里指定了目录就用它，没指定才用应用私有目录。
        // 之前这里是写死的应用私有目录，等于把用户指定的目录当不存在。
        val d = WorkRoot.sub(ctx, ROOT)
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

    /**
     * 瓦片解码的尺寸上限。超过就按这个比例降采样。
     * 取 1024 而不是精确的 IMG：正常瓦片（512）完全不受影响，
     * 只有明显异常的大图才会被压下来。
     */
    private const val TILE_MAX_PX = 1024

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
            // ⚠️ 之前直接 decodeFile：按文件原尺寸整张解码。
            // 瓦片是应用自己画的（McaTiles.IMG，通常 512×512），
            // 但磁盘上的文件是能被替换的（用户清理过、别的工具写过、
            // 或者上一次写入被打断留下半截又被拼上）。换成一张 4000×3000 的图，
            // ARGB_8888 就是 48MB —— 这一页本来就要同时挂几十张瓦片，
            // 读几张就崩，而且崩在 BitmapFactory 里，看不出是缓存目录的锅。
            // 先只解码边界，明显超尺寸就降采样；正常尺寸不受影响（inSampleSize=1）。
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
            val opt = android.graphics.BitmapFactory.Options().apply {
                val big = maxOf(bounds.outWidth, bounds.outHeight)
                if (big > 0) inSampleSize = maxOf(1, big / TILE_MAX_PX)
            }
            val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opt)
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

    /** 相册里还剩下的上一版瓦片：张数、总字节、以及最多 [limit] 个 Uri */
    data class GalleryLeftover(
        val count: Int, val bytes: Long, val uris: List<Uri>
    )

    private val galleryUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun gallerySel(): String =
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"

    private fun galleryArgs(): Array<String> =
        arrayOf("$LEGACY_REL/", "$LEGACY_REL/%")

    /** 数一数相册里还剩多少张上一版遗留的瓦片。查不到（没权限）就当没有。 */
    fun galleryLeftovers(ctx: Context, limit: Int = 300): GalleryLeftover {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return GalleryLeftover(0, 0L, emptyList())
        return try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE
            )
            ctx.contentResolver.query(
                galleryUri, proj, gallerySel(), galleryArgs(), null
            )?.use { c ->
                val iu = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val isz = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val uris = ArrayList<Uri>(limit.coerceAtMost(512))
                var n = 0
                var bytes = 0L
                while (c.moveToNext()) {
                    n++
                    if (isz >= 0) bytes += c.getLong(isz)
                    if (uris.size < limit && iu >= 0) {
                        uris.add(
                            android.content.ContentUris.withAppendedId(
                                galleryUri, c.getLong(iu)
                            )
                        )
                    }
                }
                GalleryLeftover(n, bytes, uris)
            } ?: GalleryLeftover(0, 0L, emptyList())
        } catch (t: Throwable) {
            // 没授权读图片时这里会抛。不是致命问题，当"没有遗留"处理，
            // 免得每次进地图都弹一堆错。
            Err.ignore(t, "统计相册里的遗留瓦片")
            GalleryLeftover(0, 0L, emptyList())
        }
    }

    /**
     * 让系统自己弹确认框删掉这批 Uri（Android 11+）。
     *
     * ⚠️ 为什么不能用 `contentResolver.delete` 直接删：
     * 国内 ROM（MIUI / 华为 / OPPO / vivo …）几乎都把删除改成"移入回收站"，
     * 30 天后才真删，有的还会弹二次确认。结果是——应用以为删掉了，
     * 用户一打开相册，那几百张瓦片又原样躺在那儿，甚至被系统判定为
     * "删除已被撤销"。静默删在国内机型上基本等于没删。
     *
     * `createDeleteRequest` 走的是系统级确认：用户亲手确认后是**永久删除**，
     * 不再进回收站。权限归系统，ROM 也没法替用户反悔。
     */
    @Suppress("DEPRECATION")
    fun buildDeleteRequest(ctx: Context, uris: List<Uri>): android.app.PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (uris.isEmpty()) return null
        return try {
            MediaStore.createDeleteRequest(ctx.contentResolver, uris)
        } catch (t: Throwable) {
            Err.ignore(t, "构造删除确认")
            null
        }
    }

    /** Android 10 及以下没有上面那个接口，只能退回静默删（会进回收站） */
    fun legacyDelete(ctx: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.contentResolver.delete(galleryUri, gallerySel(), galleryArgs())
            } else {
                @Suppress("DEPRECATION")
                val d = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES
                    ), "ModMigrator/地图缓存"
                )
                if (d.isDirectory && d.deleteRecursively()) 1 else 0
            }
        } catch (t: Throwable) {
            Err.ignore(t, "清理旧版相册里的瓦片")
            0
        }
    }

    /**
     * 尽量让那个目录不再被媒体扫描器收录。
     *
     * 这一步只是保险：删不掉时，`.nomedia` 至少能让相册应用不再扫它。
     * 有些 ROM 不允许 MediaStore 建隐藏文件名，失败就算了，不影响别的。
     */
    fun tryNomediaInGallery(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NOMEDIA)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$LEGACY_REL/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(galleryUri, v) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf(0)) }
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, v, null, null)
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
