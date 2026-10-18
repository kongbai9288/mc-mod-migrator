package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment

/**
 * 整合包更新。
 *
 * 对应 PC 端 Prism / MultiMC 的「Mod updates」：
 * 逐个算出本地 jar 的 SHA-1，走 Modrinth 的 version_file 接口反查项目，
 * 再拉该项目在当前 MC 版本 + 加载器下的最新版本，比对出可更新项。
 * 更新是「下载到目标目录」，不会自动替换原文件，方便你先试再换。
 */
class ModpackFragment : Fragment() {

    private var onImport: ((android.net.Uri) -> Unit)? = null

    /**
     * 选一个 .mrpack 导入。
     *
     * 导入是「用户主动」这个前提的入口：只有走这里进来的包才会被登记，
     * 也只有登记过的包才会被更新检查碰。
     */
    private val openPack = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) doImport(uri) }

    data class UpdateRow(
        var file: String,
        var current: String,
        var latest: String,
        var url: String,
        var latestName: String,
        var status: String
    )

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
                    ModpackScreen(
                        ctx,
                        pick = {
                            runCatching {
                                openPack.launch(
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
                        registerImport = { onImport = it }
                    )
                }
            }
        }
    }

    private fun doImport(uri: android.net.Uri) {
        val ctx = context ?: return
        // 之后安装还要从这个 uri 里读 overrides，权限必须留下来，
        // 只拿到 uri 不申请持久权限的话，重开应用就读不了了。
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        onImport?.invoke(uri)
    }
}

