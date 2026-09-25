package com.kongbai.modmigrator

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题：11 套配色 × 深色/浅色。
 *
 * 配色在 themes.xml 里是 style，这里只负责记录用户选了哪套，
 * 由 Activity 在 setContentView 之前 setTheme 生效。
 *
 * 深色模式独立于配色：跟随系统 / 强制浅色 / 强制深色，
 * 用 AppCompatDelegate 的 night mode 控制，不需要为每套配色再写一份深色 style。
 */
object ThemePrefs {

    data class Theme(val name: String, val styleRes: Int, val seedColor: Int)

    /** 11 套配色，seedColor 用于彩蛋里预览色块 */
    fun themes(): List<Theme> = listOf(
        Theme("森绿", R.style.Theme_ModMigrator_Green, 0xFF2E7D32.toInt()),
        Theme("深蓝", R.style.Theme_ModMigrator_Blue, 0xFF1565C0.toInt()),
        Theme("紫罗兰", R.style.Theme_ModMigrator_Purple, 0xFF6A1B9A.toInt()),
        Theme("琥珀橙", R.style.Theme_ModMigrator_Orange, 0xFFEF6C00.toInt()),
        Theme("绯红", R.style.Theme_ModMigrator_Red, 0xFFC62828.toInt()),
        Theme("青碧", R.style.Theme_ModMigrator_Teal, 0xFF00695C.toInt()),
        Theme("碧蓝", R.style.Theme_ModMigrator_Cyan, 0xFF00838F.toInt()),
        Theme("樱粉", R.style.Theme_ModMigrator_Pink, 0xFFC2185B.toInt()),
        Theme("靛青", R.style.Theme_ModMigrator_Indigo, 0xFF3949AB.toInt()),
        Theme("柠檬", R.style.Theme_ModMigrator_Lime, 0xFF9E9D24.toInt()),
        Theme("摩卡", R.style.Theme_ModMigrator_Brown, 0xFF6D4C41.toInt())
    )

    fun index(ctx: Context): Int {
        val i = Prefs.get(ctx).getInt(K.THEME, 0)
        return if (i in themes().indices) i else 0
    }

    fun save(ctx: Context, i: Int) {
        Prefs.get(ctx).edit().putInt(K.THEME, i.coerceIn(0, themes().lastIndex)).apply()
    }

    fun styleRes(ctx: Context): Int = themes()[index(ctx)].styleRes

    /** 深色模式：0 跟随系统 / 1 强制浅色 / 2 强制深色 */
    fun nightMode(ctx: Context): Int =
        Prefs.get(ctx).getInt(K.NIGHT_MODE, 0).coerceIn(0, 2)

    fun setNightMode(ctx: Context, mode: Int) {
        val m = mode.coerceIn(0, 2)
        Prefs.get(ctx).edit().putInt(K.NIGHT_MODE, m).apply()
        applyNight(m)
    }

    /** 应用夜间模式（进程内立即生效） */
    fun applyNight(mode: Int) {
        val m = when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        try {
            AppCompatDelegate.setDefaultNightMode(m)
        } catch (t: Throwable) { Err.ignore(t, "AppCompatDelegate.setDefaultNightMode(m)") }
    }

    /** 启动时调用一次 */
    fun init(ctx: Context) {
        applyNight(nightMode(ctx))
    }

    fun nightLabel(ctx: Context): String = when (nightMode(ctx)) {
        1 -> "浅色"
        2 -> "深色"
        else -> "跟随系统"
    }
}
