package com.kongbai.modmigrator

import android.content.Context
import androidx.fragment.app.Fragment

/**
 * 底部导航栏自定义。
 *
 * 用户可以把任意功能移到底部导航栏，也可以移回「更多」——双向都能走。
 * 底部最多 4 个自定义项，第 5 个固定是「更多」（Material 硬性上限 5 项，
 * 超了会在 setContentView 时直接抛异常导致启动崩溃）。
 */
object NavConfig {

    const val MAX_CUSTOM = 4

    data class Page(
        val key: String,
        val titleRes: Int,
        val iconRes: Int,
        val make: () -> Fragment
    )

    /** 所有可放进导航栏 / 更多 的页面 */
    fun pages(): List<Page> = listOf(
        Page("migration", R.string.tab_migration, R.drawable.ic_swap_horiz) { MigrationFragment() },
        Page("market", R.string.tab_market, R.drawable.ic_storefront) { MarketFragment() },
        Page("server", R.string.tab_server, R.drawable.ic_cloud_sync) { ServerFragment() },
        Page("sync", R.string.tab_sync, R.drawable.ic_devices) { SyncFragment() },
        Page("update", R.string.tab_update, R.drawable.ic_rocket_launch) { ModpackFragment() },
        Page("tools", R.string.tab_tools, R.drawable.ic_bolt) { ToolsFragment() },
        Page("plugin", R.string.tab_plugin, R.drawable.ic_extension) { PluginFragment() },
        Page("settings", R.string.tab_settings, R.drawable.ic_settings) { SettingsMainFragment() },
        Page("devs", R.string.menu_devs, R.drawable.ic_person) { DevsFragment() },
        Page("log", R.string.menu_log, R.drawable.ic_info) { SettingsLogFragment() }
    )

    /** 默认放在底部的：迁移、市场（其余进「更多」） */
    fun defaultKeys(): List<String> = listOf("migration", "market")

    /** 当前配置里放到底部的 key（有序） */
    fun keys(ctx: Context): List<String> {
        val raw = Prefs.get(ctx).getString(K.NAV_KEYS, null)
        if (raw.isNullOrBlank()) return defaultKeys()
        val all = pages().map { it.key }
        return raw.split(",").map { it.trim() }
            .filter { it in all }
            .distinct()
            .take(MAX_CUSTOM)
            .ifEmpty { defaultKeys() }
    }

    fun save(ctx: Context, list: List<String>) {
        Prefs.get(ctx).edit()
            .putString(K.NAV_KEYS, list.take(MAX_CUSTOM).joinToString(","))
            .apply()
    }

    /** 没放到底部的（显示在「更多」里） */
    fun morePages(ctx: Context): List<Page> {
        val inNav = keys(ctx)
        return pages().filter { it.key !in inNav }
    }

    fun navPages(ctx: Context): List<Page> {
        val k = keys(ctx)
        val map = pages().associateBy { it.key }
        return k.mapNotNull { map[it] }
    }

    fun find(key: String): Page? = pages().firstOrNull { it.key == key }

    /** 稳定的菜单 id：不能用 hashCode（可能变），按索引生成常量 */
    fun idOf(key: String): Int {
        val i = pages().indexOfFirst { it.key == key }
        return 1000 + (if (i < 0) 0 else i)
    }

    fun keyOfId(id: Int): String? {
        val i = id - 1000
        val ps = pages()
        return if (i in ps.indices) ps[i].key else null
    }
}
