package com.kongbai.modmigrator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import java.io.File
import java.util.Locale

/**
 * 工具箱：零散但常用的小工具入口。
 *
 * 分组按用途走，每组都有标题，不留孤立的一行。
 */
class ToolsFragment : Fragment() {

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
                    ToolsScreen(ctx, openFragment = { f -> openPage(f) })
                }
            }
        }
    }

    private fun openPage(f: Fragment) {
        try {
            //
            // 同主界面：用 add 叠加，不用 replace。
            // replace 会把底下的一级 tab 一起 remove，返回后全部重建。
            // add 之后必须手动把底下的页面 hide 掉，
            // 否则新页面叠在旧页面上面，两层内容同时可见。
            //
            val fm = parentFragmentManager
            val tx = fm.beginTransaction()
                .add(R.id.fragment_container, f, "page:tool")
                .addToBackStack("tool")
            for (x in fm.fragments) {
                if (x != null && x !== f && !x.isHidden) tx.hide(x)
            }
            tx.commitAllowingStateLoss()
        } catch (t: Throwable) {
            // 容器 id 变了（页面结构调整）时不能静默失败，给个明确提示
            Err.fail(t, "打开子页面")
            Tips.short(requireContext(), "打不开这个页面，请从「更多 → 工具箱」进入")
        }
    }
}

private data class ToolItem(val icon: Int, val label: String, val act: () -> Unit)

private sealed class ToolDlg {
    object None : ToolDlg()
    data class Text(val title: String, val body: String) : ToolDlg()
    data class Action(
        val title: String, val body: String, val actions: List<Pair<String, () -> Unit>>
    ) : ToolDlg()

    data class Pick(
        val title: String, val items: List<String>, val onPick: (Int) -> Unit
    ) : ToolDlg()

    data class Multi(
        val title: String, val items: List<String>,
        val checked: () -> List<Boolean>,
        val onToggle: (Int, Boolean) -> Unit, val onConfirm: () -> Unit,
        val confirmText: String = "确定"
    ) : ToolDlg()

    data class Confirm(
        val title: String, val body: String,
        val confirmText: String, val onConfirm: () -> Unit,
        val dismissText: String = "取消", val onDismiss: (() -> Unit)? = null
    ) : ToolDlg()

    data class Input(
        val title: String, val body: String, val hint: String, val onOk: (String) -> Unit
    ) : ToolDlg()

    data class Busy(val text: String) : ToolDlg()
}

