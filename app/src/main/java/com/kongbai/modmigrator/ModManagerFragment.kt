package com.kongbai.modmigrator

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment

/**
 * 模组管理。
 *
 * 渲染改成 Compose，业务逻辑（ModMeta / ModToggle / Trash / BatchModOps）原样保留。
 *
 * 删除**不直接删**：先挪进回收站，可撤销、可还原、超期自动清。
 * 图标优先从本地 jar 提取（ModIcons），取不到退到加载器图标。
 */
class ModManagerFragment : Fragment() {

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
            setContent { McTheme(ctx) { ModManagerScreen(ctx) { resumeCb = it } } }
        }
    }
}

@Composable
private fun ModManagerScreen(ctx: android.content.Context, register: (() -> Unit) -> Unit) {
    var files by remember { mutableStateOf(listOf<DocumentFile>()) }
    var loading by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var stateText by remember { mutableStateOf("扫描中…") }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<DocumentFile?>(null) }
    var askDelete by remember { mutableStateOf<DocumentFile?>(null) }

    fun load() {
        loading = true
        Bg.io.execute {
            val dir = try {
                // 用户选定的游戏 mods 目录优先；没选才退回工作目录。
                // 顺序反了的话，列出来的就不是游戏里那份
                Targets.dstModsDir(ctx) ?: WorkDir.modsDir(ctx)
            } catch (t: Throwable) {
                null
            }
            val list = if (dir == null) emptyList()
            else try {
                Fs.children(dir).filter {
                    // ── 必须把"已禁用"的也列出来 ──────────────────
                    // 禁用是把 x.jar 改名成 x.jar.disabled。之前只认 .jar/.zip，
                    // 于是禁用后的文件从列表里彻底消失，用户再也没法点「启用」。
                    if (!it.isFile) return@filter false
                    val n = it.name ?: return@filter false
                    n.endsWith(".jar", true) || n.endsWith(".zip", true) ||
                        n.endsWith(ModToggle.SUFFIX, true) ||
                        n.endsWith(".zip.disabled", true)
                }
            } catch (t: Throwable) {
                emptyList()
            }
            var total = 0L
            for (f in list) total += runCatching { f.length() }.getOrDefault(0L)
            val n = list.size
            val dn = list.count { ModToggle.isDisabled(it) }
            val txt = if (n == 0) "这个目录里没有模组"
            else buildString {
                append("共 $n 个模组")
                if (dn > 0) append("（$dn 个已禁用）")
                append(" · 占用 ${humanSize(total)}")
            }
            Bg.ui.post {
                files = list
                stateText = txt
                loading = false
                picked = picked.intersect(list.map { it.name ?: "" }.toSet())
            }
        }
    }

    LaunchedEffect(Unit) {
        load()
    }
    ResumeEffect(register) { load() }

    val q = query.trim()
    val shown = remember(files, q) {
        if (q.isBlank()) files else files.filter { (it.name ?: "").contains(q, true) }
    }

    fun pickedFiles(): List<DocumentFile> =
        files.filter { picked.contains(it.name ?: "") }

    fun afterBatch(msg: String) {
        Tips.short(ctx, msg)
        picked = emptySet()
        load()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        McScope {
            McNote("当前目录：${WorkDir.describe(ctx)}")
            McSearchField(query, { query = it }, "搜模组文件名")

            if (loading) {
                Text(stateText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                repeat(3) { McSkeleton(3) }
                return@McScope
            }
            if (files.isEmpty()) {
                McEmpty(
                    "没有找到模组",
                    "到「设置 → 存储」授权你的 mods 目录，或先把模组下载进来。"
                )
                return@McScope
            }

            Text(
                buildString {
                    append(stateText)
                    if (picked.isNotEmpty()) append(" · 已选 ${picked.size} 个")
                },
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 批量操作栏：装几十个模组时一个个点非常折磨
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = picked.isNotEmpty() && picked.size == files.size,
                    onCheckedChange = { on ->
                        picked = if (on) files.map { it.name ?: "" }.toSet() else emptySet()
                    }
                )
                Text("全选", fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth()) {
                McBatchBtn("删除", picked.isNotEmpty()) {
                    val list = pickedFiles()
                    if (list.isEmpty()) return@McBatchBtn
                    val dlg = ProgressDialog.show(ctx, "删除 ${list.size} 个模组")
                    Bg.io.execute {
                        val res = BatchModOps.delete(ctx, list) { i, n, _ ->
                            Bg.ui.post { dlg.update(i.toLong(), n.toLong()) }
                        }
                        Bg.ui.post {
                            dlg.dismiss()
                            afterBatch(BatchModOps.summary(res, "删除"))
                        }
                    }
                }
                McBatchBtn("禁用", picked.isNotEmpty()) {
                    Bg.io.execute {
                        val res = BatchModOps.toggle(pickedFiles(), true)
                        Bg.ui.post { afterBatch(BatchModOps.summary(res, "禁用")) }
                    }
                }
                McBatchBtn("启用", picked.isNotEmpty()) {
                    Bg.io.execute {
                        val res = BatchModOps.toggle(pickedFiles(), false)
                        Bg.ui.post { afterBatch(BatchModOps.summary(res, "启用")) }
                    }
                }
                McBatchBtn("升级", picked.isNotEmpty()) {
                    val list = pickedFiles()
                    if (list.isEmpty()) return@McBatchBtn
                    val mc = Prefs.get(ctx).getString(K.MC_VERSION, "") ?: ""
                    val loader = Prefs.get(ctx).getString(K.LOADER, "") ?: "auto"
                    if (mc.isBlank()) {
                        Tips.short(ctx, "先在设置里填目标 MC 版本")
                        return@McBatchBtn
                    }
                    doBatchUpgrade(ctx, mc, loader, list) { afterBatch(it) }
                }
            }

            if (shown.isEmpty()) {
                McEmpty("没有匹配的模组", "换个名字再试试")
                return@McScope
            }

            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                items(shown, key = { it.uri.toString() }) { f ->
                    McCheckRow(
                        checked = picked.contains(f.name ?: ""),
                        onChecked = { on ->
                            val n = f.name ?: ""
                            picked = if (on) picked + n else picked - n
                        }
                    ) {
                        ModCard(ctx, f) {
                            if (ModToggle.isDisabled(f)) toggleOne(ctx, f, false) { load() }
                            else detail = f
                        }
                    }
                }
            }
        }
    }

    detail?.let { f ->
        var text by remember { mutableStateOf<String?>(null) }
        if (text == null) {
            Bg.io.execute {
                val info = readJarInfo(ctx, f)
                val meta = runCatching { ModMeta.read(ctx, f.uri) }.getOrNull()
                // OptiFine 不是标准模组，元数据里查不到，靠特征类识别
                val optifine = if (meta?.isKnown == false) {
                    runCatching { LoaderIcons.detectOptiFine(ctx, f.uri) }.getOrDefault(false)
                } else false
                Bg.ui.post {
                    text = if (optifine) "$info\n\n识别出这是 OptiFine。" else info
                }
            }
        }
        val iconRes = remember(text) {
            runCatching {
                val meta = ModMeta.read(ctx, f.uri)
                LoaderIcons.res(meta.loader)
            }.getOrDefault(LoaderIcons.res(""))
        }
        McActionDialog(
            title = (f.name ?: "").substringBeforeLast("."),
            onDismiss = { detail = null },
            actions = listOf(
                "删除" to { detail = null; askDelete = f },
                "编辑" to {
                    detail = null
                    runCatching { EditorActivity.open(ctx, f.uri, f.name ?: "") }
                }
            )
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(text ?: "读取中…", fontSize = 12.sp)
        }
    }

    askDelete?.let { f ->
        val days = Trash.days(ctx)
        McConfirm(
            title = "删除模组",
            body = "「${f.name}」会先放进回收站（保留 $days 天），期间可以还原。",
            confirmText = "删除",
            onConfirm = {
                askDelete = null
                Bg.io.execute {
                    val ok = Trash.moveToTrash(ctx, f)
                    val name = f.name ?: ""
                    Bg.ui.post {
                        if (ok) {
                            Tips.short(ctx, "已放进回收站")
                            showUndo(ctx, name) { load() }
                        } else {
                            Tips.short(ctx, "删除失败")
                        }
                        load()
                    }
                }
            },
            onDismiss = { askDelete = null }
        )
    }
}

