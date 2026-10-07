package com.kongbai.modmigrator

import com.viaversion.nbt.io.NBTIO
import com.viaversion.nbt.limiter.TagLimiter
import com.viaversion.nbt.tag.CompoundTag
import com.viaversion.nbt.tag.ListTag
import com.viaversion.nbt.tag.LongArrayTag
import com.viaversion.nbt.tag.NumberTag
import com.viaversion.nbt.tag.StringTag
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

        /**
         * 读一个区块的 NBT。
         *
         * ⚠️ 这里是「区块编辑器一直提示空」的根因：
         * ViaNBT 的 `named` **默认是 false**，而它的 Javadoc 明确写着
         * "the standard format is always named, so make sure to call named()"。
         * 区域文件里每个区块的 NBT 根标签是**带空名字**的（0x0A 00 00 ...），
         * 不调 named() 就会把「名字长度」这两个字节当成第一个 tag 的 id，
         * 读到 0x00 = TAG_End → 复合标签**当场结束** → 得到一个空 compound。
         * 表现就是：不报错、不崩溃，但 sections 一个都没有 → 画不出东西 → "空的"。
         *
         * NBT 查看器读 level.dat 走的是另一处（NbtFile），那边有 .named()，
         * 所以 level.dat 一直能开，唯独区块不行 —— 正好对得上现象。
         *
         * 两种都试一遍：以带名字为准，读出来是空的再按不带名字试，
         * 万一遇到哪个版本真的不带名字也不会全军覆没。
         */
        fun chunk(slot: Int): CompoundTag? {
            val p = payloads[slot] ?: return null
            val bytes = inflate(comps[slot], p) ?: return null
            val named = readNbt(bytes, true)
            if (named != null && !named.isEmpty()) return named
            val flat = readNbt(bytes, false)
            if (flat != null && !flat.isEmpty()) return flat
            return named ?: flat
        }

        private fun readNbt(bytes: ByteArray, named: Boolean): CompoundTag? = try {
            val r = NBTIO.reader().tagLimiter(TagLimiter.noop())
            if (named) r.named()
            r.read(ByteArrayInputStream(bytes)) as? CompoundTag
        } catch (_: Throwable) {
            null
        }

        /**
         * 删掉一个区块（整块抹掉，不是清空方块）。
         *
         * 渲染视图那边要"删除区块"来做瘦身/重置地形，
         * 所以这里给出的是**真正的移除**：槽位表清零、负载丢弃，
         * 重新打包时该槽位不会再写出数据。
         * 抹掉之后游戏再次进入该区域会按当前版本重新生成地形，
         * 这是删除区块的预期行为，不是数据损坏。
         *
         * @return 该槽位原来是否有内容
         */
        fun remove(slot: Int): Boolean {
            if (slot !in 0 until SLOTS) return false
            val had = payloads[slot] != null
            payloads[slot] = null
            comps[slot] = 0
            return had
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
            // 4 = LZ4。⚠️ 是 lz4-java 的**块**格式，不是标准 LZ4 frame：
            // Minecraft 用 LZ4BlockOutputStream 写的，必须配对用
            // LZ4BlockInputStream，用 frame 那个流会直接解出垃圾。
            // 24w04a 起可在 server.properties 里开，新存档很常见；
            // 之前缺这一支，遇到 LZ4 的区块就是「读不出数据」。
            4 -> net.jpountz.lz4.LZ4BlockInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun deflate(tag: CompoundTag): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = DeflaterOutputStream(bos)
        // 和读同理：区块 NBT 的根标签带空名字，不加 named() 写出来的
        // 是游戏认不出的格式（不报错，只是整块区块被当成损坏）。
        NBTIO.writer().named().write(d, tag)
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
    /**
     * ⚠️ Y 在 1.18+ **既可能是 Byte 也可能是 Int**（Minecraft Wiki 原文：
     * "[Byte] Y ... Can also be an INT in 1.18+"）。
     * 之前只取 ByteTag，遇到 IntTag 直接 continue，
     * 于是**所有 section 被跳过** → 列表为空 →
     * 界面弹"这个区块里没有方块数据（可能是空区块）"。
     * 这正是"点了全提示无数据"的主因。
     */
    class Sec(var y: Int, val tag: CompoundTag) {
        val holder: CompoundTag = tag.getCompoundTag("block_states") ?: tag
        val paletteKey: String = if (tag.getCompoundTag("block_states") != null) "palette" else "Palette"
        val dataKey: String = if (tag.getCompoundTag("block_states") != null) "data" else "BlockStates"
        /**
         * 写回新方块时用哪个字段名。
         * 26.3 snap 起 `Name` 改叫 `id`，照旧写 Name 会让新版游戏读不出来。
         * 按当前调色板实际用的字段来回写，不动既有结构。
         */
        val nameKey: String = when {
            tag.getCompoundTag("block_states") == null -> "Name"
            paletteOf(this)?.getValue()?.any { (it as? CompoundTag)?.contains("id") == true } ?: false -> "id"
            else -> "Name"
        }
    }

    /** 取 section 的调色板原始 list（可能是 compound 列表，也可能是字符串列表） */
    fun paletteOf(sec: Sec): com.viaversion.nbt.tag.ListTag<*>? = sec.holder.getListTag(sec.paletteKey)

    /** 取 section 的 Y：Byte / Short / Int 都认 */
    private fun secY(c: CompoundTag): Int =
        (c.get("Y") as? NumberTag)?.getValue()?.toInt() ?: Int.MIN_VALUE

    fun sections(chunk: CompoundTag): List<Sec> {
        val out = ArrayList<Sec>()
        val modern = chunk.getListTag("sections")
        if (modern != null) {
            for (t in modern.getValue()) {
                val c = t as? CompoundTag ?: continue
                val y = secY(c)
                out.add(Sec(y, c))
            }
        } else {
            val legacy = chunk.getCompoundTag("Level")?.getListTag("Sections")
            if (legacy != null) {
                for (t in legacy.getValue()) {
                    val c = t as? CompoundTag ?: continue
                    val y = secY(c)
                    out.add(Sec(y, c))
                }
            }
        }
        // Y 全都读不到时，按 chunk 的 yPos 依次推（1.18+ 有 yPos）。
        // 读不到就丢弃 section 的话，整个区块会被判成"没有方块数据"。
        if (out.all { it.y == Int.MIN_VALUE }) {
            val base = chunk.getIntTag("yPos")?.getValue()
                ?: chunk.getCompoundTag("Level")?.getIntTag("yPos")?.getValue()
            if (base != null) out.forEachIndexed { i, sec -> sec.y = base + i }
            else out.forEachIndexed { i, sec -> sec.y = i }
        }
        out.removeAll { it.y == Int.MIN_VALUE }
        // 按高度排序，界面上从上往下看才对
        out.sortByDescending { it.y }
        return out
    }

    /**
     * 调色板里的方块名。
     *
     * ⚠️ 三种写法都要认，认不全就会整段渲染成空气（看上去"没数据"）：
     *   · 1.18 ~ 26.2：compound 里的 `Name`
     *   · 26.3 snap 起：`Name`→`id`、`Properties`→`properties`
     *   · 26.3 snap 起：调色板**可以是字符串列表**，不再是 compound 列表
     *   · 26.3 snap 起：compound 里可以只有一个空 key：`{"": "minecraft:stone"}`
     */
    fun paletteNames(sec: Sec): List<String> {
        val lt = sec.holder.getListTag(sec.paletteKey) ?: return emptyList()
        val out = ArrayList<String>()
        for (t in lt.getValue()) {
            when (t) {
                is StringTag -> out.add(t.getValue())
                is CompoundTag -> {
                    val n = (t.get("Name") as? StringTag)?.getValue()
                        ?: (t.get("id") as? StringTag)?.getValue()
                        ?: (t.get("") as? StringTag)?.getValue()
                    out.add(n ?: "minecraft:air")
                }
                else -> out.add("minecraft:air")
            }
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
            nc.putString(sec.nameKey, name)
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

/**
 * 版本适配接口（提前留好）。
 *
 * 现在只有一套实现（1.18+ 的 `sections` 写法，外加旧版 `Level.Sections` 兜底），
 * 但存档格式每次大改都会动到调色板和打包位宽，
 * 到时候只要往 [McaVersions.register] 里挂一个新的 [Adapter] 就行，
 * 不用再改解析主流程。
 *
 * 之所以现在就抽出这层：26.3 已经改过一次调色板字段名
 * （`Name` → `id`、调色板可以是字符串列表），
 * 这类改动散落在解析代码里极难收敛。
 */
interface McaVersionAdapter {
    /** 这个实现能处理的 DataVersion 区间（闭区间） */
    fun supports(dataVersion: Int): Boolean

    fun sections(chunk: com.viaversion.nbt.tag.CompoundTag): List<McaEdit.Sec>

    fun paletteNames(sec: McaEdit.Sec): List<String>

    /** 写回时用哪个字段名，null = 沿用读到的那个 */
    fun paletteIdKey(): String? = null
}

object McaVersions {

    private val impls = ArrayList<McaVersionAdapter>()

    /** 默认实现：走 McaEdit 现有逻辑，覆盖目前已知的所有写法 */
    val DEFAULT: McaVersionAdapter = object : McaVersionAdapter {
        override fun supports(dataVersion: Int) = true
        override fun sections(chunk: com.viaversion.nbt.tag.CompoundTag) =
            McaEdit.sections(chunk)
        override fun paletteNames(sec: McaEdit.Sec) = McaEdit.paletteNames(sec)
    }

    init { impls.add(DEFAULT) }

    /** 将来支持新版本时挂进来。后挂的优先匹配 */
    @Synchronized
    fun register(a: McaVersionAdapter) { impls.add(0, a) }

    @Synchronized
    fun forVersion(dataVersion: Int): McaVersionAdapter =
        impls.firstOrNull { it.supports(dataVersion) } ?: DEFAULT
}
