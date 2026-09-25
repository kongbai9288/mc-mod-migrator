package com.kongbai.modmigrator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 模组图标提取。
 *
 * 做法（和 Mod Menu / Prism 一致）：
 *  1. 读 jar 元数据，拿到里面**声明**的图标路径
 *     （fabric 的 `icon`、forge 的 `logoFile`、mcmod.info 的 `logoFile`）
 *  2. 元数据没声明时，退回几个常见候选路径
 *  3. 用 **ZipFile 直接定位条目**（有中央目录，不用顺序扫整个 jar）
 *  4. **按目标尺寸采样**再解码，避免大图直接进内存导致 OOM
 *
 * 之前的问题：
 *  - 用 ZipInputStream 顺序扫，大 jar 慢
 *  - 不采样，遇到 512×512 甚至更大的图标容易 OOM
 *  - 缓存没有上限，长时间用会一直涨
 */
object ModIcons {

    /** 缓存上限：超过就整体清掉重来，避免无限增长 */
    private const val MAX_CACHE = 120

    private val cache = LinkedHashMap<String, Bitmap?>(MAX_CACHE + 8, 0.75f, true)

    /** 元数据没声明图标时的候选路径 */
    private val FALLBACKS = listOf(
        "icon.png",
        "logo.png",
        "assets/icon.png",
        "assets/logo.png",
        "mod_icon.png",
        "pack.png",          // 资源包常见
        "icon.svg"           // 少数模组用矢量图标
    )

    /**
     * 取图标。返回 null 表示取不到，调用方应显示首字母占位图。
     * 结果会被缓存；同一个文件第二次调用几乎不耗时。
     */
    fun of(ctx: Context, f: DocumentFile): Bitmap? {
        val key = f.uri.toString()
        if (cache.containsKey(key)) return cache[key]

        val bmp = try {
            extract(ctx, f)
        } catch (t: Throwable) {
            null
        }
        synchronized(cache) {
            if (cache.size >= MAX_CACHE) cache.clear()
            cache[key] = bmp
        }
        return bmp
    }

    /** 从本地 File 取（能拿到真实路径时更快） */
    fun ofFile(file: File, targetPx: Int): Bitmap? {
        val key = "f:${file.absolutePath}:$targetPx"
        if (cache.containsKey(key)) return cache[key]
        val bmp = try {
            if (!file.exists() || !file.canRead()) null
            else ZipFile(file).use { zf ->
                val meta = ModMeta.readFile(file)
                val path = pickIconPath(zf, meta)
                if (path.isBlank()) null else decode(zf, path, targetPx)
            }
        } catch (t: Throwable) {
            null
        }
        synchronized(cache) {
            if (cache.size >= MAX_CACHE) cache.clear()
            cache[key] = bmp
        }
        return bmp
    }

    private fun extract(ctx: Context, f: DocumentFile): Bitmap? {
        // SAF 场景下拿不到真实 File，先复制到 cache 再用 ZipFile 打开：
        // ZipFile 需要可随机访问的文件，直接对 uri 流用 ZipInputStream 会慢很多
        val tmp = File(ctx.cacheDir, "icons/${f.name?.hashCode() ?: 0}.jar")
        tmp.parentFile?.mkdirs()
        try {
            ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return null

            val target = (56 * ctx.resources.displayMetrics.density).toInt()
            ZipFile(tmp).use { zf ->
                val meta = ModMeta.readFile(tmp)
                val path = pickIconPath(zf, meta)
                return if (path.isBlank()) null else decode(zf, path, target)
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 决定读哪个条目：优先元数据声明的路径 */
    private fun pickIconPath(zf: ZipFile, meta: ModMeta.Info): String {
        val declared = meta.iconPath.trim()
        if (declared.isNotBlank()) {
            val normalized = declared.trimStart('/')
            // fabric 的 icon 有时写成 "assets/xxx.png"，直接找
            if (zf.getEntry(normalized) != null) return normalized
            // 有时写的是不带 assets 前缀的名字，兜底试一次
            val alt = "assets/$normalized"
            if (zf.getEntry(alt) != null) return alt
        }
        for (c in FALLBACKS) {
            if (zf.getEntry(c) != null) return c
        }
        // 最后兜底：根目录里第一个 .png
        val e = zf.entries().asSequence().firstOrNull {
            !it.isDirectory && it.name.endsWith(".png", true) && !it.name.contains('/')
        }
        return e?.name ?: ""
    }

    /**
     * 按目标尺寸采样解码。
     * inSampleSize 必须是 2 的幂，先只解码边界拿到原始尺寸，
     * 算出采样率后再真正解码——这样大图不会整张进内存。
     */
    private fun decode(zf: ZipFile, path: String, targetPx: Int): Bitmap? {
        val entry = zf.getEntry(path) ?: return null
        if (entry.isDirectory) return null

        // SVG 无法用 BitmapFactory 解码，直接放弃（界面会退回首字母占位）
        if (path.endsWith(".svg", true)) return null

        // 第一遍：只取尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zf.getInputStream(entry).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        // 第二遍：按采样率真解码
        val sample = calculateInSampleSize(w, h, targetPx)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return zf.getInputStream(entry).use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun calculateInSampleSize(w: Int, h: Int, target: Int): Int {
        if (target <= 0) return 1
        var sample = 1
        var max = maxOf(w, h)
        // 逐级翻倍，直到不超过目标的两倍
        while (max > target * 2) {
            sample *= 2
            max /= 2
        }
        return sample.coerceIn(1, 32)   // 上限 32，防止极端情况
    }

    /** 兼容旧调用：按 uri 字符串取图标 */
    fun ofUri(ctx: Context, uriStr: String): Bitmap? {
        if (uriStr.isBlank()) return null
        return try {
            val uri = android.net.Uri.parse(uriStr)
            val f = androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)
            if (f != null) of(ctx, f) else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 清空缓存（切换目录或内存紧张时调用） */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    /** 缓存里有多少个条目（供设置页显示） */
    fun cacheSize(): Int = synchronized(cache) { cache.size }

    /** 给没有图标的模组生成一个稳定的占位色 */
    fun placeholderColor(id: String): Int {
        var hash = 0
        for (c in id.lowercase(Locale.ROOT)) hash = hash * 31 + c.code
        val hue = (hash % 360).let { if (it < 0) it + 360 else it }.toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.85f))
    }
}
