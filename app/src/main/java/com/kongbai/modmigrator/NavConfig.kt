package com.kongbai.modmigrator

import android.content.Context
import androidx.fragment.app.Fragment

/**
 * 底部导航栏自定义。
 *
 * 用户可以：
 *   - 把「更多」里的功能移到底部栏（正向）
 *   - 把底部栏的功能移回「更多」（反向）
 *   - 在底部栏内上下调序
 *
 * Material 的 BottomNavigationView 硬限制 5 项：这里底部最多放 5 个自定义项，
 * 主界面还会固定加一个「更多」，所以实际自定义上限是 4。
 */
object NavConfig {

    /** 底部自定义项上限：主界面要留一个位置给固定的「更多」 */
    const val MAX_CUSTOM = 4

    data class Item(
        val key: String,
        val title: Int,
        val icon: Int,
        val make: () -> Fragment
    )

    fun items(): List<Item> = listOf(
        Item("migration", R.string.tab_migration, R.drawable.ic_swap_horiz) { MigrationFragment() },
        Item("market", R.string.tab_market, R.drawable.ic_storefront) { MarketFragment() },
        Item("server", R.string.tab_server, R.drawable.ic_cloud_sync) { ServerFragment() },
        Item("sync", R.string.tab_sync, R.drawable.ic_devices) { SyncFragment() },
        Item("update", R.string.tab_update, R.drawable.ic_rocket_launch) { ModpackFragment() },
        Item("tools", R.string.tab_tools, R.drawable.ic_bolt) { ToolsFragment() },
        Item("plugin", R.string.tab_plugin, R.drawable.ic_extension) { PluginFragment() },
        Item("settings", R.string.tab_settings, R.drawable.ic_settings) { SettingsMainFragment() },
        Item("devs", R.string.menu_devs, R.drawable.ic_person) { DevsFragment() },
        Item("log", R.string.menu_log, R.drawable.ic_info) { SettingsLogFragment() }
    )

    fun defaultKeys(): List<String> = listOf("migration", "market")

    // ============ 读取 / 保存 ============

    /** 当前放到底部的 key（有序） */
    fun bottom(ctx: Context): List<String> {
        val raw = Prefs.get(ctx).getString(K.NAV_KEYS, null)
        if (raw.isNullOrBlank()) return defaultKeys()
        val all = items().map { it.key }
        val list = raw.split(",").map { it.trim() }
            .filter { it in all }
            .distinct()
            .take(MAX_CUSTOM)
        return list.ifEmpty { defaultKeys() }
    }

    /** 兼容旧调用名 */
    fun keys(ctx: Context): List<String> = bottom(ctx)

    /** 不在底部的项（显示在「更多」里） */
    fun more(ctx: Context): List<Item> {
        val inNav = bottom(ctx)
        return items().filter { it.key !in inNav }
    }

    fun morePages(ctx: Context): List<Item> = more(ctx)

    fun navPages(ctx: Context): List<Item> {
        val map = items().associateBy { it.key }
        return bottom(ctx).mapNotNull { map[it] }
    }

    fun item(key: String): Item? = items().firstOrNull { it.key == key }
    fun find(key: String): Item? = item(key)

    // ============ 移动操作（双向） ============

    /** 在底部栏内上下调序。up=true 上移。返回是否真的动了。 */
    fun moveInBottom(ctx: Context, key: String, up: Boolean): Boolean {
        val cur = bottom(ctx).toMutableList()
        val i = cur.indexOf(key)
        if (i < 0) return false
        val j = if (up) i - 1 else i + 1
        if (j < 0 || j >= cur.size) return false
        val t = cur[i]; cur[i] = cur[j]; cur[j] = t
        save(ctx, cur)
        return true
    }

    /** 底部 → 更多。底部至少保留 1 项。 */
    fun moveToMore(ctx: Context, key: String): Boolean {
        val cur = bottom(ctx).toMutableList()
        if (cur.size <= 1) return false
        if (!cur.remove(key)) return false
        save(ctx, cur)
        return true
    }

    /** 更多 → 底部。底部最多 4 项（要留一个位置给「更多」）。 */
    fun moveToBottom(ctx: Context, key: String): Boolean {
        val cur = bottom(ctx).toMutableList()
        if (cur.size >= MAX_CUSTOM) return false
        if (cur.contains(key)) return false
        if (item(key) == null) return false
        cur.add(key)
        save(ctx, cur)
        return true
    }

    fun save(ctx: Context, list: List<String>) {
        Prefs.get(ctx).edit()
            .putString(K.NAV_KEYS, list.take(MAX_CUSTOM).joinToString(","))
            .apply()
    }

    // ============ 菜单 id ============

    fun idOf(key: String): Int {
        val i = items().indexOfFirst { it.key == key }
        return 1000 + (if (i < 0) 0 else i)
    }

    fun keyOfId(id: Int): String? {
        val i = id - 1000
        val ps = items()
        return if (i in ps.indices) ps[i].key else null
    }
}
