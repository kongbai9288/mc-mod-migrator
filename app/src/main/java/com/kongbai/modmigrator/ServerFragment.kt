package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 服务器页：连上面板或 FTP/SFTP，列出服务器里的 jar，逐个查更新。
 *
 * 页面分三块：连接（没连上时展开、连上后自动收起）、匹配条件、
 * 文件列表。连上之前下面两步做不了，所以连接区默认展开。
 */
class ServerFragment : Fragment() {

    companion object {
        private const val NOT_CONNECTED = "未选择服务器"
        private const val REQ_OUT_DIR = 91
    }

    // ── 连接参数 ──────────────────────────────────────────────
    /** 0 = 面板 API，1/2/3 = FTP / FTPS / SFTP */
    private var connKind by mutableStateOf(0)
    private var base by mutableStateOf("")
    private var key by mutableStateOf("")
    private var dir by mutableStateOf("/mods")
    private var host by mutableStateOf("")
    private var port by mutableStateOf("")
    private var user by mutableStateOf("")
    private var pass by mutableStateOf("")

    // ── 匹配条件 ──────────────────────────────────────────────
    private var version by mutableStateOf("")
    private var loaderIdx by mutableStateOf(0)
    private var sideIdx by mutableStateOf(0)
    private var srcModrinth by mutableStateOf(true)
    private var srcCf by mutableStateOf(true)
    private var srcBackend by mutableStateOf(true)

    // ── 运行状态 ──────────────────────────────────────────────
    private var connExpanded by mutableStateOf(true)
    private var serverLabel by mutableStateOf(NOT_CONNECTED)
    private val files = mutableStateListOf<PanelFile>()
    private var filesRev by mutableStateOf(0)
    private var logText by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var outLabel by mutableStateOf("")
    private var logOpen by mutableStateOf(false)

    private var chosen: PanelServer? = null
    private val servers = mutableListOf<PanelServer>()
    /** FTP/SFTP 模式下"已选中的目录"，充当面板模式里的服务器 */
    private var remoteDir: String? = null

    private val exec = Bg.io
    private val handler: Handler = Bg.ui

    private var dlg by mutableStateOf<Dlg?>(null)
    private var manualQuery by mutableStateOf("")

