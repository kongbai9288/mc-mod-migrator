package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 设置 → 底部导航栏自定义。
 *
 * 可以：把「更多」里的功能移到底部栏、移回「更多」、在底部栏内上下调序。
 *
 * Material 的 BottomNavigationView 硬限制 5 项，
 * 其中主界面还会固定加一个「更多」，所以自定义项实际是 NavConfig.MAX_CUSTOM。
 * 提示文案直接取常量，不再写死数字——写死过一次写成了 5，
 * 用户放 4 个就被拒，提示却说还能再加。
 */
class SettingsNavFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { NavScreen(ctx) } }
        }
    }
}

@Composable
private fun NavScreen(ctx: android.content.Context) {
    // 增删都改这个计数，列表跟着重算
    var gen by remember { mutableIntStateOf(0) }
    val bottom = remember(gen) { NavConfig.bottom(ctx) }
    val more = remember(gen) { NavConfig.more(ctx) }

    fun say(s: String) = runCatching { Tips.short(ctx, s) }
    fun bump() { gen++ }
    fun rebuild() = runCatching { (ctx as? MainActivity)?.rebuildNav() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McNote(
            "底部最多 ${NavConfig.MAX_CUSTOM} 个自定义项（另有一个固定的「更多」，"
                .plus("合计不超过 Material 的 5 项上限）。")
                .plus("\n当前底部 ${bottom.size} 个、更多 ${more.size} 个。")
        )

        McSectionTitle("底部导航栏")
        for (key in bottom) {
            val nav = NavConfig.item(key) ?: continue
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val icon = runCatching { painterResource(nav.icon) }.getOrNull()
                if (icon != null) {
                    Icon(icon, null, Modifier.padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                Text(ctx.getString(nav.title), fontSize = 14.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    if (!NavConfig.moveInBottom(ctx, key, true)) say("已经在最上面")
                    bump()
                }) { Text("↑", fontSize = 14.sp) }
                IconButton(onClick = {
                    if (!NavConfig.moveInBottom(ctx, key, false)) say("已经在最下面")
                    bump()
                }) { Text("↓", fontSize = 14.sp) }
                IconButton(onClick = {
                    if (!NavConfig.moveToMore(ctx, key)) say("底部至少保留 1 项")
                    else { say("已移到「更多」"); rebuild() }
                    bump()
                }) { Text("移出", fontSize = 12.sp) }
            }
        }

        McSectionTitle("更多（不在底部栏的）")
        for (page in more) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val icon = runCatching { painterResource(page.icon) }.getOrNull()
                if (icon != null) {
                    Icon(icon, null, Modifier.padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(ctx.getString(page.title), fontSize = 14.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    if (!NavConfig.moveToBottom(ctx, page.key)) {
                        say("底部自定义项已满（${NavConfig.MAX_CUSTOM} 个），先从上面移一个出来")
                    } else { say("已移到底部导航栏"); rebuild() }
                    bump()
                }) { Text("移到底部", fontSize = 12.sp) }
            }
        }
    }
}
