package com.kongbai.modmigrator

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import java.util.Locale
import java.util.concurrent.Executors

/** 日志弹窗里最多显示多少条（完整内容走「复制」） */
private const val MAX_SHOW_LINES = 40

/** 错误弹窗最多合并多少条（再多只更新计数，不继续堆文本） */
private const val MAX_ERR_LINES = 20

class MigrationFragment : Fragment() {

    private var resumeCb: (() -> Unit)? = null
    private var resultCb: ((Int, Uri) -> Unit)? = null

    override fun onResume() {
        super.onResume()
        resumeCb?.invoke()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        resultCb?.invoke(requestCode, uri)
    }

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
                    MigrationScreen(
                        ctx = ctx,
                        register = { resumeCb = it },
                        launch = { i, code -> startActivityForResult(i, code) },
                        pickDir = { code, purpose ->
                            DirGuide.pick(this@MigrationFragment, code, purpose)
                        },
                        openServer = { (activity as? MainActivity)?.showTab("server") },
                        onResult = { resultCb = it }
                    )
                }
            }
        }
    }
}

/** 弹窗状态。用数据类承载，页面末尾统一渲染。 */
private sealed class MigDlg {
    object None : MigDlg()
    /** 长文本 + 若干操作 */
    data class Text(
        val title: String, val body: String,
        val actions: List<Pair<String, () -> Unit>>
    ) : MigDlg()

    data class Confirm(
        val title: String, val body: String,
        val ok: String, val act: () -> Unit
    ) : MigDlg()

    data class Pick(
        val title: String, val body: String,
        val options: List<String>, val act: (Int) -> Unit
    ) : MigDlg()

    data class Logs(val title: String, val shown: String, val full: String) : MigDlg()
}

private fun pathText(ctx: Context, key: String): String =
    DirGuide.human(Prefs.get(ctx).getString(key, "") ?: "")
        .ifBlank { ctx.getString(R.string.empty_hint) }

private fun serverStateOf(ctx: Context): String {
    val p = Prefs.get(ctx)
    val host = p.getString(K.REMOTE_HOST, "") ?: ""
    if (host.isBlank()) return ctx.getString(R.string.server_not_connected)
    val kind = when (p.getInt(K.REMOTE_KIND, 0)) {
        1 -> "FTP"; 2 -> "FTPS"; 3 -> "SFTP"; else -> "面板"
    }
    return "$kind · $host"
}

private fun logSummaryOf(): String {
    val n = LogCenter.count()
    val last = LogCenter.recent(1).firstOrNull()
    return if (n == 0) "暂无日志" else "共 $n 条日志 · 最新：${last?.msg ?: ""}"
}

private fun loaderIndexOf(list: List<String>, value: String): Int {
    val i = list.indexOf(Loaders.normalize(value))
    return if (i >= 0) i else 0
}

