package com.kongbai.modmigrator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import java.io.DataInputStream
import java.io.DataOutputStream
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
 *
 * 除了图，还顺手存一份「元数据副产物」（见 [Meta]）。
 * 渲染时本来就要把每个区块解压解析一遍，顺路把玩家停留时长记下来几乎不要钱；
 * 反过来，如果筛选时再去读一遍，每个区域都要几秒 —— 这两件事必须一起做。
 */
object McaTiles {

    /** 一个区域 = 32×32 个区块，每个区块 16×16 格，所以整图 512×512 */
    const val IMG = 512

    /** 内存里最多留多少张图（按字节算，不是按张数） */
    private const val MEM_MAX_BYTES = 6 * 1024 * 1024

    /** 缓存格式版本。渲染逻辑或元数据字段改了就 +1，旧缓存自动作废 */
    private const val CACHE_VER = 3

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

    // ------------------------------------------------------------------ 元数据

    /**
     * 一个区域的区块级元数据，渲染时的副产物。
     *
     * · [inhabited] 玩家在这个区块里累计待过多少 tick（20 tick ≈ 1 秒）。
     *   这是判断"这块地方有没有人认真玩过"的唯一依据 ——
     *   想让地形按新版本重新生成，删的就是这个值很小的区块。
     * · [updated] 最后写入时间（秒）。用来找"很久没去过的角落"。
     * · [broken] 解压或解析失败的槽位。这类区块会让游戏一走近就崩，
     *   必须和"没生成过"区分开 —— 所以图上画成红色而不是留黑。
     *
     * 三个数组都按槽位下标（cz * 32 + cx）对齐，长度固定 1024。
     * 用原生数组而不是 Map：一个区域上千个区块，对象开销在这台设备上很明显。
     */
    class Meta(
        val slots: IntArray,
        val inhabited: LongArray,
        val updated: IntArray,
        val broken: IntArray
    ) {
        companion object {
            val EMPTY = Meta(IntArray(0), LongArray(0), IntArray(0), IntArray(0))
        }
    }

    /** 元数据也放内存里，筛选用到它时不能再碰磁盘 */
    private val metaMem = HashMap<String, Meta>()

    // ------------------------------------------------------------------ 路径

    private fun cacheRoot(ctx: Context): File =
        File(ctx.filesDir, "mca-tiles").apply { mkdirs() }

