package com.kongbai.modmigrator

import com.viaversion.nbt.io.NBTIO
import com.viaversion.nbt.limiter.TagLimiter
import com.viaversion.nbt.tag.CompoundTag
import com.viaversion.nbt.tag.ListTag
import com.viaversion.nbt.tag.LongArrayTag
import com.viaversion.nbt.tag.Tag
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Minecraft 区域文件（.mca）与区块方块读写。
 *
 * 说明一下取舍：文件层（扇区表、压缩格式）格式固定且简单，这里自己实现，
 * 因为它是纯字节操作，出了问题能直接读到。
 * 而真正容易出错的是 **方块索引的打包位运算** ——
 * 1.16 之后 block_states.data 用 long[] 紧凑打包，
 * 每个版本每格占的位数随调色板大小变化，索引顺序也变过，
 * 这也是各库实现差异最大的地方。这部分同样自己写，
 * 目的是可读、可调，而不是依赖一个对不上 DataVersion 就静默出错的黑盒。
 *
 * 依赖只有已融合的 ViaNBT，不引入新的二进制依赖。
 */
object McaEdit {

    // ------------------------------------------------------------------
    // 区域文件层
    // ------------------------------------------------------------------

    private const val SECTOR = 4096
    private const val SLOTS = 1024
    private const val HEADER = SECTOR * 2

    class Region(raw: ByteArray) {

        /** 每个槽位压缩后的原始负载；槽位为空则是 null */
        private val payloads = arrayOfNulls<ByteArray>(SLOTS)
        private val comps = ByteArray(SLOTS)

        init {
            // 头部两张表各占一个扇区，文件比这还小就肯定不是有效的区域文件。
            // 不挡住的话下面按槽位读表会直接越界。
            if (raw.size >= HEADER) {
                for (i in 0 until SLOTS) {
                    val e = int24(raw, i * 4)
                    val cnt = raw[i * 4 + 3].toInt() and 0xFF
                    if (e == 0 || cnt == 0) continue
                    val pos = e * SECTOR
                    if (pos + 5 > raw.size) continue
                    val len = int32(raw, pos)
                    // length 字段 = 压缩数据长度 + 1（含压缩类型那一字节）
                    if (len < 2 || pos + 4 + len > raw.size) continue
                    comps[i] = raw[pos + 4]
                    payloads[i] = raw.copyOfRange(pos + 5, pos + 4 + len)
                }
            }
        }

        /** 已存在的槽位列表 */
        fun present(): List<Int> = (0 until SLOTS).filter { payloads[it] != null }

        fun chunk(slot: Int): CompoundTag? {
            val p = payloads[slot] ?: return null
            val bytes = inflate(comps[slot], p) ?: return null
            return NBTIO.reader()
                .tagLimiter(TagLimiter.noop())
                .read(ByteArrayInputStream(bytes)) as? CompoundTag
        }

        fun put(slot: Int, tag: CompoundTag) {
            comps[slot] = 2
            payloads[slot] = deflate(tag)
        }

        /**
         * 重新打包成 .mca 字节。
         * 没动过的区块沿用原始压缩字节，不重新压缩 ——
         * 避免"打开一次再保存，整个文件都被改写"的情况。
         */
        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(ByteArray(HEADER))
            val offsets = IntArray(SLOTS)
            val counts = ByteArray(SLOTS)
            // 数据区从 sector 2 开始（0、1 是两张表）
            var sector = 2
            for (i in 0 until SLOTS) {
                val p = payloads[i] ?: continue
                val total = 5 + p.size
                val need = (total + SECTOR - 1) / SECTOR
                offsets[i] = sector
                counts[i] = need.toByte()
                val buf = ByteArray(need * SECTOR)
                val lenField = p.size + 1
                buf[0] = (lenField ushr 24).toByte()
                buf[1] = (lenField ushr 16).toByte()
                buf[2] = (lenField ushr 8).toByte()
                buf[3] = lenField.toByte()
                buf[4] = comps[i]
                System.arraycopy(p, 0, buf, 5, p.size)
                out.write(buf)
                sector += need
            }
            val res = out.toByteArray()
            for (i in 0 until SLOTS) {
                val o = offsets[i]
                if (o == 0) continue
                res[i * 4] = (o ushr 16).toByte()
                res[i * 4 + 1] = (o ushr 8).toByte()
                res[i * 4 + 2] = o.toByte()
                res[i * 4 + 3] = counts[i]
            }
            return res
        }

        private fun int24(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 16) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                (b[o + 2].toInt() and 0xFF)

