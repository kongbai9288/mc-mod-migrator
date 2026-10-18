package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站。
 *
 * 数据层（Trash）原样保留，只把渲染换成 Compose。
 * 能看、能还原、能彻底删除、能清空，保留天数可在这里改。
 *
 * ⚠️ Trash.items() / totalSize() / count() 都是读磁盘上的 JSON 索引，
 * deleteForever / empty 还要走 SAF 删文件，全是 IO。
 * 所以这里的规矩和老版本一样：IO 一律放后台，只把结果带回主线程改状态。
 */
class TrashFragment : Fragment() {

    private var resumeCb: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        resumeCb?.invoke()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { TrashScreen(ctx) { resumeCb = it } } }
        }
    }
}

@Composable
private fun TrashScreen(ctx: android.content.Context, register: (() -> Unit) -> Unit) {
    var items by remember { mutableStateOf(listOf<Trash.Item>()) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var days by remember { mutableStateOf(Trash.days(ctx)) }
    var pendingDelete by remember { mutableStateOf<Trash.Item?>(null) }
    var askEmpty by remember { mutableStateOf(false) }
    var askDays by remember { mutableStateOf(false) }

    fun refresh() {
        Bg.io.execute {
            val got = runCatching { Trash.items(ctx) }.getOrDefault(emptyList())
            Bg.ui.post { items = got }
        }
    }

    var loaded by remember { mutableStateOf(false) }
    if (!loaded) {
        loaded = true
        refresh()
    }

    ResumeEffect(register) {
        // 回到前台顺手清一次过期的
        Bg.io.execute {
            runCatching { Trash.purgeExpired(ctx) }
            Bg.ui.post { refresh() }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        McScope {
            McNote("删掉的模组先放这里，过期自动清掉。点「还原」可放回原处。")

            if (items.isEmpty()) {
                McEmpty("回收站是空的", "删掉模组后会出现在这里。")
                return@McScope
            }

            Text(
                "共 ${items.size} 个 · 保留 $days 天",
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            McButtonRow(
                left = "保留天数：$days 天", onLeft = { askDays = true },
                right = "清空回收站", onRight = { askEmpty = true }
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = picked.size == items.size,
                    onCheckedChange = { on ->
                        picked = if (on) items.map { it.name }.toSet() else emptySet()
                    }
                )
                Text("全选", fontSize = 13.sp)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                McFilledButton(
                    text = if (picked.isEmpty()) "还原所选" else "还原所选（${picked.size}）",
                    enabled = picked.isNotEmpty(),
                    onClick = { restoreSelected(ctx, picked.toList()) { picked = emptySet(); refresh() } }
                )
            }

            val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
            LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)) {
                items(items, key = { it.name }) { item ->
                    McCheckRow(
                        checked = picked.contains(item.name),
                        onChecked = { on ->
                            picked = if (on) picked + item.name else picked - item.name
                        }
                    ) {
                        TrashCard(ctx, item, fmt) {
                            Bg.io.execute {
                                val ok = Trash.restore(ctx, item)
                                Bg.ui.post {
                                    Tips.short(
                                        ctx, if (ok) "已还原" else "还原失败（原目录可能已不可用）"
                                    )
                                    refresh()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        McConfirm(
            title = "彻底删除",
            body = "「${item.restoreName}」将被永久删除，无法还原。",
            confirmText = "删除",
            onConfirm = {
                pendingDelete = null
                Bg.io.execute {
                    Trash.deleteForever(ctx, item)
                    Bg.ui.post { refresh() }
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }

    if (askEmpty) {
        McConfirm(
            title = "清空回收站",
            body = "回收站里 ${items.size} 个文件会被永久删除。",
            confirmText = "清空",
            onConfirm = {
                askEmpty = false
                Bg.io.execute {
                    val n = Trash.empty(ctx)
                    Bg.ui.post {
                        Tips.short(ctx, "已清空 $n 个")
                        refresh()
                    }
                }
            },
            onDismiss = { askEmpty = false }
        )
    }

    if (askDays) {
        val opts = listOf("1 天", "3 天", "7 天", "15 天", "30 天", "90 天")
        val vals = listOf(1, 3, 7, 15, 30, 90)
        McPickOne(
            title = "回收站保留天数",
            options = opts,
            selected = vals.indexOf(days).let { if (it >= 0) it else 2 },
            onPick = { i ->
                Trash.setDays(ctx, vals[i])
                days = vals[i]
                askDays = false
                Tips.short(ctx, "已设为 ${opts[i]}")
            },
            onDismiss = { askDays = false }
        )
    }
}

@Composable
private fun TrashCard(
    ctx: android.content.Context,
    item: Trash.Item,
    fmt: SimpleDateFormat,
    onRestore: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                // 显示原始文件名；回收站里那份如果带序号，在副行说明，
                // 否则用户会看到 sodium(1).jar 却不知道它还原出来叫什么
                Text(item.restoreName, maxLines = 2)
                Text(
                    buildString {
                        append("删除于 ${fmt.format(Date(item.at))} · ${item.size / 1024} KB")
                        if (item.name != item.restoreName) append(" · 回收站内名：${item.name}")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRestore) { Text("还原") }
        }
    }
}

private fun restoreSelected(
    ctx: android.content.Context, names: List<String>, done: () -> Unit
) {
    if (names.isEmpty()) return
    Tips.short(ctx, "正在还原 ${names.size} 个…")
    Bg.io.execute {
        var ok = 0
        // ⚠️ 之前 Trash.items(ctx) 写在循环里 —— 每还原一个就把整个索引
        // JSON 重新读一遍，还原 20 个 = 读 20 次文件，而且每还原一个都会
        // 改写一次索引，读到的还是上一版快照。先读一次，循环里只做还原。
        val all = runCatching { Trash.items(ctx) }.getOrDefault(emptyList())
        for (n in names) {
            val item = all.firstOrNull { it.name == n }
            if (item != null && Trash.restore(ctx, item)) ok++
        }
        val doneCount = ok
        Bg.ui.post {
            Tips.short(ctx, "已还原 $doneCount / ${names.size}")
            done()
        }
    }
}
