package com.kongbai.modmigrator

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Compose 页面的主题。
 *
 * 配色不在这里另立一套，直接取 ThemePrefs 里那 11 套的 seedColor ——
 * 否则用户在设置里换了主题色，只有老页面跟着变、Compose 页面不变，
 * 同一个应用里出现两种颜色。
 */
object ComposeTheme {

    fun seed(ctx: Context): Color {
        val list = ThemePrefs.themes()
        val i = ThemePrefs.index(ctx).coerceIn(0, list.lastIndex)
        return Color(list[i].seedColor)
    }

    /** 0 跟随系统 / 1 浅色 / 2 深色，与 ThemePrefs 的取值一致 */
    fun dark(ctx: Context): Boolean = when (ThemePrefs.nightMode(ctx)) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
fun McTheme(ctx: Context, content: @Composable () -> Unit) {
    val seed = ComposeTheme.seed(ctx)
    val scheme = if (ComposeTheme.dark(ctx)) {
        darkColorScheme(
            primary = seed,
            onPrimary = Color.White,
            secondary = seed,
            surface = Color(0xFF121212),
            background = Color(0xFF0E0E0E)
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            secondary = seed,
            surface = Color(0xFFFAFAFA),
            background = Color(0xFFF5F5F5)
        )
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
