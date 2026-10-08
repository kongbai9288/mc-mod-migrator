package com.kongbai.modmigrator

import com.viaversion.nbt.tag.CompoundTag

/**
 * .mca 区块渲染（自研）。
 *
 * # 为什么不用移植版渲染器
 *
 * 上一版用的是 MCA Selector 的 Android 移植 AAR，它的各版本实现靠
 * `ClassIndex` 反射注册。AAR 打包时索引没生成的话，
 * `VersionHandler.getImpl()` 一律返回 null —— 不报错，就是画不出东西，
 * 完全没法定位。所以改回自己画：解析与位运算本来就在 [McaEdit] 里，
 * 这里只做「方块名 → 颜色」这一层。
 *
 * # 画法
 *
 * 取每一列 (x,z) **最高的非空气方块**（含 cave_air 也当空气跳过），
 * 用方块本身的颜色，再按高度做明暗。这样地形起伏看得出来，
 * 也是手机上各类编辑器通用的画法 —— 只算表面一层，
 * 不必把整个区块的 16×16×384 全部展开，内存和耗时都可控。
 */
object McaRender {

    // ---------------------------------------------------------------- 颜色

    private val FIXED: Map<String, Int> = mapOf(
        "minecraft:air" to 0, "minecraft:cave_air" to 0, "minecraft:void_air" to 0,
        "minecraft:grass_block" to 0xFF5FAE3C.toInt(),
        "minecraft:dirt" to 0xFF8B6B45.toInt(),
        "minecraft:coarse_dirt" to 0xFF7A5C3A.toInt(),
        "minecraft:podzol" to 0xFF6B4A22.toInt(),
        "minecraft:rooted_dirt" to 0xFF7A5A38.toInt(),
        "minecraft:mud" to 0xFF3F3226.toInt(),
        "minecraft:packed_mud" to 0xFF8E7355.toInt(),
        "minecraft:farmland" to 0xFF6B4A2A.toInt(),
        "minecraft:dirt_path" to 0xFF9A7A50.toInt(),
        "minecraft:stone" to 0xFF8A8A8A.toInt(),
        "minecraft:andesite" to 0xFF8E8E8E.toInt(),
        "minecraft:diorite" to 0xFFD0D0D0.toInt(),
        "minecraft:granite" to 0xFFA06B52.toInt(),
        "minecraft:cobblestone" to 0xFF7A7A7A.toInt(),
        "minecraft:mossy_cobblestone" to 0xFF6B7A5A.toInt(),
        "minecraft:stone_bricks" to 0xFF7D7D7D.toInt(),
        "minecraft:mossy_stone_bricks" to 0xFF6E7A66.toInt(),
        "minecraft:deepslate" to 0xFF4A4A52.toInt(),
        "minecraft:cobbled_deepslate" to 0xFF4C4C54.toInt(),
        "minecraft:deepslate_bricks" to 0xFF45454D.toInt(),
        "minecraft:tuff" to 0xFF6B6B5A.toInt(),
        "minecraft:calcite" to 0xFFE2E0DC.toInt(),
        "minecraft:dripstone_block" to 0xFF8A6B55.toInt(),
        "minecraft:bedrock" to 0xFF3F3F3F.toInt(),
        "minecraft:sand" to 0xFFE0D3A0.toInt(),
        "minecraft:red_sand" to 0xFFC97A45.toInt(),
        "minecraft:sandstone" to 0xFFDFD3A6.toInt(),
        "minecraft:red_sandstone" to 0xFFC08A55.toInt(),
        "minecraft:gravel" to 0xFF8B8480.toInt(),
        "minecraft:clay" to 0xFFA0A8B8.toInt(),
        "minecraft:terracotta" to 0xFFB07050.toInt(),
        "minecraft:water" to 0xFF3B6FD4.toInt(),
        "minecraft:lava" to 0xFFE25822.toInt(),
        "minecraft:ice" to 0xFF9ED0F5.toInt(),
        "minecraft:packed_ice" to 0xFF7FB6E8.toInt(),
        "minecraft:blue_ice" to 0xFF6FA8DE.toInt(),
        "minecraft:frosted_ice" to 0xFFA8DCF5.toInt(),
        "minecraft:snow_block" to 0xFFF5FAFF.toInt(),
        "minecraft:snow" to 0xFFF0F6FF.toInt(),
        "minecraft:obsidian" to 0xFF1E1526.toInt(),
        "minecraft:nether_portal" to 0xFFB060FF.toInt(),
        "minecraft:end_portal" to 0xFF101018.toInt(),
        "minecraft:end_stone" to 0xFFE0DE9A.toInt(),
        "minecraft:end_stone_bricks" to 0xFFD6D48C.toInt(),
        "minecraft:purpur_block" to 0xFFAE7BB0.toInt(),
        "minecraft:netherrack" to 0xFF8B3A3A.toInt(),
        "minecraft:crimson_nylium" to 0xFF9B3A5A.toInt(),
        "minecraft:warped_nylium" to 0xFF2E6B6B.toInt(),
        "minecraft:soul_sand" to 0xFF6B4A32.toInt(),
        "minecraft:soul_soil" to 0xFF5A3A28.toInt(),
        "minecraft:magma_block" to 0xFF8B3A1A.toInt(),
        "minecraft:nether_bricks" to 0xFF52282B.toInt(),
        "minecraft:nether_wart_block" to 0xFF8B2030.toInt(),
        "minecraft:crimson_hyphae" to 0xFF8B3A55.toInt(),
        "minecraft:warped_hyphae" to 0xFF2E7B7B.toInt(),
        "minecraft:crimson_planks" to 0xFF9B4A60.toInt(),
        "minecraft:warped_planks" to 0xFF3E8B8B.toInt(),
        "minecraft:blackstone" to 0xFF2E2A30.toInt(),
        "minecraft:basalt" to 0xFF4A4A50.toInt(),
        "minecraft:ancient_debris" to 0xFF5A4038.toInt(),
        "minecraft:glowstone" to 0xFFD8B060.toInt(),
        "minecraft:amethyst_block" to 0xFF9B6BD8.toInt(),
        "minecraft:moss_block" to 0xFF5A8B3A.toInt(),
        "minecraft:moss_carpet" to 0xFF6B9B45.toInt(),
        "minecraft:torch" to 0xFFFFD050.toInt(),
        "minecraft:soul_torch" to 0xFF7FD0E8.toInt(),
        "minecraft:lantern" to 0xFFFFD060.toInt(),
        "minecraft:glass" to 0xFFC8E8F5.toInt(),
        "minecraft:tinted_glass" to 0xFF3A3A3A.toInt(),
        "minecraft:hay_block" to 0xFFC8A838.toInt(),
        "minecraft:cobweb" to 0xFFE8E8E8.toInt(),
        "minecraft:sponge" to 0xFFD8D060.toInt(),
        "minecraft:wet_sponge" to 0xFFB8A84A.toInt(),
        "minecraft:mycelium" to 0xFF8B6B7A.toInt(),
        "minecraft:slime_block" to 0xFF6BB05A.toInt(),
        "minecraft:honeycomb_block" to 0xFFD8A838.toInt(),
        "minecraft:bookshelf" to 0xFFA87A45.toInt(),
        "minecraft:crafting_table" to 0xFFA87A45.toInt(),
        "minecraft:furnace" to 0xFF7A7A7A.toInt(),
        "minecraft:chest" to 0xFFA87A45.toInt(),
        "minecraft:dispenser" to 0xFF8A8A8A.toInt(),
        "minecraft:dropper" to 0xFF8A8A8A.toInt(),
        "minecraft:observer" to 0xFF6B6B6B.toInt(),
        "minecraft:piston" to 0xFF9A8A6B.toInt(),
        "minecraft:redstone_block" to 0xFFC02020.toInt(),
        "minecraft:iron_block" to 0xFFE0E0E0.toInt(),
        "minecraft:gold_block" to 0xFFE8C020.toInt(),
        "minecraft:diamond_block" to 0xFF60E0E0.toInt(),
        "minecraft:emerald_block" to 0xFF40C060.toInt(),
        "minecraft:lapis_block" to 0xFF2040C0.toInt(),
        "minecraft:netherite_block" to 0xFF3A3538.toInt(),
        "minecraft:coal_block" to 0xFF202020.toInt(),
        "minecraft:coal_ore" to 0xFF6B6B6B.toInt(),
        "minecraft:iron_ore" to 0xFFB89478.toInt(),
        "minecraft:gold_ore" to 0xFFD8B44A.toInt(),
        "minecraft:diamond_ore" to 0xFF60C8D8.toInt(),
        "minecraft:emerald_ore" to 0xFF40B060.toInt(),
        "minecraft:lapis_ore" to 0xFF3A5AA8.toInt(),
        "minecraft:redstone_ore" to 0xFFA83030.toInt(),
        "minecraft:nether_quartz_ore" to 0xFFC8B4A0.toInt(),
        "minecraft:deepslate_coal_ore" to 0xFF5A5A5A.toInt(),
        "minecraft:deepslate_iron_ore" to 0xFFA88062.toInt(),
        "minecraft:deepslate_gold_ore" to 0xFFC09A38.toInt(),
        "minecraft:deepslate_diamond_ore" to 0xFF4EB0C0.toInt(),
        "minecraft:deepslate_emerald_ore" to 0xFF309050.toInt(),
        "minecraft:copper_ore" to 0xFFB87333.toInt(),
        "minecraft:deepslate_copper_ore" to 0xFF9A5C28.toInt(),
        "minecraft:clay_ore" to 0xFFA0A8B8.toInt()
    )