@Composable
private fun ModpackScreen(
    ctx: android.content.Context,
    pick: () -> Unit,
    registerImport: ((android.net.Uri) -> Unit) -> Unit
) {
    var stateText by remember {
        mutableStateOf("上面两个按钮是整合包：导入后才登记，可检查更新、一次装进多个启动器。")
    }
    var results by remember { mutableStateOf<List<ModpackFragment.UpdateRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<List<MrpackInstall.Pack>?>(null) }
    var packFor by remember { mutableStateOf<MrpackInstall.Pack?>(null) }
    var updateItems by remember { mutableStateOf<List<MrpackUpdate.Item>?>(null) }
    var candidates by remember { mutableStateOf<List<Pair<String, DocumentFile>>?>(null) }
    // 选完目标之后要做什么：装包 / 应用更新，由发起方在扫描前挂上来
    var onTargetsOk by remember { mutableStateOf<((List<DocumentFile>) -> Unit)?>(null) }
    var doneText by remember { mutableStateOf<String?>(null) }

    fun setState(s: String) { stateText = s }
    fun toast(s: String) { Tips.short(ctx, s) }
    fun bg(block: () -> Unit) {
        busy = true
        Bg.io.execute {
            try {
                block()
            } catch (t: Throwable) {
                Bg.ui.post { setState("出错了：${t.message}") }
            } finally {
                // Progress 是全局单例，异常/提前 return 时不复位会永久卡在"运行中"，
                // 下次开 App 出现幽灵进度条。
                try { Progress.done() } catch (_: Throwable) {}
                Bg.ui.post { busy = false }
            }
        }
    }
    fun scanTargets(then: (List<DocumentFile>) -> Unit) {
        onTargetsOk = then
        pickTargets(ctx, ::setState, ::toast) { cands -> candidates = cands }
    }

    DisposableEffect(Unit) {
        registerImport { uri -> importPack(ctx, uri, ::setState) { packFor = it } }
        onDispose { registerImport({}) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item { McNote(stateText) }
        item {
            OutlinedButton(
                onClick = pick,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("导入整合包（.mrpack / .zip）") }
        }
        item {
            OutlinedButton(
                onClick = {
                    val list = MrpackInstall.list(ctx)
                    if (list.isEmpty()) {
                        toast("还没有导入过整合包。点上面的「导入整合包」")
                    } else {
                        imported = list
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("已导入的整合包（更新 / 多实例安装）") }
        }
        item {
            McExpand("按目录检查模组更新", moreOpen, { moreOpen = !moreOpen }) {
                Column {
                    McNote("不属于整合包更新，也不会登记。")
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                checkUpdates(ctx, ::setState, ::toast, ::bg) { results = it }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("检查更新") }
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(
                            onClick = { downloadAll(ctx, results, ::setState, ::toast) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("下载全部更新") }
                    }
                }
            }
        }
        if (busy) item { McSkeleton(2) }
        if (results.isNotEmpty()) {
            item { McSectionTitle("检查结果") }
            itemsIndexed(results, key = { i, _ -> i }) { _, r ->
                UpdateRowCard(r) { downloadOne(ctx, r, ::toast) { doneText = it } }
            }
        }
    }

    imported?.let { packs ->
        PickDialog(
            "已导入的整合包",
            packs.map { "${it.label()}（${it.kindLabel()}）\n${it.fileCount} 个文件" },
            onPick = { w -> imported = null; packFor = packs[w] },
            onDismiss = { imported = null }
        )
    }
    packFor?.let { p ->
        PickDialog(
            p.name,
            listOf("检查更新", "安装到多个实例 / 启动器", "移除登记"),
            onPick = { w ->
                packFor = null
                when (w) {
                    0 -> checkPackUpdate(ctx, p, ::setState, ::bg) { updateItems = it }
                    1 -> scanTargets { targets ->
                        installPack(ctx, p, targets, ::setState)
                    }
                    2 -> {
                        MrpackInstall.remove(ctx, p.id)
                        setState("已移除登记：${p.name}（之后不再检查它的更新）")
                    }
                }
            },
            onDismiss = { packFor = null }
        )
    }
    updateItems?.let { items ->
        UpdateListDialog(
            items,
            onApply = {
                updateItems = null
                scanTargets { targets -> applyPackUpdate(ctx, items, targets, ::setState) }
            },
            onDismiss = { updateItems = null }
        )
    }
    candidates?.let { cands ->
        MultiTargetDialog(
            cands,
            onOk = { picked ->
                candidates = null
                val cb = onTargetsOk
                onTargetsOk = null
                if (picked.isEmpty()) {
                    toast("没有选中任何目标")
                } else {
                    cb?.invoke(picked)
                }
            },
            onDismiss = { candidates = null; onTargetsOk = null }
        )
    }
    doneText?.let { t ->
        AlertDialog(
            onDismissRequest = { doneText = null },
            title = { Text("下载完成") },
            text = { Text("$t\n\n已放到目标 mods 目录，替换旧文件前建议先备份。") },
            confirmButton = { TextButton(onClick = { doneText = null }) { Text("确定") } }
        )
    }
}

@Composable
private fun UpdateRowCard(r: ModpackFragment.UpdateRow, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(r.file, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                r.status + (if (r.latest.isNotBlank()) " → ${r.latest}" else ""),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (r.url.isNotBlank() && r.status == "可更新") {
                TextButton(onClick = onDownload) { Text("下载新版") }
            }
        }
    }
}

@Composable
private fun PickDialog(
    title: String,
    options: List<String>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, o ->
                    TextButton(
                        onClick = { onPick(i) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(o, fontSize = 13.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun UpdateListDialog(
    items: List<MrpackUpdate.Item>,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("可更新 ${items.size} 个") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                McMono(
                    items.take(12).joinToString("\n") {
                        "${it.currentName}\n  → ${it.latestName}"
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss(); onApply() }) { Text("更新到全部实例") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun MultiTargetDialog(
    cands: List<Pair<String, DocumentFile>>,
    onOk: (List<DocumentFile>) -> Unit,
    onDismiss: () -> Unit
) {
    val checked = remember(cands) {
        mutableStateListOf<Boolean>().apply { repeat(cands.size) { add(false) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("装到哪里（可多选）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                cands.forEachIndexed { i, c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { checked[i] = !checked[i] }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                        Text(c.first, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val picked = cands.filterIndexed { i, _ -> checked[i] }.map { it.second }
                onDismiss()
                onOk(picked)
            }) { Text("安装") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ------------------------------------------------------------------
// 业务逻辑：与界面分离，迁移前后行为一致
// ------------------------------------------------------------------

private fun importPack(
    ctx: android.content.Context,
    uri: android.net.Uri,
    setState: (String) -> Unit,
    onPack: (MrpackInstall.Pack) -> Unit
) {
    setState("正在读取清单…")
    Bg.io.execute {
        val msgs = ArrayList<String>()
        val pack = MrpackInstall.importFrom(ctx, uri) { msgs.add(it) }
        try { Progress.done() } catch (_: Throwable) {}
        Bg.ui.post {
            if (pack == null) {
                setState(
                    msgs.joinToString("\n").ifBlank {
                        "导入失败：这个文件读不出清单，也不是能直接解压的 zip"
                    }
                )
            } else {
                setState("已导入：${pack.label()}（${pack.kindLabel()}）\n共 ${pack.fileCount} 个文件")
                onPack(pack)
            }
        }
    }
}

private fun checkUpdates(
    ctx: android.content.Context,
    setState: (String) -> Unit,
    toast: (String) -> Unit,
    bg: (() -> Unit) -> Unit,
    onRows: (List<ModpackFragment.UpdateRow>) -> Unit
) {
    val p = Prefs.get(ctx)
    val src = p.getString(K.SRC_URI, null)
    if (src.isNullOrBlank()) {
        toast("请先在迁移页选择「迁移前」的版本目录")
        return
    }
    val mc = p.getString(K.DEF_VERSION, "") ?: ""
    val loader = p.getString(K.DEF_LOADER, "") ?: "auto"
    if (p.getBoolean(K.OFFLINE, false)) {
        setState("离线模式：无法联网检查更新")
        return
    }
    setState("正在算哈希并查询…")
    bg {
        val root = Fs.tree(ctx, src)
        val mods = root?.let { Fs.find(it, McDirs.MODS, 2) }
        if (mods == null) {
            setState("源目录里没有找到 mods 文件夹")
            return@bg
        }
        val jars = Fs.children(mods).filter {
            it.isFile && (it.name ?: "").endsWith(".jar", true)
        }
        if (jars.isEmpty()) {
            setState("mods 目录里没有 jar")
            return@bg
        }

        // 三次批量，而不是每个 jar 两层串行请求：
        // 50 个模组串行就是 100 次排队等待，检查一轮要好几分钟。
        setState("正在计算 ${jars.size} 个文件的指纹…")
        val shaOf = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            (p.getInt(K.DOWNLOAD_PARALLEL, 3)).coerceIn(1, 4)
        )
        val latch = java.util.concurrent.CountDownLatch(jars.size)
        for (j in jars) {
            pool.execute {
                try {
                    val s = try { Fs.sha1(ctx, j) } catch (t: Throwable) { "" }
                    shaOf[j.uri.toString()] = s
                } catch (t: Throwable) {
                    Err.ignore(t, "计算整合包指纹")
                } finally { latch.countDown() }
            }
        }
        latch.await()
        pool.shutdown()

        val hashes = ArrayList<String>()
        for (j in jars) {
            val s = shaOf[j.uri.toString()].orEmpty()
            if (s.isNotBlank()) hashes.add(s.lowercase())
        }

        setState("批量反查 ${hashes.size} 个指纹…")
        val found = try {
            ModrinthApi.lookupHashes(hashes)
        } catch (t: Throwable) {
            emptyMap<String, ModrinthApi.LookupResult>()
        }
        ModrinthApi.refreshAll(found.values.map { it.projectId })

        setState("批量查询最新版本…")
        val latest = try {
            ModrinthApi.latestForHashes(hashes, mc, loader)
        } catch (t: Throwable) {
            emptyMap<String, ModFile>()
        }

        val rows = ArrayList<ModpackFragment.UpdateRow>()
        for (j in jars) {
            val name = j.name ?: ""
            val s = shaOf[j.uri.toString()].orEmpty()
            if (s.isBlank()) {
                rows.add(ModpackFragment.UpdateRow(name, "", "", "", "", "哈希计算失败"))
                continue
            }
            val hit = found[s.lowercase()]
            if (hit == null || !hit.found) {
                rows.add(ModpackFragment.UpdateRow(name, "", "", "", "", "Modrinth 上没找到"))
                continue
            }
            val f0 = latest[s.lowercase()]
            if (f0 == null) {
                rows.add(
                    ModpackFragment.UpdateRow(name, hit.version, "", "", "", "当前版本下无可用更新")
                )
                continue
            }
            val same = f0.fileName.equals(name, true)
            rows.add(
                ModpackFragment.UpdateRow(
                    file = name,
                    current = hit.version,
                    latest = f0.fileName.substringBeforeLast(".jar"),
                    url = f0.url,
                    latestName = f0.fileName,
                    status = if (same) "已是最新" else "可更新"
                )
            )
        }
        Bg.ui.post {
            onRows(rows)
            val n = rows.count { it.status == "可更新" }
            setState("共 ${rows.size} 个模组，其中 $n 个可更新")
        }
    }
}

private fun downloadOne(
    ctx: android.content.Context,
    r: ModpackFragment.UpdateRow,
    toast: (String) -> Unit,
    onDone: (String) -> Unit
) {
    toast("开始下载 ${r.latestName}")
    Bg.io.execute {
        try {
            val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
            if (dir == null) {
                Bg.ui.post { toast("目标目录不可用，请先在设置里选工作目录") }
                return@execute
            }
            // 不看返回值就弹"下载完成"的话，下载失败也一样报成功
            val out = Downloader.download(ctx, r.url, dir, r.latestName, emptyMap())
            Bg.ui.post {
                if (out == null) toast("下载失败：${r.latestName}") else onDone(r.latestName)
            }
        } catch (t: Throwable) {
            Bg.ui.post { toast("下载失败：${Err.humanMessage(t)}") }
        } finally {
            try { Progress.done() } catch (_: Throwable) {}
        }
    }
}

private fun downloadAll(
    ctx: android.content.Context,
    results: List<ModpackFragment.UpdateRow>,
    setState: (String) -> Unit,
    toast: (String) -> Unit
) {
    val todo = results.filter { it.status == "可更新" && it.url.isNotBlank() }
    if (todo.isEmpty()) {
        toast("没有可更新的模组")
        return
    }
    val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
    if (dir == null) {
        toast("目标目录不可用，请先在设置里选工作目录")
        return
    }
    toast("开始下载 ${todo.size} 个")
    // 真并发，而且只在真的拿到文件时才计数 ——
    // 否则下载失败也计成成功，最后报"完成 20/20"，实际一个都没落地
    val ok = java.util.concurrent.atomic.AtomicInteger(0)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(
        (Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3) / 2).coerceIn(1, 4)
    )
    val latch = java.util.concurrent.CountDownLatch(todo.size)
    for (r in todo) {
        pool.execute {
            try {
                if (Downloader.download(ctx, r.url, dir, r.latestName, emptyMap()) != null) {
                    ok.incrementAndGet()
                } else {
                    Err.ignore(RuntimeException("下载失败 ${r.latestName}"), "整合包批量下载")
                }
            } catch (t: Throwable) {
                Err.ignore(t, "整合包批量下载 ${r.latestName}")
            } finally { latch.countDown() }
        }
    }
    java.lang.Thread {
        latch.await()
        pool.shutdown()
        try { Progress.done() } catch (_: Throwable) {}
        Bg.ui.post {
            setState("下载完成：${ok.get()}/${todo.size}")
            toast("完成 ${ok.get()}/${todo.size}")
        }
    }.start()
}

private fun checkPackUpdate(
    ctx: android.content.Context,
    pack: MrpackInstall.Pack,
    setState: (String) -> Unit,
    bg: (() -> Unit) -> Unit,
    onItems: (List<MrpackUpdate.Item>) -> Unit
) {
    if (Prefs.get(ctx).getBoolean(K.OFFLINE, false)) {
        setState("离线模式：无法联网检查更新")
        return
    }
    setState("正在检查「${pack.name}」的更新…")
    bg {
        val msgs = ArrayList<String>()
        val items = MrpackUpdate.check(ctx, pack) { msgs.add(it) }
        Bg.ui.post {
            if (items == null) {
                setState(msgs.joinToString("\n").ifBlank { "检查失败" })
            } else if (items.isEmpty()) {
                setState("「${pack.name}」都已是最新")
            } else {
                setState("${items.size} 个文件可更新")
                onItems(items)
            }
        }
    }
}

private fun installPack(
    ctx: android.content.Context,
    pack: MrpackInstall.Pack,
    targets: List<DocumentFile>,
    setState: (String) -> Unit
) {
    setState("正在安装到 ${targets.size} 个目标…")
    Bg.io.execute {
        val msgs = ArrayList<String>()
        val ok = MrpackInstall.install(
            ctx, pack, targets, MrpackInstall.Side.CLIENT
        ) { msgs.add(it) }
        try { Progress.done() } catch (_: Throwable) {}
        Bg.ui.post {
            setState("安装完成 $ok/${targets.size}\n" + msgs.takeLast(8).joinToString("\n"))
        }
    }
}

private fun applyPackUpdate(
    ctx: android.content.Context,
    items: List<MrpackUpdate.Item>,
    targets: List<DocumentFile>,
    setState: (String) -> Unit
) {
    Bg.io.execute {
        val log = ArrayList<String>()
        val n = MrpackUpdate.apply(ctx, items, targets) { log.add(it) }
        try { Progress.done() } catch (_: Throwable) {}
        Bg.ui.post { setState("更新完成 $n 处\n" + log.takeLast(6).joinToString("\n")) }
    }
}

/**
 * 扫出可安装的目标，交给界面弹多选。
 *
 * 候选来自**所有已授权的游戏目录**以及它们下面扫出来的实例 ——
 * 手机上装了 FCL 又装了 Zalith 时，两边都能出现在列表里，
 * 一个包可以同时装进多个启动器。
 */
private fun pickTargets(
    ctx: android.content.Context,
    setState: (String) -> Unit,
    toast: (String) -> Unit,
    onCands: (List<Pair<String, DocumentFile>>) -> Unit
) {
    setState("正在扫描可安装的目标…")
    Bg.io.execute {
        val cands = ArrayList<Pair<String, DocumentFile>>()
        val seen = HashSet<String>()
        for (e in GameDirs.list(ctx)) {
            val tree = runCatching {
                DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(e.uri))
            }.getOrNull()
            if (tree != null && seen.add(tree.uri.toString())) {
                cands.add(Pair("游戏目录 · ${e.label()}", tree))
            }
            val insts = runCatching {
                InstanceScanner.scanFrom(ctx, e.uri) { }
            }.getOrDefault(emptyList())
            for (i in insts) {
                val d = runCatching {
                    DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(i.uri))
                }.getOrNull()
                if (d != null && seen.add(d.uri.toString())) {
                    val from = i.source.ifBlank { e.label() }
                    cands.add(Pair("实例 · ${i.name}（来自 $from）", d))
                }
            }
        }
        try { Progress.done() } catch (_: Throwable) {}
        Bg.ui.post {
            if (cands.isEmpty()) {
                toast("没有可安装的目标。先在设置 → 存储里添加启动器目录")
            } else {
                onCands(cands)
            }
        }
    }
}
