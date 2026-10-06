package com.kongbai.modmigrator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

/**
 * 区域缩略图的生成与缓存。
 *
 * 参照 MCA Selector 的做法分两级：
 *
 *   1. 磁盘：每个区域渲染成一张 PNG，下次打开直接读图，不用再解一遍 NBT。
 *      这是最关键的一级 —— 解析一个区域要解压上千个区块，
 *      而读一张 PNG 只要几毫秒。
 *   2. 内存：最近看过的几张图留在内存里（LRU，按字节数封顶），
 *      平移回来不用再碰磁盘。
 *
 * 缓存失效只看源文件的修改时间和大小变了没有，
 * 这两个变了才重新生成 —— 改过区块后保存，图会自动重画。
 *
 * 之所以要封顶：一张 512×512 的 ARGB 图就是 1MB，
 * 一台设备上同时留几十张就足以把应用拖崩。
 */
object McaTiles {

    /** 一个区域 = 32×32 个区块，每个区块 16×16 格，所以整图 512×512 */
    const val IMG = 512

    /** 内存里最多留多少张图（按字节算，不是按张数） */
    private const val MEM_MAX_BYTES = 6 * 1024 * 1024

    /** 缓存格式版本。渲染逻辑改了就 +1，旧缓存自动作废 */
    private const val CACHE_VER = 2

    private val mem = object : LruCache<String, Bitmap>(MEM_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(
            evicted: Boolean, key: String, old: Bitmap, new: Bitmap?
        ) {
            // LRU 踢出来的只是内存副本，磁盘那份还在，不用管
        }
    }

    /** 正在生成中的 key，避免同一个区域被重复排队 */
    private val inflight = HashSet<String>()

    // ------------------------------------------------------------------ 路径

    private fun cacheRoot(ctx: Context): File =
        File(ctx.filesDir, "mca-tiles").apply { mkdirs() }

    /** 世界/维度目录 → 一个稳定的缓存子目录名 */
    private fun dirKey(dimDir: File): String {
        val raw = dimDir.absolutePath
        val h = try {
            MessageDigest.getInstance("MD5").digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            raw.hashCode().toString()
        }
        return h.take(16)
    }

    private fun pngOf(ctx: Context, ref: McaWorld.Ref): File =
        File(cacheRoot(ctx), "${dirKey(ref.file.parentFile ?: ref.file)}/" +
            "${ref.rx}.${ref.rz}.v$CACHE_VER.png")

    /**
     * 缓存是否还有效。
     * 源文件的大小或修改时间只要有一个对不上就作废 ——
     * 改过区块后保存，图必须重画，否则用户会看到旧的地形。
     */
    private fun valid(png: File, ref: McaWorld.Ref): Boolean {
        if (!png.isFile) return false
        val f = ref.file
        if (png.length() == 0L) return false
        return png.lastModified() >= f.lastModified()
    }

    // ------------------------------------------------------------------ 取图

    /**
     * 取一个区域的缩略图。
     *
     * @param onRender 需要重新生成时调用，用来刷新进度
     * @return 图，或 null（读不出来/被取消）
     */
    fun get(
        ctx: Context, ref: McaWorld.Ref, onRender: ((String) -> Unit)? = null
    ): Bitmap? {
        val key = "${ref.file.absolutePath}#$CACHE_VER"

        mem.get(key)?.let { return it }

        val png = pngOf(ctx, ref)
        if (valid(png, ref)) {
            val b = BitmapFactory.decodeFile(png.absolutePath)
            if (b != null) {
                mem.put(key, b)
                return b
            }
        }

        synchronized(inflight) {
            if (!inflight.add(key)) return null
        }
        try {
            onRender?.invoke("正在生成 ${ref.name} …")
            val bmp = render(ref)
            if (bmp != null) {
                runCatching {
                    png.parentFile?.mkdirs()
                    png.outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                mem.put(key, bmp)
            }
            return bmp
        } finally {
            synchronized(inflight) { inflight.remove(key) }
        }
    }

    /** 内存里有没有（决定要不要走磁盘/重新生成） */
    fun has(key: String): Boolean = mem.get(key) != null

    private fun keyOf(ref: McaWorld.Ref) = "${ref.file.absolutePath}#$CACHE_VER"

    /** 改过区块后要让这张图作废，否则看到的还是旧地形 */
    fun invalidate(ctx: Context, ref: McaWorld.Ref) {
        mem.remove(keyOf(ref))
        runCatching { pngOf(ctx, ref).delete() }
    }

    fun clearDisk(ctx: Context) {
        runCatching { cacheRoot(ctx).deleteRecursively() }
        mem.evictAll()
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * 把一个区域文件渲染成 512×512 俯视图。
     *
     * 每格一个方块，取该列最高的非空气方块着色。
     * 这是 MCA Selector 和手机端编辑器通用的画法 ——
     * 一眼能看出地形轮廓，而不是把整列压成一团。
     */
    private fun render(ref: McaWorld.Ref): Bitmap? {
        val raw = try {
            ref.file.readBytes()
        } catch (_: Throwable) {
            return null
        }
        if (raw.size < 8192) return null

        val region = try {
            McaEdit.Region(raw)
        } catch (_: Throwable) {
            return null
        }
        val present = region.present()
        if (present.isEmpty()) return null

        val px = IntArray(IMG * IMG)
        paintBackdrop(px)

        var drawn = 0
        for (slot in present) {
            val cx = slot and 31
            val cz = (slot shr 5) and 31
            val c = try {
                region.chunk(slot)
            } catch (_: Throwable) {
                null
            }
            if (c == null) {
                // 读不出来的区块画成"损坏"标记，而不是留黑 ——
                // 留黑会被当成没生成过，用户就不知道这里其实有问题
                markBroken(px, cx * 16, cz * 16)
                continue
            }
            if (McaRender.drawTop(c, px, IMG, cx * 16, cz * 16, 1)) drawn++
            else markEmpty(px, cx * 16, cz * 16)
        }
        // 解析完就把原始数据和 Region 放开，只留图
        val bmp = Bitmap.createBitmap(px, IMG, IMG, Bitmap.Config.ARGB_8888)
        return bmp
    }

    /** 未生成的区域：深灰底 + 棋盘格，一眼区分"没去过"和"去过但是平地" */
    private fun paintBackdrop(px: IntArray) {
        for (z in 0 until IMG) {
            for (x in 0 until IMG) {
                val checker = ((x / 16) + (z / 16)) and 1
                px[z * IMG + x] = if (checker == 0) 0xFF171717.toInt()
                else 0xFF1D1D1D.toInt()
            }
        }
    }

    private fun markEmpty(px: IntArray, ox: Int, oz: Int) {
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                px[(oz + z) * IMG + (ox + x)] = 0xFF232323.toInt()
            }
        }
    }

