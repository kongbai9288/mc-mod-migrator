package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 设置 → 主题色。
 *
 * 11 套配色 + 深色模式三选一。
 *
 * 配色用三列网格：竖排一列时后半截要滚动才能看到，
 * 看着像"只有这几个配色"。
 *
 * 每个配色前面带色块：11 个中文名摆在一起只能逐条读，
 * "森绿""青碧""碧蓝"根本分不清谁是谁。
 *
 * 选中项用描边表示，不再往名字前加 "✓" ——
 * 那个符号是纯文本，某些字体下显示成方框或干脆不显示。
 */
class SettingsThemeFragment : Fragment() {

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
            setContent { McTheme(ctx) { ThemeScreen(ctx) } }
        }
    }
}

@Composable
private fun ThemeScreen(ctx: android.content.Context) {
    var cur by remember { mutableIntStateOf(ThemePrefs.index(ctx)) }
    var night by remember { mutableIntStateOf(ThemePrefs.nightMode(ctx)) }
    var pending by remember { mutableStateOf<Int?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McSectionTitle("深色模式")
        McChipRow(listOf("跟随系统", "浅色", "深色"), night) {
            if (it == night) return@McChipRow
            night = it
            ThemePrefs.setNightMode(ctx, it)
        }

        McSectionTitle("配色")
        val items = ThemePrefs.themes()
        for (row in items.chunked(3)) {
            Row(Modifier.fillMaxWidth()) {
                for (t in row) {
                    val i = items.indexOf(t)
                    SwatchCell(
                        name = t.name,
                        color = Color(t.seedColor),
                        picked = i == cur,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (i == cur) {
                            Tips.short(ctx, "已经是「${t.name}」了")
                            return@SwatchCell
                        }
                        pending = i
                    }
                }
                // 末尾不满一行时补空位，否则最后一格会被拉宽
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        McNote("切换配色会重建界面，改动立即生效。")
    }

    val p = pending
    if (p != null) {
        val name = ThemePrefs.themes()[p].name
        McActionDialog(
            name,
            { pending = null },
            listOf(
                ctx.getString(R.string.ok) to {
                    // commit 而不是 apply：重建会立刻读这个值，
                    // apply 是异步落盘的，新 Activity 可能读到旧配色
                    val prev = cur
                    ThemePrefs.save(ctx, p)
                    cur = p
                    pending = null
                    (ctx as? android.app.Activity)?.recreateSafely()
                        ?: run { ThemePrefs.save(ctx, prev); cur = prev }
                }
            )
        ) {
            Text(
                ctx.getString(R.string.theme_apply),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwatchCell(
    name: String,
    color: Color,
    picked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val stroke = if (picked) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (picked) 2.5.dp else 0.dp,
                    color = stroke,
                    shape = CircleShape
                )
        )
        Text(
            name,
            fontSize = 12.sp,
            color = if (picked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (picked) {
            Text(
                "当前",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
