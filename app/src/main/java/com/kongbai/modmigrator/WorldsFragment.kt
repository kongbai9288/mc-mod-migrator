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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import coil.compose.AsyncImage
import java.io.File

/**
 * 世界存档：查看信息 + 改名。
 *
 * 渲染改成 Compose，扫描逻辑原样保留。
 *
 * ⚠️ 之前所有目录操作都走 SAF（DocumentFile），但 SAF 进不去
 * Android/data，而很多启动器的 .minecraft 就在那下面 —— 表现就是
 * "扫不到存档"。所以两条路都留：有「所有文件访问」权限直接用 File，
 * 没有就走已授权的目录（SAF），退而求其次。
 */
class WorldsFragment : Fragment() {

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
            setContent { McTheme(ctx) { WorldsScreen(ctx) { resumeCb = it } } }
        }
    }
}

@Composable
private fun WorldsScreen(ctx: android.content.Context, register: (() -> Unit) -> Unit) {
    var worlds by remember { mutableStateOf(listOf<WorldInfo.World>()) }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf("正在找存档…") }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<WorldInfo.World?>(null) }

    fun load() {
        loading = true
        progress = "正在找存档…"
        Bg.io.execute {
            val list = ArrayList<WorldInfo.World>()
            for (d in findSavesDirs(ctx)) {
                Bg.ui.post { progress = "正在找存档…（已看 ${list.size} 个）" }
                list.addAll(WorldInfo.listIn(d))
            }
            // SAF 授权目录走另一条路
            val gameUri = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""
            if (gameUri.startsWith("content://")) {
                Bg.ui.post { progress = "正在读取授权目录里的存档…" }
                list.addAll(loadFromSaf(ctx, gameUri))
            }
            list.sortByDescending { it.lastPlayed }
            Bg.ui.post {
                worlds = list
                loading = false
            }
        }
    }

    var loaded by remember { mutableStateOf(false) }
    if (!loaded) {
        loaded = true
        load()
    }
    ResumeEffect(register) { load() }

    val q = query.trim()
    val shown = remember(worlds, q, sortMode) {
        val filtered = if (q.isBlank()) worlds
        else worlds.filter { it.name.contains(q, true) }
        when (sortMode) {
            1 -> filtered.sortedBy { it.name }
            2 -> filtered.sortedByDescending { it.sizeBytes }
            else -> filtered.sortedByDescending { it.lastPlayed }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        McScope {
            McNote("只读取展示；要改具体数值请用 NBT 编辑器。")
            McSearchField(query, { query = it }, "搜存档名")
            McChipRow(listOf("最近游玩", "按名字", "按体积"), sortMode) { sortMode = it }

            if (loading) {
                Text(progress, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                // 摆三块：够撑起一屏，又不至于多到让人以为已经扫出这么多
                repeat(3) { McSkeleton(3) }
                return@McScope
            }

            if (worlds.isEmpty()) {
                McEmpty(
                    "没找到存档",
                    "先在设置 → 存储里把游戏目录指到启动器的 .minecraft，" +
                        "并且确认那个启动器至少启动过一次游戏。"
                )
                return@McScope
            }

            Text(
                if (q.isBlank()) "共 ${shown.size} 个存档" else "匹配 ${shown.size} / ${worlds.size} 个",
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (shown.isEmpty()) {
                McEmpty("没有匹配的存档", "换个名字再试试")
                return@McScope
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.dir.absolutePath }) { w ->
                    WorldCard(w) { detail = w }
                }
            }
        }
    }

    detail?.let { w ->
        WorldDetail(ctx, w) { detail = null }
    }
}

