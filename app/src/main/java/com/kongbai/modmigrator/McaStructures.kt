package com.kongbai.modmigrator

import com.viaversion.nbt.tag.CompoundTag

/**
 * 在已有存档里找结构。
 *
 * # 两条路，优先走准的那条
 *
 * ① **结构索引**：1.13 起每个区块的 NBT 里存了 `Structures.References`，
 *    记录哪些结构的起点落在这个区块 —— 这是权威数据，坐标精确、名字准确。
 *
 * ② **特征方块**：索引不存在时（1.12 及更早、或某些模组维度）才回退到
 *    按调色板里的方块认。这条只能给出"这里有这类结构的痕迹"，
 *    坐标是**区块级**的，不作为精确起点。
 *
 * 之所以要分主次：特征方块的误报无法避免 —— 玩家自己拿黑石盖的房子
 * 和堡垒遗迹用的是同一种方块。所以只要索引在，就绝不拿特征去猜。
 */
object McaStructures {

    /** 找到的一处结构 */
    class Hit(
        /** 结构名（原始 id，如 minecraft:stronghold） */
        val id: String,
        /** 中文名，认不出来就退回原始 id */
        val label: String,
        /** 区域文件坐标 */
        val rx: Int,
        val rz: Int,
        /** 该结构起点在区域内的区块下标 0..31 */
        val cx: Int,
        val cz: Int,
        /** true = 来自结构索引（可信）；false = 特征方块推断 */
        val exact: Boolean
    ) {
        /** 世界区块坐标 */
        val worldChunkX get() = rx * 32 + cx
        val worldChunkZ get() = rz * 32 + cz
    }

    /** 结构 id → 中文名 */
    private val LABELS: Map<String, String> = mapOf(
        "minecraft:stronghold" to "要塞",
        "minecraft:village" to "村庄",
        "minecraft:mineshaft" to "废弃矿井",
        "minecraft:fortress" to "下界要塞",
        "minecraft:endcity" to "末地城",
        "minecraft:monument" to "海底神殿",
        "minecraft:mansion" to "林地府邸",
        "minecraft:shipwreck" to "沉船",
        "minecraft:ocean_ruin" to "海底废墟",
        "minecraft:buried_treasure" to "埋藏的宝藏",
        "minecraft:desert_pyramid" to "沙漠神殿",
        "minecraft:jungle_pyramid" to "丛林神庙",
        "minecraft:swamp_hut" to "沼泽小屋",
        "minecraft:igloo" to "雪屋",
        "minecraft:pillager_outpost" to "掠夺者前哨站",
        "minecraft:bastion_remnant" to "堡垒遗迹",
        "minecraft:nether_fossil" to "下界化石",
        "minecraft:ruined_portal" to "废弃传送门",
        "minecraft:ancient_city" to "深暗古城",
        "minecraft:trail_ruins" to "古迹废墟",
        "minecraft:trial_chambers" to "试炼密室"
    )

    /** 特征方块 → 结构 id。
     *  只收置信度高的：宁可漏，不可把玩家自己盖的房子报成遗迹。 */
    private val SIGNATURES: List<Pair<String, String>> = listOf(
        "minecraft:end_portal_frame" to "minecraft:stronghold",
        "minecraft:prismarine" to "minecraft:monument",
        "minecraft:prismarine_bricks" to "minecraft:monument",
        "minecraft:sea_lantern" to "minecraft:monument",
        "minecraft:nether_bricks" to "minecraft:fortress",
        "minecraft:gilded_blackstone" to "minecraft:bastion_remnant",
        "minecraft:polished_blackstone_bricks" to "minecraft:bastion_remnant",
        "minecraft:purpur_block" to "minecraft:endcity",
        "minecraft:sculk_sensor" to "minecraft:ancient_city",
        "minecraft:sculk_shrieker" to "minecraft:ancient_city",
        "minecraft:sculk_catalyst" to "minecraft:ancient_city",
        "minecraft:trial_spawner" to "minecraft:trial_chambers",
        "minecraft:vault" to "minecraft:trial_chambers"
    )

    fun labelOf(id: String): String = LABELS[id] ?: id.substringAfter(':').replace('_', ' ')

    /**
     * 扫一个区域文件。
     *
     * 1024 个区块全展开不是小开销，调用方必须放到后台线程。
     */
    fun scan(region: McaEdit.Region, rx: Int, rz: Int): List<Hit> {
        val out = ArrayList<Hit>()
        for (slot in region.present()) {
            val cx = slot and 31
            val cz = (slot shr 5) and 31
            val c = try {
                region.chunk(slot)
            } catch (_: Throwable) {
                null
            } ?: continue

            val fromIndex = references(c)
            if (fromIndex.isNotEmpty()) {
                // 权威数据：结构起点就落在这个区块
                for (id in fromIndex) {
                    out.add(Hit(id, labelOf(id), rx, rz, cx, cz, true))
                }
                continue
            }

            // 回退：按调色板里的方块认
            val sig = signature(c)
            if (sig != null) {
                out.add(Hit(sig, labelOf(sig), rx, rz, cx, cz, false))
            }
        }
        return out
    }

    /**
     * 读 `Structures.References`。
     *
     * 值是 long[]，每个 long 编码一个区块坐标（高 32 位 x、低 32 位 z，有符号）。
     * 只取名字，坐标用当前区块 —— 起点就在这里，够用了。
     */
    private fun references(c: CompoundTag): List<String> {
        val out = ArrayList<String>()
        runCatching {
            val st = c.getCompoundTag("Structures")
                ?: c.getCompoundTag("structures")
                ?: return out
            val refs = st.getCompoundTag("References") ?: st.getCompoundTag("references")
            if (refs != null) {
                // CompoundTag 没有直接列 key 的方法，取底层 map 的键
                for (k in refs.value.keys) out.add(k)
            }
            // 1.13~1.15 是 Starts/Ranges 那套，取不到名字就放弃，交给特征方块
        }
        return out
    }

    /** 按调色板认结构。命中多个时返回第一个。 */
    private fun signature(c: CompoundTag): String? {
        val secs = try {
            McaEdit.sections(c)
        } catch (_: Throwable) {
            return null
        }
        if (secs.isEmpty()) return null
        val names = HashSet<String>()
        // 不用把所有 section 都展开：结构特征在上层，往下挖是浪费
        for (s in secs) {
            runCatching {
                for (n in McaEdit.paletteNames(s)) names.add(n)
            }
            if (names.size > 64) break
        }
        for ((block, id) in SIGNATURES) {
            if (names.contains(block)) return id
        }
        return null
    }

    /** 把结果按结构归类，方便 UI 列出"找到哪些、各几处" */
    fun group(hits: List<Hit>): List<Pair<String, List<Hit>>> =
        hits.groupBy { it.id }
            .map { (id, list) -> id to list.sortedBy { it.worldChunkX * 1L shl 32 or it.worldChunkZ.toLong() } }
            .sortedByDescending { it.second.size }
}
