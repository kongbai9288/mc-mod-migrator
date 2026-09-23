package com.kongbai.modmigrator

import java.util.Locale

/**
 * 加载器归一化 + 跨加载器替代建议。
 *
 * 归一化的原因：同一个加载器在不同来源里写法五花八门
 * （fabric / Fabric / fabric-loader / net.fabricmc / 加载器ID…），
 * 不统一就会出现「同一个实例查出两种加载器」的情况。
 *
 * 替代表来自电脑端 Mod Migrator 的做法：
 * 某个模组在目标加载器上没有构建时，给出已知的对应分支/平替。
 */
object Loaders {

    /** 展示顺序，也是选择器里的顺序 */
    val ALL = listOf("auto", "fabric", "forge", "neoforge", "quilt")

    fun label(name: String): String = when (normalize(name)) {
        "fabric" -> "Fabric"
        "forge" -> "Forge"
        "neoforge" -> "NeoForge"
        "quilt" -> "Quilt"
        else -> "自动"
    }

    /**
     * 把各种写法归一到标准名。认不出来返回 "auto"。
     */
    fun normalize(raw: String): String {
        val s = raw.trim().lowercase(Locale.ROOT)
        if (s.isBlank()) return "auto"
        return when {
            // Fabric 系
            s == "fabric" || s.startsWith("fabric") ||
                s.contains("net.fabricmc") || s.contains("fabric-loader") ||
                s.contains("fabricloader") -> "fabric"

            // Quilt（要在 fabric 之后判断， quilt 也带 fabric 兼容层字样）
            s == "quilt" || s.contains("org.quiltmc") ||
                s.contains("quilt-loader") || s.contains("quiltloader") -> "quilt"

            // NeoForge（必须在 forge 之前，否则 "neoforge" 会被 forge 吃掉）
            s == "neoforge" || s.contains("neoforge") ||
                s.contains("net.neoforged") || s == "neo" -> "neoforge"

            // Forge
            s == "forge" || s.contains("forge") ||
                s.contains("net.minecraftforge") || s.contains("minecraftforge") -> "forge"

            s == "vanilla" || s == "原版" -> "auto"
            else -> "auto"
        }
    }

    /** 两个加载器是否视为同一个（用于去重比较） */
    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /**
     * 跨加载器替代：目标加载器上没找到时，给出已知平替。
     * 数据来自社区常识与 Mod Migrator 的 Equivalents 表。
     * key = 原模组名小写，value = 各加载器下的替代名
     */
    private val EQUIVALENTS = mapOf(
        "sodium" to mapOf("forge" to "Embeddium", "neoforge" to "Embeddium"),
        "iris" to mapOf("forge" to "Oculus", "neoforge" to "Oculus"),
        "lithium" to mapOf("forge" to "Canary", "neoforge" to "Canary", "quilt" to "Radium"),
        "phosphor" to mapOf("fabric" to "Starlight", "forge" to "Starlight"),
        "starlight" to mapOf("forge" to "Starlight", "fabric" to "Starlight"),
        "optifine" to mapOf("fabric" to "Iris + Sodium", "neoforge" to "Iris + Sodium"),
        "sodium extra" to mapOf("forge" to "Embeddium++", "neoforge" to "Embeddium++"),
        "reeses-sodium-options" to mapOf("forge" to "Embeddium++", "neoforge" to "Embeddium++"),
        "modmenu" to mapOf("forge" to "", "neoforge" to ""),
        "fabric api" to mapOf("forge" to "", "neoforge" to ""),
        "roughly enough items" to mapOf("forge" to "JEI", "neoforge" to "JEI"),
        "rei" to mapOf("forge" to "JEI", "neoforge" to "JEI"),
        "jei" to mapOf("fabric" to "REI", "quilt" to "REI"),
        "emi" to mapOf("forge" to "JEI", "neoforge" to "JEI")
    )

    /**
     * 查替代模组。
     * @return 替代名；返回 null 表示没有已知替代；返回 "" 表示该加载器下不需要（如 Mod Menu 是 Fabric 专有）
     */
    fun equivalent(modName: String, targetLoader: String): String? {
        val key = modName.trim().lowercase(Locale.ROOT)
        val loader = normalize(targetLoader)
        val m = EQUIVALENTS[key] ?: return null
        return m[loader]
    }

    /** 判断某加载器是否需要 Fabric API 这类前置 */
    fun needsApi(loader: String): String? = when (normalize(loader)) {
        "fabric" -> "Fabric API"
        "quilt" -> "Quilt Standard Libraries"
        "forge", "neoforge" -> null
        else -> null
    }
}
