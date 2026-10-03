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

    /**
     * 缓存上限（**字节**）。
     *
     * ⚠️ 之前按**条数**（120 条）限制，但每条的实际大小差很多：
     * 图标按 56dp 采样解码，在 3x 屏上就是 168×168，
     * 一张 168×168 的 ARGB_8888 位图约 **110KB**，
     * 120 条就是 13MB 以上。
     * 而这台设备崩的时候堆只剩 1.9MB —— 光图标缓存就能把它压垮。
     * 改成按字节算，4MB 封顶。
     */
    private const val MAX_CACHE_BYTES = 4 * 1024 * 1024

    /** 图标解码的目标边长（dp）。56 → 48，位图面积小三成 */
    private const val TARGET_DP = 48

    /**
     * 缓存本体。用 LinkedHashMap(accessOrder=true) 而不是 LruCache：
     * LruCache **不接受 null 值**（put 时会抛 NPE），
     * 而"这个模组取不到图标"本身是个需要记住的结论
     * ——不记住的话，列表每滑一次就要把 jar 重新解一遍。
     */
    private val cache = LinkedHashMap<String, Bitmap?>(64, 0.75f, true)
    private val lock = Any()

    /** 当前缓存占用的字节数（只统计真正有位图的条目） */
    private var bytes = 0

    /** 内存紧张时由 [App.onTrimMemory] 调用 */
    fun clear() {
        synchronized(lock) {
            cache.clear()
            bytes = 0
        }
    }

    /** 元数据没声明图标时的候选路径 */
    private val FALLBACKS = listOf(
        "icon.png",
        "logo.png",
        "assets/icon.png",
        "assets/logo.png",
        "mod_icon.png",
        "pack.png",          // 资源包常见
        // 有些模组的图标是 GIF（fabric.mod.json 的 icon 字段允许写 .gif），
        // 之前候选里只有 png，这类模组一律取不到图标、只能显示首字母。
        "icon.gif",
        "logo.gif",
        "assets/icon.gif",
        "icon.svg"           // 少数模组用矢量图标
    )

    /** 兜底扫描时也要认的扩展名 */
    private val ICON_EXT = listOf(".png", ".gif", ".jpg", ".jpeg", ".webp")

    /**
     * 取图标。返回 null 表示取不到，调用方应显示首字母占位图。
     * 结果会被缓存；同一个文件第二次调用几乎不耗时。
     */
    fun of(ctx: Context, f: DocumentFile): Bitmap? {
        val key = f.uri.toString()
        synchronized(lock) {
            if (cache.containsKey(key)) return cache[key]
        }
        val bmp = try {
            extract(ctx, f)
        } catch (t: Throwable) {
            null
        }
        put(key, bmp)
        return bmp
    }

    /** 从本地 File 取（能拿到真实路径时更快） */
    fun ofFile(file: File, targetPx: Int): Bitmap? {
        val key = "f:${file.absolutePath}:$targetPx"
        synchronized(lock) {
            if (cache.containsKey(key)) return cache[key]
        }
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
        put(key, bmp)
        return bmp
    }

    /**
     * 入缓存，并按字节上限淘汰最久未用的。
     *
     * ⚠️ 淘汰时**不能**调 Bitmap.recycle()：
     * 被淘汰的位图很可能还挂在某个 ImageView 上（列表只是滑出屏幕，
     * ViewHolder 还没被回收），recycle 之后 Canvas 再画它就会抛
     * "trying to use a recycled bitmap"，直接崩。
     * 这里只解除引用，交给 GC —— 真正还在显示的那些本来也回收不了。
     */
    private fun put(key: String, bmp: Bitmap?) {
        synchronized(lock) {
            val old = cache.put(key, bmp)
            if (old != null && old !== bmp) bytes -= old.byteCount
            if (bmp != null) bytes += bmp.byteCount
            if (bytes <= MAX_CACHE_BYTES) return
            val it = cache.entries.iterator()
            while (it.hasNext() && bytes > MAX_CACHE_BYTES) {
                val e = it.next()
                if (e.key == key) continue
                if (e.value != null) bytes -= e.value!!.byteCount
                it.remove()
            }
        }
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

            val target = (TARGET_DP * ctx.resources.displayMetrics.density).toInt()
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
        // 最后兜底：根目录里第一个看起来像图标的图片。
        // 之前只找 .png，GIF 图标的模组在这里会漏掉。
        val e = zf.entries().asSequence().firstOrNull {
            !it.isDirectory && !it.name.contains('/') &&
                ICON_EXT.any { ext -> it.name.endsWith(ext, true) }
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

        // GIF：BitmapFactory 只能解出**第一帧**（静态图），
        // 本地 jar 里的图标本来也不需要在列表里播放动画，
        // 取首帧即可。网络图标（Modrinth 的动图）由 Coil 的 GifDecoder 处理。
        val isGif = path.endsWith(".gif", true)

        // 第一遍：只取尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zf.getInputStream(entry).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        // 第二遍：按采样率真解码。
        // GIF 不采样：inJustDecodeBounds 对部分 GIF 拿到的尺寸不可靠，
        // 采样后反而可能解不出来，首帧本来就小，直接解即可。
        val sample = if (isGif) 1 else calculateInSampleSize(w, h, targetPx)
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