    /** 后缀/关键词规则：木材、树叶、羊毛、混凝土这些有几十种，逐条列不完。 */
    private fun byRule(name: String): Int? {
        val n = name.removePrefix("minecraft:")
        if (n == "water") return 0xFF3B6FD4.toInt()
        return when {
            n.endsWith("_leaves") -> if (n.startsWith("cherry")) 0xFFF0A0C0.toInt() else if (n.startsWith("spruce")) 0xFF2E6B4A.toInt() else 0xFF4A9B32.toInt()
            n == "mangrove_roots" -> 0xFF7A4A2A.toInt()
            n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_hyphae") -> when {
                n.startsWith("oak") || n.startsWith("dark_oak") -> 0xFF8B6B41.toInt()
                n.startsWith("birch") -> 0xFFD8CBA0.toInt()
                n.startsWith("spruce") -> 0xFF6B4A24.toInt()
                n.startsWith("acacia") -> 0xFFB07040.toInt()
                n.startsWith("jungle") -> 0xFF9B7A52.toInt()
                n.startsWith("cherry") -> 0xFFC08070.toInt()
                n.startsWith("mangrove") -> 0xFF7A3A3A.toInt()
                n.startsWith("crimson") -> 0xFF8B3A55.toInt()
                n.startsWith("warped") -> 0xFF2E7B7B.toInt()
                else -> 0xFF8B6B41.toInt()
            }
            n.endsWith("_planks") -> when {
                n.startsWith("oak") -> 0xFFB08A50.toInt()
                n.startsWith("birch") -> 0xFFE0CE9A.toInt()
                n.startsWith("spruce") -> 0xFF8B6B41.toInt()
                n.startsWith("acacia") -> 0xFFC08050.toInt()
                n.startsWith("jungle") -> 0xFFA8885A.toInt()
                n.startsWith("cherry") -> 0xFFD0A080.toInt()
                n.startsWith("dark_oak") -> 0xFF6B4A2A.toInt()
                n.startsWith("mangrove") -> 0xFF8B4A3A.toInt()
                else -> 0xFFB08A50.toInt()
            }
            n.endsWith("_wool") -> WOOL[n.removeSuffix("_wool")]
            n.endsWith("_concrete") -> WOOL[n.removeSuffix("_concrete")]
            n.endsWith("_concrete_powder") -> WOOL[n.removeSuffix("_concrete_powder")]?.let { it and 0xD0FFFFFF.toInt() }
            n.endsWith("_terracotta") -> WOOL[n.removeSuffix("_terracotta")]?.let { mix(it, 0xFFB07050.toInt(), 0.55f) }
            n.endsWith("_glazed_terracotta") -> WOOL[n.removeSuffix("_glazed_terracotta")]
            n.endsWith("_stained_glass") -> WOOL[n.removeSuffix("_stained_glass")]
            n.endsWith("_carpet") -> WOOL[n.removeSuffix("_carpet")]
            n.endsWith("_bed") -> WOOL[n.removeSuffix("_bed")]
            n.endsWith("_ore") -> 0xFF8A8A8A.toInt()
            n.endsWith("_block") && n.contains("deepslate") -> 0xFF45454D.toInt()
            n.startsWith("wheat") || n.startsWith("carrots") || n.startsWith("potatoes") || n.startsWith("beetroots") -> 0xFF6BA83A.toInt()
            n.contains("nether_brick") -> 0xFF52282B.toInt()
            n.contains("prismarine") -> 0xFF5AA88A.toInt()
            n.contains("purpur") -> 0xFFAE7BB0.toInt()
            n.contains("coral") -> 0xFFD85AA8.toInt()
            n.contains("shulker") -> 0xFF8B6BB0.toInt()
            n.contains("quartz") -> 0xFFE8E0D8.toInt()
            n.contains("bamboo") -> 0xFF7AB04A.toInt()
            n.contains("sculk") -> 0xFF12222A.toInt()
            n.contains("kelp") || n.contains("seagrass") -> 0xFF3A8B4A.toInt()
            else -> null
        }
    }

    private val WOOL: Map<String, Int> = mapOf(
        "white" to 0xFFF0F0F0.toInt(), "orange" to 0xFFE08A20.toInt(), "magenta" to 0xFFC040C0.toInt(),
        "light_blue" to 0xFF60A8E8.toInt(), "yellow" to 0xFFE8D040.toInt(), "lime" to 0xFF80D020.toInt(),
        "pink" to 0xFFF080A0.toInt(), "gray" to 0xFF606060.toInt(), "light_gray" to 0xFFA8A8A8.toInt(),
        "cyan" to 0xFF30A0A0.toInt(), "purple" to 0xFF8030C0.toInt(), "blue" to 0xFF3050C0.toInt(),
        "brown" to 0xFF8B5A2A.toInt(), "green" to 0xFF4A8B32.toInt(), "red" to 0xFFB03030.toInt(),
        "black" to 0xFF202020.toInt()
    )

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = ((a shr 16 and 0xFF) * (1 - t) + (b shr 16 and 0xFF) * t).toInt()
        val g = ((a shr 8 and 0xFF) * (1 - t) + (b shr 8 and 0xFF) * t).toInt()
        val bl = ((a and 0xFF) * (1 - t) + (b and 0xFF) * t).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
    }

    /** 没收录的方块：按名字哈希出一个稳定颜色，至少不会每次打开都在闪。 */
    private fun hashed(name: String): Int {
        var h = name.hashCode()
        h = h xor (h ushr 16)
        val r = 90 + (h and 0x7F)
        val g = 90 + ((h ushr 7) and 0x7F)
        val b = 90 + ((h ushr 14) and 0x7F)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    fun colorOf(name: String): Int {
        if (name == "minecraft:air" || name == "minecraft:cave_air" || name == "minecraft:void_air") return 0
        FIXED[name]?.let { return it }
        byRule(name)?.let { return it }
        return hashed(name)
    }

    // ---------------------------------------------------------------- 渲染

    private class Ready(val y: Int, val names: List<String>, val bits: Int, val data: LongArray?)

    private fun ready(chunk: CompoundTag): List<Ready> {
        val secs = McaEdit.sections(chunk)
        val out = ArrayList<Ready>(secs.size)
        for (s in secs) {
            val nm = McaEdit.paletteNames(s)
            if (nm.isEmpty()) continue
            val data = McaEdit.dataArray(s)
            // 整段全空就别进后面的逐格扫描了。
            // 未生成区域的 section 调色板只有空气、data 全是 0，
            // 一个区域 1024 个空区块能把渲染拖到几分钟 —— 手机上必须省掉。
            if (isAir(nm[0]) && (data == null || data.isEmpty() || data.all { it == 0L })) continue
            out.add(Ready(s.y, nm, McaEdit.bitsFor(nm.size), data))
        }
        return out
    }

    private fun isAir(n: String) =
        n == "minecraft:air" || n == "minecraft:cave_air" || n == "minecraft:void_air"

    /**
     * 画一整个区块的俯视图（每列最高非空气方块）。
     * @param px  整张画布
     * @param imgW 画布宽度（像素）
     * @param ox/oz 该区块左上角在画布中的位置
     * @param scale 每格几个像素
     * @return 是否画出了东西
     */
    fun drawTop(
        chunk: CompoundTag, px: IntArray, imgW: Int, ox: Int, oz: Int, scale: Int,
        yMin: Int = Int.MIN_VALUE, yMax: Int = Int.MAX_VALUE
    ): Boolean {
        val rs = ready(chunk)
        if (rs.isEmpty()) return false
        // 下界/上界：只在用户圈定的高度区间里找表面。
        // 用途是"看某一层" —— 比如只想看地下矿道，就设上界；
        // 只想看地表以上，就设下界。不设就是原行为。
        val lo = (rs.minOf { it.y } * 16).coerceAtLeast(yMin)
        val hi = ((rs.maxOf { it.y } * 16) + 15).coerceAtMost(yMax)
        if (hi < lo) return false
        val span = (hi - lo).coerceAtLeast(1)
        var painted = 0
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                var col = 0
                var wy = lo
                outer@ for (i in rs.indices) {
                    val r = rs[i]
                    for (y in 15 downTo 0) {
                        val wy0 = r.y * 16 + y
                        if (wy0 < yMin || wy0 > yMax) continue
                        val idx = (y * 16 + z) * 16 + x
                        val v = McaEdit.readIndex(r.data, r.bits, idx)
                        val n = if (v < r.names.size) r.names[v] else "minecraft:air"
                        if (!isAir(n)) {
                            col = colorOf(n)
                            wy = r.y * 16 + y
                            break@outer
                        }
                    }
                }
                if (col == 0) continue
                painted++
                val t = ((wy - lo).toFloat() / span).coerceIn(0f, 1f)
                // 0.72 ~ 1.15：低处暗、高处亮，起伏才看得出来
                val k = 0.72f + 0.43f * t
                val c = shade(col, k)
                val bx = ox + x * scale
                val bz = oz + z * scale
                for (dz in 0 until scale) {
                    val row = (bz + dz) * imgW
                    if (row < 0 || row + imgW > px.size) continue
                    for (dx in 0 until scale) {
                        val pxx = bx + dx
                        if (pxx < 0 || pxx >= imgW) continue
                        px[row + pxx] = c
                    }
                }
            }
        }
        return painted > 0
    }

    /** 只画某一层（sectionY 是区段号：1.18 起从 -4 开始）。 */
    fun drawLayer(
        chunk: CompoundTag, sectionY: Int, px: IntArray, imgW: Int, ox: Int, oz: Int, scale: Int
    ): Boolean {
        val rs = ready(chunk)
        val r = rs.firstOrNull { it.y == sectionY } ?: return false
        var painted = 0
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                var col = 0
                for (y in 15 downTo 0) {
                    val idx = (y * 16 + z) * 16 + x
                    val v = McaEdit.readIndex(r.data, r.bits, idx)
                    val n = if (v < r.names.size) r.names[v] else "minecraft:air"
                    if (!isAir(n)) { col = colorOf(n); break }
                }
                if (col == 0) {
                    // 整格空：画个很淡的底，方便看出「这层是空的」而不是「画崩了」
                    col = 0xFF1A1A1A.toInt()
                } else painted++
                val bx = ox + x * scale
                val bz = oz + z * scale
                for (dz in 0 until scale) {
                    val row = (bz + dz) * imgW
                    if (row < 0 || row + imgW > px.size) continue
                    for (dx in 0 until scale) {
                        val pxx = bx + dx
                        if (pxx < 0 || pxx >= imgW) continue
                        px[row + pxx] = col
                    }
                }
            }
        }
        return painted > 0
    }

    private fun shade(c: Int, k: Float): Int {
        val a = c ushr 24
        if (a == 0) return 0
        val r = minOf(255, ((c shr 16 and 0xFF) * k).toInt())
        val g = minOf(255, ((c shr 8 and 0xFF) * k).toInt())
        val b = minOf(255, ((c and 0xFF) * k).toInt())
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 这一层有没有内容（用于跳过大片空的层）。 */
    fun layerHasBlocks(chunk: CompoundTag, sectionY: Int): Boolean {
        val rs = ready(chunk)
        val r = rs.firstOrNull { it.y == sectionY } ?: return false
        for (i in 0 until 4096) {
            val v = McaEdit.readIndex(r.data, r.bits, i)
            val n = if (v < r.names.size) r.names[v] else "minecraft:air"
            if (!isAir(n)) return true
        }
        return false
    }
}
