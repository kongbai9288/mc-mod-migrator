package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 设置 → 内置数据更新。
 *
 * 做成界面而不是静默后台更新：自动更新 6 小时一次、失败了什么都不说，等于没有。
 * 现在每项显示内置条数、远端条数、上次更新时间，手动刷新立刻执行并把结果写出来。
 */
class SettingsPatchFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { PatchScreen(ctx) } }
        }
    }
}

@Composable
private fun PatchScreen(ctx: android.content.Context) {
    var gen by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(gen) { busy = false }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McNote(
            "应用内置的数据会随 MC 与启动器变化而过期。下面的内容可以从仓库单独更新，"
                .plus("装了旧版本也能拿到新数据，不需要重装应用。")
        )

        val kinds = PatchCenter.all()
        for (k in kinds) {
            Card(
                Modifier.padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("${k.title}（${k.key}）", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(k.desc, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val at = PatchCenter.lastAt(ctx, k)
                    Text(
                        buildString {
                            append("内置 ${k.builtinCount} 项")
                            if (k.remoteCount > 0) append(" · 远端 ${k.remoteCount} 项")
                            append(" · 上次更新 ${PatchCenter.timeText(at)}")
                            if (k.lastError.isNotBlank()) append("\n${k.lastError}")
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    McFilledButton("刷新", !busy) {
                        busy = true
                        Tips.short(ctx, "正在刷新「${k.title}」…")
                        Bg.io.execute {
                            val ok = PatchCenter.update(ctx, k, force = true)
                            Bg.ui.post {
                                gen++
                                Tips.long(
                                    ctx,
                                    if (ok) "「${k.title}」已更新，共 ${k.remoteCount} 项"
                                    else "「${k.title}」更新失败：${k.lastError}"
                                )
                            }
                        }
                    }
                }
            }
        }
        if (kinds.isEmpty()) McNote("暂无可更新的内容")

        McButtonRow("全部刷新", {
            busy = true
            Tips.short(ctx, "正在刷新全部…")
            Bg.io.execute {
                var ok = 0
                for (k in kinds) if (PatchCenter.update(ctx, k, force = true)) ok++
                Bg.ui.post {
                    gen++
                    Tips.long(ctx, "完成：$ok / ${kinds.size} 项已更新")
                }
            }
        }, "刷新界面", { gen++ })
    }
}