@Composable
private fun ToolsScreen(ctx: Context, openFragment: (Fragment) -> Unit) {

    var dlg by remember { mutableStateOf<ToolDlg>(ToolDlg.None) }

    fun toast(s: String) {
        Bg.ui.post { Tips.short(ctx, s) }
    }

    fun bg(block: () -> Unit) {
        Bg.io.execute {
            try {
                block()
            } catch (t: Throwable) {
                toast("出错了：${t.message}")
            } finally {
                // Progress 是全局单例，异常/提前 return 时不复位会永久卡在"运行中"，
                // 下次开 App 出现幽灵进度条。
                try { Progress.done() } catch (_: Throwable) {}
            }
        }
    }

    fun show(title: String, text: String) {
        dlg = ToolDlg.Text(title, text)
    }

    // ---------------- 世界与数据 ----------------

    fun openWorlds() {
        openFragment(WorldsFragment())
    }

    /**
     * NBT 编辑器入口。
     *
     * ⚠️ 之前只从「游戏目录」里用 File 扫 .dat/.nbt。
     * 而现在游戏目录几乎都是 SAF 授权 URI（Android 11+ 下 File API
     * 进不去 Android/data），于是候选永远是空的，只能退化成"手填完整路径"——
     * 用户根本不知道路径，功能等于不可用。
     *
     * 现在两条路都扫：真实路径 + SAF 授权目录。
     */
    fun openNbt() {
        toast("正在查找…")
        bg {
            val found = ArrayList<Pair<String, Any>>()
            val game = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""
            if (game.isNotBlank() && !game.startsWith("content://")) {
                runCatching {
                    File(game).walkTopDown()
                        .filter { it.isFile && (it.name.endsWith(".dat") || it.name.endsWith(".nbt")) }
                        .take(40)
                        .forEach { found.add(labelOf(it.name, it) to it) }
                }
            }
            if (game.startsWith("content://")) {
                runCatching { collectSafNbt(ctx, game, found) }
            }
            if (found.isEmpty() && Perms.allFiles()) {
                for ((_, dir) in LauncherDirs.detect(ctx)) {
                    runCatching {
                        dir.walkTopDown()
                            .filter { it.isFile && (it.name.endsWith(".dat") || it.name.endsWith(".nbt")) }
                            .take(40)
                            .forEach { found.add(labelOf(it.name, it) to it) }
                    }
                }
            }
            Bg.ui.post {
                if (found.isEmpty()) {
                    dlg = ToolDlg.Input(
                        "NBT 文件路径",
                        "没在游戏目录里找到 .dat。\n" +
                            "先在「设置 → 存储」把游戏目录指到 .minecraft，\n" +
                            "或在这里直接填完整路径。",
                        "完整路径"
                    ) { p ->
                        if (p.isNotBlank()) NbtViewerActivity.open(ctx, File(p.trim()))
                    }
                } else {
                    dlg = ToolDlg.Pick("打开哪个文件（共 ${found.size} 个）", found.map { it.first }) { w ->
                        val v = found[w].second
                        if (v is File) NbtViewerActivity.open(ctx, v)
                        else NbtViewerActivity.openUri(ctx, v as Uri)
                    }
                }
            }
        }
    }

    /**
     * 区块编辑器入口。
     *
     * ⚠️ 之前是「先扫 .mca 文件、再反推世界」，而且最多只取 40 个文件。
     * 于是世界一多，前 40 个文件可能全来自第一个世界 ——
     * 列表里只剩一个世界，看着像「世界数量不对」。
     *
     * 现在改为直接发现世界目录，不截断数量，SAF 也走世界级。
     */
    fun openMca() {
        toast("正在查找存档…")
        bg {
            val game = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""

            val worldFiles = LinkedHashMap<String, File>()
            fun collect(saves: File) {
                saves.listFiles()?.forEach { d ->
                    if (d.isDirectory && File(d, "level.dat").isFile) {
                        worldFiles.putIfAbsent(d.name, d)
                    }
                }
            }
            if (game.isNotBlank() && !game.startsWith("content://")) {
                for (s in savesDirsOf(File(game))) collect(s)
            }
            if (Perms.allFiles()) {
                for ((_, dir) in LauncherDirs.detect(ctx)) {
                    for (s in savesDirsOf(dir)) collect(s)
                }
            }
            val safWorlds = ArrayList<Pair<String, DocumentFile>>()
            if (game.startsWith("content://")) {
                runCatching {
                    val tree = Fs.tree(ctx, game) ?: return@runCatching
                    val saves = tree.findFile(McDirs.SAVES) ?: tree
                    for (d in saves.listFiles()) {
                        if (d.isDirectory && d.findFile("level.dat") != null) {
                            safWorlds.add((d.name ?: "存档") to d)
                        }
                    }
                }
            }

            Bg.ui.post {
                val names = ArrayList<String>()
                names.addAll(worldFiles.keys)
                names.addAll(safWorlds.map { it.first + "（授权目录）" })
                if (names.isNotEmpty()) {
                    dlg = ToolDlg.Pick("打开哪个世界（共 ${names.size} 个）", names) { w ->
                        if (w < worldFiles.size) {
                            ChunkMapActivity.open(ctx, worldFiles.values.elementAt(w))
                        } else {
                            val doc = safWorlds[w - worldFiles.size].second
                            dlg = ToolDlg.Busy("正在读取存档…\n复制区域文件到本机，稍后可保存回去")
                            bg {
                                val dir = mirrorSafWorld(ctx, doc)
                                Bg.ui.post {
                                    if (dir == null) toast("这个存档里没有区域文件")
                                    else ChunkMapActivity.open(ctx, dir)
                                    dlg = ToolDlg.None
                                }
                            }
                        }
                    }
                    return@post
                }

                // 找不到 level.dat（多半授权到了世界内部），退回按文件列
                val found = ArrayList<Pair<String, Any>>()
                if (game.isNotBlank() && !game.startsWith("content://")) {
                    runCatching {
                        File(game).walkTopDown()
                            .filter {
                                it.isFile && isTerrain(it) &&
                                    (it.name.endsWith(".mca") || it.name.endsWith(".mcr"))
                            }
                            .take(200)
                            .forEach { found.add(labelOf(it.name, it) to it) }
                    }
                }
                if (game.startsWith("content://")) {
                    runCatching { collectSafNbt(ctx, game, found, listOf(".mca", ".mcr"), 6) }
                }
                if (found.isEmpty()) {
                    show(
                        "没找到存档",
                        "没找到带 level.dat 的世界目录，也没找到存地形的 .mca。\n\n" +
                            "存档在「游戏目录/saves/世界名/」下，区域文件在其 region/ 目录里。\n\n" +
                            "请在「设置 → 存储」把游戏目录指到 .minecraft（或直接指到 saves 那一层）。"
                    )
                } else {
                    dlg = ToolDlg.Pick(
                        "打开哪个区域文件（共 ${found.size} 个）", found.map { it.first }
                    ) { w ->
                        val v = found[w].second
                        if (v is File) ChunkMapActivity.openFile(ctx, v)
                        else ChunkMapActivity.openUri(ctx, v as Uri)
                    }
                }
            }
        }
    }

    fun openDatapack() {
        // 原生生成器，列表从 schema 推导。
        // 旧的网页版入口撤到开发者实验室里当备用，避免同一个功能两个入口。
        ctx.startActivity(Intent(ctx, DatapackActivity::class.java))
    }

    // ---------------- 依赖体检 ----------------

    fun depCheck() {
        toast("正在读取本地模组…")
        bg {
            val dir = DirResolve.modsDir(ctx)
            if (dir == null) {
                toast("没有可用的 mods 目录")
                return@bg
            }
            val files = dir.listFiles().filter {
                // 必须排除"已禁用"的（x.jar.disabled）。
                // 禁用的模组不会被加载器装载，它既不提供依赖、也不参与冲突，
                // 算进去只会让报告出现一堆根本不存在的"缺失依赖"。
                val n = it.name ?: return@filter false
                n.endsWith(".jar", true) && !ModToggle.isDisabled(it)
            }
            if (files.isEmpty()) {
                toast("mods 目录是空的（或全部被禁用）")
                return@bg
            }
            val mods = ArrayList<ModDepGraph.Mod>()
            for (f in files) {
                val meta = ModMeta.read(ctx, f.uri)
                val name = f.name ?: ""
                val id = meta.id.ifBlank { name.substringBeforeLast('.') }
                mods.add(
                    ModDepGraph.Mod(
                        file = name,
                        id = id,
                        name = meta.name.ifBlank { id },
                        version = meta.version,
                        loader = meta.loader,
                        depends = meta.depends.keys.toList(),
                        breaks = meta.breaks.keys.toList()
                    )
                )
            }
            val report = ModDepGraph.analyze(mods)
            Bg.ui.post { show("依赖体检（${mods.size} 个模组）", ModDepGraph.format(report)) }
        }
    }

    // ---------------- 模组开关 ----------------

    // 列表项状态放这里：勾选后要立刻反映到界面上
    var toggleFiles by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var toggleStates by remember { mutableStateOf<List<Boolean>>(emptyList()) }

    fun modToggle() {
        toast("正在读取本地模组…")
        bg {
            val dir = DirResolve.modsDir(ctx)
            if (dir == null) {
                toast("没有可用的 mods 目录")
                return@bg
            }
            val files = dir.listFiles().filter {
                val n = (it.name ?: "").lowercase()
                n.endsWith(".jar") || n.endsWith(".jar.disabled")
            }
            if (files.isEmpty()) {
                toast("mods 目录里没有模组")
                return@bg
            }
            val names = files.map { ModToggle.displayName(it) }
            val states = files.map { !ModToggle.isDisabled(it) }
            Bg.ui.post {
                toggleFiles = files
                toggleStates = states
                dlg = ToolDlg.Multi(
                    title = "模组开关（点一下切换）",
                    items = names,
                    checked = { toggleStates },
                    onToggle = { i, checked ->
                        toggleStates = toggleStates.toMutableList().also { it[i] = checked }
                        val f = toggleFiles[i]
                        val wantDisabled = !checked
                        bg {
                            val ok = if (wantDisabled) ModToggle.disable(f) else ModToggle.enable(f)
                            Bg.ui.post {
                                if (ok != null) {
                                    toast(if (wantDisabled) "已禁用" else "已启用")
                                } else {
                                    // 失败要把界面上的勾选退回，否则显示和实际不一致
                                    toggleStates =
                                        toggleStates.toMutableList().also { it[i] = !checked }
                                    toast("操作失败（目录可能只读）")
                                }
                            }
                        }
                    },
                    onConfirm = {
                        dlg = ToolDlg.Text(
                            "这是怎么生效的",
                            "禁用 = 把 xxx.jar 改名成 xxx.jar.disabled。\n\n" +
                                "加载器只加载 .jar 结尾的文件，改了名它就不加载了。\n\n" +
                                "好处：文件还在原地，随时能改回来，比挪走安全。"
                        )
                    },
                    confirmText = "说明"
                )
            }
        }
    }

    // ---------------- 实例结构 ----------------

    fun createInstance() {
        val dstUri = Prefs.get(ctx).getString(K.DST_URI, "") ?: ""
        if (dstUri.isBlank()) {
            toast("请先在迁移页设置「迁移后」的目录")
            return
        }
        val opts = listOf("Fabric", "Forge", "Quilt", "NeoForge", "先不装加载器")
        val keys = listOf("fabric", "forge", "quilt", "neoforge", "")
        dlg = ToolDlg.Pick("目标实例用哪个加载器", opts) { w ->
            val loader = keys[w]
            bg {
                val dst = Fs.tree(ctx, dstUri)
                if (dst == null) {
                    toast("目标目录不可访问")
                    return@bg
                }
                val made = InstanceCreator.scaffold(ctx, dst)
                val mc = Prefs.get(ctx).getString(K.MC_VERSION, "") ?: ""
                val name = dst.name ?: "新实例"
                InstanceCreator.writeInstanceCfg(ctx, dst, name, mc)
                InstanceCreator.writeMmcPack(ctx, dst, name, mc, loader)

                val sb = StringBuilder()
                sb.append("已补齐实例结构。\n\n")
                if (made.isNotEmpty()) sb.append("新建目录：${made.joinToString("、")}\n")
                sb.append("已写入 instance.cfg 与 mmc-pack.json，")
                sb.append("Prism / MultiMC 可直接识别。\n")
                Bg.ui.post {
                    if (loader.isBlank()) {
                        sb.append("\n未选择加载器，之后可以随时再装。")
                        show("已创建", sb.toString())
                    } else {
                        sb.append("\n接下来需要你自己装 ${opts[w]}：")
                        dlg = ToolDlg.Action(
                            "安装 ${opts[w]}",
                            sb.toString() + "\n\n" + InstanceCreator.installGuide(loader, mc),
                            listOf("打开官方下载页" to {
                                val page = InstanceCreator.loaderPage(loader)
                                if (page.isNotBlank()) WebActivity.open(ctx, page, "${opts[w]} 下载")
                                dlg = ToolDlg.None
                            })
                        )
                    }
                }
            }
        }
    }

    // ---------------- 导出整合包 ----------------

    fun doExport(
        srcUri: String, fmt: ModpackFormat, parts: List<McParts.Part>, embedAll: Boolean
    ) {
        toast("正在打包…")
        bg {
            val src = Fs.tree(ctx, srcUri)
            if (src == null) {
                toast("源目录不可访问")
                return@bg
            }
            val mc = Prefs.get(ctx).getString(K.MC_VERSION, "") ?: ""
            val loader = Prefs.get(ctx).getString(K.LOADER, "") ?: ""
            val name = src.name ?: "整合包"
            val out = File(ctx.cacheDir, "export/${fmt.fileName(name, parts)}")
            out.parentFile?.mkdirs()
            val r = MrpackExport.export(
                ctx,
                MrpackExport.Options(
                    name = name,
                    mcVersion = mc,
                    loader = loader,
                    format = fmt,
                    parts = parts,
                    embedAll = embedAll
                ) { msg -> LogCenter.i("整合包", msg) },
                src, out
            )
            Bg.ui.post {
                val f = r.file
                if (f == null || !f.exists()) {
                    toast(if (r.note.isBlank()) "导出失败" else r.note)
                    return@post
                }
                var msg = "${f.name}\n${f.length() / 1024} KB\n\n"
                msg += "清单远程下载 ${r.indexed} 个，随包带走 ${r.embedded} 个。\n"
                msg += "「远程下载」的由启动器按清单拉取并校验哈希。"
                if (r.note.isNotBlank()) msg += "\n\n" + r.note
                dlg = ToolDlg.Action(
                    "已导出", msg,
                    listOf("分享" to {
                        QuickTransfer.shareOne(ctx, f, f.name)
                        dlg = ToolDlg.None
                    })
                )
            }
        }
    }

    fun askEmbed(srcUri: String, fmt: ModpackFormat, parts: List<McParts.Part>) {
        val msg = if (fmt == ModpackFormat.MULTIMC || fmt == ModpackFormat.PLAIN) {
            "这个格式没有远程下载，文件会全部随包带走。"
        } else {
            "按规范导出（推荐）：\n" +
                "清单里只写下载地址与哈希，包只有几十 KB，" +
                "装的时候由启动器下载并校验。\n" +
                "查不到的模组会自动随包带走，不会丢。\n\n" +
                "全部随包带走：\n" +
                "包会很大，但没网也能装。"
        }
        dlg = ToolDlg.Confirm(
            title = "模组本体怎么带",
            body = msg,
            confirmText = "按规范导出",
            onConfirm = { doExport(srcUri, fmt, parts, embedAll = false) },
            dismissText = "全部随包",
            onDismiss = { doExport(srcUri, fmt, parts, embedAll = true) }
        )
    }

    var partsChecked by remember { mutableStateOf<List<Boolean>>(emptyList()) }

    /**
     * 选打包哪些内容。
     *
     * 个人数据（存档、按键设置、服务器列表）默认不选：
     * 分享整合包时把别人的存档和服务器列表带出去是没道理的。
     */
    fun askParts(srcUri: String, fmt: ModpackFormat) {
        val saved = Prefs.get(ctx).getString(McParts.PREF_EXPORT, "") ?: ""
        val on = McParts.resolve(saved, true)
        val items = McParts.ALL.map {
            if (it.personal) it.label + "（个人数据）" else it.label
        }
        partsChecked = McParts.ALL.map { on.contains(it) }
        dlg = ToolDlg.Multi(
            title = "打包哪些内容",
            items = items,
            checked = { partsChecked },
            onToggle = { i, v ->
                partsChecked = partsChecked.toMutableList().also { it[i] = v }
            },
            onConfirm = {
                val picked = McParts.ALL.filterIndexed { idx, _ -> partsChecked[idx] }
                if (picked.isEmpty()) {
                    toast("至少选一类")
                } else {
                    Prefs.get(ctx).edit()
                        .putString(McParts.PREF_EXPORT, McParts.toPref(picked)).apply()
                    askEmbed(srcUri, fmt, picked)
                }
            },
            confirmText = "下一步"
        )
    }

    fun askFormat(srcUri: String) {
        val fs = ModpackFormat.values()
        // 只出 .mrpack 的话，用 CurseForge 启动器、Prism / MultiMC 的人就装不了
        dlg = ToolDlg.Pick("导出成哪种整合包", fs.map { it.label + "\n" + it.note }) { w ->
            askParts(srcUri, fs[w])
        }
    }

    fun exportMrpack() {
        val srcUri = Prefs.get(ctx).getString(K.SRC_URI, "") ?: ""
        if (srcUri.isBlank()) {
            toast("请先在迁移页设置「迁移前」的目录")
            return
        }
        askFormat(srcUri)
    }

    // ---------------- 代码级迁移 ----------------

    fun codeMigrate() {
        val pairs = CodeMigrator.supportedPairs()
        dlg = ToolDlg.Pick(
            "代码级迁移", pairs.map { "${it.first} → ${it.second}" }
        ) { w ->
            val (from, to) = pairs[w]
            val root = WorkDir.root(ctx)
            val dir = File(ctx.getExternalFilesDir(null), "code")
            val target = if (root != null && root.uri.scheme == "file") {
                File(root.uri.path ?: "")
            } else dir

            toast("正在扫描…")
            bg {
                val hits = CodeMigrator.scan(target, from, to)
                Bg.ui.post {
                    dlg = ToolDlg.Action(
                        "$from → $to", CodeMigrator.report(hits),
                        listOf("全部应用" to {
                            dlg = ToolDlg.None
                            bg {
                                val (ok, bad) = CodeMigrator.apply(target, hits)
                                toast("已改 $ok 个文件${if (bad > 0) "，$bad 个失败" else ""}")
                            }
                        })
                    )
                }
            }
        }
    }

    /**
     * 打包 mods 目录并走系统分享面板发送到别的设备。
     *
     * 打包必须在后台线程做：mods 目录动辄几百 MB，
     * 在主线程压缩会直接 ANR。
     */
    fun shareMods() {
        bg {
            val dir = DirResolve.modsDir(ctx)
            if (dir == null) {
                toast("没有可用的 mods 目录")
                return@bg
            }
            val files = dir.listFiles().filter { (it.name ?: "").endsWith(".jar", true) }
            if (files.isEmpty()) {
                toast("mods 目录里没有模组")
                return@bg
            }
            toast("正在打包 ${files.size} 个模组…")

            // DocumentFile 不能直接给 ZipOutputStream，先落到本地缓存再打包
            val tmp = File(ctx.cacheDir, "share_mods").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val locals = ArrayList<File>()
            for (f in files) {
                val out = File(tmp, f.name ?: continue)
                try {
                    ctx.contentResolver.openInputStream(f.uri)?.use { raw ->
                        out.outputStream().use { o ->
                            // 同 MrpackExport：SAF 流必须包缓冲，
                            // 否则每次 read 都是一次跨进程调用
                            java.io.BufferedInputStream(raw, 1 shl 18).use {
                                it.copyTo(o, 1 shl 18)
                            }
                        }
                    }
                    if (out.exists() && out.length() > 0) locals.add(out)
                } catch (t: Throwable) {
                    Err.ignore(t, "复制 ${f.name} 到临时目录")
                }
            }
            if (locals.isEmpty()) {
                toast("没有可打包的文件（可能读不到）")
                return@bg
            }
            Bg.ui.post { QuickTransfer.shareFiles(ctx, locals, "mods.zip") }
        }
    }

    /** 崩溃日志。放在后台读：readAll 会把历次崩溃文件全读一遍，是磁盘 IO。 */
    fun showCrashLog() {
        toast("正在读取崩溃日志…")
        bg {
            val txt = CrashHandler.readAll(ctx)
            Bg.ui.post {
                val body = txt.ifBlank { "没有记录到崩溃" }
                dlg = ToolDlg.Action(
                    "崩溃日志", body,
                    listOf(
                        "复制" to {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                            if (cm == null) {
                                toast("复制失败：拿不到剪贴板")
                            } else {
                                cm.setPrimaryClip(ClipData.newPlainText("崩溃日志", body))
                                toast("已复制")
                            }
                            dlg = ToolDlg.None
                        },
                        "清空" to {
                            CrashHandler.clear(ctx)
                            toast("已清空")
                            dlg = ToolDlg.None
                        }
                    )
                )
            }
        }
    }

    fun doctor() {
        val src = Prefs.get(ctx).getString(K.SRC_URI, null)
        if (src.isNullOrBlank()) {
            toast("请先在迁移页选择「迁移前」的版本目录")
            return
        }
        toast("体检中…")
        bg {
            val rep = ModTools.doctor(ctx, src)
            Bg.ui.post { show(rep.title, rep.text) }
        }
    }

    fun diff() {
        val p = Prefs.get(ctx)
        val src = p.getString(K.SRC_URI, null)
        val dst = p.getString(K.DST_URI, null)
        if (src.isNullOrBlank() || dst.isNullOrBlank()) {
            toast("请同时选择「迁移前」与「迁移后」的目录")
            return
        }
        toast("对比中…")
        bg {
            val rep = ModTools.diffConfig(ctx, src, dst)
            Bg.ui.post { show(rep.title, rep.text) }
        }
    }

    fun clearCache() {
        bg {
            var n = 0
            runCatching {
                val c = WorkDir.cache(ctx)
                if (c != null) {
                    for (f in Fs.children(c)) {
                        if (f.isFile && f.delete()) n++
                    }
                }
                val tc = WorkDir.translate(ctx)
                if (tc != null) {
                    for (f in Fs.children(tc)) {
                        if (f.isFile && f.name?.endsWith(".json") == true && f.delete()) n++
                    }
                }
            }
            toast("已清理 $n 个缓存文件")
        }
    }

    fun usage() {
        bg {
            val sb = StringBuilder()
            val root = WorkDir.root(ctx)
            if (root == null) {
                Bg.ui.post {
                    show("存储用量", "还没设置工作目录。去「设置 → 存储」选一个目录即可。")
                }
                return@bg
            }
            var total = 0L
            for (name in listOf("packs", "mods", "configs", "cache", "translate", "logs", "data")) {
                val d = Fs.find(root, name, 1)
                if (d == null) continue
                var size = 0L
                var count = 0
                fun walk(dir: DocumentFile, depth: Int) {
                    if (depth > 4) return
                    for (c in Fs.children(dir)) {
                        if (c.isDirectory) walk(c, depth + 1)
                        else {
                            size += c.length()
                            count++
                        }
                    }
                }
                walk(d, 0)
                total += size
                sb.append("$name：$count 个文件，${fmt(size)}\n")
            }
            sb.append("\n合计：${fmt(total)}")
            Bg.ui.post { show("存储用量", sb.toString()) }
        }
    }

    val groups = listOf(
        "迁移 · 检查" to listOf(
            ToolItem(R.drawable.ti_circle_check, "模组体检") { doctor() },
            ToolItem(R.drawable.ti_puzzle, "依赖体检") { depCheck() },
            ToolItem(R.drawable.ti_copy, "配置对比") { diff() },
            ToolItem(R.drawable.ti_info_circle, "崩溃日志") { showCrashLog() }
        ),
        // 跨加载器已挪到迁移页：它要用到迁移页填好的目标版本与加载器，
        // 放这里就得让用户再填一遍，两处不一致会出现"换了加载器但版本对不上"
        "迁移 · 模组处理" to listOf(
            ToolItem(R.drawable.ti_alert_triangle, "模组开关") { modToggle() },
            ToolItem(R.drawable.ti_bolt, "代码级迁移") { codeMigrate() }
        ),
        "世界与数据" to listOf(
            ToolItem(R.drawable.ti_folder, "世界存档") { openWorlds() },
            ToolItem(R.drawable.ti_packages, "区块编辑器") { openMca() },
            ToolItem(R.drawable.ti_pencil, "NBT 编辑器") { openNbt() },
            ToolItem(R.drawable.ti_code, "数据包") { openDatapack() }
        ),
        "实例与整合包" to listOf(
            ToolItem(R.drawable.ti_rocket, "补齐实例") { createInstance() },
            ToolItem(R.drawable.ti_devices, "加载器支持器") {
                ctx.startActivity(Intent(ctx, LoaderForgeActivity::class.java))
            },
            ToolItem(R.drawable.ti_cloud_up, "导出整合包") { exportMrpack() }
        ),
        "存储与传输" to listOf(
            ToolItem(R.drawable.ti_refresh_dot, "存储用量") { usage() },
            ToolItem(R.drawable.ti_trash, "清理缓存") { clearCache() },
            ToolItem(R.drawable.ti_building_store, "云盘") { CloudDriveActivity.open(ctx) },
            ToolItem(R.drawable.ti_external_link, "打包发送") { shareMods() }
        )
    )

    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item { McNote("工具箱：零散但常用的小工具。长按没有额外操作，点一下即用。") }
        for ((title, items) in groups) {
            item { McSectionTitle(title) }
            item { ToolGrid(items) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    // ---------------- 弹窗统一渲染 ----------------

    when (val d = dlg) {
        is ToolDlg.None -> Unit
        is ToolDlg.Busy -> AlertDialog(
            onDismissRequest = { },
            title = { Text("请稍候") },
            text = { Text(d.text) },
            confirmButton = { }
        )
        is ToolDlg.Text -> McLongTextDialog(
            title = d.title,
            body = d.body,
            onDismiss = { dlg = ToolDlg.None },
            action = "复制" to {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(d.title, d.body))
                toast("已复制")
            }
        )
        is ToolDlg.Action -> McActionDialog(
            title = d.title,
            onDismiss = { dlg = ToolDlg.None },
            actions = d.actions,
            content = { McMono(d.body) }
        )
        is ToolDlg.Pick -> McPickOne(
            title = d.title, options = d.items, selected = -1,
            onPick = { i ->
                val act = d.onPick
                dlg = ToolDlg.None
                act(i)
            },
            onDismiss = { dlg = ToolDlg.None }
        )
        is ToolDlg.Multi -> McMultiPick(
            title = d.title,
            items = d.items,
            checked = d.checked(),
            onToggle = d.onToggle,
            onDismiss = { dlg = ToolDlg.None },
            confirmText = d.confirmText,
            onConfirm = { d.onConfirm() }
        )
        is ToolDlg.Confirm -> AlertDialog(
            onDismissRequest = { dlg = ToolDlg.None },
            title = { Text(d.title) },
            text = { Text(d.body) },
            confirmButton = {
                TextButton(onClick = {
                    val act = d.onConfirm
                    dlg = ToolDlg.None
                    act()
                }) { Text(d.confirmText) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val act = d.onDismiss
                    dlg = ToolDlg.None
                    act?.invoke()
                }) { Text(d.dismissText) }
            }
        )
        is ToolDlg.Input -> {
            var text by remember(d.title) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dlg = ToolDlg.None },
                title = { Text(d.title) },
                text = {
                    Column {
                        Text(d.body, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = text, onValueChange = { text = it },
                            label = { Text(d.hint) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val act = d.onOk
                        val v = text
                        dlg = ToolDlg.None
                        act(v)
                    }) { Text("打开") }
                },
                dismissButton = { TextButton(onClick = { dlg = ToolDlg.None }) { Text("取消") } }
            )
        }
    }
}

