package com.kongbai.modmigrator

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes

/**
 * 启动器品牌识别 + 对外的"来源启动器"接口。
 *
 * ## 一、品牌识别
 * 世界存档、NBT 这类界面列出的是**某个启动器目录下**的文件。
 * 同一台机器上往往装着好几个启动器，目录名又各不相同，
 * 光看路径很难一眼分清这个文件属于谁。这里把路径/名字映射成品牌，
 * 界面上配一枚图标，扫一眼就知道。
 *
 * ## 二、来源启动器接口（预留）
 * 万一哪天本应用被某个启动器集成（从它里面直接跳进来），
 * 我们希望知道"是谁带我进来的"，好在开场时做个呼应。
 *
 * **这里刻意不做包名检测**：本项目是开源的，任何人都可以 fork 后改包名，
 * 按包名判定对 fork 版本一律失效，而且会把第三方 fork 误认成官方。
 * 所以只认启动器**自报的名字**，认不出来就用通用图标，不做任何校验。
 *
 * ### 调用方怎么传
 * 启动器拉起本应用时，在 Intent 里带上两个 extra 即可：
 * ```kotlin
 * Intent(Intent.ACTION_VIEW).apply {
 *     setClassName("com.kongbai.modmigrator",
 *                  "com.kongbai.modmigrator.MainActivity")
 *     putExtra("com.kongbai.modmigrator.LAUNCHER_NAME", "Zalith Launcher 2")
 *     putExtra("com.kongbai.modmigrator.LAUNCHER_VERSION", "2.0.7")
 * }
 * ```
 * 两个 extra 都是可选的：只传名字也行，都不传就是普通启动。
 *
 * 另外也接受 `Intent.EXTRA_REFERRER_NAME` / [Activity.getReferrer()]，
 * 那是系统给的调用方包名，仅作兜底提示，**不参与品牌判定**。
 */
object LauncherBrand {

    /** 一个已知品牌 */
    data class Brand(
        /** 规范化 key */
        val key: String,
        /** 显示名 */
        val label: String,
        @DrawableRes val icon: Int,
    )

    // 关键词全部小写比对。顺序有意义：先匹配到的胜出，
    // 所以更具体的要排在前面（zalith2 必须在 zalith 之前）。
    private val BRANDS = listOf(
        Brand("zalith2", "Zalith Launcher 2", R.drawable.ic_launcher_zalith2),
        Brand("zalith", "Zalith Launcher", R.drawable.ic_launcher_zalith),
        Brand("pojav", "PojavLauncher", R.drawable.ic_launcher_pojav),
        Brand("amethyst", "Amethyst", R.drawable.ic_launcher_amethyst),
        Brand("fcl", "FCL", R.drawable.ic_launcher_fcl),
        Brand("hmcl", "HMCL PE", R.drawable.ic_launcher_hmclpe),
        Brand("mcinabox", "MCinaBox", R.drawable.ic_launcher_mcinabox),
        Brand("mio", "澪 MioLauncher", R.drawable.ic_launcher_mio),
    )

    private val ALL = BRANDS + Brand("other", "其他启动器", R.drawable.ic_launcher_other)

    fun other(): Brand = ALL.last()

    /**
     * 按路径/名字猜品牌。
     * 路径里只要出现关键词就算命中（大小写不敏感），
     * 认不出来返回 [other]——宁可显示通用图标，也不猜错。
     */
    fun fromPath(path: String?): Brand {
        if (path.isNullOrBlank()) return other()
        val p = path.lowercase()
        for (b in BRANDS) {
            if (p.contains(b.key)) return b
        }
        return other()
    }

    fun byKey(key: String): Brand = ALL.firstOrNull { it.key == key } ?: other()

    fun all(): List<Brand> = ALL

    // ── 下面是"来源启动器"接口 ──────────────────────────────

    const val EXTRA_LAUNCHER_NAME = "com.kongbai.modmigrator.LAUNCHER_NAME"
    const val EXTRA_LAUNCHER_VERSION = "com.kongbai.modmigrator.LAUNCHER_VERSION"

    /** 一次"从启动器进来"的记录 */
    data class Handoff(
        val brand: Brand,
        /** 启动器自报的原始名字；未自报时为 "" */
        val rawName: String,
        val version: String,
    ) {
        /** 展示用：优先显示自报的名字，其次品牌名 */
        fun display(): String = rawName.ifBlank { brand.label }
        /** 副标题：版本 */
        fun subtitle(): String =
            if (version.isBlank()) brand.label else "${brand.label} $version"
    }

    /**
     * 从 Intent 里读来源启动器。读不到返回 null（普通启动）。
     * 不做任何包名校验——见类注释。
     */
    fun handoff(a: Activity): Handoff? {
        val i: Intent = a.intent ?: return null
        val name = i.getStringExtra(EXTRA_LAUNCHER_NAME)?.trim().orEmpty()
        val ver = i.getStringExtra(EXTRA_LAUNCHER_VERSION)?.trim().orEmpty()
        if (name.isBlank()) return null
        return Handoff(fromPath(name), name, ver)
    }

    /** 供设置页展示：当前这次是不是从启动器进来的、对方报了什么 */
    fun describe(h: Handoff?): String =
        if (h == null) "普通启动（没有启动器传入信息）"
        else "来自：${h.display()}${if (h.version.isBlank()) "" else " ${h.version}"}"

    fun icon(ctx: Context, b: Brand) = b.icon
}
