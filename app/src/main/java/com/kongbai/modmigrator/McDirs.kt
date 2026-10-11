package com.kongbai.modmigrator

/**
 * Minecraft 的目录名与文件名。
 *
 * 这些字符串以前散在十几个文件里各写一份：
 * 认目录、建目录、扫实例、迁移、同步都要用到，写死就各改各的。
 * Mojang 或启动器改了名字（saves → minecraftWorlds 这种）就得全局翻。
 *
 * 注意：这里只放**文件系统里的名字**。
 * API 返回的 JSON 字段也叫 "mods"（ModMeta / BackendApi / SyncManager），
 * 那些是接口约定不是目录，不要换成这里的值。
 */
object McDirs {

    const val MODS = "mods"
    const val SAVES = "saves"
    const val CONFIG = "config"
    const val VERSIONS = "versions"
    const val INSTANCES = "instances"
    const val RESOURCEPACKS = "resourcepacks"
    const val SHADERPACKS = "shaderpacks"
    const val SCRIPTS = "scripts"
    const val DATAPACKS = "datapacks"

    const val LEVEL_DAT = "level.dat"
    const val OPTIONS_TXT = "options.txt"

    /** 判断「这是不是一个 .minecraft」时看的标志目录，有一个就算 */
    val ROOT_MARKS = listOf(MODS, SAVES, VERSIONS, RESOURCEPACKS, CONFIG)

    /** 新建实例时要建的目录 */
    val NEW_INSTANCE = listOf(MODS, CONFIG, SAVES, RESOURCEPACKS, SHADERPACKS)
}
