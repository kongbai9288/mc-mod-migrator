package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 插件页：装插件、跑插件、启停、卸载。
 *
 * 插件是**目录里的文件**（manifest.json + 脚本），不是独立 APK。
 * 扫描是读目录，很快；安装要解压，跑脚本可能连网或读写文件，都放后台。
 */
class PluginFragment : Fragment() {

    private var onReload: (() -> Unit)? = null

    private val openZip = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) doInstall(uri) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                McTheme(ctx) {
                    PluginScreen(
                        ctx,
                        pick = {
                            runCatching {
                                openZip.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            }.onFailure {
                                Tips.short(ctx, "打不开文件选择器：${it.message}")
                            }
                        },
                        register = { onReload = it }
                    )
                }
            }
        }
    }

    private fun doInstall(uri: android.net.Uri) {
        val ctx = context ?: return
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        Tips.short(ctx, "正在安装…")
        val app = ctx.applicationContext
        Bg.io.execute {
            val (ok, msg) = PluginHost.installFromZip(app, uri)
            Bg.ui.post {
                if (!isAdded) return@post
                if (ok) {
                    Tips.short(ctx, msg)
                    onReload?.invoke()
                } else {
                    SheetDialog.show(ctx, "安装失败", msg, listOf("知道了" to null))
                }
            }
        }
    }
}

@Composable
private fun PluginScreen(
    ctx: android.content.Context,
    pick: () -> Unit,
    register: (() -> Unit) -> Unit
) {
    var gen by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<PluginHost.Plugin>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var offered by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var samplesOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<PluginHost.Plugin?>(null) }
    var detailText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var uninstallFor by remember { mutableStateOf<PluginHost.Plugin?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        register { gen++ }
        onDispose { register({}) }
    }

    LaunchedEffect(gen) {
        loading = true
        Bg.io.execute {
            val found = PluginHost.scan(ctx.applicationContext)
            Bg.ui.post {
                items = found
                loading = false
                if (found.isEmpty() && !offered) {
                    offered = true
                    samplesOpen = true
                }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = pick, modifier = Modifier.weight(1f)) {
                    Text("安装")
                }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { gen++ },
                    enabled = !loading,
                    modifier = Modifier.weight(1f)
                ) { Text(if (loading) "扫描中…" else "刷新") }
            }
        }
        if (loading && items.isEmpty()) {
            item { McSkeleton(2) }
        } else if (items.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("还没有插件", fontWeight = FontWeight.Bold)
                        Text(
                            "点上面的「安装」选一个插件包，或点「看说明」了解插件长什么样。\n\n" +
                                "插件目录：${PluginHost.dir(ctx.applicationContext).absolutePath}",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { helpOpen = true }) { Text("看说明") }
                    }
                }
            }
        } else {
            items(items) { p -> PluginRow(p) { menuFor = p } }
        }
        item { Spacer(Modifier.size(12.dp)) }
    }

    if (helpOpen) {
        McLongTextDialog("插件怎么用", PluginHost.help(ctx), onDismiss = { helpOpen = false })
    }
    if (samplesOpen) {
        SamplesDialog(
            onWrite = {
                samplesOpen = false
                Bg.io.execute {
                    val n = PluginHost.writeSamples(ctx.applicationContext)
                    Bg.ui.post {
                        Tips.short(
                            ctx,
                            if (n > 0) "已写入 $n 个示例" else "示例已存在，没有重复写入"
                        )
                        if (n > 0) gen++
                    }
                }
            },
            onDismiss = { samplesOpen = false }
        )
    }
    menuFor?.let { p ->
        MenuDialog(p, onPick = { w ->
            menuFor = null
            when (w) {
                0 -> runPlugin(ctx, p) { out -> resultText = out.take(8000) }
                1 -> {
                    PluginHost.setEnabled(ctx, p.id, !p.enabled)
                    gen++
                }
                2 -> detailText = Pair(p.title(), describe(p))
                3 -> uninstallFor = p
            }
        }, onDismiss = { menuFor = null })
    }
    detailText?.let { (t, b) ->
        McLongTextDialog(t, b, onDismiss = { detailText = null })
    }
    uninstallFor?.let { p ->
        UninstallDialog(
            title = "卸载 ${p.title()}",
            onConfirm = {
                uninstallFor = null
                Bg.io.execute {
                    val ok = PluginHost.uninstall(ctx, p)
                    Bg.ui.post {
                        Tips.short(ctx, if (ok) "已卸载" else "删不掉这个目录")
                        gen++
                    }
                }
            },
            onDismiss = { uninstallFor = null }
        )
    }
    resultText?.let { out ->
        McLongTextDialog("执行结果", out, onDismiss = { resultText = null })
    }
}

@Composable
private fun PluginRow(p: PluginHost.Plugin, onMore: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (p.enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(p.title(), fontWeight = FontWeight.Bold)
            Text(
                buildString {
                    append(if (p.enabled) "已启用" else "已禁用")
                    append(" · ").append(p.engine)
                    if (p.version.isNotBlank()) append(" · v").append(p.version)
                    if (p.permissions.isNotEmpty()) {
                        append(" · 权限：").append(p.permissions.joinToString("、"))
                    }
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            TextButton(onClick = onMore) { Text("运行 / 启停 / 详情 / 卸载") }
        }
    }
}

@Composable
private fun MenuDialog(
    p: PluginHost.Plugin,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val acts = listOf("运行", if (p.enabled) "禁用" else "启用", "详情", "卸载")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.title()) },
        text = {
            Column {
                acts.forEachIndexed { i, a ->
                    TextButton(
                        onClick = { onPick(i) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(a) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SamplesDialog(onWrite: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("写入示例插件") },
        text = {
            Text(
                "会在插件目录里写入三个能跑的示例（打招呼 / 列目录 / 取网页）。\n\n" +
                    "同名目录已存在时跳过，不会覆盖你改过的东西。\n\n" +
                    "跑通之后再照着改成自己的，比对着说明猜快。"
            )
        },
        confirmButton = { Button(onClick = onWrite) { Text("写入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun UninstallDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text("会删掉整个插件目录，脚本和清单一起没，不可恢复。\n\n只是暂时不想用的话，点「禁用」就够了。")
        },
        confirmButton = { Button(onClick = onConfirm) { Text("卸载") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun describe(p: PluginHost.Plugin): String = buildString {
    append("id：").append(p.id).append('\n')
    append("版本：").append(p.version.ifBlank { "未写" }).append('\n')
    append("作者：").append(p.author.ifBlank { "未写" }).append('\n')
    append("引擎：").append(p.engine).append('\n')
    append("入口：").append(p.entry?.name ?: "找不到").append('\n')
    append("权限：").append(
        if (p.permissions.isEmpty()) "无" else p.permissions.joinToString("、")
    ).append('\n')
    append("状态：").append(if (p.enabled) "已启用" else "已禁用").append('\n')
    if (p.desc.isNotBlank()) append('\n').append(p.desc)
}

private fun runPlugin(
    ctx: android.content.Context,
    p: PluginHost.Plugin,
    onDone: (String) -> Unit
) {
    if (!p.enabled) {
        Tips.short(ctx, "这个插件已禁用")
        return
    }
    Tips.short(ctx, "正在执行…")
    val app = ctx.applicationContext
    Bg.io.execute {
        val out = PluginHost.run(app, p)
        Bg.ui.post { onDone(out) }
    }
}