@Composable
private fun ToolGrid(list: List<ToolItem>) {
    Column(Modifier.fillMaxWidth()) {
        for (row in list.chunked(3)) {
            Row(Modifier.fillMaxWidth()) {
                for (it in row) {
                    Box(Modifier.weight(1f).padding(4.dp)) { ToolTile(it) }
                }
                // 最后一行不满时补空位，否则剩下的格子会被 weight 拉宽、跟上面几行对不齐
                repeat(3 - row.size) { Spacer(Modifier.weight(1f).padding(4.dp)) }
            }
        }
    }
}

@Composable
private fun ToolTile(it: ToolItem) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = it.act),
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
                painterResource(it.icon), null,
                Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                it.label, fontSize = 12.sp, textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

/**
 * 在 SAF 树里递归找指定后缀的文件。
 *
 * @param exts 后缀集合，如 listOf(".dat", ".nbt")
 * @param maxDepth 目录层级上限。默认 4 对 .dat 够用，
 *   但 .mca 藏在 saves/<世界>/region/ 下，需要放宽到 6。
 */
private fun collectSafNbt(
    ctx: Context, uriStr: String, out: ArrayList<Pair<String, Any>>,
    exts: List<String> = listOf(".dat", ".nbt"), maxDepth: Int = 4
) {
    val tree = Fs.tree(ctx, uriStr) ?: return
    fun walk(d: DocumentFile, depth: Int) {
        if (out.size >= 40 || depth > maxDepth) return
        for (c in d.listFiles()) {
            if (out.size >= 40) return
            if (c.isDirectory) walk(c, depth + 1)
            else {
                val n = c.name ?: continue
                if (!exts.any { n.endsWith(it) }) continue
                // 找 .mca 时排除 poi/ 与 entities/：它们不存方块，
                // 点开只能看到「没有方块数据」
                val pn = c.parentFile?.name
                val wantRegion = exts.any { it == ".mca" || it == ".mcr" }
                if (wantRegion && (pn == "poi" || pn == "entities")) continue
                out.add(labelOf(n, c) to c.uri)
            }
        }
    }
    walk(tree, 0)
}

