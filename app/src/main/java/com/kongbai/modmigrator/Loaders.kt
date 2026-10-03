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

    /**
     * 手机启动器（Pojav / FCL / Zalith 等）**通常支持**的加载器。
     *
     * 这几个是 Android 上真正跑得起来的：装进实例就能用。
     * 默认只显示这些，避免一打开下拉就是一堆手机上根本用不了的选项。
     */
    val PHONE = listOf("auto", "fabric", "forge", "neoforge", "quilt", "optifine")

    /**
     * 手机启动器**不支持或极少支持**的加载器。
     *
     * 它们大多停留在 1.12.2 及更早、或是早已停止维护
     * （LiteLoader、Rift、Risugami ModLoader、BTA、Nilloader…）。
     * 列出来是为了识别**已有实例/已有模组**时不出错，
     * 而不是让用户去选一个装了也跑不起来的环境。
     *
     * 只有用户在设置里主动勾选后，才出现在分类下拉里。
     */
    val EXTRA = listOf(
        "cleanroom", "legacy_fabric", "babric", "ornithe", "liteloader",
        "rift", "risugami_modloader", "nilloader", "bta", "java_agent"
    )

    /** 全部已知加载器（手机支持的前面，其余在后） */
    val ALL: List<String> get() = PHONE + EXTRA

    /**
     * 当前应该在分类下拉里出现的加载器。
     *
     * @param showExtra 用户是否在设置里勾了「显示手机不支持的加载器」
     */
    fun visible(showExtra: Boolean): List<String> =
        if (showExtra) ALL else PHONE

    /** 加载器 → 图标资源（线稿，可用 setColorFilter 染色/置灰） */
    fun icon(name: String): Int = when (normalize(name)) {
        "fabric" -> R.drawable.ic_ld_fabric
        "forge" -> R.drawable.ic_ld_forge
        "neoforge" -> R.drawable.ic_ld_neoforge
        "quilt" -> R.drawable.ic_ld_quilt
        "cleanroom" -> R.drawable.ic_ld_cleanroom
        "legacy_fabric" -> R.drawable.ic_ld_legacy_fabric
        "babric" -> R.drawable.ic_ld_babric
        "ornithe" -> R.drawable.ic_ld_ornithe
        "liteloader" -> R.drawable.ic_ld_liteloader
        "rift" -> R.drawable.ic_ld_rift
        "risugami_modloader" -> R.drawable.ic_ld_risugami_modloader
        "nilloader" -> R.drawable.ic_ld_nilloader
        "bta" -> R.drawable.ic_ld_bta
        "java_agent" -> R.drawable.ic_ld_java_agent
        // 图标包里没有 OptiFine，继续用老的自绘矢量图
        "optifine" -> R.drawable.ic_loader_optifine
        else -> R.drawable.ic_ld_auto
    }

    /** 是否手机启动器支持 */
    fun isPhoneSupported(name: String): Boolean = normalize(name) in PHONE

    fun label(name: String): String = when (normalize(name)) {
        "fabric" -> "Fabric"
        "forge" -> "Forge"
        "neoforge" -> "NeoForge"
        "quilt" -> "Quilt"
        "optifine" -> "OptiFine"
        "cleanroom" -> "Cleanroom"
        "legacy_fabric" -> "Legacy Fabric"
        "babric" -> "Babric"
        "ornithe" -> "Ornithe"
        "liteloader" -> "LiteLoader"
        "rift" -> "Rift"
        "risugami_modloader" -> "Risugami ModLoader"
        "nilloader" -> "Nilloader"
        "bta" -> "Beta TAS"
        "java_agent" -> "Java Agent"
        else -> "自动"
    }

    /**
     * 把各种写法归一到标准名。认不出来返回 "auto"。
     */
    fun normalize(raw: String): String {
        val s = raw.trim().lowercase(Locale.ROOT)
        if (s.isBlank()) return "auto"
        return when {
            // ── 必须在 fabric / forge 之前判断的 ──────────────────
            // 这几个名字里都带 "fabric" 或 "forge" 子串，
            // 放到后面会被更宽泛的规则先吃掉，识别成 Fabric/Forge。

            // Cleanroom：Forge 1.12.2 的 fork（用 Java 21+ 重写）。
            // 包名前缀 com.cleanroommc，见 CleanroomMC/Cleanroom 仓库。
            s.contains("cleanroom") || s.contains("com.cleanroommc") -> "cleanroom"

            // Legacy Fabric / Babric / Ornithe：1.12.2 及更早的 Fabric 分支
            s.contains("legacyfabric") || s.contains("legacy-fabric") ||
                s.contains("legacy_fabric") -> "legacy_fabric"
            s.contains("babric") -> "babric"
            s.contains("ornithe") -> "ornithe"

            // NeoForge（必须在 forge 之前，否则 "neoforge" 会被 forge 吃掉）
            s == "neoforge" || s.contains("neoforge") ||
                s.contains("net.neoforged") || s == "neo" -> "neoforge"

            // Fabric 系
            s == "fabric" || s.startsWith("fabric") ||
                s.contains("net.fabricmc") || s.contains("fabric-loader") ||
                s.contains("fabricloader") -> "fabric"

            // Quilt
            s == "quilt" || s.contains("org.quiltmc") ||
                s.contains("quilt-loader") || s.contains("quiltloader") -> "quilt"

            // Forge
            s == "forge" || s.contains("forge") ||
                s.contains("net.minecraftforge") || s.contains("minecraftforge") -> "forge"

            // ── 老旧 / 小众 ────────────────────────────────────
            s.contains("liteloader") || s.contains("lite-loader") -> "liteloader"
            s == "rift" || s.contains("rift") -> "rift"
            s.contains("risugami") || s.contains("modloader") -> "risugami_modloader"
            s.contains("nilloader") || s.contains("nil-loader") -> "nilloader"
            // BTA = Better Than Adventure（1.7.3 分支），不是"加载器"但与上面同源
            s == "bta" || s.contains("betterthanadventure") ||
                s.contains("better-than-adventure") -> "bta"
            s.contains("javaagent") || s.contains("java-agent") -> "java_agent"

            s.contains("optifine") -> "optifine"

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
        val loader = normalize(targetLoader)
        // ── 不能只拿完整模组名去查表 ──────────────────────────
        // 词表 key 是 "sodium" 这类**简称**，而实际传进来的常常是
        // "Sodium" / "sodium-fabric-1.20.1.jar" / "Sodium Extra" 这类带后缀的全名，
        // 精确匹配基本命中不了 —— 于是"建议改用 Embeddium"这类提示
        // 从来没出现过，等于这个功能没生效。
        // 这里按从具体到宽泛依次试几种写法。
        val raw = modName.trim().lowercase(Locale.ROOT)
        if (raw.isBlank()) return null
        val base = raw.substringBeforeLast(".")
            .replace(Regex("[\\-_]?\\d+(\\.\\d+)*[\\-_]?(fabric|forge|neoforge|quilt)?$"), "")
            .trim()
        val candidates = listOf(
            raw,
            base,
            base.substringBefore(" - "),
            base.substringBefore("-fabric").substringBefore("-forge"),
            base.substringBefore(" "),
            base.replace(" ", "")
        )
        for (c in candidates) {
            val m = EQUIVALENTS[c.trim()] ?: continue
            return m[loader]
        }
        return null
    }

    /** 判断某加载器是否需要 Fabric API 这类前置 */
    fun needsApi(loader: String): String? = when (normalize(loader)) {
        "fabric" -> "Fabric API"
        "quilt" -> "Quilt Standard Libraries"
        "forge", "neoforge" -> null
        else -> null
    }
}
