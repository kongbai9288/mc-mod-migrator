package com.kongbai.modmigrator

import androidx.fragment.app.Fragment

/**
 * 设置子页的「页面 key → 界面 / 标题」映射。
 *
 * ## 为什么收在一处
 *
 * 之前这份映射写了三份：`MainActivity.openSettingsPage()`、
 * `SettingsHostActivity.show()`、`SettingsHostActivity.showCurrent()`，
 * 各写一个 when，而且**互不一样**：
 *
 *   - `show()` 里没有 "lab"、"feed"、"patch"
 *   - `showCurrent()` 里没有 "lab"、"feed"
 *   - "patch" 在 `showCurrent()` 里有、在 `show()` 里没有
 *
 * 而设置主页的 `go(page)` 一旦处在 SettingsHostActivity 里走的就是 `show()`，
 * 于是「实验室 / 高级」「MC 资讯」「补丁」这三项点进去会落到
 * `else -> SettingsMainFragment()` —— 界面停在设置入口页不动，
 * 看着就像这几个页面坏了、点不开。
 *
 * 同一个 "patch" 在两个方法里行为还不一样：正常点进去显示入口页，
 * 重建之后（切主题色会 recreate）反而能显示出来，前后不一致。
 *
 * 现在两处都从这里取，以后加子页只改这一张表。
 *
 * ## 关于 else
 *
 * 未知 key 一律落到设置入口页而不是“什么都不做”。
 * 以前 `showCurrent()` 遇到未知 key 直接 return，
 * 容器里就留着上一个 Fragment，界面看着像卡住了。
 */
object SettingsPages {

    fun make(page: String): Fragment = when (page) {
        "backend" -> SettingsBackendFragment()
        "search" -> SettingsSearchFragment()
        "migrate" -> SettingsMigrateFragment()
        "storage" -> SettingsStorageFragment()
        "nav" -> SettingsNavFragment()
        "theme" -> SettingsThemeFragment()
        "lang" -> SettingsLangFragment()
        "anim" -> SettingsAnimFragment()
        "translate" -> SettingsTranslateFragment()
        "plugin" -> PluginFragment()
        "devs" -> DevsFragment()
        "log" -> SettingsLogFragment()
        "patch" -> SettingsPatchFragment()
        "about" -> SettingsAboutFragment()
        "lab" -> SettingsLabFragment()
        "feed" -> McFeedFragment()
        // 下面这几项平时是一级 tab，但从「更多」进设置时也在容器里打开，
        // 所以一并列进来，别让它们掉进 else
        "favorites" -> FavoritesFragment()
        "weekly" -> WeeklyReportFragment()
        "sites" -> SitesFragment()
        "more" -> MoreFragment()
        else -> SettingsMainFragment()
    }

    fun titleRes(page: String): Int = when (page) {
        "backend" -> R.string.menu_backend
        "search" -> R.string.menu_search
        "migrate" -> R.string.menu_migrate
        "storage" -> R.string.menu_storage
        "nav" -> R.string.menu_nav
        "theme" -> R.string.theme_title
        "lang" -> R.string.lang_title
        "anim" -> R.string.anim_title
        "translate" -> R.string.menu_translate
        "plugin" -> R.string.menu_plugin
        "devs" -> R.string.menu_devs
        "log" -> R.string.menu_log
        "patch" -> R.string.menu_patch
        "about" -> R.string.menu_about
        "lab" -> R.string.menu_lab
        "feed" -> R.string.tab_feed
        "favorites" -> R.string.tab_favorites
        "weekly" -> R.string.tab_weekly
        "sites" -> R.string.tab_sites
        "more" -> R.string.tab_more
        else -> R.string.tab_settings
    }
}
