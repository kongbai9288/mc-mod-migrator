package com.kongbai.modmigrator

/**
 * 一个实例里能被搬走的目录与文件。迁移页和整合包导出共用这份清单。
 *
 * 目录名一律走 [McDirs]，这里只定「有哪些类、默认勾不勾」。
 */
object McParts {

    const val PREF_MIGRATE = "migrate_parts"
    const val PREF_EXPORT = "export_parts"

    data class Part(
        /** 稳定标识，存偏好用。不要用中文，也不要用下标 */
        val id: String,
        val label: String,
        val dir: String? = null,
        val file: String? = null,
        /** 迁移页默认勾选 */
        val migrateDefault: Boolean = false,
        /** 导出时默认勾选。个人数据一律默认不选 */
        val exportDefault: Boolean = false,
        /** 属于个人数据：存档、按键设置、服务器列表。分享给别人前想清楚 */
        val personal: Boolean = false,
        val hint: String = ""
    ) {
        val name: String get() = dir ?: file ?: ""
        val isDir: Boolean get() = dir != null
    }

    val MODS = Part(
        McDirs.MODS, "模组 mods/", dir = McDirs.MODS, exportDefault = true,
        hint = "迁移时模组由下载阶段处理，这里勾了也不复制；导出整合包时才用得上"
    )
    val CONFIG = Part(
        McDirs.CONFIG, "模组配置 config/", dir = McDirs.CONFIG,
        migrateDefault = true, exportDefault = true
    )
    val DEFAULT_CONFIGS = Part(
        "defaultconfigs", "默认配置 defaultconfigs/", dir = "defaultconfigs",
        migrateDefault = true, exportDefault = true,
        hint = "Forge 系的初始配置模板，玩家没改过时会从这里生成 config/"
    )
    val SCRIPTS = Part(
        McDirs.SCRIPTS, "脚本 scripts/", dir = McDirs.SCRIPTS,
        migrateDefault = true, exportDefault = true,
        hint = "CraftTweaker 用的目录"
    )
    val KUBEJS = Part(
        "kubejs", "KubeJS 脚本 kubejs/", dir = "kubejs",
        migrateDefault = true, exportDefault = true
    )
    val RESOURCEPACKS = Part(
        McDirs.RESOURCEPACKS, "资源包 resourcepacks/", dir = McDirs.RESOURCEPACKS,
        exportDefault = true
    )
    val SHADERPACKS = Part(
        McDirs.SHADERPACKS, "光影包 shaderpacks/", dir = McDirs.SHADERPACKS,
        exportDefault = true
    )
    val DATAPACKS = Part(
        McDirs.DATAPACKS, "数据包 datapacks/", dir = McDirs.DATAPACKS,
        exportDefault = true,
        hint = "实例级的数据包。存档自带的在 saves/<世界>/datapacks，跟着存档走"
    )
    val TEXTUREPACKS = Part(
        "texturepacks", "旧版材质包 texturepacks/", dir = "texturepacks",
        hint = "1.5 及更早的目录名，之后的叫 resourcepacks"
    )
    val SAVES = Part(
        McDirs.SAVES, "存档 / 地图 saves/", dir = McDirs.SAVES,
        personal = true, hint = "体积通常很大，而且是个人进度"
    )
    val OPTIONS = Part(
        "options", "游戏设置 options.txt", file = McDirs.OPTIONS_TXT,
        migrateDefault = true, personal = true,
        hint = "画质、按键、音量"
    )
    val SERVERS = Part(
        "servers", "服务器列表 servers.dat", file = "servers.dat",
        migrateDefault = true, personal = true
    )

    val ALL: List<Part> = listOf(
        MODS, CONFIG, DEFAULT_CONFIGS, SCRIPTS, KUBEJS,
        RESOURCEPACKS, SHADERPACKS, DATAPACKS, TEXTUREPACKS,
        SAVES, OPTIONS, SERVERS
    )

    /** 任何情况下都不搬：日志与缓存会无限增长，版本库由启动器维护，账号文件含登录态 */
    val NEVER: Set<String> = setOf(
        "logs", "crash-reports", "cache", ".cache", "libraries", "assets",
        "versions", "runtime", "natives", "downloads", "staging", "quickPlay",
        "usercache.json", "usernamecache.json",
        "launcher_profiles.json", "launcher_accounts.json",
        "modrinth.index.json", "manifest.json", "mmc-pack.json", "instance.cfg",
        ".fabric", ".mixin.out"
    )

    fun byId(id: String): Part? = ALL.firstOrNull { it.id == id }

    fun defaultIds(forExport: Boolean): String =
        ALL.filter { if (forExport) it.exportDefault else it.migrateDefault }
            .joinToString(",") { it.id }

    /** 偏好里存的是逗号分隔的 id。空串表示没存过，用默认 */
    fun resolve(saved: String, forExport: Boolean): LinkedHashSet<Part> {
        val out = LinkedHashSet<Part>()
        if (saved.isBlank()) {
            for (p in ALL) if (if (forExport) p.exportDefault else p.migrateDefault) out.add(p)
            return out
        }
        for (id in saved.split(',')) {
            byId(id.trim())?.let { out.add(it) }
        }
        return out
    }

    fun toPref(parts: Collection<Part>): String =
        ALL.filter { parts.contains(it) }.joinToString(",") { it.id }
}
