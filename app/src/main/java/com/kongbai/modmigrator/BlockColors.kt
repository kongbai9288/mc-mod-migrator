package com.kongbai.modmigrator

import android.graphics.Color

/**
 * 方块名 → 显示颜色。
 *
 * 只收录地形里最常见的那些，够用来分辨"这是山还是水"就够了。
 * 没收录的按名字哈希出一个稳定颜色 ——
 * 同一个方块每次打开颜色一致，不会每次刷新都在闪。
 */
object BlockColors {

    private val M: Map<String, Int> = mapOf(
        "minecraft:air" to Color.parseColor("#20242a"),
        "minecraft:cave_air" to Color.parseColor("#20242a"),
        "minecraft:stone" to Color.parseColor("#7d7d7d"),
        "minecraft:granite" to Color.parseColor("#956154"),
        "minecraft:diorite" to Color.parseColor("#bcbcbc"),
        "minecraft:andesite" to Color.parseColor("#888888"),
        "minecraft:dirt" to Color.parseColor("#8a5d3b"),
        "minecraft:coarse_dirt" to Color.parseColor("#7a5133"),
        "minecraft:grass_block" to Color.parseColor("#5f9b3a"),
        "minecraft:podzol" to Color.parseColor("#6b4a24"),
        "minecraft:mycelium" to Color.parseColor("#6f6265"),
        "minecraft:sand" to Color.parseColor("#dbd3a0"),
        "minecraft:red_sand" to Color.parseColor("#bd6a3c"),
        "minecraft:gravel" to Color.parseColor("#7f7f74"),
        "minecraft:clay" to Color.parseColor("#9aa3b0"),
        "minecraft:cobblestone" to Color.parseColor("#7a7a7a"),
        "minecraft:bedrock" to Color.parseColor("#4f4f4f"),
        "minecraft:obsidian" to Color.parseColor("#151021"),
        "minecraft:water" to Color.parseColor("#3f5fd0"),
        "minecraft:lava" to Color.parseColor("#d85a12"),
        "minecraft:snow_block" to Color.parseColor("#f2f6f8"),
        "minecraft:snow" to Color.parseColor("#eef4f7"),
        "minecraft:ice" to Color.parseColor("#8fb6e8"),
        "minecraft:packed_ice" to Color.parseColor("#7aa4d8"),
        "minecraft:oak_planks" to Color.parseColor("#b28b52"),
        "minecraft:spruce_planks" to Color.parseColor("#7d5c37"),
        "minecraft:birch_planks" to Color.parseColor("#c8b077"),
        "minecraft:oak_log" to Color.parseColor("#6f5233"),
        "minecraft:oak_leaves" to Color.parseColor("#4a7a30"),
        "minecraft:spruce_leaves" to Color.parseColor("#3d6b34"),
        "minecraft:birch_leaves" to Color.parseColor("#5f8c3c"),
        "minecraft:glass" to Color.parseColor("#c8e8f0"),
        "minecraft:torch" to Color.parseColor("#e0a020"),
        "minecraft:glowstone" to Color.parseColor("#e8c07a"),
        "minecraft:coal_ore" to Color.parseColor("#3a3a3a"),
        "minecraft:iron_ore" to Color.parseColor("#b08a6a"),
        "minecraft:gold_ore" to Color.parseColor("#e0c040"),
        "minecraft:diamond_ore" to Color.parseColor("#5fd8d0"),
        "minecraft:redstone_ore" to Color.parseColor("#c03030"),
        "minecraft:emerald_ore" to Color.parseColor("#40c060"),
        "minecraft:lapis_ore" to Color.parseColor("#3050b0"),
        "minecraft:netherrack" to Color.parseColor("#8a3a3a"),
        "minecraft:soul_sand" to Color.parseColor("#5a4230"),
        "minecraft:magma_block" to Color.parseColor("#a84018"),
        "minecraft:glowstone" to Color.parseColor("#e8c07a"),
        "minecraft:end_stone" to Color.parseColor("#d8d4a0"),
        "minecraft:purpur_block" to Color.parseColor("#a070a8"),
        "minecraft:diamond_block" to Color.parseColor("#5fd8d0"),
        "minecraft:gold_block" to Color.parseColor("#f0c828"),
        "minecraft:iron_block" to Color.parseColor("#d8d8d8"),
        "minecraft:emerald_block" to Color.parseColor("#40c060"),
        "minecraft:redstone_block" to Color.parseColor("#c03030"),
        "minecraft:lapis_block" to Color.parseColor("#3050b0"),
        "minecraft:wool" to Color.parseColor("#e8e8e8"),
        "minecraft:white_wool" to Color.parseColor("#e8e8e8"),
        "minecraft:red_wool" to Color.parseColor("#a03030"),
        "minecraft:blue_wool" to Color.parseColor("#3050a0"),
        "minecraft:terracotta" to Color.parseColor("#a06840"),
        "minecraft:bricks" to Color.parseColor("#9a5c48"),
        "minecraft:stone_bricks" to Color.parseColor("#6d6d6d"),
        "minecraft:deepslate" to Color.parseColor("#4a4a52"),
        "minecraft:tuff" to Color.parseColor("#6b6f63"),
        "minecraft:calcite" to Color.parseColor("#dfe0dc"),
        "minecraft:dripstone_block" to Color.parseColor("#8a6b52"),
        "minecraft:amethyst_block" to Color.parseColor("#8a6bc0"),
        "minecraft:copper_block" to Color.parseColor("#b06a3a"),
        "minecraft:moss_block" to Color.parseColor("#5a8a3a"),
        "minecraft:mud" to Color.parseColor("#3f3226"),
        "minecraft:mangrove_planks" to Color.parseColor("#8a4a3a")
    )

    /** 界面上可直接涂的方块（挑常用的，不做成几百项的长列表） */
    val BRUSHES: List<String> = listOf(
        "minecraft:air",
        "minecraft:stone",
        "minecraft:dirt",
        "minecraft:grass_block",
        "minecraft:cobblestone",
        "minecraft:oak_planks",
        "minecraft:sand",
        "minecraft:gravel",
        "minecraft:water",
        "minecraft:lava",
        "minecraft:glass",
        "minecraft:obsidian",
        "minecraft:bedrock",
        "minecraft:diamond_block",
        "minecraft:gold_block",
        "minecraft:iron_block",
        "minecraft:glowstone",
        "minecraft:deepslate"
    )

    fun of(name: String): Int {
        val c = M[name]
        if (c != null) return c
        // 未收录：按名字生成稳定的颜色，避免每次都是同一片灰
        val h = name.hashCode()
        val r = 60 + (h ushr 16 and 0x7F)
        val g = 60 + (h ushr 8 and 0x7F)
        val b = 60 + (h and 0x7F)
        return Color.rgb(r, g, b)
    }

    /** 去掉命名空间前缀，界面上显示更短 */
    fun short(name: String): String =
        if (name.startsWith("minecraft:")) name.removePrefix("minecraft:") else name
}