@Composable
private fun WorldCard(w: WorldInfo.World, onDetail: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(w.name)
                Text(
                    buildString {
                        append("版本 ${w.mcVersion.ifBlank { "未知" }} · ")
                        append("${w.gameTypeText()} · ${w.difficultyText()}\n")
                        append("种子 ${w.seed?.toString() ?: "未公开"}\n")
                        append("上次游玩 ${WorldInfo.timeText(w.lastPlayed)}\n")
                        append("游戏内 ${w.days} 天 · ${w.sizeText()}")
                        if (w.allowCommands) append("\n允许作弊：是")
                        if (w.dataVersion > 0) append("\n数据版本 ${w.dataVersion}")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 可能装着好几个启动器，存档目录名又各不相同，
            // 光看路径分不清属于谁 —— 按路径认出品牌，配一枚图标
            val brand = LauncherBrand.fromPath(w.dir.absolutePath)
            androidx.compose.foundation.Image(
                painter = painterResource(brand.icon),
                contentDescription = "属于 ${brand.label}",
                modifier = Modifier.size(20.dp).padding(end = 4.dp)
            )
            TextButton(onClick = onDetail) { Text("详情") }
        }
    }
}

@Composable
private fun WorldDetail(ctx: android.content.Context, w: WorldInfo.World, onDismiss: () -> Unit) {
    McActionDialog(
        title = w.name,
        onDismiss = onDismiss,
        actions = listOf(
            "用 NBT 编辑器打开" to {
                onDismiss()
                NbtViewerActivity.open(ctx, File(w.dir, "level.dat"))
            },
            "区块地图" to {
                onDismiss()
                ChunkMapActivity.open(ctx, w.dir)
            }
        )
    ) {
        val brand = LauncherBrand.fromPath(w.dir.absolutePath)
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                painter = painterResource(brand.icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Text("属于 ${brand.label}", Modifier.padding(start = 8.dp), fontSize = 12.sp)
        }
        w.iconPath?.let { p ->
            AsyncImage(
                model = File(p),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .size(height = 160.dp, width = 160.dp)
            )
        }
    }
}

/** 找所有可能的 saves 目录（File 优先，SAF 兜底） */
private fun findSavesDirs(ctx: android.content.Context): List<File> {
    val out = LinkedHashSet<File>()
    val game = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""
    if (game.isNotBlank() && !game.startsWith("content://")) {
        val base = File(game)
        for (cand in listOf(
            File(base, McDirs.SAVES), base,
            File(base, ".minecraft/saves")
        )) {
            // 只要求是目录：世界是它下面的子目录，
            // 判断 level.dat 该由 WorldInfo.listIn 在子目录上做
            if (cand.isDirectory) out.add(cand)
        }
    }
    // 有「所有文件访问」权限时，直接扫已知启动器目录
    if (Perms.allFiles()) {
        for ((_, f) in LauncherDirs.detect(ctx)) {
            val s = File(f, McDirs.SAVES)
            if (s.isDirectory) out.add(s)
            // 版本隔离：mods 在 versions/<v>/ 下，saves 也可能在那
            val versions = File(f, McDirs.VERSIONS)
            versions.listFiles()?.forEach { v ->
                val vs = File(v, McDirs.SAVES)
                if (vs.isDirectory) out.add(vs)
            }
        }
    }
    return out.toList()
}

/**
 * SAF 授权目录（content://）下的存档。
 * 逐个把 level.dat 读成字节再解析 —— SAF 拿不到真实 File。
 */
private fun loadFromSaf(ctx: android.content.Context, uriStr: String): List<WorldInfo.World> {
    val tree = Fs.tree(ctx, uriStr) ?: return emptyList()
    val saves = tree.findFile(McDirs.SAVES) ?: tree
    val out = ArrayList<WorldInfo.World>()
    for (d in saves.listFiles()) {
        if (!d.isDirectory) continue
        val lvl = d.findFile("level.dat") ?: continue
        val bytes = readSafBytes(ctx, lvl) ?: continue
        out.add(WorldInfo.fromBytes(d.name ?: "存档", bytes))
    }
    return out
}

private fun readSafBytes(ctx: android.content.Context, f: DocumentFile): ByteArray? {
    return try {
        ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
    } catch (t: Throwable) {
        Err.ignore(t, "读取 SAF 文件")
        null
    }
}