@Composable
private fun MigrationScreen(
    ctx: Context,
    register: (() -> Unit) -> Unit,
    launch: (Intent, Int) -> Unit,
    pickDir: (Int, DirGuide.Purpose) -> Unit,
    openServer: () -> Unit,
    onResult: ((Int, Uri) -> Unit) -> Unit
) {
    val prefs = Prefs.get(ctx)

    var serverMode by remember { mutableStateOf(prefs.getInt(K.APP_MODE, 0) == 1) }
    var srcText by remember { mutableStateOf(pathText(ctx, K.SRC_URI)) }
    var dstText by remember { mutableStateOf(pathText(ctx, K.DST_URI)) }
    var dirState by remember {
        mutableStateOf(DirGuide.state(ctx, prefs.getString(K.SRC_URI, "") ?: ""))
    }
    var serverText by remember { mutableStateOf(serverStateOf(ctx)) }
    var version by remember { mutableStateOf(prefs.getString(K.DEF_VERSION, "") ?: "") }
    val loaders = remember { Loaders.visible(prefs.getBoolean(K.SHOW_EXTRA_LOADERS, false)) }
    var loaderIdx by remember {
        mutableStateOf(loaderIndexOf(loaders, prefs.getString(K.DEF_LOADER, "auto") ?: "auto"))
    }
    var parts by remember {
        mutableStateOf(
            McParts.resolve(prefs.getString(McParts.PREF_MIGRATE, "") ?: "", false)
                .map { it.id }.toSet()
        )
    }
    var scanHint by remember { mutableStateOf(ctx.getString(R.string.scan_launcher_dir_hint)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(Progress.State()) }
    val mods = remember { mutableStateListOf<ModEntry>() }
    var rev by remember { mutableStateOf(0) }
    var logText by remember { mutableStateOf("") }
    var logSummary by remember { mutableStateOf(logSummaryOf()) }
    var dlg by remember { mutableStateOf<MigDlg>(MigDlg.None) }
    var srcMore by remember { mutableStateOf(false) }
    var moreOps by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var modeTip by remember { mutableStateOf(false) }
    var partsOpen by remember { mutableStateOf(false) }
    var errShow by remember { mutableStateOf(false) }
    var errBuf by remember { mutableStateOf("") }
    var errCount by remember { mutableStateOf(0) }
    var errMuted by remember { mutableStateOf(false) }
    val instances = remember { mutableListOf<InstanceInfo>() }

    fun toast(s: String) {
        Bg.ui.post { runCatching { Tips.short(ctx, s) } }
    }

    fun post(block: () -> Unit) {
        Bg.ui.post(block)
    }

    fun log(s: String) {
        LogCenter.i("迁移", s)
        post {
            logText = (logText + s + "\n").takeLast(20000)
            logSummary = logSummaryOf()
        }
    }

    /** 列表条目是在原地改字段的，改完要显式触发一次重绘 */
    fun bump() {
        rev++
    }

    fun bg(block: () -> Unit) {
        Bg.io.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("错误：${t.message}")
            } finally {
                // 无论正常结束、提前 return 还是抛异常，都必须复位进度状态
                runCatching { Progress.done() }
            }
        }
    }

    fun setVersion(v: String) {
        version = v
        Prefs.get(ctx).edit().putString(K.DEF_VERSION, v.trim()).apply()
    }

    fun setLoader(name: String) {
        val i = loaderIndexOf(loaders, name)
        loaderIdx = i
        Prefs.get(ctx).edit()
            .putString(K.DEF_LOADER, loaders.getOrNull(i) ?: "auto").apply()
    }

    fun refreshPaths() {
        srcText = pathText(ctx, K.SRC_URI)
        dstText = pathText(ctx, K.DST_URI)
        dirState = DirGuide.state(ctx, Prefs.get(ctx).getString(K.SRC_URI, "") ?: "")
        serverText = serverStateOf(ctx)
        serverMode = Prefs.get(ctx).getInt(K.APP_MODE, 0) == 1
    }

    fun copyLogs(txt: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                toast("复制失败：拿不到剪贴板")
                return
            }
            cm.setPrimaryClip(ClipData.newPlainText("运行日志", txt))
            toast("已复制全部日志（${txt.length} 字符）")
        }.onFailure { toast("复制失败：${Err.humanMessage(it)}") }
    }

    fun showAllLogs() {
        val all = LogCenter.all()
        val full = all.joinToString("\n") { it.toString() }.ifBlank { "暂无日志" }
        // 显示层截断、复制层给全文：整段日志塞进对话框会把内存吃紧的设备拖垮
        val shown = if (all.size > MAX_SHOW_LINES) {
            "（仅显示最近 $MAX_SHOW_LINES 条，共 ${all.size} 条；点「复制」可拿到完整日志）\n\n" +
                all.take(MAX_SHOW_LINES).joinToString("\n") { it.toString() }
        } else {
            all.joinToString("\n") { it.toString() }
        }
        dlg = MigDlg.Logs(
            "运行日志（共 ${LogCenter.count()} 条）",
            shown.ifBlank { "暂无日志" },
            full
        )
    }

    /**
     * 错误弹窗：已经在弹的就往里追加，不再叠一个新的。
     * 判断依据是"对话框还开着吗"，而不是"距上次多久"——
     * 按时间判的话，批量操作持续几十秒，3 秒一过就又叠一个。
     */
    fun showErr(line: LogCenter.LogLine) {
        if (errShow) {
            errCount++
            if (errBuf.length < 3000 && errCount <= MAX_ERR_LINES) {
                errBuf = errBuf + "\n\n" + line.msg
            }
            return
        }
        errBuf = line.msg
        errCount = 1
        errShow = true
    }

    val errText = if (errCount > 1) {
        buildString {
            append("（共 $errCount 条错误")
            if (errCount > MAX_ERR_LINES) append("，仅显示前 $MAX_ERR_LINES 条")
            append("）\n\n").append(errBuf)
        }
    } else errBuf

    // ── 目录 / 实例 ──────────────────────────────────────────

    fun askAllFiles() {
        try {
            if (InstanceScanner.hasAllFilesAccess()) {
                toast("已有全部文件访问权限")
                return
            }
            val i = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            i.data = Uri.parse("package:" + ctx.packageName)
            ctx.startActivity(i)
            toast("请打开「允许访问所有文件」，回来后重新扫描")
        } catch (t: Throwable) {
            try {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            } catch (t2: Throwable) {
                toast("无法打开权限设置页，请手动在系统设置里授权")
            }
        }
    }

    fun detectLoaderFromMods(root: DocumentFile): String {
        val modsDir = runCatching { Fs.find(root, McDirs.MODS) }.getOrNull() ?: return "auto"
        val files = runCatching { modsDir.listFiles() }.getOrNull() ?: return "auto"
        val votes = HashMap<String, Int>()
        for (f in files) {
            val n = (f.name ?: "").lowercase(Locale.ROOT)
            if (!n.endsWith(".jar") && !n.endsWith(".jar.disabled")) continue
            val ld = Loaders.normalize(n)
            if (ld == "auto") continue
            votes[ld] = (votes[ld] ?: 0) + 1
        }
        if (votes.isEmpty()) return "auto"
        return votes.entries.maxByOrNull { it.value }?.key ?: "auto"
    }

    fun detectSource(root: DocumentFile) {
        val modsLoader = detectLoaderFromMods(root)
        val mmc = Fs.find(root, "mmc-pack.json")
        if (mmc != null) {
            val o = Json.obj(Fs.readText(ctx, mmc))
            val comps = Json.a(o, "components")
            var mc = ""
            var loader = "auto"
            if (comps != null) {
                for (c in comps) {
                    val uid = Json.s(c, "uid")
                    val ver = Json.s(c, "version")
                    when {
                        uid == "net.minecraft" -> mc = ver
                        uid.startsWith("net.fabricmc") || uid.startsWith("org.quiltmc") ->
                            if (uid.contains("quilt")) loader = "quilt" else loader = "fabric"
                        uid.startsWith("net.minecraftforge") -> loader = "forge"
                        uid.startsWith("net.neoforged") -> loader = "neoforge"
                    }
                }
            }
            val finalLoader = if (loader == "auto") modsLoader else loader
            if (mc.isNotBlank()) {
                post { setVersion(mc); setLoader(finalLoader) }
                log("识别到 MC $mc / $finalLoader（MultiMC/Prism 实例）")
                return
            }
        }
        val man = Fs.find(root, "manifest.json")
        if (man != null) {
            val o = Json.obj(Fs.readText(ctx, man))
            val mcObj = o?.asJsonObject?.get("minecraft")
            val mc = Json.s(mcObj, "version")
            var loader = "auto"
            val ls = Json.a(mcObj, "modLoaders")
            if (ls != null && ls.size() > 0) {
                val idv = Json.s(ls.get(0), "id")
                loader = when {
                    idv.startsWith("forge") -> "forge"
                    idv.startsWith("neoforge") -> "neoforge"
                    idv.startsWith("fabric") -> "fabric"
                    idv.startsWith("quilt") -> "quilt"
                    else -> "auto"
                }
            }
            val finalLoader = if (loader == "auto") modsLoader else loader
            if (mc.isNotBlank()) {
                post { setVersion(mc); setLoader(finalLoader) }
                log("识别到 MC $mc / $finalLoader（CurseForge 清单）")
                return
            }
        }
        val vj = Fs.find(root, "version.json")
        if (vj != null) {
            val o = Json.obj(Fs.readText(ctx, vj))
            val id = Json.s(o, "id")
            if (id.isNotBlank()) {
                post {
                    setVersion(id)
                    if (modsLoader != "auto") setLoader(modsLoader)
                }
                log("识别到 MC $id / $modsLoader（version.json + 模组推断）")
                return
            }
        }
        if (modsLoader != "auto") {
            post { setLoader(modsLoader) }
            log("未找到版本清单，按已装模组推断加载器：$modsLoader。MC 版本仍需手动填写")
            return
        }
        log("未能自动识别版本，请手动填写目标 MC 版本")
    }

    fun setAs(inst: InstanceInfo, asSource: Boolean) {
        val p = Prefs.get(ctx)
        if (asSource) {
            p.edit().putString(K.SRC_URI, inst.uri).apply()
            log("迁移前的版本：${inst.name}（同设备）")
        } else {
            p.edit().putString(K.DST_URI, inst.uri).apply()
            log("迁移后的版本：${inst.name}（同设备）")
        }
        if (inst.mcVersion.isNotBlank() && !asSource) {
            post { setVersion(inst.mcVersion); setLoader(inst.loader) }
        }
        refreshPaths()
        if (asSource) {
            bg {
                val root = Fs.tree(ctx, inst.uri)
                if (root != null) detectSource(root)
            }
        }
    }

    fun showInstancePicker() {
        val labels = instances.map {
            val src = if (it.source.isBlank()) "" else " · ${it.source}"
            "${it.name} · MC ${it.mcVersion.ifBlank { "?" }} · ${it.modCount} 模组$src"
        }
        dlg = MigDlg.Pick(ctx.getString(R.string.pick_instance), "", labels) { w ->
            val inst = instances[w]
            dlg = MigDlg.None
            dlg = MigDlg.Text(
                "把 ${inst.name} 设为？", "",
                listOf(
                    "迁移前的版本" to { dlg = MigDlg.None; setAs(inst, true) },
                    "迁移后的版本" to { dlg = MigDlg.None; setAs(inst, false) }
                )
            )
        }
    }

    fun scanLauncherData(pkg: String) {
        val dirs = LauncherHelper.dataDirs(ctx, pkg)
        if (dirs.isEmpty()) {
            toast("没找到 ${pkg} 的数据目录，可手动指定目录")
            return
        }
        toast("按 ${pkg} 去找各个版本…")
        bg {
            val found = mutableListOf<InstanceInfo>()
            for (d in dirs) {
                log("检查数据目录：$d")
                found.addAll(InstanceScanner.scanFilesFrom(d) { m -> log(m) })
            }
            post {
                instances.clear()
                instances.addAll(found)
                if (found.isEmpty()) {
                    toast("该目录下没找到版本，可手动选择目录")
                } else {
                    toast("找到 ${found.size} 个版本")
                    scanHint = "找到 ${found.size} 个版本，可点上面的框选择"
                    showInstancePicker()
                }
            }
        }
    }

    fun scanLocal() {
        toast("扫描中…")
        scanHint = "正在扫描…"
        bg {
            var list = mutableListOf<InstanceInfo>()
            if (InstanceScanner.hasAllFilesAccess()) {
                list.addAll(InstanceScanner.scanFiles(log = { m -> log(m) }))
            } else {
                log("未获得「所有文件访问」权限，先试 SAF 目录扫描…")
            }
            if (list.isEmpty()) {
                val root = Prefs.get(ctx).getString(K.SCAN_ROOT, null)
                if (!root.isNullOrBlank()) {
                    list.addAll(InstanceScanner.scanFrom(ctx, root) { m -> log(m) })
                }
            }
            val fromDirs = InstanceScanner.scanAll(ctx) { m -> log(m) }
            val known = list.map { it.uri }.toHashSet()
            for (info in fromDirs) {
                val old = list.firstOrNull { it.uri == info.uri }
                if (old != null) {
                    if (old.source.isBlank()) old.source = info.source
                    continue
                }
                list.add(info)
            }
            if (known.isNotEmpty() || fromDirs.isNotEmpty()) {
                log("合计 ${list.size} 个版本${if (known.isNotEmpty()) "（其中 ${known.size} 个已扫到过）" else ""}")
            }
            val finalList = list
            if (finalList.isEmpty() && !InstanceScanner.hasAllFilesAccess()) {
                post {
                    log("提示：启动器实例通常在 Android/data 下，需要「所有文件访问」权限才能读到")
                    dlg = MigDlg.Text(
                        "扫不到实例",
                        "启动器数据多在 Android/data 目录，Android 11 起必须授予「所有文件访问」权限才能读取。要现在去授权吗？",
                        listOf(
                            "去授权" to { dlg = MigDlg.None; askAllFiles() },
                            "改用手动选目录" to { dlg = MigDlg.None; pickDir(13, DirGuide.Purpose.SCAN_ROOT) }
                        )
                    )
                }
            }
            post {
                instances.clear()
                instances.addAll(finalList)
                toast(if (finalList.isEmpty()) "没找到实例" else "找到 ${finalList.size} 个实例")
                if (finalList.isNotEmpty()) showInstancePicker()
            }
        }
    }

    fun handleResult(requestCode: Int, uri: Uri) {
        if (!Perms.take(ctx, uri)) log("授权持久化失败，可到设置 → 存储里重新选择目录")
        val p = Prefs.get(ctx)
        when (requestCode) {
            11 -> {
                p.edit().putString(K.SRC_URI, uri.toString()).apply()
                refreshPaths()
                DirGuide.check(ctx, uri, DirGuide.Purpose.SOURCE)?.let { log("⚠ $it") }
                val root = Fs.tree(ctx, uri.toString())
                if (root != null) {
                    log("「迁移前」的版本已选择，正在识别版本…")
                    bg { detectSource(root) }
                }
            }
            12 -> {
                p.edit().putString(K.DST_URI, uri.toString()).apply()
                refreshPaths()
                DirGuide.check(ctx, uri, DirGuide.Purpose.TARGET)?.let { log("⚠ $it") }
                log("「迁移后」的版本已选择")
            }
            31 -> {
                val pkg = Prefs.get(ctx).getString(K.LAUNCHER, "") ?: ""
                if (pkg.isNotBlank()) {
                    log("已选启动器：$pkg")
                    scanLauncherData(pkg)
                }
            }
            13 -> {
                p.edit().putString(K.SCAN_ROOT, uri.toString()).apply()
                DirGuide.check(ctx, uri, DirGuide.Purpose.SCAN_ROOT)?.let { log("⚠ $it") }
                log("已选择目录，正在里面找各个版本…")
                scanLocal()
            }
        }
    }

    // ── 下载与重试 ──────────────────────────────────────────

    fun downloadOne(m: ModEntry) {
        bg {
            val dir = Targets.modsDir(ctx)
            if (dir == null) {
                toast("「迁移后」的目录不可用")
                return@bg
            }
            post {
                m.status = "下载中"
                bump()
            }
            val name = m.targetFileName.ifBlank { Downloader.guessName(m.targetUrl) }
            val f = Downloader.download(ctx, m.targetUrl, dir, name)
            post {
                m.status = if (f == null) "下载失败" else "已安装"
                bump()
            }
            Notifier.show(ctx, "模组迁移", m.name)
        }
    }

    fun downloadAll() {
        val list = mods.filter { it.targetUrl.isNotBlank() }
        if (list.isEmpty()) {
            toast("没有可下载的模组（可能还没扫描，或都没有适配版本）")
            return
        }
        val dir = Targets.modsDir(ctx)
        if (dir == null) {
            toast("「迁移后」的目录不可用")
            return
        }
        dlg = MigDlg.Confirm(
            "一键下载",
            ctx.getString(R.string.download_all_confirm, list.size),
            "开始"
        ) {
            dlg = MigDlg.None
            for (m in list) {
                DownloadService.start(
                    ctx, m.targetUrl,
                    m.targetFileName.ifBlank { Downloader.guessName(m.targetUrl) },
                    "mods"
                )
            }
            toast(ctx.getString(R.string.download_all_bg, list.size))
            log("已在后台开始下载 ${list.size} 个模组")
        }
    }

    fun retryDownloads() {
        val todo = mods.filter { it.status.startsWith("下载失败") }
        if (todo.isEmpty()) {
            toast("没有需要重试的项")
            return
        }
        val dstUri = Prefs.get(ctx).getString(K.DST_URI, null)
        if (dstUri == null) {
            toast("「迁移后」的目录不可用")
            return
        }
        toast("重试 ${todo.size} 个…")
        busy = true
        bg {
            val dst = Fs.tree(ctx, dstUri)
            val dir = if (dst != null) Fs.ensureDir(dst, McDirs.MODS) else null
            if (dir == null) {
                post {
                    busy = false
                    toast("目标目录不可用")
                }
                return@bg
            }
            var ok = 0
            val still = ArrayList<String>()
            for (m in todo) {
                val name = m.targetFileName.ifBlank { Downloader.guessName(m.targetUrl) }
                var err = ""
                val f = try {
                    Downloader.download(ctx, m.targetUrl, dir, name)
                } catch (t: Throwable) {
                    err = Http.describeError(t)
                    null
                }
                if (f != null) {
                    ok++
                    post { m.status = "已安装"; bump() }
                } else {
                    still.add("${m.name.ifBlank { m.fileName }}：$err")
                    post { m.status = "下载失败（$err）"; bump() }
                }
            }
            post {
                busy = false
                toast("重试完成：成功 $ok / ${todo.size}")
                log("下载重试：成功 $ok / ${todo.size}")
                if (still.isNotEmpty()) {
                    dlg = MigDlg.Text(
                        "仍有 ${still.size} 个失败",
                        still.take(10).joinToString("\n") { "  · $it" },
                        emptyList()
                    )
                }
            }
        }
    }

    fun retryFailed() {
        val todo = mods.filter { it.netError }
        if (todo.isEmpty()) {
            toast("没有需要重试的项")
            return
        }
        toast("重试 ${todo.size} 个…")
        val pp = Prefs.get(ctx)
        val mc = pp.getString(K.DEF_VERSION, "") ?: ""
        val loader = pp.getString(K.DEF_LOADER, "auto") ?: "auto"
        bg {
            var fixed = 0
            val hashes = todo.map { it.sha1.lowercase() }
            val found = ModrinthApi.lookupHashes(hashes)
            val recognized = todo.filter { found[it.sha1.lowercase()]?.found == true }
            val latests = if (recognized.isEmpty()) {
                emptyMap()
            } else {
                ModrinthApi.latestForHashes(recognized.map { it.sha1 }, mc, loader)
            }
            ModrinthApi.refreshAll(
                todo.mapNotNull { found[it.sha1.lowercase()]?.projectId }
                    .filter { it.isNotBlank() }
                    .distinct()
            )
            for (e in todo) {
                val key = e.sha1.lowercase()
                val info = found[key]
                when {
                    info == null -> post { e.status = "Modrinth 未收录"; bump() }
                    info.netError -> post { e.status = "仍连不上（${info.msg}）"; bump() }
                    else -> {
                        val v0 = latests[key]
                        post {
                            if (v0 != null) {
                                e.projectId = info.projectId
                                e.name = ModrinthApi.title(info.projectId).ifBlank { e.fileName }
                                e.targetVersion = v0.version
                                e.targetUrl = v0.url
                                e.targetFileName = v0.fileName
                                e.status = "可迁移"
                                e.netError = false
                                fixed++
                            } else {
                                e.status = "重试后仍无可用文件"
                                e.netError = false
                            }
                            bump()
                        }
                    }
                }
            }
            post {
                toast("重试完成，成功 $fixed / ${todo.size}")
                log("重试完成：成功 $fixed / ${todo.size}")
            }
        }
    }

    // ── 扫描 ────────────────────────────────────────────────

    fun scan() {
        val p = Prefs.get(ctx)
        val srcUri = p.getString(K.SRC_URI, null)
        if (srcUri == null) {
            toast(ctx.getString(R.string.no_source))
            return
        }
        val mc = version.trim()
        val loader = loaders.getOrNull(loaderIdx) ?: "auto"
        if (mc.isBlank()) {
            toast("请填写目标 MC 版本")
            return
        }
        p.edit().putString(K.DEF_VERSION, mc).putString(K.DEF_LOADER, loader).apply()
        busy = true
        mods.clear()
        log("开始扫描…目标 MC $mc / $loader")

        bg {
            val root = Fs.tree(ctx, srcUri)
            if (root == null) {
                log("「迁移前」的目录不可访问")
                post { busy = false }
                return@bg
            }
            val dir = Fs.find(root, McDirs.MODS)
            if (dir == null) {
                log("「迁移前」的目录里没有找到 mods 文件夹")
                post { busy = false }
                return@bg
            }
            val files = Fs.children(dir).filter {
                it.isFile && (it.name ?: "").endsWith(".jar", true)
            }
            log("发现 ${files.size} 个 jar")
            var netFail = 0
            val netFailNames = ArrayList<String>()
            var notFound = 0
            var unreadable = 0
            val unreadableNames = ArrayList<String>()
            if (files.isEmpty()) log("这个目录里没有 jar 文件")

            val entries = ArrayList<ModEntry>(files.size)
            var idx = 0
            for (f in files) {
                idx++
                Progress.update("计算文件指纹（$idx/${files.size}）", idx, files.size)
                val e = ModEntry(fileName = f.name ?: "mod.jar", uri = f.uri.toString())
                e.status = "正在识别…"
                e.sha1 = Fs.sha1(ctx, f)
                if (e.sha1.isBlank()) {
                    e.status = "读不出文件内容（权限或损坏）"
                    // 不是网络问题，重试联网也没用，所以不能标 netError
                    e.netError = false
                    unreadable++
                    unreadableNames.add(e.fileName)
                    log("× ${e.fileName}：无法读取")
                }
                entries.add(e)
                // 算完一条就摆进列表，别让用户对着空列表干等后面的网络请求
                post { mods.add(e) }
            }
            val hashes = entries.map { it.sha1.lowercase() }

            Progress.update("正在识别 ${entries.size} 个模组…", 1, 3)
            log("批量识别 ${entries.size} 个模组…")
            val found = ModrinthApi.lookupHashes(hashes)

            val recognized = entries.filter { found[it.sha1.lowercase()]?.found == true }
            Progress.update("正在获取目标版本…", 2, 3)
            val latests = if (recognized.isEmpty()) {
                emptyMap()
            } else {
                ModrinthApi.latestForHashes(recognized.map { it.sha1 }, mc, loader)
            }

            Progress.update("正在取模组名称…", 3, 3)
            // 必须从 found 里取 projectId：entry.projectId 要到回填时才被赋值
            val recognizedPids = entries
                .mapNotNull { found[it.sha1.lowercase()]?.projectId }
                .filter { it.isNotBlank() }
                .distinct()
            ModrinthApi.refreshAll(recognizedPids)

            for (e in entries) {
                if (e.sha1.isBlank()) {
                    post { bump() }
                    continue
                }
                val key = e.sha1.lowercase()
                val info = found[key]
                when {
                    info == null -> {
                        notFound++
                        e.status = "Modrinth 未收录（可去市场手动标记链接）"
                    }
                    info.netError -> {
                        netFail++
                        netFailNames.add(e.fileName)
                        e.status = "网络问题未能识别（${info.msg}）"
                        e.netError = true
                        log("× ${e.fileName}：${info.msg}，已跳过")
                    }
                    else -> {
                        e.projectId = info.projectId
                        e.currentVersion = info.version
                        e.slug = ModrinthApi.slug(info.projectId)
                        e.name = ModrinthApi.title(info.projectId).ifBlank { e.fileName }
                        e.pageUrl = if (e.slug.isNotBlank()) {
                            "https://modrinth.com/mod/${e.slug}"
                        } else {
                            "https://modrinth.com/mod/${e.projectId}"
                        }
                        val v0 = latests[key]
                        if (v0 == null) {
                            val alt = Loaders.equivalent(e.name.ifBlank { e.fileName }, loader)
                            e.status = when {
                                alt == null -> "「迁移后」的版本无可用文件"
                                alt.isBlank() -> "该加载器下不需要此组件"
                                else -> "建议改用：$alt"
                            }
                            if (!alt.isNullOrBlank()) {
                                e.targetFileName = alt
                                log("${e.name} 在 ${Loaders.label(loader)} 上无构建，平替：${alt}")
                            }
                        } else {
                            e.targetVersion = v0.version
                            e.targetUrl = v0.url
                            e.targetFileName = v0.fileName
                            e.status = "可迁移"
                        }
                    }
                }
                post { bump() }
            }
            log("扫描完成（网络请求：识别 1 次 + 取版本 1 次 + 取名 1 次）")
            val nf = netFail
            val nfNames = ArrayList(netFailNames)
            val nfound = notFound
            val nUnread = unreadable
            val unreadNames = ArrayList(unreadableNames)
            post {
                busy = false
                val ok = mods.count { it.targetUrl.isNotBlank() }
                toast("可迁移 $ok / ${mods.size}")
                if (nf > 0 || nUnread > 0) {
                    if (nf > 0) log("⚠ $nf 个因网络问题没能识别，其余已正常处理")
                    if (nUnread > 0) log("⚠ $nUnread 个文件读不出来（权限或损坏），与网络无关")
                    dlg = MigDlg.Text(
                        "有 ${nf + nUnread} 个模组没识别成功",
                        buildString {
                            if (nf > 0) {
                                append("因网络问题没查到（可重试）：\n\n")
                                nfNames.take(10).forEach { append("  · $it\n") }
                                if (nf > 10) append("  …等 $nf 个\n")
                                append("\n")
                            }
                            if (nUnread > 0) {
                                append("读不出内容（权限或文件损坏，重试没用）：\n\n")
                                unreadNames.take(10).forEach { append("  · $it\n") }
                                if (nUnread > 10) append("  …等 $nUnread 个\n")
                                append("\n")
                            }
                            append("另外有 $nfound 个是 Modrinth 确实没收录，")
                            append("这类重试也没用，需手动标记链接。\n\n")
                            append("建议：换个网络或稍后再「重试失败项」。")
                        },
                        listOf("重试失败项" to { dlg = MigDlg.None; retryFailed() })
                    )
                }
            }
        }
    }

    // ── 迁移执行 ────────────────────────────────────────────

    fun openModDetail(m: ModEntry) {
        val url = if (m.pageUrl.isNotBlank()) {
            m.pageUrl
        } else {
            val q = Http.enc(m.name.ifBlank { m.fileName.substringBeforeLast(".jar") })
            "https://modrinth.com/mods?q=$q"
        }
        val i = Intent(ctx, ModPageActivity::class.java)
        i.putExtra("url", url)
        ctx.startActivity(i)
    }

    fun togglePause() {
        if (!Progress.get().running) return
        if (Progress.isPaused()) {
            Progress.resume()
            log("已继续")
        } else {
            Progress.pause()
            log("已暂停（当前这一步完成后停下）")
        }
        post { progress = Progress.get() }
    }

    fun cancelTask() {
        if (!Progress.get().running) return
        Progress.cancel()
        log("已请求中止，正在收尾…")
        post { progress = Progress.get() }
    }

    fun doMigrate(srcUri: String, dstUri: String) {
        // 同一时间只允许一个迁移。busy 只是页面状态，离开页面就丢了，
        // 通知栏、工具箱这些别的入口还能再起一个；两个任务同时往同一个
        // mods 目录写，出来的包是坏的，而用户只看得到进度条乱跳。
        if (!MigGuard.acquire(MigGuard.TAG_MIGRATE)) {
            toast("已经有一个迁移在跑，请先等它结束，或点「中止」")
            return
        }
        val p = Prefs.get(ctx)
        val chosen = McParts.ALL.filter { parts.contains(it.id) }
        busy = true
        log("开始迁移…")

        bg {
            // try/finally 而不是在每个分支里各放一次：
            // 下面有好几处 return@bg（目录打不开、被中止、没有可下载的），
            // 漏一处就会永久占住，之后再也迁不动。
            try {
            val src = Fs.tree(ctx, srcUri)
            val dst = Fs.tree(ctx, dstUri)
            if (src == null || dst == null) {
                log("目录不可访问，请重新选择并授权")
                Progress.done("目录不可访问")
                post { busy = false }
                return@bg
            }

            val steps = ArrayList<Pair<String, String>>()
            for (pt in chosen) {
                if (pt.id == McDirs.MODS) continue // 模组走下载阶段，不在这里复制
                steps.add(Pair(pt.name, pt.label))
            }

            for ((idx, step) in steps.withIndex()) {
                if (Progress.awaitIfPaused()) {
                    log("已中止：${step.second} 未复制")
                    Progress.done("已中止")
                    post { busy = false }
                    return@bg
                }
                Progress.update("复制${step.second}", idx, steps.size)
                log("复制${step.second}…")
                Fs.find(src, step.first)?.let {
                    Fs.copyInto(ctx, it, dst) { m -> log(m) }
                }
            }
            if (steps.isNotEmpty()) {
                Progress.done("配置复制完成（${steps.size} 项）")
                log("配置文件复制阶段结束")
            }

            val todo = mods.filter { it.targetUrl.isNotBlank() }
            if (todo.isEmpty()) {
                Progress.done("没有需要下载的模组")
                post { busy = false }
                log("没有需要下载的模组")
                return@bg
            }

            val dir = Fs.ensureDir(dst, McDirs.MODS)
            if (dir == null) {
                Progress.done("目标 mods 目录创建失败")
                post { busy = false }
                log("目标 mods 目录创建失败")
                return@bg
            }

            val parallel = Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3).coerceIn(1, 8)
            val doneCnt = java.util.concurrent.atomic.AtomicInteger(0)
            val okCnt = java.util.concurrent.atomic.AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(parallel)

            Progress.start("下载模组", todo.size)
            log("开始下载 ${todo.size} 个模组（并发 $parallel）")

            val todoItems = todo.map {
                it.targetUrl to (it.targetFileName.ifBlank { Downloader.guessName(it.targetUrl) })
            }
            Resume.begin(ctx, Resume.KIND_MIGRATE, "迁移：下载模组", todoItems)

            val failed = java.util.Collections.synchronizedList(ArrayList<String>())
            for (m in todo) {
                if (Progress.awaitIfPaused()) {
                    log("已中止：还剩 ${todo.size - doneCnt.get()} 个没下载")
                    break
                }
                pool.submit {
                    val name = m.targetFileName.ifBlank { Downloader.guessName(m.targetUrl) }
                    var err = ""
                    val f = try {
                        Downloader.download(ctx, m.targetUrl, dir, name)
                    } catch (t: Throwable) {
                        err = Http.describeError(t)
                        null
                    }
                    val n = doneCnt.incrementAndGet()
                    if (f != null) {
                        okCnt.incrementAndGet()
                        Resume.mark(ctx, m.targetUrl)
                    } else failed.add("${m.name.ifBlank { m.fileName }}：$err")
                    log("${if (f == null) "失败（$err）" else "已安装"}：${m.name} ${m.targetVersion}")
                    Progress.update("下载模组", n, todo.size)
                    post {
                        m.status = if (f == null) "下载失败（$err）" else "已安装"
                        bump()
                    }
                }
            }
            pool.shutdown()
            try {
                pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES)
            } catch (t: Throwable) {
                Err.ignore(t, "等待下载线程池关闭")
            }

            val ok = okCnt.get()
            val total = todo.size
            val bad = ArrayList(failed)
            // 被中止时磁盘上必须留着记录，下次打开才问"要不要接着做"
            if (Progress.isCanceled()) {
                Progress.done("已中止")
                log("已中止：已完成 $ok/$total")
                post { busy = false }
                Notifier.show(ctx, "迁移已中止", "已完成 $ok/$total，下次可继续")
                return@bg
            }
            Resume.finish(ctx)
            Progress.done("迁移完成：模组 $ok/$total")
            log("迁移完成：模组 $ok/$total")
            post {
                busy = false
                if (bad.isNotEmpty()) {
                    dlg = MigDlg.Text(
                        "有 ${bad.size} 个没下成",
                        buildString {
                            append("成功 $ok / $total。失败的：\n\n")
                            bad.take(10).forEach { append("  · $it\n") }
                            if (bad.size > 10) append("  …等 ${bad.size} 个\n")
                            append("\n这些通常是网络问题或源站临时不可用，")
                            append("可以点「重试」只重下失败的，其余不受影响。")
                        },
                        listOf("重试失败的" to { dlg = MigDlg.None; retryDownloads() })
                    )
                }
            }
            Notifier.show(ctx, "迁移完成", "模组 $ok/$total")
            if (p.getBoolean(K.AUTO_LAUNCH, false)) {
                val pkg = p.getString(K.LAUNCHER, "") ?: ""
                if (pkg.isNotBlank()) {
                    val launched = LauncherHelper.launch(ctx, pkg)
                    log(if (launched) "已启动 $pkg" else "启动失败 $pkg")
                }
            }
            } finally {
                MigGuard.release(MigGuard.TAG_MIGRATE)
            }
        }
    }

    fun runMigration() {
        val p = Prefs.get(ctx)
        val srcUri = p.getString(K.SRC_URI, null)
        val dstUri = p.getString(K.DST_URI, null)
        if (srcUri == null) {
            toast(ctx.getString(R.string.no_source))
            return
        }
        if (dstUri == null) {
            toast(ctx.getString(R.string.no_target))
            return
        }
        if (srcUri == dstUri) {
            dlg = MigDlg.Confirm(
                "这是同一个实例",
                "迁移前和迁移后选的是同一个目录：${Fs.tree(ctx, srcUri)?.name ?: srcUri}\n\n" +
                    "把配置迁到自己身上没有任何效果，" +
                    "而且同名文件覆盖时可能把原文件写坏。\n\n" +
                    "请到下面重新选一个「迁移后的版本」。",
                "仍然继续"
            ) {
                dlg = MigDlg.None
                doMigrate(srcUri, dstUri)
            }
            return
        }
        if (mods.isEmpty()) {
            toast("请先扫描生成迁移方案")
            return
        }
        // 迁移前先确认游戏没在跑。判不出来就问用户，不把"没查到"当"没在跑"。
        val rep = MigGuard.check(
            ctx,
            listOf(Fs.tree(ctx, srcUri), Fs.tree(ctx, dstUri)),
            MigGuard.launcherPkgs(ctx)
        )
        when (rep.verdict) {
            MigGuard.Verdict.RUNNING -> {
                dlg = MigDlg.Confirm(
                    "游戏可能还在运行",
                    rep.detail +
                        "\n\n这时候复制存档和模组，拿到的是写到一半的内容，" +
                        "装上去多半是坏包。\n\n" +
                        "请先在游戏里存档并完全退出，再回来继续。",
                    "我确定已经关了，继续"
                ) {
                    dlg = MigDlg.None
                    doMigrate(srcUri, dstUri)
                }
                return
            }

            MigGuard.Verdict.UNKNOWN -> {
                dlg = MigDlg.Confirm(
                    "请确认游戏已关闭",
                    rep.detail +
                        "\n\n自动判断不了，需要你确认一下：" +
                        "游戏正在跑的时候迁移，存档可能复制到被改写一半的内容。",
                    "已经关了，开始迁移"
                ) {
                    dlg = MigDlg.None
                    doMigrate(srcUri, dstUri)
                }
                return
            }

            MigGuard.Verdict.IDLE -> {
                log("未发现游戏在运行（${rep.detail}）")
                doMigrate(srcUri, dstUri)
            }
        }
    }

    // ── 迁移相关工具 ────────────────────────────────────────

    fun runDoctor() {
        val src = Prefs.get(ctx).getString(K.SRC_URI, null)
        if (src.isNullOrBlank()) {
            toast("请先选择「迁移前」的版本目录")
            return
        }
        toast("体检中…")
        bg {
            val rep = ModTools.doctor(ctx, src)
            post { dlg = MigDlg.Text(rep.title, rep.text, listOf("复制结果" to { copyLogs(rep.text) })) }
        }
    }

    fun runDiff() {
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
            post { dlg = MigDlg.Text(rep.title, rep.text, listOf("复制结果" to { copyLogs(rep.text) })) }
        }
    }

    fun doCrossDownload(plans: List<CrossLoader.Plan>) {
        val todo = plans.filter { it.url.isNotBlank() }
        if (todo.isEmpty()) {
            toast("没有可替换的项")
            return
        }
        toast("开始下载 ${todo.size} 个…")
        bg {
            val dir = DirResolve.modsDir(ctx)
            if (dir == null) {
                toast("目标目录不可用")
                return@bg
            }
            var ok = 0
            val failed = ArrayList<String>()
            val targetLoader = plans.firstOrNull()?.toLoader ?: ""
            for (p0 in todo) {
                val name = p0.url.substringAfterLast('/').ifBlank { "${p0.modName}.jar" }
                val f = try {
                    Downloader.download(ctx, p0.url, dir, name)
                } catch (t: Throwable) {
                    null
                }
                if (f != null) ok++ else failed.add(p0.modName)
            }
            post {
                dlg = MigDlg.Text(
                    "替换完成",
                    buildString {
                        append("已下载 $ok / ${todo.size} 个 ${Loaders.label(targetLoader)} 版本。\n\n")
                        append("原来的文件还留在 mods 目录里，")
                        append("请到「模组管理」里删掉旧的那份（会进回收站，可还原）。")
                        if (failed.isNotEmpty()) {
                            append("\n\n失败的：").append(failed.joinToString("、"))
                        }
                    },
                    emptyList()
                )
            }
        }
    }

    fun runCrossLoader(from: String, to: String, mc: String) {
        toast("正在分析…")
        bg {
            val dir = DirResolve.modsDir(ctx)
            if (dir == null) {
                toast("没有可用的 mods 目录")
                return@bg
            }
            val names = dir.listFiles()
                .filter { (it.name ?: "").endsWith(".jar", true) && !ModToggle.isDisabled(it) }
                .map { it.name ?: "" }
            if (names.isEmpty()) {
                toast("mods 目录是空的（或全部被禁用）")
                return@bg
            }
            val plans = CrossLoader.plan(ctx, names, from, to, mc)
            post {
                dlg = MigDlg.Text(
                    "${Loaders.label(from)} → ${Loaders.label(to)}（MC $mc）",
                    CrossLoader.report(plans),
                    listOf("下载可替换的" to { dlg = MigDlg.None; doCrossDownload(plans) })
                )
            }
        }
    }

    fun crossLoader() {
        val mc = version.trim()
        if (mc.isBlank()) {
            toast("请先填写目标 MC 版本")
            return
        }
        val targetLoader = loaders.getOrNull(loaderIdx) ?: "auto"
        val pairs = listOf(
            "forge" to "fabric", "fabric" to "forge",
            "forge" to "neoforge", "fabric" to "quilt"
        )
        val ordered = if (targetLoader == "auto") pairs
        else pairs.sortedByDescending { it.second == targetLoader }
        val labels = ordered.map { "${Loaders.label(it.first)} → ${Loaders.label(it.second)}" }
        dlg = MigDlg.Pick(
            "跨加载器转换（MC $mc）",
            "把 mods 目录里的模组换成另一个加载器的版本。\n\n" +
                "只在同一个 MC 版本内替换；跨 MC 版本不做，" +
                "那种需求用「检查更新」找新版。",
            labels
        ) { w ->
            val (from, to) = ordered[w]
            dlg = MigDlg.None
            runCrossLoader(from, to, mc)
        }
    }

    fun codeMigrate() {
        val pairs = CodeMigrator.supportedPairs()
        dlg = MigDlg.Pick(
            "代码级迁移",
            "按官方改名表改源码里的包名/类名。",
            pairs.map { "${it.first} → ${it.second}" }
        ) { w ->
            val (from, to) = pairs[w]
            dlg = MigDlg.None
            val root = WorkDir.root(ctx)
            val dir = java.io.File(ctx.getExternalFilesDir(null), "code")
            val target = if (root != null && root.uri.scheme == "file") {
                java.io.File(root.uri.path ?: "")
            } else dir
            toast("正在扫描…")
            bg {
                val hits = CodeMigrator.scan(target, from, to)
                post {
                    dlg = MigDlg.Text(
                        "$from → $to",
                        CodeMigrator.report(hits),
                        listOf("全部应用" to {
                            dlg = MigDlg.None
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

    fun openAllTools() {
        try {
            parentFragmentManagerOf(ctx)?.beginTransaction()
                ?.replace(R.id.fragment_container, ToolsFragment())
                ?.addToBackStack("tools")
                ?.commit()
        } catch (t: Throwable) {
            Err.fail(t, "打开工具箱")
            Tips.short(ctx, "打不开工具箱，请从「更多」进入")
        }
    }

    fun openMigrationTools() {
        val items: List<Pair<String, () -> Unit>> = listOf(
            "模组体检" to { dlg = MigDlg.None; runDoctor() },
            "配置对比" to { dlg = MigDlg.None; runDiff() },
            "跨加载器转换" to { dlg = MigDlg.None; crossLoader() },
            "代码级迁移" to { dlg = MigDlg.None; codeMigrate() },
            "打开完整工具箱" to { dlg = MigDlg.None; openAllTools() }
        )
        dlg = MigDlg.Pick("迁移相关工具", "", items.map { it.first }) { w ->
            dlg = MigDlg.None
            items[w].second.invoke()
        }
    }

    // ── 回调注册 ────────────────────────────────────────────

    val lastFlush = remember { longArrayOf(0L) }
    val progressHook: (Progress.State) -> Unit = { st ->
        val now = android.os.SystemClock.uptimeMillis()
        val isFinal = !st.running || st.percent >= 100
        // 扫描上千个文件会连续触发上千次更新，这里按 120ms 节流；
        // 最后一次必定刷新，不会丢最终状态
        if (isFinal || now - lastFlush[0] >= 120) {
            lastFlush[0] = now
            post { progress = st }
        }
    }
    val logHook: (List<LogCenter.LogLine>) -> Unit = {
        post { logSummary = logSummaryOf() }
    }
    val mutedNow by rememberUpdatedState(errMuted)
    val errorHook: (LogCenter.LogLine) -> Unit = { line ->
        if (!mutedNow) post { showErr(line) }
    }

    DisposableEffect(Unit) {
        Progress.addHook(progressHook)
        LogCenter.addListener(logHook)
        LogCenter.addErrorHook(errorHook)
        onResult { code, uri -> handleResult(code, uri) }
        onDispose {
            Progress.removeHook(progressHook)
            LogCenter.removeListener(logHook)
            LogCenter.removeErrorHook(errorHook)
            onResult { _, _ -> }
        }
    }

    ResumeEffect(register) {
        refreshPaths()
        logSummary = logSummaryOf()
    }

    // ── 界面 ────────────────────────────────────────────────

    val resultH = remember {
        val px = ctx.resources.getDimensionPixelSize(R.dimen.mig_result_h)
        (px / ctx.resources.displayMetrics.density).dp
    }
    val running = progress.running || busy

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text(
            logSummary,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAllLogs() }
                .padding(bottom = 8.dp)
        )

        if (running) {
            if (progress.text.isNotBlank()) {
                Text(progress.text, fontSize = 12.sp)
            }
            if (progress.detail.isNotBlank()) {
                Text(
                    progress.detail,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = progress.percent / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = { togglePause() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (progress.paused) ctx.getString(R.string.btn_resume)
                        else ctx.getString(R.string.btn_pause)
                    )
                }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { cancelTask() },
                    modifier = Modifier.weight(1f)
                ) { Text("中止") }
            }
        }

        if (serverMode) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ctx.getString(R.string.server_card_title),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "?",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { modeTip = !modeTip }.padding(8.dp)
                        )
                    }
                    Text(
                        serverText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    OutlinedButton(
                        onClick = openServer,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text(ctx.getString(R.string.btn_open_server)) }
                    if (modeTip) {
                        Text(
                            ctx.getString(R.string.mode_tip),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ctx.getString(R.string.sec_source),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            ctx.getString(R.string.more),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { srcMore = !srcMore }.padding(8.dp)
                        )
                    }
                    Text(
                        srcText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        dirState,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    OutlinedButton(
                        onClick = { pickDir(11, DirGuide.Purpose.SOURCE) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text(ctx.getString(R.string.pick_source)) }

                    if (srcMore) {
                        OutlinedButton(
                            onClick = { scanLocal() },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text(ctx.getString(R.string.btn_scan_local)) }
                        OutlinedButton(
                            onClick = { launch(Intent(ctx, AppPickerActivity::class.java), 31) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text(ctx.getString(R.string.cfg_pick_launcher)) }
                        Text(
                            scanHint,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(ctx.getString(R.string.sec_target), fontWeight = FontWeight.Bold)
                McField(version, { setVersion(it) }, ctx.getString(R.string.hint_mc_version))
                Spacer(Modifier.height(8.dp))
                McSpinner(
                    options = loaders.map { Loaders.label(it) },
                    selected = loaderIdx,
                    onPick = { i ->
                        loaderIdx = i
                        Prefs.get(ctx).edit()
                            .putString(K.DEF_LOADER, loaders.getOrNull(i) ?: "auto").apply()
                    },
                    label = ctx.getString(R.string.loader)
                )
                Text(
                    dstText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                OutlinedButton(
                    onClick = { pickDir(12, DirGuide.Purpose.TARGET) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text(ctx.getString(R.string.pick_target)) }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { partsOpen = !partsOpen }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "要迁移的内容（已选 ${parts.size} 项）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (partsOpen) "收起" else "展开",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (partsOpen) {
                    for (pt in McParts.ALL) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { togglePart(ctx, parts, pt) { parts = it } }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = parts.contains(pt.id),
                                onCheckedChange = { togglePart(ctx, parts, pt) { parts = it } }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(pt.label, fontSize = 14.sp)
                                if (pt.hint.isNotBlank()) {
                                    Text(
                                        pt.hint,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scan() }, modifier = Modifier.weight(1f)) {
                Text(ctx.getString(R.string.btn_scan))
            }
            Button(onClick = { runMigration() }, modifier = Modifier.weight(1f)) {
                Text(ctx.getString(R.string.btn_run))
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable { moreOps = !moreOps }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                ctx.getString(R.string.mig_tools),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (moreOps) "收起" else "展开",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (moreOps) {
            OutlinedButton(
                onClick = { downloadAll() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) { Text(ctx.getString(R.string.btn_download_all)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runDoctor() }, modifier = Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.tool_doctor))
                }
                OutlinedButton(onClick = { runDiff() }, modifier = Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.tool_diff))
                }
            }
            OutlinedButton(
                onClick = { openMigrationTools() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(ctx.getString(R.string.mig_tools_entry)) }
        }

        Text(
            ctx.getString(R.string.mig_result_title),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        )
        if (mods.isEmpty()) {
            Text(
                "还没有扫描结果。填好目标版本、选好「迁移前」的目录后点「扫描」。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else {
            key(rev) {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(resultH)
                ) {
                    items(mods.toList()) { m ->
                        MigModRow(
                            m = m,
                            onDownload = { downloadOne(m) },
                            onOpen = { openModDetail(m) }
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable { logOpen = !logOpen }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                ctx.getString(R.string.log_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (logOpen) "收起" else "展开",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (logOpen && logText.isNotBlank()) {
            McMono(logText)
        }
        Spacer(Modifier.height(24.dp))
    }

    // ── 弹窗统一渲染 ────────────────────────────────────────

    when (val d = dlg) {
        is MigDlg.Text -> ScrollTextDialog(d.title, d.body, d.actions) { dlg = MigDlg.None }
        is MigDlg.Confirm -> McConfirm(d.title, d.body, d.ok, d.act) { dlg = MigDlg.None }
        is MigDlg.Pick -> PickDialog(d.title, d.body, d.options, d.act) { dlg = MigDlg.None }
        is MigDlg.Logs -> ScrollTextDialog(
            d.title, d.shown,
            listOf(
                "复制" to { copyLogs(d.full) },
                "清空" to { LogCenter.clear(); logSummary = logSummaryOf() }
            )
        ) { dlg = MigDlg.None }
        MigDlg.None -> {}
    }

    if (errShow) {
        AlertDialog(
            onDismissRequest = { errShow = false; errBuf = ""; errCount = 0 },
            title = { Text("操作失败") },
            text = {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 300.dp)
                ) { McMono(errText) }
            },
            confirmButton = {
                TextButton(onClick = { errShow = false; errBuf = ""; errCount = 0 }) {
                    Text(ctx.getString(R.string.ok))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { errShow = false; showAllLogs() }) {
                        Text("查看全部日志")
                    }
                    TextButton(onClick = {
                        errMuted = true
                        errShow = false
                        toast("后续错误只记录到日志，不再弹窗")
                    }) { Text("本次不再提示") }
                }
            }
        )
    }
}

private fun togglePart(
    ctx: Context, current: Set<String>, pt: McParts.Part, apply: (Set<String>) -> Unit
) {
    val next = if (current.contains(pt.id)) current - pt.id else current + pt.id
    apply(next)
    Prefs.get(ctx).edit()
        .putString(
            McParts.PREF_MIGRATE,
            McParts.toPref(McParts.ALL.filter { next.contains(it.id) })
        )
        .apply()
}

private fun parentFragmentManagerOf(ctx: Context): androidx.fragment.app.FragmentManager? =
    (ctx as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager

@Composable
private fun MigModRow(m: ModEntry, onDownload: () -> Unit, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ModIcon(m.uri)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    m.name.ifBlank { m.fileName },
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    fontSize = 14.sp
                )
                Text(
                    "当前 ${m.currentVersion.ifBlank { "未知" }} → 目标 ${m.targetVersion.ifBlank { "无" }}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    m.status,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2
                )
            }
            TextButton(onClick = onDownload, enabled = m.targetUrl.isNotBlank()) {
                Text(if (m.targetUrl.isBlank()) "跳过" else "下载", fontSize = 12.sp)
            }
        }
    }
}

/** 图标优先从本地 jar 提取（离线可用、不耗流量），取不到用方块占位 */
@Composable
private fun ModIcon(uri: String) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        if (uri.isBlank()) return@LaunchedEffect
        Bg.io.execute {
            val b = runCatching { ModIcons.ofUri(ctx, uri) }.getOrNull()
            if (b != null) Bg.ui.post { bmp = b }
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape)
        )
    } else {
        Image(
            painter = painterResource(R.drawable.ti_puzzle),
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )
    }
}

/** 长文本弹窗：正文可滚动，另挂若干操作 */
@Composable
private fun ScrollTextDialog(
    title: String,
    body: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 340.dp)
            ) { McMono(body) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            Row {
                actions.forEach { (label, act) ->
                    TextButton(onClick = act) { Text(label) }
                }
            }
        }
    )
}

@Composable
private fun PickDialog(
    title: String,
    body: String,
    options: List<String>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (body.isNotBlank()) {
                    Text(
                        body,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                options.forEachIndexed { i, o ->
                    TextButton(onClick = { onPick(i) }, modifier = Modifier.fillMaxWidth()) {
                        Text(o, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
