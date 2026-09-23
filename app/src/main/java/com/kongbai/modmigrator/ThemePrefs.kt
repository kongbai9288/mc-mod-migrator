package com.kongbai.modmigrator

import android.content.Context

/**
 * 主题色切换。
 *
 * 每套主题在 themes.xml 里是一个 style，这里只负责记录用户选了哪套，
 * 并由 Activity 在 setContentView 之前 setTheme 生效。
 */
object ThemePrefs {

    data class Theme(val name: String, val styleRes: Int)

    fun themes(): List<Theme> = listOf(
        Theme("森绿（默认）", R.style.Theme_ModMigrator_Green),
        Theme("深蓝", R.style.Theme_ModMigrator_Blue),
        Theme("紫罗兰", R.style.Theme_ModMigrator_Purple),
        Theme("琥珀橙", R.style.Theme_ModMigrator_Orange),
        Theme("绯红", R.style.Theme_ModMigrator_Red),
        Theme("青碧", R.style.Theme_ModMigrator_Teal)
    )

    fun index(ctx: Context): Int {
        val i = Prefs.get(ctx).getInt(K.THEME, 0)
        return if (i in themes().indices) i else 0
    }

    fun save(ctx: Context, i: Int) {
        Prefs.get(ctx).edit().putInt(K.THEME, i).apply()
    }

    fun styleRes(ctx: Context): Int = themes()[index(ctx)].styleRes
}
