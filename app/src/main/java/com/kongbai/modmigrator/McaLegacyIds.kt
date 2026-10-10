package com.kongbai.modmigrator

/**
 * 旧版本方块「数字 ID」→ 现代方块名的映射。
 *
 * 为什么需要这份表：
 * 1.13（扁平化）之前的存档里，区块存的是**数字 ID** 而不是方块名。
 * 没有这份表，读出来的就只是一串 0~255 的数字，
 * 渲染器按名字取颜色，一个都对不上 —— 表现就是整张图一片黑。
 *
 * 覆盖范围以真实存档样本为准（Beta 1.3 / 1.2.1）：
 * 自然地形与常见建筑方块全部收录，冷门方块回退到
 * `minecraft:unknown_<id>`，渲染器对未知名字有兜底配色，
 * 不会出现"整片黑"这种没法判断是没数据还是没配色的情况。
 *
 * 只用于**读**。写回时 [McaLegacyIds.idOf] 找不到对应 ID 会返回 -1，
 * 调用方应当跳过而不是写个错误的方块进去。
 */
object McaLegacyIds {

    /** 数字 ID → 现代方块名 */
    private val NAMES: Map<Int, String> = mapOf(
        0 to "air", 1 to "stone", 2 to "grass_block", 3 to "dirt",
        4 to "cobblestone", 5 to "oak_planks", 6 to "oak_sapling", 7 to "bedrock",
        8 to "water", 9 to "water", 10 to "lava", 11 to "lava",
        12 to "sand", 13 to "gravel", 14 to "gold_ore", 15 to "iron_ore",
        16 to "coal_ore", 17 to "oak_log", 18 to "oak_leaves", 19 to "sponge",
        20 to "glass", 21 to "lapis_ore", 22 to "lapis_block", 23 to "dispenser",
        24 to "sandstone", 25 to "note_block", 26 to "red_bed", 27 to "powered_rail",
        28 to "detector_rail", 29 to "sticky_piston", 30 to "cobweb", 31 to "short_grass",
        32 to "dead_bush", 33 to "piston", 34 to "piston_head", 35 to "white_wool",
        37 to "dandelion", 38 to "poppy", 39 to "brown_mushroom", 40 to "red_mushroom",
        41 to "gold_block", 42 to "iron_block", 43 to "stone_slab", 44 to "stone_slab",
        45 to "bricks", 46 to "tnt", 47 to "bookshelf", 48 to "mossy_cobblestone",
        49 to "obsidian", 50 to "torch", 51 to "fire", 52 to "spawner",
        53 to "oak_stairs", 54 to "chest", 55 to "redstone_wire", 56 to "diamond_ore",
        57 to "diamond_block", 58 to "crafting_table", 59 to "wheat", 60 to "farmland",
        61 to "furnace", 62 to "furnace", 63 to "oak_sign", 64 to "oak_door",
        65 to "ladder", 66 to "rail", 67 to "cobblestone_stairs", 68 to "oak_sign",
        69 to "lever", 70 to "stone_pressure_plate", 71 to "iron_door",
        72 to "oak_pressure_plate", 73 to "redstone_ore", 74 to "redstone_ore",
        75 to "redstone_torch", 76 to "redstone_torch", 77 to "stone_button",
        78 to "snow", 79 to "ice", 80 to "snow_block", 81 to "cactus",
        82 to "clay", 83 to "sugar_cane", 84 to "jukebox", 85 to "oak_fence",
        86 to "pumpkin", 87 to "netherrack", 88 to "soul_sand", 89 to "glowstone",
        90 to "nether_portal", 91 to "jack_o_lantern", 92 to "cake", 93 to "repeater",
        94 to "repeater", 95 to "white_stained_glass", 96 to "oak_trapdoor",
        97 to "infested_stone", 98 to "stone_bricks", 99 to "brown_mushroom_block",
        100 to "red_mushroom_block", 101 to "iron_bars", 102 to "glass_pane",
        103 to "melon", 104 to "pumpkin_stem", 105 to "melon_stem", 106 to "vine",
        107 to "oak_fence_gate", 108 to "brick_stairs", 109 to "stone_brick_stairs",
        110 to "mycelium", 111 to "lily_pad", 112 to "nether_bricks",
        113 to "nether_brick_fence", 114 to "nether_brick_stairs", 115 to "nether_wart",
        116 to "enchanting_table", 117 to "brewing_stand", 118 to "cauldron",
        119 to "end_portal", 120 to "end_portal_frame", 121 to "end_stone",
        122 to "dragon_egg", 123 to "redstone_lamp", 124 to "redstone_lamp",
        125 to "oak_slab", 126 to "oak_slab", 127 to "cocoa",
        128 to "sandstone_stairs", 129 to "emerald_ore", 130 to "ender_chest",
        131 to "tripwire_hook", 132 to "tripwire", 133 to "emerald_block",
        134 to "spruce_stairs", 135 to "birch_stairs", 136 to "jungle_stairs",
        137 to "command_block", 138 to "beacon", 139 to "cobblestone_wall",
        140 to "flower_pot", 141 to "carrots", 142 to "potatoes", 143 to "oak_button",
        144 to "skeleton_skull", 145 to "anvil", 146 to "trapped_chest",
        147 to "light_weighted_pressure_plate", 148 to "heavy_weighted_pressure_plate",
        149 to "comparator", 150 to "comparator", 151 to "daylight_detector",
        152 to "redstone_block", 153 to "nether_quartz_ore", 154 to "hopper",
        155 to "quartz_block", 156 to "quartz_stairs", 157 to "activator_rail",
        158 to "dropper", 159 to "white_terracotta", 160 to "white_stained_glass_pane",
        161 to "acacia_leaves", 162 to "acacia_log", 163 to "acacia_stairs",
        164 to "dark_oak_stairs", 165 to "slime_block", 166 to "barrier",
        167 to "iron_trapdoor", 168 to "prismarine", 169 to "sea_lantern",
        170 to "hay_block", 171 to "white_carpet", 172 to "terracotta",
        173 to "coal_block", 174 to "packed_ice", 175 to "sunflower"
    )

    /**
     * 反查：现代方块名 → 数字 ID。
     * 同名（比如 8/9 都是水、43/44 都是石头台阶）取**最小的**那个 ID，
     * 写回时落到更常见的那个形态上。
     */
    private val IDS: Map<String, Int> = run {
        val m = HashMap<String, Int>()
        NAMES.entries.sortedBy { it.key }.forEach { (id, n) ->
            if (!m.containsKey(n)) m[n] = id
        }
        m
    }

    /** 数字 ID → 带命名空间的方块名。未知 ID 也有名字，避免渲染成一片黑。 */
    fun nameOf(id: Int): String {
        val n = NAMES[id]
        return if (n != null) "minecraft:$n" else "minecraft:unknown_$id"
    }

    /** 方块名 → 数字 ID。找不到返回 -1，调用方应跳过而不是写错方块。 */
    fun idOf(name: String): Int {
        val n = name.removePrefix("minecraft:")
        return IDS[n] ?: -1
    }
}