/**
 * 只有 `region/` 下的 .mca 才存方块。
 *
 * `poi/` 存兴趣点、`entities/` 存实体，两者里面没有 sections，
 * 打开必然是「没有方块数据」。全都列出来的话，
 * 随手一点有三分之二是空的 —— 这正是「点进去全提示无数据」的直接来源。
 */
private fun isTerrain(f: File): Boolean {
    val p = f.parentFile?.name
    return p != "poi" && p != "entities"
}

/**
 * 列表里怎么称呼一个文件。
 *
 * ⚠️ 之前直接显示文件名：level.dat / r.0.0.mca 在每个世界里都叫这个，
 * 一排同名摆在一起根本分不清是哪个世界的。
 * 现在带上所属世界名，形如「新的世界 / level.dat」。
 */
private fun labelOf(fileName: String, f: File): String {
    val p0 = f.parentFile
    val p0n = p0?.name
    // region/ / poi/ / entities/ 这层没有信息量，再往上一层才是世界名
    val wn = if (p0n == "region" || p0n == "poi" || p0n == "entities")
        p0?.parentFile?.name else p0n
    return if (wn.isNullOrBlank()) fileName else "$wn / $fileName"
}

private fun labelOf(fileName: String, d: DocumentFile): String {
    val parent = d.parentFile?.name
    val wn = if (parent == "region" || parent == "poi" || parent == "entities")
        d.parentFile?.parentFile?.name else parent
    return if (wn.isNullOrBlank()) fileName else "$wn / $fileName"
}