    private fun markBroken(px: IntArray, ox: Int, oz: Int) {
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                val edge = x == 0 || z == 0 || x == 15 || z == 15
                px[(oz + z) * IMG + (ox + x)] =
                    if (edge) 0xFF7A3B3B.toInt() else 0xFF4A2222.toInt()
            }
        }
    }

    // ------------------------------------------------------------------ 覆盖层

    /**
     * 在区域图上画出选中的格子。
     *
     * 选区单独画在一层，不并进缓存的 PNG ——
     * 否则每勾一个格子就要重画整张图。
     */
    fun drawSelection(
        canvas: Canvas,
        ox: Float, oy: Float, size: Float,
        rx: Int, rz: Int,
        chunkMode: Boolean,
        selReg: Set<Long>,
        selChunk: Set<Long>
    ) {
        val fill = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(90, 66, 165, 245)
        }
        val line = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(210, 66, 165, 245)
        }
        if (chunkMode) {
            if (selChunk.isEmpty()) return
            val cell = size / 32f
            val top = packChunk(rx, rz, 0, 0) and (0xFFFFFFFFL shl 32)
            for (key in selChunk) {
                if (key and (0xFFFFFFFFL shl 32) != top) continue
                val cx = ((key ushr 16) and 0xFFFFL).toInt()
                val cz = (key and 0xFFFFL).toInt()
                val x = ox + cx * cell
                val y = oy + cz * cell
                canvas.drawRect(x, y, x + cell, y + cell, fill)
                canvas.drawRect(x, y, x + cell, y + cell, line)
            }
        } else {
            if (!selReg.contains(packRegion(rx, rz))) return
            canvas.drawRect(ox, oy, ox + size, oy + size, fill)
            canvas.drawRect(ox, oy, ox + size, oy + size, line)
        }
    }

    /**
     * 把坐标压成 long —— 选区可能有成千上万个格子，
     * 每个都建一个 Pair 对象在这台设备上吃不消。
     *
     * 坐标可能是负数（r.-1.0.mca），所以每一段都要先掩成 16 位再移，
     * 否则 -1 会污染上面所有的位段。
     */
    fun packRegion(rx: Int, rz: Int): Long =
        ((rx.toLong() and 0xFFFFL) shl 16) or (rz.toLong() and 0xFFFFL)

    fun unpackRegion(key: Long): Pair<Int, Int> =
        sign16(((key ushr 16) and 0xFFFFL).toInt()) to
            sign16((key and 0xFFFFL).toInt())

    fun packChunk(rx: Int, rz: Int, cx: Int, cz: Int): Long =
        ((rx.toLong() and 0xFFFFL) shl 48) or
            ((rz.toLong() and 0xFFFFL) shl 32) or
            ((cx.toLong() and 0xFFFFL) shl 16) or
            (cz.toLong() and 0xFFFFL)

    fun unpackChunk(key: Long): Pair<Int, Int> =
        ((key ushr 16) and 0xFFFFL).toInt() to (key and 0xFFFFL).toInt()

    fun chunkRegionOf(key: Long): Pair<Int, Int> =
        sign16(((key ushr 48) and 0xFFFFL).toInt()) to
            sign16(((key ushr 32) and 0xFFFFL).toInt())

    private fun sign16(v: Int): Int = if (v >= 0x8000) v - 0x10000 else v
}