    private sealed class Dlg {
        class Info(val title: String, val msg: String) : Dlg()
        class KeyHint(val hint: String) : Dlg()
        /** 可滚动的列表选择：目录浏览、服务器挑选都用它 */
        class Pick(
            val title: String, val sub: String,
            val items: List<Pair<String, () -> Unit>>,
            val confirm: Pair<String, () -> Unit>? = null
        ) : Dlg()
        class Manual(val f: PanelFile) : Dlg()
        class Candidates(val f: PanelFile, val list: List<MarketMod>) : Dlg()
        class OutDirPick : Dlg()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        loadPrefs(ctx)
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { ServerScreen() } }
        }
    }

    private fun loadPrefs(ctx: android.content.Context) {
        val p = Prefs.get(ctx)
        connKind = p.getInt(K.REMOTE_KIND, 0).coerceIn(0, 3)
        host = p.getString(K.REMOTE_HOST, "") ?: ""
        port = p.getString(K.REMOTE_PORT, "") ?: ""
        user = p.getString(K.REMOTE_USER, "") ?: ""
        pass = p.getString(K.REMOTE_PASS, "") ?: ""
        base = p.getString(K.PANEL_BASE, "") ?: ""
        key = p.getString(K.PANEL_KEY, "") ?: ""
        dir = p.getString(K.PANEL_DIR, "/mods") ?: "/mods"
        version = p.getString(K.DEF_VERSION, "") ?: ""
        val saved = ServerUpdate.sources(ctx)
        srcModrinth = saved.contains(ServerUpdate.MODRINTH)
        srcCf = saved.contains(ServerUpdate.CURSEFORGE)
        srcBackend = saved.contains(ServerUpdate.BACKEND)
        connExpanded = true
        refreshOutDirLabel()
    }

    // ── 界面 ─────────────────────────────────────────────────

    @Composable
    private fun ServerScreen() {
        // 读一次计数：后台改完文件属性后靠它触发重组
        val rev = filesRev
        val ctx = requireContext()
        val loaders = Loaders.visible(
            Prefs.get(ctx).getBoolean(K.SHOW_EXTRA_LOADERS, false)
        )
        val loaderNames = loaders.map { Loaders.label(it) }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            // ── 连接区 ────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (connExpanded) "连接" else serverLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { connExpanded = !connExpanded }) {
                            Text(if (connExpanded) "收起" else "展开")
                        }
                    }
                    if (connExpanded) {
                        McChipRow(
                            listOf("面板 API", "FTP", "FTPS", "SFTP"), connKind
                        ) { connKind = it; saveRemote() }
                        McNote(
                            if (connKind == 0) {
                                "用面板的 Client API Key 连接（不是登录密码）。没有面板 Key 就改选上面的 FTP/SFTP。"
                            } else {
                                "用服务器的 FTP / SSH 账号密码直连，可以浏览并读取 mods、plugins 目录。"
                            }
                        )
                        if (connKind == 0) {
                            McField(base, { base = it }, "面板地址，如 https://panel.example.com")
                            McField(key, { key = it }, "Client API Key")
                        } else {
                            McField(host, { host = it }, "主机地址")
                            McField(port, { port = it }, "端口（FTP 21 / SFTP 22）")
                            McField(user, { user = it }, "用户名")
                            McField(pass, { pass = it }, "密码")
                        }
                        McField(
                            dir, { dir = it },
                            if (connKind == 0) "服务器内目录，如 /mods 或 /plugins" else "远程目录，如 /mods"
                        )
                    }
                }
            }

            // ── 匹配条件 ──────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("匹配条件", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    McField(version, { version = it }, "服务器 MC 版本，如 1.20.1")
                    McSpinner(loaderNames, loaderIdx, { loaderIdx = it }, "加载器")
                    McChipRow(listOf("服务端", "客户端"), sideIdx) { sideIdx = it }
                    McNote(
                        if (sideIdx == 0) {
                            "按服务端匹配：只取标了服务端可用的文件。"
                        } else {
                            "按客户端匹配：只取客户端可用的文件。"
                        }
                    )
                    Text("更新来源", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    McSwitchRow(srcModrinth, {
                        srcModrinth = it
                        saveSources()
                    }, "Modrinth")
                    McSwitchRow(srcCf, { srcCf = it; saveSources() }, "CurseForge", "插件是独立分类，和模组不在一个池子里")
                    McSwitchRow(srcBackend, { srcBackend = it; saveSources() }, "后端搜索")
                }
            }

            // ── 操作 ──────────────────────────────────────
            McButtonRow(
                if (connKind == 0) "连接面板" else "连接", { connect() },
                "找模组目录", { probeDirs() }
            )
            McButtonRow(
                "浏览目录", { browseDir(dir.trim().ifBlank { "/" }) },
                "扫描文件", { scanFiles() }
            )
            McFilledButton("检测更新", enabled = !busy && files.isNotEmpty()) { checkUpdates() }
            McButtonRow(
                "下载全部可更新", { downloadAll() },
                "清空列表", { files.clear(); bump() }
            )
            McPickRow("更新存放位置", outLabel.ifBlank { "默认（迁移后目录）" }) { pickOutDir() }
            McNote("下载只落到本机目录，不会上传回服务器。")

            if (busy) Text("正在处理…", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)

            // ── 文件列表 ──────────────────────────────────
            if (files.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有文件", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "连上服务器后点「扫描文件」，列出的 jar 会显示在这里，再点「检测更新」逐个查版本。",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            } else {
                McSectionTitle("服务器文件 ${files.size} 个")
                key(rev) {
                    for (f in files.toList()) {
                        FileCard(f)
                    }
                }
            }

            // ── 日志 ──────────────────────────────────────
            McExpand("日志", logOpen, { logOpen = !logOpen }) {
                McMono(logText.ifBlank { "（暂无）" })
            }
        }

        when (val d = dlg) {
            null -> Unit
            is Dlg.Info -> McLongTextDialog(
                title = d.title, body = d.msg, onDismiss = { dlg = null }
            )
            is Dlg.KeyHint -> McConfirm(
                title = "API Key 可能不对",
                body = d.hint,
                confirmText = "仍然连接",
                onConfirm = { dlg = null; doConnect(cred()) },
                onDismiss = { dlg = null }
            )
            is Dlg.Pick -> ScrollPickDialog(d)
            is Dlg.Manual -> ManualDialog(d.f)
            is Dlg.Candidates -> CandidatesDialog(d.f, d.list)
            is Dlg.OutDirPick -> OutDirDialog()
        }
    }

    @Composable
    private fun FileCard(f: PanelFile) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        f.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { downloadOne(f) }) {
                        Text(if (f.latestUrl.isBlank()) "无更新" else "下载")
                    }
                }
                Text(
                    "${f.kind} · ${f.size / 1024}KB · ${f.path}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    f.status,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    /** 目录浏览、服务器挑选：条目数量不定，正文要能滚 */
    @Composable
    private fun ScrollPickDialog(d: Dlg.Pick) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text(d.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (d.sub.isNotBlank()) {
                        Text(d.sub, fontSize = 12.sp)
                    }
                    d.items.forEach { (label, act) ->
                        TextButton(onClick = act) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                val cf = d.confirm
                if (cf != null) {
                    TextButton(onClick = cf.second) { Text(cf.first) }
                }
            },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("取消") } }
        )
    }

    @Composable
    private fun ManualDialog(f: PanelFile) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text("手动搜索：${f.name}") },
            text = {
                Column {
                    McNote("自动匹配认不出来时，自己填关键词搜。")
                    OutlinedTextField(
                        value = manualQuery,
                        onValueChange = { manualQuery = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("模组名", fontSize = 13.sp) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val q = manualQuery.trim()
                    if (q.isBlank()) return@TextButton
                    dlg = null
                    manualSearch(f, q)
                }) { Text("搜索") }
            },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("取消") } }
        )
    }

    @Composable
    private fun CandidatesDialog(f: PanelFile, list: List<MarketMod>) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text("选一个：${f.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEach { mod ->
                        TextButton(onClick = {
                            val mc = version.trim()
                            val loader = currentLoader()
                            dlg = null
                            bg {
                                val h = ServerUpdate.resolve(
                                    requireContext(), mod, mc, loader, currentSide()
                                )
                                safePost(handler) {
                                    if (h == null) {
                                        toast("这个项目没有可用于 MC $mc 的文件")
                                    } else {
                                        applyHit(f, h, currentSide())
                                        bump()
                                        toast("已指定：${h.version}")
                                    }
                                }
                            }
                        }) {
                            Text(
                                "${mod.name}（${ServerUpdate.label(mod.source)}）",
                                fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = null }) { Text("关闭") } }
        )
    }

    @Composable
    private fun OutDirDialog() {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text("更新下来的文件放哪") },
            text = {
                Column {
                    McNote("服务器模组的更新只会下载到本机指定目录，不会上传回服务器。")
                    TextButton(onClick = {
                        Prefs.get(requireContext()).edit()
                            .putString(K.PANEL_OUT_DIR, "").apply()
                        dlg = null
                        refreshOutDirLabel()
                    }) { Text("用默认目录（迁移后 / 工作目录）", fontSize = 13.sp) }
                    TextButton(onClick = {
                        dlg = null
                        val i = android.content.Intent(
                            android.content.Intent.ACTION_OPEN_DOCUMENT_TREE
                        )
                        startActivityForResult(i, REQ_OUT_DIR)
                    }) { Text("自己选一个目录…", fontSize = 13.sp) }
                    TextButton(onClick = {
                        Prefs.get(requireContext()).edit()
                            .putString(K.PANEL_OUT_DIR, "").apply()
                        dlg = null
                        refreshOutDirLabel()
                        toast("已回到默认目录")
                    }) { Text("清除选择，回到默认", fontSize = 13.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = null }) { Text("关闭") } }
        )
    }

    // ── 输出目录（不上传，只落地到本地） ──────────────────────

    private fun outDir(): androidx.documentfile.provider.DocumentFile? {
        val ctx = context ?: return null
        val saved = Prefs.get(ctx).getString(K.PANEL_OUT_DIR, "") ?: ""
        return if (saved.isNotBlank()) {
            runCatching { Fs.tree(ctx, saved) }.getOrNull()
        } else {
            WorkDir.modsDir(ctx) ?: outDir()
        }
    }

    private fun refreshOutDirLabel() {
        val ctx = context ?: return
        val saved = Prefs.get(ctx).getString(K.PANEL_OUT_DIR, "") ?: ""
        outLabel = if (saved.isBlank()) {
            "默认（迁移后目录）"
        } else {
            val d = runCatching { Fs.tree(ctx, saved) }.getOrNull()
            d?.name ?: "（已失效，点此重选）"
        }
    }

    private fun pickOutDir() {
        dlg = Dlg.OutDirPick()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OUT_DIR || resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = context ?: return
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (t: Throwable) {
            Err.ignore(t, "保存输出目录授权")
        }
        Prefs.get(ctx).edit().putString(K.PANEL_OUT_DIR, uri.toString()).apply()
        refreshOutDirLabel()
        toast("已设置存放位置")
    }

    // ── 目录浏览 ─────────────────────────────────────────────

    private fun browseDir(path: String) {
        val srv = chosen
        if (srv == null) {
            toast("请先连接并选择服务器")
            return
        }
        val c = cred()
        toast("正在读取 $path …")
        bg {
            val token = try {
                ServerPanelApi.tokenOf(c)
            } catch (t: Throwable) {
                log("认证失败：${Err.humanMessage(t)}")
                safePost(handler) { toast("认证失败：${Err.humanMessage(t)}") }
                return@bg
            }
            if (token == null) {
                safePost(handler) { showLoginUnsupported() }
                return@bg
            }
            val entries = try {
                ServerPanelApi.listRaw(c.base, token, srv.uuid.ifBlank { srv.id }, path)
            } catch (t: Throwable) {
                log("读取失败：${t.message}")
                emptyList<Pair<String, Boolean>>()
            }
            if (entries.isEmpty()) {
                safePost(handler) {
                    toast("这个目录是空的，或读不出来（面板可能限制了访问）")
                }
                return@bg
            }

            // 目录在前，文件在后；jar 单独标出来
            val dirs = entries.filter { it.second }.map { it.first }.sorted()
            val jars = entries.filter { !it.second && it.first.endsWith(".jar", true) }
                .map { it.first }.sorted()
            val others = entries.filter { !it.second && !it.first.endsWith(".jar", true) }

            safePost(handler) {
                val items = ArrayList<Pair<String, () -> Unit>>()
                if (path != "/" && path.isNotBlank()) {
                    val parent = path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }
                    items.add("↑ 上一级" to { browseDir(parent) })
                }
                for (d in dirs) {
                    items.add("📁 $d" to { browseDir(path.trimEnd('/') + "/" + d) })
                }
                for (j in jars) {
                    items.add("📦 $j" to { })
                }
                val sub = buildString {
                    append("当前：").append(path).append('\n')
                    append("子目录 ").append(dirs.size).append(" 个 · jar ").append(jars.size)
                        .append(" 个")
                    if (others.isNotEmpty()) append(" · 其它 ").append(others.size).append(" 个")
                }
                dlg = Dlg.Pick(
                    title = "浏览服务器目录",
                    sub = sub,
                    items = items,
                    confirm = "就用这个目录" to {
                        dir = path
                        Prefs.get(requireContext()).edit()
                            .putString(K.PANEL_DIR, path).apply()
                        dlg = null
                        if (jars.isNotEmpty()) {
                            scanFiles()
                        } else {
                            toast("这个目录没有 jar，可继续进入子目录找找")
                        }
                    }
                )
            }
        }
    }

    // ── 连接 ─────────────────────────────────────────────────

    private fun isRemote(): Boolean = connKind > 0

    private fun remoteConf(): RemoteFs.Conf {
        val kind = when (connKind) {
            1 -> RemoteFs.Kind.FTP
            2 -> RemoteFs.Kind.FTPS
            3 -> RemoteFs.Kind.SFTP
            else -> RemoteFs.Kind.FTP
        }
        return RemoteFs.Conf(
            kind = kind,
            host = host.trim(),
            port = port.trim().toIntOrNull() ?: 0,
            user = user.trim(),
            pass = pass.trim()
        )
    }

    private fun saveRemote() {
        val ctx = context ?: return
        Prefs.get(ctx).edit()
            .putInt(K.REMOTE_KIND, connKind)
            .putString(K.REMOTE_HOST, host.trim())
            .putString(K.REMOTE_PORT, port.trim())
            .putString(K.REMOTE_USER, user.trim())
            .putString(K.REMOTE_PASS, pass.trim())
            .apply()
    }

    private fun cred(): ServerPanelApi.Cred {
        val c = ServerPanelApi.Cred(
            base = base.trim(),
            mode = ServerPanelApi.Mode.KEY,
            key = key.trim()
        )
        val ctx = context ?: return c
        Prefs.get(ctx).edit()
            .putString(K.PANEL_BASE, c.base)
            .putString(K.PANEL_KEY, c.key)
            .apply()
        return c
    }

    private fun connect() {
        if (isRemote()) {
            connectRemote()
            return
        }
        val c = cred()
        if (c.base.isBlank()) { toast("请填写面板地址"); return }
        if (c.key.isBlank()) { toast("请填写 Client API Key"); return }
        val hint = ServerPanelApi.keyHint(c.key)
        if (hint != null) {
            log("Key 检查：$hint")
            dlg = Dlg.KeyHint(hint)
            return
        }
        doConnect(c)
    }

    private fun connectRemote() {
        val c = remoteConf()
        if (c.host.isBlank()) { toast("请填写主机地址"); return }
        if (c.user.isBlank()) { toast("请填写用户名"); return }
        saveRemote()
        toast("正在连接 ${c.kind.label} ${c.host} …")
        bg {
            val err = RemoteFs.test(c)
            if (err != null) {
                log("连接失败：$err")
                safePost(handler) { toast(err) }
                return@bg
            }
            log("已连上，正在找模组目录…")
            // 并发试候选目录，找出真正有 jar 的那些
            val found = probeRemoteDirs(c)
            safePost(handler) {
                servers.clear()
                if (found.isEmpty()) {
                    serverLabel = "已连接，但没找到有 jar 的目录"
                    connExpanded = false
                    toast("没找到 mods/plugins 目录，可以在下面手动填路径再扫描")
                    return@safePost
                }
                // 有 jar 的目录当成"服务器"列出来让用户选，
                // 只有一个就直接选中，不让他多点一次
                servers.addAll(found.map { d -> PanelServer(id = "remote:$d", uuid = d, name = d) })
                if (found.size == 1) pickServer(servers[0]) else pickServerDialog()
            }
        }
    }

    private fun doConnect(c: ServerPanelApi.Cred) {
        toast("正在连接面板…")
        bg {
            val token = try {
                ServerPanelApi.tokenOf(c)
            } catch (t: Throwable) {
                log("认证失败：${Err.humanMessage(t)}")
                safePost(handler) { toast("认证失败：${Err.humanMessage(t)}") }
                return@bg
            }
            if (token == null) {
                safePost(handler) { showLoginUnsupported() }
                return@bg
            }
            val list = try {
                ServerPanelApi.servers(c.base, token)
            } catch (t: Throwable) {
                log("连接失败：${t.message}")
                emptyList<PanelServer>()
            }
            safePost(handler) {
                servers.clear()
                servers.addAll(list)
                if (list.isEmpty()) {
                    toast("没读到服务器，请检查地址与 Key（需 Client API Key）")
                    return@safePost
                }
                val saved = Prefs.get(requireContext()).getString(K.PANEL_SERVER_ID, "") ?: ""
                val remembered = list.firstOrNull { it.id == saved }
                val auto = remembered ?: if (list.size == 1) list[0] else null
                if (auto != null) {
                    pickServer(auto)
                    toast("已选择 ${auto.name}")
                    return@safePost
                }
                dlg = Dlg.Pick(
                    title = "选择服务器",
                    sub = "找到 ${list.size} 台",
                    items = list.map { s ->
                        "${s.name}（${s.id}）" to {
                            dlg = null
                            pickServer(s)
                            log("已选择 ${s.name}")
                        }
                    }
                )
                toast("找到 ${list.size} 台服务器")
            }
        }
    }

    private fun showLoginUnsupported() {
        dlg = Dlg.Info("请用 API Key 连接", ServerPanelApi.LOGIN_UNSUPPORTED_MSG)
    }

    private fun pickServerDialog() {
        if (servers.isEmpty()) return
        dlg = Dlg.Pick(
            title = "选择目录",
            sub = "找到 ${servers.size} 个含 jar 的目录",
            items = servers.map { s -> s.name to { dlg = null; pickServer(s) } }
        )
    }

    /** 选中一台服务器并立刻去找目录 */
    private fun pickServer(s: PanelServer) {
        chosen = s
        if (s.id.startsWith("remote:")) {
            remoteDir = s.uuid
            dir = s.uuid
        }
        serverLabel = "服务器：${s.name} · ${s.id}"
        connExpanded = false
        Prefs.get(requireContext()).edit().putString(K.PANEL_SERVER_ID, s.id).apply()
        // 选完立刻找目录，省得用户自己去猜路径
        probeDirs()
    }

    private fun probeDirs() {
        val srv = chosen
        if (srv == null) {
            toast("请先连接并选一台服务器")
            return
        }
        // FTP/SFTP：直接列候选目录，不用面板那套 API
        if (srv.id.startsWith("remote:")) {
            val c = remoteConf()
            toast("正在找模组目录…")
            bg {
                val dirs = probeRemoteDirs(c)
                safePost(handler) {
                    if (dirs.isEmpty()) {
                        log("没自动找到含 jar 的目录，可手动填路径（常见：/mods、/plugins）")
                        toast("没找到，请手动填目录")
                    } else {
                        log("找到目录：${dirs.joinToString("、")}")
                        pickDirDialog(dirs)
                    }
                }
            }
            return
        }
        val c = cred()
        toast("正在找模组目录…")
        bg {
            val token = try {
                ServerPanelApi.tokenOf(c)
            } catch (t: Throwable) {
                log("认证失败：${Err.humanMessage(t)}")
                safePost(handler) { toast("认证失败：${Err.humanMessage(t)}") }
                return@bg
            }
            if (token == null) {
                safePost(handler) { showLoginUnsupported() }
                return@bg
            }
            val dirs = ServerPanelApi.probeDirs(c.base, token, srv.uuid.ifBlank { srv.id })
            safePost(handler) {
                if (dirs.isEmpty()) {
                    log("没自动找到含 jar 的目录，可手动填路径（常见：/mods、/plugins）")
                    toast("没找到，请手动填目录")
                } else {
                    log("找到目录：${dirs.joinToString("、")}")
                    pickDirDialog(dirs)
                }
            }
        }
    }

    private fun pickDirDialog(dirs: List<String>) {
        dlg = Dlg.Pick(
            title = "选择目录",
            sub = "找到 ${dirs.size} 个含 jar 的目录",
            items = dirs.map { d ->
                d to {
                    dir = d
                    remoteDir = d
                    dlg = null
                    scanFiles()
                }
            }
        )
    }

    private fun probeRemoteDirs(c: RemoteFs.Conf): List<String> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            RemoteFs.DIR_CANDIDATES.size.coerceAtMost(6)
        )
        return try {
            val slots = arrayOfNulls<String>(RemoteFs.DIR_CANDIDATES.size)
            val tasks = RemoteFs.DIR_CANDIDATES.mapIndexed { i, d ->
                pool.submit {
                    try {
                        if (RemoteFs.jars(c, d).isNotEmpty()) slots[i] = d
                    } catch (_: Throwable) {}
                }
            }
            for (t in tasks) runCatching { t.get() }
            slots.filterNotNull()
        } finally {
            pool.shutdownNow()
        }
    }

    // ── 扫描 ─────────────────────────────────────────────────

    private fun scanFiles() {
        val s = chosen
        if (s == null) {
            toast("请先连接并选择目录")
            return
        }
        if (s.id.startsWith("remote:")) {
            scanRemote()
            return
        }
        val c = cred()
        val base = c.base
        val d = dir.trim().ifBlank { "/mods" }
        toast("正在列出 $d …")
        bg {
            val token = try {
                ServerPanelApi.tokenOf(c)
            } catch (t: Throwable) {
                log("认证失败：${Err.humanMessage(t)}")
                safePost(handler) { toast("认证失败：${Err.humanMessage(t)}") }
                return@bg
            }
            if (token == null) {
                safePost(handler) { showLoginUnsupported() }
                return@bg
            }
            val list = try {
                ServerPanelApi.listFiles(base, token, s.uuid.ifBlank { s.id }, d)
            } catch (t: Throwable) {
                log("读取失败：${t.message}")
                emptyList<PanelFile>()
            }
            for (f in list) f.kind = ServerPanelApi.guessKind(f.path)
            safePost(handler) {
                files.clear()
                files.addAll(list)
                bump()
                toast("${list.size} 个 jar")
            }
            log("列出 ${list.size} 个文件（$d）")
        }
    }

    private fun scanRemote() {
        val d = remoteDir ?: dir.trim().ifBlank { "/mods" }
        val c = remoteConf()
        toast("正在列出 $d …")
        bg {
            val list = try {
                RemoteFs.jars(c, d).map {
                    PanelFile(
                        name = it.name,
                        path = it.path,
                        size = it.size,
                        kind = ServerPanelApi.guessKind(it.path)
                    )
                }
            } catch (t: Throwable) {
                log("读取失败：${t.message}")
                emptyList<PanelFile>()
            }
            safePost(handler) {
                files.clear()
                files.addAll(list)
                bump()
                toast("${list.size} 个 jar")
            }
        }
    }

    // ── 检查更新 ─────────────────────────────────────────────

    private fun currentLoader(): String {
        val ctx = context ?: return "auto"
        val list = Loaders.visible(
            Prefs.get(ctx).getBoolean(K.SHOW_EXTRA_LOADERS, false)
        )
        return list.getOrNull(loaderIdx) ?: "auto"
    }

    private fun currentSide(): Environ.Side =
        if (sideIdx == 0) Environ.Side.SERVER else Environ.Side.CLIENT

    private fun sideLabel(): String =
        if (currentSide() == Environ.Side.SERVER) "服务端" else "客户端"

    private fun selectedSources(): Set<String> {
        val s = LinkedHashSet<String>()
        if (srcModrinth) s.add(ServerUpdate.MODRINTH)
        if (srcCf) s.add(ServerUpdate.CURSEFORGE)
        if (srcBackend) s.add(ServerUpdate.BACKEND)
        return s
    }

    private fun saveSources() {
        val ctx = context ?: return
        ServerUpdate.saveSources(ctx, selectedSources())
    }

    private fun checkUpdates() {
        if (files.isEmpty()) {
            toast("请先扫描文件")
            return
        }
        val mc = version.trim()
        val loader = currentLoader()
        val ctx = requireContext()
        if (mc.isBlank()) {
            toast("请填写服务器 MC 版本，用于匹配更新")
            return
        }
        val srcs = selectedSources()
        if (srcs.isEmpty()) {
            toast("至少选一个平台")
            return
        }
        val names = srcs.joinToString("、") { ServerUpdate.label(it) }
        log("开始检测更新（MC $mc / $loader / ${sideLabel()} / $names）")
        busy = true
        bg {
            val side = currentSide()
            val targets = ArrayList<PanelFile>()
            for (f in files) {
                if (ServerUpdate.queryOf(f.name).isBlank()) continue
                targets.add(f)
                f.status = "检测中…"
            }
            safePost(handler) { bump() }

            val pool = java.util.concurrent.Executors.newFixedThreadPool(
                (Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 4) / 2).coerceIn(1, 4)
            )
            val latch = java.util.concurrent.CountDownLatch(targets.size)
            for (f in targets) {
                pool.execute {
                    try {
                        val q = ServerUpdate.queryOf(f.name)
                        val hits = ServerUpdate.find(ctx, q, mc, loader, side, f.kind, srcs)
                        if (hits.isEmpty()) {
                            f.status = "没搜到，点一下手动指定"
                        } else {
                            applyHit(f, hits.first(), side)
                        }
                    } catch (t: Throwable) {
                        Err.ignore(t, "检测服务器模组更新")
                        f.status = "检测失败"
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            pool.shutdown()

            val found = files.count { it.latestUrl.isNotBlank() }
            safePost(handler) {
                bump()
                busy = false
                toast("可更新 $found / ${files.size}（${sideLabel()}）")
            }
            log("检测完成：可更新 $found（${sideLabel()}）")
        }
    }

    private fun applyHit(f: PanelFile, h: ServerUpdate.Hit, side: Environ.Side) {
        f.projectId = h.projectId
        f.latestVersion = h.version
        f.latestUrl = h.url
        f.latestName = h.fileName
        f.latestSource = h.source
        f.status = "可更新 → ${h.version}（${ServerUpdate.label(h.source)}）"
        if (Environ.isClientOnly(h.environment)) {
            f.status += "（仅客户端）"
        }
    }

    private fun manualPick(f: PanelFile) {
        manualQuery = ServerUpdate.queryOf(f.name)
        dlg = Dlg.Manual(f)
    }

    private fun manualSearch(f: PanelFile, q: String) {
        val mc = version.trim()
        val loader = currentLoader()
        val srcs = selectedSources()
        toast("正在搜索 $q …")
        bg {
            val list = ServerUpdate.searchAll(requireContext(), q, mc, loader, srcs)
            safePost(handler) {
                if (list.isEmpty()) {
                    dlg = Dlg.Info("没搜到", "换一个关键词试试，或者在设置里换一个平台。")
                } else {
                    dlg = Dlg.Candidates(f, list)
                }
            }
        }
    }

    // ── 下载 ─────────────────────────────────────────────────

    private fun downloadOne(f: PanelFile) {
        if (f.latestUrl.isBlank()) {
            manualPick(f)
            return
        }
        val ctx = requireContext()
        val out = outDir()
        if (out == null) {
            toast("「迁移后」的目录不可用，请先在迁移页选择")
            return
        }
        bg {
            safePost(handler) {
                f.status = "下载中"
                bump()
            }
            val r = Downloader.download(ctx, f.latestUrl, out, f.latestName)
            safePost(handler) {
                f.status = if (r == null) "下载失败" else "已下载，请自行上传到服务器"
                bump()
            }
            Notifier.show(ctx, "服务器模组", f.name)
            log(if (r == null) "下载失败：${f.name}" else "已下载：${f.latestName}（请手动上传）")
        }
    }

    private fun downloadAll() {
        val pend = files.filter { it.latestUrl.isNotBlank() }
        if (pend.isEmpty()) {
            toast("没有可下载的项")
            return
        }
        val ctx = requireContext()
        val out = outDir()
        if (out == null) {
            toast("「迁移后」的目录不可用")
            return
        }
        toast("开始下载 ${pend.size} 个…")
        busy = true
        bg {
            var ok = 0
            for (f in pend) {
                val r = Downloader.download(ctx, f.latestUrl, out, f.latestName)
                if (r != null) ok++
                safePost(handler) {
                    f.status = if (r == null) "下载失败" else "已下载，请自行上传"
                    bump()
                }
            }
            safePost(handler) { busy = false }
            log("批量下载完成 $ok/${pend.size}，文件在目标 mods 目录，需自行上传")
            toast("完成 $ok/${pend.size}")
        }
    }

    // ── 小工具 ───────────────────────────────────────────────

    private fun bump() {
        filesRev++
    }

    private fun log(s: String) {
        safePost(handler) { logText += "$s\n" }
    }

    private fun toast(s: String) {
        handler.post {
            if (!isAdded) return@post
            try {
                context?.let { Tips.short(it, s) }
            } catch (t: Throwable) {
                Err.ignore(t, "界面已销毁，不弹")
            }
        }
    }

    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("错误：${t.message}")
            } finally {
                // 同 MigrationFragment：Progress 是全局单例，
                // 异常/提前 return 时不复位会永久卡在"运行中"。
                try { Progress.done() } catch (_: Throwable) {}
            }
        }
    }

    private fun save() {
        val ctx = context ?: return
        Prefs.get(ctx).edit()
            .putString(K.PANEL_BASE, base.trim())
            .putString(K.PANEL_KEY, key.trim())
            .putString(K.PANEL_DIR, dir.trim().ifBlank { "/mods" })
            .apply()
    }

    override fun onPause() {
        super.onPause()
        save()
        saveRemote()
    }
}