        private fun int32(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 24) or
                ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or
                (b[o + 3].toInt() and 0xFF)
    }

    private fun inflate(comp: Byte, data: ByteArray): ByteArray? = try {
        when (comp.toInt()) {
            1 -> GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            2 -> InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            3 -> data
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun deflate(tag: CompoundTag): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = DeflaterOutputStream(bos)
        NBTIO.writer().write(d, tag)
        d.finish()
        return bos.toByteArray()
    }

    // ------------------------------------------------------------------
    // 区块方块层
    // ------------------------------------------------------------------

    /**
     * 一个 section（16×16×16）。
     *
     * 1.18 之后方块数据在顶层的 `sections` 里，调色板是
     * `block_states.palette` / `block_states.data`；
     * 1.16–1.17 则藏在 `Level.Sections` 下，字段名首字母大写且没有 block_states 这层。
     * 这里两种都认，用 key 名区分。
     */
    class Sec(val y: Int, val tag: CompoundTag) {
        val holder: CompoundTag = tag.getCompoundTag("block_states") ?: tag
        val paletteKey: String = if (tag.getCompoundTag("block_states") != null) "palette" else "Palette"
        val dataKey: String = if (tag.getCompoundTag("block_states") != null) "data" else "BlockStates"
    }

    fun sections(chunk: CompoundTag): List<Sec> {
        val out = ArrayList<Sec>()
        val modern = chunk.getListTag("sections")
        if (modern != null) {
            for (t in modern.getValue()) {
                val c = t as? CompoundTag ?: continue
                val y = c.getByteTag("Y")?.getValue()?.toInt() ?: continue
                out.add(Sec(y, c))
            }
        } else {
            val legacy = chunk.getCompoundTag("Level")?.getListTag("Sections")
            if (legacy != null) {
                for (t in legacy.getValue()) {
                    val c = t as? CompoundTag ?: continue
                    val y = c.getByteTag("Y")?.getValue()?.toInt() ?: continue
                    out.add(Sec(y, c))
                }
            }
        }
        // 按高度排序，界面上从上往下看才对
        out.sortByDescending { it.y }
        return out
    }

    fun paletteNames(sec: Sec): List<String> {
        val lt = sec.holder.getListTag(sec.paletteKey) ?: return emptyList()
        val out = ArrayList<String>()
        for (t in lt.getValue()) {
            val c = t as? CompoundTag
            out.add(c?.getString("Name") ?: "minecraft:air")
        }
        return out
    }

    fun dataArray(sec: Sec): LongArray? =
        sec.holder.getLongArrayTag(sec.dataKey)?.getValue()

    /** 调色板大小为 n 时，每格占多少位（Minecraft 最小值是 4） */
    fun bitsFor(n: Int): Int {
        if (n <= 1) return 4
        return maxOf(4, 32 - (n - 1).countLeadingZeroBits())
    }

    /** 区块内的线性下标：1.16 之后是 y 优先 */
    fun indexOf(x: Int, y: Int, z: Int): Int = (y * 16 + z) * 16 + x

    fun readIndex(data: LongArray?, bits: Int, i: Int): Int {
        val d = data ?: return 0
        if (d.isEmpty()) return 0
        val per = 64 / bits
        val li = i / per
        if (li >= d.size) return 0
        val off = (i % per) * bits
        return ((d[li] ushr off) and ((1L shl bits) - 1L)).toInt()
    }

    fun blockAt(sec: Sec, x: Int, y: Int, z: Int): String {
        val names = paletteNames(sec)
        if (names.isEmpty()) return "minecraft:air"
        if (names.size == 1) return names[0]
        val bits = bitsFor(names.size)
        val v = readIndex(dataArray(sec), bits, indexOf(x, y, z))
        return if (v < names.size) names[v] else "minecraft:air"
    }

    /**
     * 把某格设成指定方块。
     *
     * ⚠️ 调色板可能因此变长，导致每格位数变化 ——
     * 位数一变，整个 long[] 的布局就全变了，必须**全量重编码**，
     * 只改目标那几位会把后面所有格子的方块打乱（表现为整片地形错乱）。
     */
    fun setBlock(sec: Sec, x: Int, y: Int, z: Int, name: String) {
        @Suppress("UNCHECKED_CAST")
        val lt = sec.holder.getListTag(sec.paletteKey) as? ListTag<Tag> ?: return
        val oldNames = paletteNames(sec)
        val oldBits = bitsFor(oldNames.size)
        val oldData = dataArray(sec)

        var idx = oldNames.indexOf(name)
        if (idx < 0) {
            val nc = CompoundTag()
            nc.putString("Name", name)
            lt.add(nc)
            idx = oldNames.size
        }

        val newSize = oldNames.size + if (idx == oldNames.size) 1 else 0
        val newBits = bitsFor(newSize)
        val target = indexOf(x, y, z)

        if (newBits == oldBits && oldData != null &&
            oldData.size >= (4096 + (64 / newBits) - 1) / (64 / newBits)
        ) {
            // 位数没变：只改目标位
            val per = 64 / newBits
            val li = target / per
            val off = (target % per) * newBits
            val mask = (1L shl newBits) - 1L
            oldData[li] = (oldData[li] and (mask shl off).inv()) or (idx.toLong() shl off)
            sec.holder.put(sec.dataKey, LongArrayTag(oldData))
        } else {
            // 位数变了：全量重编码
            val per = 64 / newBits
            val need = (4096 + per - 1) / per
            val arr = LongArray(need)
            for (i in 0 until 4096) {
                val v = if (i == target) idx else {
                    val ov = readIndex(oldData, oldBits, i)
                    if (ov < oldNames.size) ov else 0
                }
                arr[i / per] = arr[i / per] or ((v.toLong() and ((1L shl newBits) - 1L)) shl ((i % per) * newBits))
            }
            sec.holder.put(sec.dataKey, LongArrayTag(arr))
        }
    }
}
