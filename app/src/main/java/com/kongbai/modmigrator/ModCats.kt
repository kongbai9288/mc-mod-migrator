package com.kongbai.modmigrator

import java.util.Locale

/**
 * 商店卡片的**分类标签**（Modrinth 的 categories 字段）。
 *
 * ⚠️ 为什么必须过滤：categories 里混着三类完全不同的东西——
 *   · 内容分类：optimization / technology / magic / storage …
 *   · 加载器：  fabric / forge / quilt（**已经在加载器图标那一行显示过了**）
 *   · 游戏版本：1.20.x / 1.21.x / snapshot
 * 直接全画出来的话，卡片上会挂一堆"fabric""1.20.x"，
 * 跟上面的加载器图标重复，还把真正有用的内容分类挤出屏幕。
 *
 * 只保留内容分类，并且翻成中文（列表横向空间有限，中文更省宽度）。
 */
object ModCats {

    /** 每条最多显示几个标签：再多就该点开详情页看了 */
    const val MAX_SHOW = 4

    /** 已废弃/重复的类别名 → 忽略 */
    private val SKIP = setOf(
        "fabric", "forge", "quilt", "neoforge", "bukkit", "spigot", "paper",
        "modloader", "liteloader", "rift", "risugami", "optifine",
        "datapack", "resourcepack", "shader", "plugin", "modpack",
        "minecraft", "iris", "java-agent", "nilloader", "cleanroom"
    )

    private val ZH = mapOf(
        "adventure" to "冒险",
        "atmosphere" to "氛围",
        "audio" to "音效",
        "bug-fixes" to "修复",
        "cartography" to "地图",
        "challenging" to "高难",
        "combat" to "战斗",
        "compat" to "兼容",
        "cursed" to "魔幻",
        "decoration" to "装饰",
        "economy" to "经济",
        "equipment" to "装备",
        "food" to "食物",
        "game-mechanics" to "机制",
        "library" to "库",
        "lightweight" to "轻量",
        "magic" to "魔法",
        "management" to "管理",
        "minigame" to "小游戏",
        "mobs" to "生物",
        "multiplayer" to "联机",
        "optimization" to "优化",
        "quests" to "任务",
        "redstone" to "红石",
        "sandbox" to "沙盒",
        "simulation" to "模拟",
        "social" to "社交",
        "storage" to "存储",
        "technology" to "科技",
        "transportation" to "运输",
        "tweaks" to "微调",
        "utility" to "实用",
        "utility-and-qol" to "实用",
        "worldgen" to "世界生成",
        "core-shaders" to "光影核心",
        "patches" to "补丁",
        "gui" to "界面",
        "kotlin" to "Kotlin",
        "scala" to "Scala",
        "easy" to "简易",
        "hard" to "硬核",
        "realistic" to "写实",
        "vanilla-like" to "原版风",
        "8x-16x" to "低分辨率",
        "16x" to "16x",
        "32x-64x" to "中分辨率",
        "64x" to "64x",
        "128x-256x" to "高分辨率",
        "256x-and-above" to "超高分辨率",
        "modded" to "模组扩展",
        "semi-realistic" to "半写实",
        "fantasy" to "奇幻",
        "modern" to "现代",
        "retro" to "复古",
        "blocks" to "方块",
        "entities" to "实体",
        "items" to "物品",
        "environment" to "环境",
        "fonts" to "字体",
        "optimisation" to "优化",
        "mcaddon" to "附加包",
        "datapacks" to "数据包",
        "configuration" to "配置",
        "server" to "服务端",
        "client" to "客户端",
        "performance" to "性能",
        "qol" to "体验优化"
    )

    /**
     * 归一化：丢掉加载器/版本类、去重、翻译。
     *
     * @return 用于显示的中文标签；空列表表示"这个模组没有可展示的分类"
     */
    fun clean(raw: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (r in raw) {
            val k = r.trim().lowercase(Locale.ROOT)
            if (k.isBlank()) continue
            if (k in SKIP) continue
            // 版本号（1.20.x / 1.21 / snapshot）一律不要
            if (k.first().isDigit()) continue
            if (k.startsWith("mc")) continue
            out.add(ZH[k] ?: zhFallback(r.trim()))
        }
        return out.take(MAX_SHOW)
    }

    /** 没收录的类别：把连字符换成空格、首字母大写，比原样小写好看 */
    private fun zhFallback(raw: String): String {
        val s = raw.replace('-', ' ').replace('_', ' ').trim()
        if (s.isBlank()) return raw
        return s.split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }
}
