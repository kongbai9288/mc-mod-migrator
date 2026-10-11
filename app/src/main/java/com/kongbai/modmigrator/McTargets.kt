package com.kongbai.modmigrator

/**
 * 迁移目标的候选项：MC 版本与加载器。
 *
 * 为什么单独放这里：
 *   之前各处需要版本列表时就地写一份 arrayOf(...)，
 *   结果同一个列表散落在好几个文件里，
 *   改一处漏一处，列表之间还会不一致（有的多了 1.21.8，有的没有）。
 *
 * 现在统一从这里取，任何页面要下拉选项都用它。
 * 「自动」排在第一位：扫到存档/实例时会自己判断，不强制用户选。
 *
 * ⚠️ 版本清单本身不在这里写死：由 [McVer] 负责解析与获取
 * （联网抓过就以远端为准，没抓过用内置兜底），
 * 这里只负责拼上下拉需要的「自动」那一项。
 */
object McTargets {

    /** 目标 MC 版本。「自动」+ 已知正式版（新→旧） */
    val MC_VERSIONS: Array<String> get() = (listOf("自动") + McVer.known()).toTypedArray()

    /** 清单里最新的正式版（写默认值时用，不要写死具体版本号） */
    fun latestMc(): String = McVer.latest(McVer.known())

    /** 目标加载器。值为**标准名**，不要写显示用的标签（Fabric / Forge） */
    val LOADERS: Array<String> = arrayOf(
        "自动", "fabric", "forge", "neoforge", "quilt"
    )

    fun mcIndex(v: String?): Int {
        val i = MC_VERSIONS.indexOf(v)
        return if (i < 0) 0 else i
    }

    fun loaderIndex(v: String?): Int {
        val i = LOADERS.indexOf(v)
        return if (i < 0) 0 else i
    }
}