@Composable
private fun McBatchBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(end = 4.dp)) {
        Text(text, fontSize = 12.sp)
    }
}

@Composable
private fun ModCard(ctx: android.content.Context, f: DocumentFile, onClick: () -> Unit) {
    var bmp by remember(f.uri) { mutableStateOf<Bitmap?>(null) }
    var loaderIcon by remember(f.uri) { mutableStateOf(0) }
    if (bmp == null && loaderIcon == 0) {
        Bg.io.execute {
            val got = runCatching { ModIcons.of(ctx, f) }.getOrNull()
            // 取不到 jar 内图标时退到加载器图标，列表里一眼看出是哪种加载器
            val ld = if (got == null) {
                runCatching {
                    val l = ModMeta.read(ctx, f.uri).loader
                    if (l.isBlank()) 0 else LoaderIcons.res(l)
                }.getOrDefault(0)
            } else 0
            Bg.ui.post {
                bmp = got
                loaderIcon = ld
            }
        }
    }

    val disabled = ModToggle.isDisabled(f)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bmp != null) {
                // 模组自带图标原样显示，不染色
                Image(
                    bitmap = bmp!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            } else if (loaderIcon != 0) {
                // 加载器图标是彩色品牌图，不能像线稿那样染成主题色，
                // 否则 Fabric / Forge / Quilt 会长得一模一样
                Image(
                    painter = painterResource(loaderIcon),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Image(
                    painter = painterResource(
                        if (disabled) R.drawable.ti_x else R.drawable.ti_puzzle
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    ModToggle.displayName(f).substringBeforeLast("."),
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Text(
                    buildString {
                        append("${f.length() / 1024} KB")
                        if (disabled) append(" · 已禁用")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClick) { Text(if (disabled) "启用" else "详情") }
        }
    }
}

private fun toggleOne(ctx: android.content.Context, f: DocumentFile, disable: Boolean, done: () -> Unit) {
    Bg.io.execute {
        val r = if (disable) ModToggle.disable(f) else ModToggle.enable(f)
        Bg.ui.post {
            Tips.short(ctx, if (r == null) "操作失败" else if (disable) "已禁用" else "已启用")
            if (r != null) done()
        }
    }
}

private fun doBatchUpgrade(
    ctx: android.content.Context, mc: String, loader: String,
    list: List<DocumentFile>, done: (String) -> Unit
) {
    val dlg = ProgressDialog.show(ctx, "检查 ${list.size} 个模组的更新")
    Bg.io.execute {
        val tasks = ArrayList<Pair<String, String>>()
        list.forEachIndexed { i, f ->
            val n = f.name ?: return@forEachIndexed
            Bg.ui.post { dlg.update(i.toLong() + 1, list.size.toLong()) }
            val clean = CrossLoader.cleanName(n)
            val hits = try {
                ModrinthApi.search(clean, mc, loader, 3)
            } catch (t: Throwable) {
                emptyList<MarketMod>()
            }
            // 搜索是模糊匹配，hits.firstOrNull() 会把 sodium 匹配到 Sodium Extra，
            // 然后下载一个别的模组的新版本进来 —— 复用名称核对，对不上就跳过
            val proj = CrossLoader.pickProject(hits, clean) ?: return@forEachIndexed
            val vers = try {
                ModrinthApi.versions(proj.id, mc, loader)
            } catch (t: Throwable) {
                emptyList<ModFile>()
            }
            val pick = vers.firstOrNull() ?: return@forEachIndexed
            if (pick.url.isNotBlank()) {
                tasks.add(Pair(pick.url, pick.fileName.ifBlank { pick.name }))
            }
        }
        Bg.ui.post {
            dlg.dismiss()
            if (tasks.isEmpty()) {
                Tips.short(ctx, "没找到可用的更新")
                return@post
            }
            val dir = Targets.dstModsDir(ctx) ?: WorkDir.modsDir(ctx)
            if (dir == null) {
                Tips.short(ctx, "目标目录不可用")
                return@post
            }
            val d2 = ProgressDialog.show(ctx, "下载 ${tasks.size} 个更新")
            Bg.io.execute {
                val res = BatchModOps.download(ctx, dir, tasks) { a, b, _ ->
                    Bg.ui.post { d2.update(a.toLong(), b.toLong()) }
                }
                Bg.ui.post {
                    d2.dismiss()
                    done(BatchModOps.summary(res, "下载"))
                }
            }
        }
    }
}

/**
 * 撤销提示。
 *
 * 之前用 `it.name == name` 找刚删的那条 —— 但回收站里的 name
 * 在同名时是带序号的（sodium(1).jar），跟原始文件名对不上，
 * firstOrNull 返回 null 直接 return，撤销入口根本不出现。
 * 现在按 restoreName（原始文件名）匹配。
 */
private fun showUndo(ctx: android.content.Context, name: String, done: () -> Unit) {
    val item = runCatching {
        Trash.items(ctx).firstOrNull { it.restoreName == name }
    }.getOrNull() ?: return
    Bg.ui.postDelayed({
        runCatching {
            android.app.AlertDialog.Builder(ctx)
                .setTitle("已删除「$name」")
                .setMessage("要撤销吗？")
                .setPositiveButton("撤销") { _, _ ->
                    Bg.io.execute {
                        val ok = Trash.restore(ctx, item)
                        Bg.ui.post {
                            Tips.short(ctx, if (ok) "已还原" else "还原失败")
                            done()
                        }
                    }
                }
                .setNegativeButton("不用", null)
                .show()
        }
    }, 300)
}

private fun humanSize(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/**
 * 读 jar 元数据。
 *
 * 之前用正则 grep JSON/TOML，遇到嵌套、数组、多行值就解析不出来，
 * 详情页经常只显示"文件/大小"两行。现在统一走 ModMeta
 * （Gson 解 JSON、tomlj 解 TOML）。
 */
private fun readJarInfo(ctx: android.content.Context, f: DocumentFile): String {
    val meta = ModMeta.read(ctx, f.uri)
    val sb = StringBuilder()
    sb.appendLine("文件：${f.name}")
    sb.appendLine("大小：${f.length() / 1024} KB")
    sb.appendLine()

    if (!meta.isKnown) {
        sb.appendLine("没识别到加载器元数据：")
        sb.appendLine("包里没有 fabric.mod.json / mods.toml / mcmod.info。")
        sb.appendLine()
        sb.appendLine("可能不是模组（比如资源包或光影），或者是损坏的 jar。")
        if (meta.warnings.isNotEmpty()) {
            sb.appendLine()
            meta.warnings.forEach { sb.appendLine("· $it") }
        }
        return sb.toString()
    }

    sb.appendLine("加载器：${ModMeta.loaderLabel(meta.loader)}")
    if (meta.name.isNotBlank()) sb.appendLine("名称：${meta.name}")
    if (meta.id.isNotBlank()) sb.appendLine("Mod ID：${meta.id}")
    if (meta.version.isNotBlank()) sb.appendLine("版本：${meta.version}")
    if (meta.mcRange.isNotBlank()) sb.appendLine("MC 版本：${meta.mcRange}")
    if (meta.authors.isNotEmpty()) sb.appendLine("作者：${meta.authors.joinToString("、")}")
    if (meta.license.isNotBlank()) sb.appendLine("许可：${meta.license}")
    if (meta.description.isNotBlank()) {
        sb.appendLine()
        sb.appendLine("简介：${meta.description}")
    }
    if (meta.depends.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("必需依赖：")
        meta.depends.forEach { (k, v) ->
            sb.appendLine("  · $k${if (v.isNotBlank()) " ($v)" else ""}")
        }
    }
    if (meta.breaks.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("会冲突（同时装会崩）：")
        meta.breaks.forEach { (k, _) -> sb.appendLine("  · $k") }
    }
    if (meta.provides.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("提供的 ID：${meta.provides.joinToString("、")}")
    }
    if (meta.warnings.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("解析提示：")
        meta.warnings.forEach { sb.appendLine("  · $it") }
    }
    return sb.toString()
}