/** 一个游戏目录（.minecraft）里可能的存档根目录，按可能性排序 */
private fun savesDirsOf(base: File): List<File> {
    val out = LinkedHashSet<File>()
    for (c in listOf(
        File(base, McDirs.SAVES), base,
        File(base, ".minecraft/saves")
    )) {
        if (c.isDirectory) out.add(c)
    }
    // 版本隔离：saves 也可能在 versions/<版本>/ 下
    File(base, McDirs.VERSIONS).listFiles()?.forEach { v ->
        val vs = File(v, McDirs.SAVES)
        if (vs.isDirectory) out.add(vs)
    }
    return out.toList()
}

/**
 * SAF 世界 → 本地镜像目录。
 *
 * 区块地图整块逻辑基于 File，而 content:// 拿不到真实路径，
 * 所以先把 region 下的 .mca 复制过来，
 * 并在 [McaWorld.Mirror] 里记下每个文件的原始 Uri，保存时写回。
 * poi/ 与 entities/ 不复制 —— 它们不存方块，白占空间。
 */
private fun mirrorSafWorld(ctx: Context, world: DocumentFile): File? {
    val name = (world.name ?: "存档").replace(Regex("[^A-Za-z0-9._ -]"), "_")
    val root = File(ctx.filesDir, "mca-mirror").apply { mkdirs() }
    // 旧镜像不清会和别的世界混在一起
    root.listFiles()?.forEach { it.deleteRecursively() }
    val dir = File(root, name)
    McaWorld.Mirror.clear()
    var n = 0
    fun walk(d: DocumentFile, depth: Int, rel: String) {
        if (depth > 5) return
        for (c in d.listFiles()) {
            val cn = c.name ?: continue
            if (c.isDirectory) {
                if (cn == "poi" || cn == "entities") continue
                walk(c, depth + 1, if (rel.isEmpty()) cn else "$rel/$cn")
            } else if (cn.endsWith(".mca") || cn.endsWith(".mcr")) {
                val t = File(dir, if (rel.isEmpty()) cn else "$rel/$cn")
                t.parentFile?.mkdirs()
                runCatching {
                    ctx.contentResolver.openInputStream(c.uri)?.use { src ->
                        t.outputStream().use { src.copyTo(it) }
                    }
                } ?: continue
                if (!t.isFile || t.length() <= 0) continue
                McaWorld.Mirror.put(t, c.uri)
                n++
            }
        }
    }
    walk(world, 0, "")
    return if (n > 0) dir else null
}

private fun fmt(size: Long): String {
    if (size < 1024) return "${size}B"
    if (size < 1024 * 1024) return "${size / 1024}KB"
    return String.format(Locale.ROOT, "%.1fMB", size / 1024.0 / 1024.0)
}