    /** 世界/维度目录 → 一个稳定的缓存子目录名 */
    private fun dirKey(dimDir: File): String {
        val raw = dimDir.absolutePath
        val h = try {
            MessageDigest.getInstance("MD5").digest(raw.toByteArray())
                // 必须掩成无符号：Byte 是 -128..127，直接格式化负数
                // 会变成 ffffff80 这种 8 位一串，长度都不一样
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (_: Throwable) {
            java.lang.Integer.toHexString(raw.hashCode())
        }
        return h.take(16)
    }

    private fun pngOf(ctx: Context, ref: McaWorld.Ref): File =
        File(cacheRoot(ctx), "${dirKey(ref.file.parentFile ?: ref.file)}/" +
            "${ref.rx}.${ref.rz}.v$CACHE_VER.png")

    private fun metaOf(ctx: Context, ref: McaWorld.Ref): File =
        File(cacheRoot(ctx), "${dirKey(ref.file.parentFile ?: ref.file)}/" +
            "${ref.rx}.${ref.rz}.v$CACHE_VER.meta")

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
     * @return 图，或 null（读不出来/正被别的线程生成）
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
                // 图是旧的，元数据大概率也在，顺手读回来
                ensureMeta(ctx, ref)
                return b
            }
        }

        synchronized(inflight) {
            if (!inflight.add(key)) return null
        }
        try {
            onRender?.invoke("正在生成 ${ref.name} …")
            val out = render(ctx, ref)
            val bmp = out?.first
            if (bmp != null) {
                runCatching {
                    png.parentFile?.mkdirs()
                    png.outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                mem.put(key, bmp)
            }
            val m = out?.second
            if (m != null) {
                metaMem[key] = m
                runCatching { writeMeta(metaOf(ctx, ref), m) }
            }
            return bmp
        } finally {
            synchronized(inflight) { inflight.remove(key) }
        }
    }

    /** 只看内存里有没有（决定要不要走磁盘或重新生成，不触发渲染） */
    fun peek(ref: McaWorld.Ref): Bitmap? = mem.get(keyOf(ref))

    /**
     * 只在内存里找元数据，找不到就返回 null，绝不去读文件。
     *
     * 选择、框选这些操作跑在 UI 线程上，而解析一个区域要几秒 ——
     * 这里一旦顺手去解析，用户点一下就得卡住。
     * 所以调用方拿不到数据时应当按"未知"处理，
     * 需要精确结果的操作（比如筛选）另开后台线程用 [meta]。
     */
    fun metaCached(ref: McaWorld.Ref): Meta? = metaMem[keyOf(ref)]

    /**
     * 取一个区域的元数据。图已经缓存过时几乎零成本；
     * 没缓存过才会去解析（慢，所以要放在后台线程）。
     */
    fun meta(ctx: Context, ref: McaWorld.Ref): Meta {
        val key = keyOf(ref)
        metaMem[key]?.let { return it }
        ensureMeta(ctx, ref)
        metaMem[key]?.let { return it }
        // 磁盘上也没有 —— 只能解析一遍。这一步要几秒，调用方必须在后台。
        val m = render(ctx, ref)?.second
        if (m != null) metaMem[key] = m
        return m ?: Meta.EMPTY
    }

    private fun ensureMeta(ctx: Context, ref: McaWorld.Ref) {
        val key = keyOf(ref)
        if (metaMem.containsKey(key)) return
        val f = metaOf(ctx, ref)
        if (!f.isFile) return
        val m = try {
            DataInputStream(f.inputStream().buffered()).use { readMeta(it) }
        } catch (_: Throwable) {
            null
        }
        if (m != null) metaMem[key] = m
    }

    private fun keyOf(ref: McaWorld.Ref) = "${ref.file.absolutePath}#$CACHE_VER"

    /** 改过区块后要让这张图作废，否则看到的还是旧地形 */
    fun invalidate(ctx: Context, ref: McaWorld.Ref) {
        val k = keyOf(ref)
        mem.remove(k)
        metaMem.remove(k)
        runCatching { pngOf(ctx, ref).delete() }
        runCatching { metaOf(ctx, ref).delete() }
    }

    fun clearDisk(ctx: Context) {
        runCatching { cacheRoot(ctx).deleteRecursively() }
        mem.evictAll()
        metaMem.clear()
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * 把一个区域文件渲染成 512×512 俯视图，并顺路收集区块元数据。
     *
     * 每格一个方块，取该列最高的非空气方块着色。
     * 这是 MCA Selector 和手机端编辑器通用的画法 ——
     * 一眼能看出地形轮廓，而不是把整列压成一团。
     *
     * 图和元数据必须一次算完：两者都要把每个区块解压解析一遍，
     * 分开做等于把最贵的那一步做两遍。
     */
    private fun render(ctx: Context, ref: McaWorld.Ref): Pair<Bitmap, Meta>? {
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

        val inhabited = LongArray(1024)
        val updated = IntArray(1024)
        val brokenList = ArrayList<Int>()

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
                brokenList.add(slot)
                markBroken(px, cx * 16, cz * 16)
                continue
            }
            inhabited[slot] = inhabitedOf(c)
            updated[slot] = updatedOf(c)
            if (McaRender.drawTop(c, px, IMG, cx * 16, cz * 16, 1)) drawn++
            else markEmpty(px, cx * 16, cz * 16)
        }

        val meta = Meta(
            present.toIntArray(), inhabited, updated, brokenList.toIntArray()
        )
        // 解析完就把原始数据和 Region 放开，只留图
        val bmp = Bitmap.createBitmap(px, IMG, IMG, Bitmap.Config.ARGB_8888)
        return bmp to meta
    }

    /**
     * 玩家在这个区块累计待过多少 tick。
     *
     * 1.13 起这个字段在根标签上，更早的版本在 Level 子标签里，两种都要认。
     * 取不到就当 0 —— 标成"没人待过"比标成"待过很久"安全，
     * 用户是按这个筛"可以放心删的区块"的，宁可少删也不能误删。
     */
    private fun inhabitedOf(c: com.viaversion.nbt.tag.CompoundTag): Long {
        val root = c.getLong("InhabitedTime", -1L)
        if (root >= 0) return root
        val lvl = c.get("Level")
        if (lvl is com.viaversion.nbt.tag.CompoundTag) {
            val v = lvl.getLong("InhabitedTime", 0L)
            if (v > 0) return v
        }
        return 0L
    }

    private fun updatedOf(c: com.viaversion.nbt.tag.CompoundTag): Int {
        val root = c.getInt("LastUpdate", 0)
        if (root != 0) return root
        val lvl = c.get("Level")
        if (lvl is com.viaversion.nbt.tag.CompoundTag) {
            return lvl.getInt("LastUpdate", 0)
        }
        return 0
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

    // ------------------------------------------------------------------ 元数据落盘

    private fun writeMeta(f: File, m: Meta) {
        f.parentFile?.mkdirs()
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(CACHE_VER)
            o.writeInt(m.slots.size)
            for (s in m.slots) {
                o.writeInt(s)
                o.writeLong(m.inhabited[s])
                o.writeInt(m.updated[s])
            }
            o.writeInt(m.broken.size)
            for (s in m.broken) o.writeInt(s)
        }
    }

    private fun readMeta(i: DataInputStream): Meta? {
        val ver = i.readInt()
        if (ver != CACHE_VER) return null
        val n = i.readInt()
        if (n < 0 || n > 1024) return null
        val slots = IntArray(n)
        val inhabited = LongArray(1024)
        val updated = IntArray(1024)
        for (k in 0 until n) {
            val s = i.readInt()
            if (s < 0 || s >= 1024) return null
            slots[k] = s
            inhabited[s] = i.readLong()
            updated[s] = i.readInt()
        }
        val nb = i.readInt()
        if (nb < 0 || nb > 1024) return null
        val broken = IntArray(nb)
        for (k in 0 until nb) broken[k] = i.readInt()
        return Meta(slots, inhabited, updated, broken)
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
