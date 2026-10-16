package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 「更多」页：所有没放到底部导航栏的功能都在这里。
 *
 * 按用途分组 + 方格网格，一屏能看全，不用靠记位置找东西。
 *
 * 每一项都可以「移到底部导航栏」（长按）；在设置 → 导航栏自定义里可以反向移回来。
 */
class MoreFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                McTheme(ctx) {
                    MoreScreen(
                        ctx,
                        onMove = { key -> moveToBottom(key) },
                        onOpen = { p -> openPage(p) }
                    )
                }
            }
        }
    }

    private fun openPage(p: NavConfig.Item) {
        try {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, p.make())
                .addToBackStack(null)
                .commit()
        } catch (t: Throwable) { Err.ignore(t, ".commit()") }
    }

    /** 长按卡片：把这一项移到底部导航栏。返回是否真的动了 */
    private fun moveToBottom(key: String): Boolean {
        val ctx = context ?: return false
        val ok = NavConfig.moveToBottom(ctx, key)
        Tips.short(ctx, if (ok) "已移到底部导航栏" else "底部最多 4 个，先移一个出来")
        if (ok) {
            (activity as? MainActivity)?.rebuildNav()
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MoreFragment())
                .commit()
        }
        return ok
    }
}

@Composable
private fun MoreScreen(
    ctx: android.content.Context,
    onMove: (String) -> Boolean,
    onOpen: (NavConfig.Item) -> Unit
) {
    // 移走一项后列表会变短，用计数触发重算
    var gen by remember { mutableStateOf(0) }
    val pages = remember(gen) { NavConfig.more(ctx) }

    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item { McNote(stringResource(R.string.more_hint)) }

        if (pages.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("所有功能都在底部栏了", fontWeight = FontWeight.Bold)
                        McNote("到「设置 → 底部导航栏」可以把功能移回这里。")
                    }
                }
            }
            return@LazyColumn
        }

        for (g in GROUPS) {
            val list = pages.filter { groupOf(it.key) == g }
            if (list.isEmpty()) continue
            item { McSectionTitle(g) }
            item {
                TileGrid(
                    list,
                    onOpen = onOpen,
                    onLong = { p -> if (onMove(p.key)) gen++ }
                )
            }
        }

        // 兜底：分组覆盖了全部已知 key，但万一以后加了新项忘了归类，
        // 不能让它凭空消失 —— 归到「其他」里照常显示。
        val others = pages.filter { p -> GROUPS.none { it == groupOf(p.key) } }
        if (others.isNotEmpty()) {
            item { McSectionTitle("其他") }
            item {
                TileGrid(
                    others,
                    onOpen = onOpen,
                    onLong = { p -> if (onMove(p.key)) gen++ }
                )
            }
        }
    }
}

private val GROUPS = listOf("常用", "商店与社区", "服务器与传输", "工具与文件", "设置与其他")

private fun groupOf(key: String): String = when (key) {
    "migration", "mods", "update", "tools", "favorites" -> "常用"
    "market", "devs", "feed", "weekly" -> "商店与社区"
    "server", "sync", "sites" -> "服务器与传输"
    "plugin", "trash" -> "工具与文件"
    "settings", "log" -> "设置与其他"
    else -> "其他"
}

@Composable
private fun TileGrid(
    list: List<NavConfig.Item>,
    onOpen: (NavConfig.Item) -> Unit,
    onLong: (NavConfig.Item) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        for (row in list.chunked(3)) {
            Row(Modifier.fillMaxWidth()) {
                for (p in row) {
                    Box(Modifier.weight(1f).padding(4.dp)) {
                        Tile(p, onOpen, onLong)
                    }
                }
                // 最后一行不满时补空位，否则剩下的格子会被 weight 拉宽、跟上面几行对不齐
                repeat(3 - row.size) { Spacer(Modifier.weight(1f).padding(4.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    p: NavConfig.Item,
    onOpen: (NavConfig.Item) -> Unit,
    onLong: (NavConfig.Item) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onOpen(p) }, onLongClick = { onLong(p) }),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painterResource(p.icon),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource(p.title),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
