package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class ServerFragment : Fragment() {

    private lateinit var etBase: EditText

    private lateinit var etKey: EditText

    private val cred = ServerPanelApi.Cred()
    private lateinit var etDir: EditText
    private lateinit var boxPanel: android.widget.LinearLayout
    private lateinit var boxRemote: android.widget.LinearLayout
    private lateinit var tvConnMode: android.widget.TextView
    private lateinit var etVersion: EditText
    private lateinit var spLoader: Spinner
    private lateinit var spSide: Spinner
    private lateinit var tvServer: TextView
    private lateinit var tvLog: TextView
    private lateinit var rv: RecyclerView

    private val servers = mutableListOf<PanelServer>()
    private val files = mutableListOf<PanelFile>()
    private lateinit var adapter: UpdateAdapter
    private var chosen: PanelServer? = null

    // ---- 远程文件（FTP / FTPS / SFTP）----
    // 面板 API 必须有 ptlc_ Key，而很多服务器只给了 FTP/SSH 账号，
    // 之前那种情况就完全没法用。这里让两种连接方式共存。
    private lateinit var spRemoteKind: Spinner
    private lateinit var boxRemote: View
    private lateinit var etRemoteHost: android.widget.EditText
    private lateinit var etRemotePort: android.widget.EditText
    private lateinit var etRemoteUser: android.widget.EditText
    private lateinit var etRemotePass: android.widget.EditText
    /** FTP/SFTP 模式下"已选中的目录"，充当面板模式里的服务器 */
    private var remoteDir: String? = null

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_server, container, false)
        // 下面恢复 FTP/SFTP 表单时要读它，必须先取到
        val p = Prefs.get(requireContext())
        etBase = v.findViewById(R.id.etPanelBase)
        etKey = v.findViewById(R.id.etPanelKey)
        etDir = v.findViewById(R.id.etPanelDir)
        boxPanel = v.findViewById(R.id.boxPanel)
        boxRemote = v.findViewById(R.id.boxRemote)
        tvConnMode = v.findViewById(R.id.tvConnMode)
        etVersion = v.findViewById(R.id.etVersion)
        spLoader = v.findViewById(R.id.spLoader)
        LoaderSpinner.attachByPref(spLoader)
        spSide = v.findViewById(R.id.spSide)
        tvServer = v.findViewById(R.id.tvServer)
        tvLog = v.findViewById(R.id.tvLog)
        rv = v.findViewById(R.id.rvUpdates)
        spRemoteKind = v.findViewById(R.id.spRemoteKind)
        boxRemote = v.findViewById(R.id.boxRemote)
        etRemoteHost = v.findViewById(R.id.etRemoteHost)
        etRemotePort = v.findViewById(R.id.etRemotePort)
        etRemoteUser = v.findViewById(R.id.etRemoteUser)
        etRemotePass = v.findViewById(R.id.etRemotePass)
        // 0 = 面板 API，1/2/3 = FTP / FTPS / SFTP
        spRemoteKind.setSelection(p.getInt(K.REMOTE_KIND, 0).coerceIn(0, 3), false)
        etRemoteHost.setText(p.getString(K.REMOTE_HOST, "") ?: "")
        etRemotePort.setText(p.getString(K.REMOTE_PORT, "") ?: "")
        etRemoteUser.setText(p.getString(K.REMOTE_USER, "") ?: "")
        etRemotePass.setText(p.getString(K.REMOTE_PASS, "") ?: "")
        spRemoteKind.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    a: android.widget.AdapterView<*>?, b: View?, pos: Int, id: Long
                ) {
                    syncRemoteFields()
                    saveRemote()
                }
                override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
            }
        syncRemoteFields()

        adapter = UpdateAdapter(files) { f -> downloadOne(f) }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        etBase.setText(p.getString(K.PANEL_BASE, "") ?: "")
        etKey.setText(p.getString(K.PANEL_KEY, "") ?: "")
        etDir.setText(p.getString(K.PANEL_DIR, "/mods") ?: "/mods")
        etVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")

        v.findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }
        v.findViewById<Button>(R.id.btnScanFiles).setOnClickListener { scanFiles() }
        v.findViewById<Button>(R.id.btnProbeDir)?.setOnClickListener { probeDirs() }
        v.findViewById<Button>(R.id.btnCheckUpdates).setOnClickListener { checkUpdates() }
        v.findViewById<Button>(R.id.btnDownloadAll).setOnClickListener { downloadAll() }
        // 目录浏览器：连上之后逐级进入目录，边逛边看这个目录里有哪些 jar
        v.findViewById<Button>(R.id.btnBrowseDir)?.setOnClickListener {
            browseDir(etDir.text.toString().trim().ifBlank { "/" })
        }
        // 选择"更新下来的文件放哪"（不上传回服务器）
        v.findViewById<Button>(R.id.btnPickOutDir)?.setOnClickListener { pickOutDir() }
        refreshOutDirLabel()
        return v
    }

    // ---------------- 输出目录（不上传，只落地到本地） ----------------

    /** 更新结果存放目录。空=用默认（迁移后目录/工作目录） */
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
        val btn = view?.findViewById<Button>(R.id.btnPickOutDir) ?: return
        btn.text = if (saved.isBlank()) {
            "存放位置：默认（迁移后目录）"
        } else {
            val d = runCatching { Fs.tree(ctx, saved) }.getOrNull()
            "存放位置：${d?.name ?: "（已失效，点此重选）"}"
        }
    }

    /**
     * 让用户自己挑一个目录放更新下来的模组。
     * 明确说明：文件只落地到本地，**不会**上传回服务器。
     */
    private fun pickOutDir() {
        val ctx = context ?: return
        val opts = arrayOf("用默认目录（迁移后 / 工作目录）", "自己选一个目录…", "清除选择，回到默认")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("更新下来的文件放哪")
            .setMessage("服务器模组的更新只会下载到本机指定目录，不会上传回服务器。")
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> {
                        Prefs.get(ctx).edit().putString(K.PANEL_OUT_DIR, "").apply()
                        refreshOutDirLabel()
                    }
                    1 -> {
                        val i = android.content.Intent(
                            android.content.Intent.ACTION_OPEN_DOCUMENT_TREE
                        )
                        startActivityForResult(i, 91)
                    }
                    2 -> {
                        Prefs.get(ctx).edit().putString(K.PANEL_OUT_DIR, "").apply()
                        refreshOutDirLabel()
                        toast("已回到默认目录")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 91 || resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = context ?: return
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (t: Throwable) {
            Err.ignore(t, "恢复上次保存的面板凭据")
        }
        Prefs.get(ctx).edit().putString(K.PANEL_OUT_DIR, uri.toString()).apply()
        refreshOutDirLabel()
        toast("已设置存放位置")
    }

    // ---------------- 目录浏览器 ----------------

    /**
     * 逐级浏览服务器目录。
     *
     * 之前只能手填路径，面板结构各家不同，用户根本猜不出该填什么。
     * 现在列当前目录的内容：目录可进入，jar 会实时标出来，
     * 选好了直接"就用这个目录"去扫描。
     */
    private fun browseDir(path: String) {
        val srv = chosen
        if (srv == null) {
            toast("请先连接并选择服务器")
            return
        }
        val c = cred()
        val ctx = requireContext()
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
                .map { it.first }

            safePost(handler) {
                // safePost 内部已检查 isAdded，这里只用 safePost 的标签返回
                val labels = ArrayList<String>()
                val actions = ArrayList<() -> Unit>()

                // 上一层
                if (path != "/" && path.isNotBlank()) {
                    labels.add("↑ 上一级")
                    actions.add {
                        val parent = path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }
                        browseDir(parent)
                    }
                }
                for (d in dirs) {
                    labels.add("📁 $d")
                    actions.add { browseDir(path.trimEnd('/') + "/" + d) }
                }
                for (j in jars) {
                    labels.add("📦 $j")
                    actions.add { /* 点 jar 不做事，用下面的按钮确认目录 */ }
                }

                val sb = StringBuilder()
                sb.append("当前：").append(path).append('\n')
                sb.append("子目录 ").append(dirs.size).append(" 个 · jar ").append(jars.size)
                    .append(" 个")
                if (others.isNotEmpty()) sb.append(" · 其它 ").append(others.size).append(" 个")

                MaterialAlertDialogBuilder(ctx)
                    .setTitle("浏览服务器目录")
                    .setMessage(sb.toString())
                    .setItems(labels.toTypedArray()) { _, w -> actions[w].invoke() }
                    .setPositiveButton("就用这个目录") { _, _ ->
                        etDir.setText(path)
                        Prefs.get(ctx).edit().putString(K.PANEL_DIR, path).apply()
                        if (jars.isNotEmpty()) {
                            scanFiles()
                        } else {
                            toast("这个目录没有 jar，可继续进入子目录找找")
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /**
     * 按连接方式切换**整块**字段。
     *
     * ⚠️ 之前只把面板地址/Key 两个框设成 GONE，顶部标题
     * 「面板连接（Pterodactyl Client API）」还留在那儿，
     * 而下面出现的却是主机/用户名/密码 —— 标题说的是 A，
     * 底下填的是 B，看的人根本不知道自己在用哪套。
     *
     * 现在两组字段各带自己的标题，整块互斥显示，
     * 并且用 tvConnMode 一句话说清当前这套是什么、需要什么。
     */
    private fun syncRemoteFields() {
        if (!::boxPanel.isInitialized || !::boxRemote.isInitialized) return
        val remote = isRemote()
        boxPanel.visibility = if (remote) View.GONE else View.VISIBLE
        boxRemote.visibility = if (remote) View.VISIBLE else View.GONE
        if (::tvConnMode.isInitialized) {
            tvConnMode.text = if (remote) {
                "用服务器的 FTP / SSH 账号密码直连，可以浏览并读取 mods、plugins 目录。"
            } else {
                "用面板的 Client API Key 连接（不是登录密码）。没有面板 Key 就改选上面的 FTP/SFTP。"
            }
        }
        // 目录框两种模式共用，但含义不同，说清楚免得填错
        if (::etDir.isInitialized) {
            etDir.hint = if (remote) "远程目录，如 /mods" else "服务器内目录，如 /mods 或 /plugins"
        }
    }

    /** 是否走 FTP/FTPS/SFTP（0 = 面板 API） */
    private fun isRemote(): Boolean = ::spRemoteKind.isInitialized &&
        spRemoteKind.selectedItemPosition > 0

    /** 当前远程连接配置 */
    private fun remoteConf(): RemoteFs.Conf {
        val kind = when (spRemoteKind.selectedItemPosition) {
            1 -> RemoteFs.Kind.FTP
            2 -> RemoteFs.Kind.FTPS
            3 -> RemoteFs.Kind.SFTP
            else -> RemoteFs.Kind.FTP
        }
        return RemoteFs.Conf(
            kind = kind,
            host = etRemoteHost.text.toString().trim(),
            port = etRemotePort.text.toString().trim().toIntOrNull() ?: 0,
            user = etRemoteUser.text.toString().trim(),
            pass = etRemotePass.text.toString().trim()
        )
    }

    private fun saveRemote() {
        if (!::spRemoteKind.isInitialized) return
        Prefs.get(requireContext()).edit()
            .putInt(K.REMOTE_KIND, spRemoteKind.selectedItemPosition)
            .putString(K.REMOTE_HOST, etRemoteHost.text.toString().trim())
            .putString(K.REMOTE_PORT, etRemotePort.text.toString().trim())
            .putString(K.REMOTE_USER, etRemoteUser.text.toString().trim())
            .putString(K.REMOTE_PASS, etRemotePass.text.toString().trim())
            .apply()
    }

    /** 收集当前凭据 */
    private fun cred(): ServerPanelApi.Cred {
        val p = Prefs.get(requireContext())
        val c = ServerPanelApi.Cred(
            base = etBase.text.toString().trim(),
            mode = ServerPanelApi.Mode.KEY,
            key = etKey.text.toString().trim()
        )
        // 保存（密码走加密 Prefs）
        p.edit()
            .putString(K.PANEL_BASE, c.base)

            .putString(K.PANEL_KEY, c.key)

            .apply()
        return c
    }

    /** 自动找目录：面板下各服务器根目录结构不统一，逐个试候选 */
    /** 让用户在找到的目录里挑一个，然后立刻扫描 */
    private fun pickDirDialog(dirs: List<String>) {
        val arr = dirs.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.panel_dir_pick)
            .setItems(arr) { _, w ->
                etDir.setText(arr[w])
                remoteDir = arr[w]
                scanFiles()
            }
            .show()
    }

    /** 多个候选目录时让用户选一台（面板模式下的"服务器"同理） */
    private fun pickServerDialog() {
        val arr = servers.map { it.name }.toTypedArray()
        if (arr.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择目录")
            .setItems(arr) { _, w -> pickServer(servers[w]) }
            .show()
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
                toast("认证失败：${Err.humanMessage(t)}")
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

    private fun log(s: String) {
        safePost(handler) { tvLog.append("$s\n") }
    }

    private fun toast(s: String) {
        handler.post {
            if (!isAdded) return@post
            try {
                context?.let { android.widget.Toast.makeText(it, s, android.widget.Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                // 界面已销毁，不弹
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
                // 异常/提前 return 时不复位会永久卡在"运行中"，
                // 下次开 App 出现幽灵进度条。
                try { Progress.done() } catch (_: Throwable) {}
            }
        }
    }

    private fun save() {
        val p = Prefs.get(requireContext())
        p.edit()
            .putString(K.PANEL_BASE, etBase.text.toString().trim())
            .putString(K.PANEL_KEY, etKey.text.toString().trim())
            .putString(K.PANEL_DIR, etDir.text.toString().trim().ifBlank { "/mods" })
            .apply()
    }

    private fun connect() {
        // 选了 FTP/FTPS/SFTP：不再要求面板 Key，用账号密码直连
        if (isRemote()) {
            connectRemote()
            return
        }
        val c = cred()
        if (c.base.isBlank()) {
            toast("请填写面板地址")
            return
        }
        if (c.key.isBlank()) {
            toast("请填写 Client API Key")
            return
        }
        // 提前校验 Key 形态：填成应用 Key（ptla_）会直接 403，
        // 而面板返回的 403 信息对用户等于天书，这里先拦下来说明白
        val hint = ServerPanelApi.keyHint(c.key)
        if (hint != null) {
            log("Key 检查：$hint")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("API Key 可能不对")
                .setMessage(hint)
                .setPositiveButton("仍然连接") { _, _ -> doConnect(c) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        doConnect(c)
    }

    /**
     * 账号密码模式走不通时的引导。
     *
     * 之前依赖 `login()` 抛异常把这段话带出来 —— 那是**异常当业务提示用**，
     * 任何一处漏了 try-catch 就是崩溃。现在 login() 返回 null，
     * 提示统一由这里弹，并且在**发起连接之前**就拦下来，
     * 不再让用户先等一轮网络请求才看到结论。
     */
    private fun showLoginUnsupported() {
        val ctx = context ?: return
        runCatching {
            if (!isAdded) return
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("请用 API Key 连接")
                .setMessage(ServerPanelApi.LOGIN_UNSUPPORTED_MSG)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    /**
     * FTP / SFTP 连接：测通 → 自动找 mods/plugins 目录 → 列出 jar。
     *
     * 之所以要整条串起来：面板模式下用户要自己填目录，
     * 而 FTP 根目录结构各家都不一样，让用户猜路径等于让他放弃。
     */
    /** FTP/SFTP 下列出目录里的 jar */
    private fun scanRemote() {
        val dir = remoteDir ?: etDir.text.toString().trim().ifBlank { "/mods" }
        val c = remoteConf()
        toast("正在列出 $dir …")
        bg {
            val list = try {
                RemoteFs.jars(c, dir).map {
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
                adapter.notifyDataSetChanged()
                toast("${list.size} 个 jar")
            }
        }
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
                    tvServer.text = "已连接，但没找到有 jar 的目录"
                    toast("没找到 mods/plugins 目录，可以在下面手动填路径再扫描")
                    return@safePost
                }
                // 有 jar 的目录当成"服务器"列出来让用户选，
                // 只有一个就直接选中，不让他多点一次
                servers.addAll(found.mapIndexed { i, d ->
                    PanelServer(id = "remote:$d", uuid = d, name = d)
                })
                if (found.size == 1) pickServer(servers[0]) else pickServerDialog()
            }
        }
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

                // ⚠️ 之前**无论如何都要弹窗让用户选一台**：
                // 面板上明明只有一台服务器，也要用户多点一次；
                // 而且每次重新连接都要再选一遍，很烦。
                // 现在：只有一台就直接选上，不再弹窗。
                val saved = Prefs.get(requireContext()).getString(K.PANEL_SERVER_ID, "") ?: ""
                val remembered = list.firstOrNull { it.id == saved }
                val auto = remembered ?: if (list.size == 1) list[0] else null
                if (auto != null) {
                    pickServer(auto)
                    toast("已选择 ${auto.name}")
                    return@safePost
                }

                val names = list.map { "${it.name}（${it.id}）" }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("选择服务器")
                    .setItems(names) { _, w ->
                        pickServer(list[w])
                        log("已选择 ${list[w].name}")
                    }
                    .show()
                toast("找到 ${list.size} 台服务器")
            }
        }
    }

    /**
     * 选中一台服务器并立刻去找目录。
     * 选完就记住 id，下次连接自动选回同一台，不用每次都点。
     */
    private fun pickServer(s: PanelServer) {
        chosen = s
        if (s.id.startsWith("remote:")) {
            remoteDir = s.uuid
            etDir.setText(s.uuid)
        }
        tvServer.text = "服务器：${s.name} · ${s.id}"
        Prefs.get(requireContext()).edit().putString(K.PANEL_SERVER_ID, s.id).apply()
        // 选完立刻找目录，省得用户自己去猜路径
        probeDirs()
    }

    private fun scanFiles() {
        val s = chosen
        if (s == null) {
            toast("请先连接并选择目录")
            return
        }
        // FTP/SFTP 模式：目录已经在连接时选好了，直接列
        if (s.id.startsWith("remote:")) {
            scanRemote()
            return
        }
        val c = cred()
        val base = c.base
        val dir = etDir.text.toString().trim().ifBlank { "/mods" }
        val ctx = requireContext()
        toast("正在列出 $dir …")
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
                ServerPanelApi.listFiles(base, token, s.uuid.ifBlank { s.id }, dir)
            } catch (t: Throwable) {
                log("读取失败：${t.message}")
                emptyList<PanelFile>()
            }
            for (f in list) f.kind = ServerPanelApi.guessKind(f.path)
            safePost(handler) {
                files.clear()
                files.addAll(list)
                adapter.notifyDataSetChanged()
                toast("${list.size} 个 jar")
            }
            log("列出 ${list.size} 个文件（$dir）")
        }
    }

    private fun checkUpdates() {
        if (files.isEmpty()) {
            toast("请先扫描文件")
            return
        }
        val mc = etVersion.text.toString().trim()
        val loader = spLoader.selectedItem?.toString() ?: "auto"
        val ctx = requireContext()
        if (mc.isBlank()) {
            toast("请填写服务器 MC 版本，用于匹配更新")
            return
        }
        log("开始检测更新（MC $mc / $loader / ${sideLabel()}）")
        bg {
            // ── 三个问题一起修 ──────────────────────────────
            // 1) `res.firstOrNull()`：搜索是模糊匹配，装的是 sodium
            //    可能匹配到 Sodium Extra，然后把它的版本当成本项目的更新
            //    → 给出**错误的下载地址**，用户下载到的是另一个模组。
            //    改用 CrossLoader.pickProject 做名称核对，对不上就如实跳过。
            // 2) 不看运行环境：服务端场景会把纯客户端模组（Mod Menu）
            //    也列成"可更新"。Modrinth 官方 environment 字段
            //    （client_side/server_side 已废弃）能区分，这里按端过滤并标注。
            // 3) 串行逐个请求：几十个文件就是几十次排队等待。
            //    改成并发（受下载并发设置约束的一半，上限 4）。
            val side = currentSide()
            val targets = ArrayList<PanelFile>()
            for (f in files) {
                val q = f.name
                    .replace(Regex("\\.jar$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[-_](fabric|forge|neoforge|quilt)$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[-_]mc1?[._-]?\\d+.*$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[._-]"), " ")
                    .trim()
                if (q.isBlank()) continue
                targets.add(f)
                f.status = "检测中…"
            }
            safePost(handler) { adapter.notifyDataSetChanged() }

            val pool = java.util.concurrent.Executors.newFixedThreadPool(
                (Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 4) / 2).coerceIn(1, 4)
            )
            val latch = java.util.concurrent.CountDownLatch(targets.size)
            for (f in targets) {
                pool.execute {
                    try {
                        val q = f.name
                            .replace(Regex("\\.jar$", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("[-_](fabric|forge|neoforge|quilt)$", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("[-_]mc1?[._-]?\\d+.*$", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("[._-]"), " ")
                            .trim()
                        // ⚠️ 这里**不再把 side 传给 API 层过滤**。
                        // 之前传 side 会往 new_filters 里拼
                        // `environment!="client_only"`，但：
                        //   1) Modrinth 官方可过滤字段清单里**没有 environment**；
                        //   2) 官方前端源码里 environment 过滤器明确标注
                        //      supports_negative_filter = false（不支持否定过滤）。
                        // 非法字段可能让整串 new_filters 失效、请求异常或返回空，
                        // 结果就是搜索直接搜不出东西。
                        // 运行环境改为**拿到结果后本地筛**（下面 Environ.okFor 已经在做），
                        // 不依赖 API 支持，行为一致且更稳。
                        val res = try {
                            ModrinthApi.search(q, mc, loader, 8, 0, null)
                        } catch (t: Throwable) {
                            emptyList<MarketMod>()
                        }
                        val hit = CrossLoader.pickProject(res, q)
                        if (hit == null) {
                            f.status = if (res.isEmpty()) "平台上没搜到（可手动搜）" else "搜到但名字对不上，已跳过"
                        } else if (!Environ.okFor(hit.environment, side)) {
                            f.status = "「${Environ.label(hit.environment)}」，不适用于${sideLabel()}"
                        } else {
                            val vers = try {
                                ModrinthApi.versions(hit.id, mc, loader)
                            } catch (t: Throwable) {
                                emptyList<ModFile>()
                            }
                            val v0 = vers.firstOrNull { Environ.okFor(it.environment, side) }
                                ?: vers.firstOrNull()
                            if (v0 == null) {
                                f.status = "无 ${mc} 版本"
                            } else {
                                f.projectId = hit.id
                                f.latestVersion = v0.version
                                f.latestUrl = v0.url
                                f.latestName = v0.fileName.ifBlank { Downloader.guessName(v0.url) }
                                f.status = "可更新 → ${v0.version}"
                                if (Environ.isClientOnly(v0.environment)) {
                                    f.status += "（仅客户端）"
                                }
                            }
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
                adapter.notifyDataSetChanged()
                toast("可更新 $found / ${files.size}（${sideLabel()}）")
            }
            log("检测完成：可更新 $found（${sideLabel()}）")
        }
    }

    /** 当前要按哪一端来匹配：服务端 or 客户端 */
    private fun currentSide(): Environ.Side =
        if (spSide.selectedItemPosition == 0) Environ.Side.SERVER else Environ.Side.CLIENT

    private fun sideLabel(): String =
        if (currentSide() == Environ.Side.SERVER) "服务端" else "客户端"

    private fun downloadOne(f: PanelFile) {
        if (f.latestUrl.isBlank()) {
            toast("这个还没有可用的更新地址")
            return
        }
        val ctx = requireContext()
        val dir = outDir()
        if (dir == null) {
            toast("「迁移后」的目录不可用，请先在迁移页选择")
            return
        }
        bg {
            safePost(handler) {
                f.status = "下载中"
                adapter.notifyDataSetChanged()
            }
            val out = Downloader.download(ctx, f.latestUrl, dir, f.latestName)
            safePost(handler) {
                f.status = if (out == null) "下载失败" else "已下载，请自行上传到服务器"
                adapter.notifyDataSetChanged()
            }
            Notifier.show(ctx, "服务器模组", f.name)
            log(if (out == null) "下载失败：${f.name}" else "已下载：${f.latestName}（请手动上传）")
        }
    }

    private fun downloadAll() {
        val pend = files.filter { it.latestUrl.isNotBlank() }
        if (pend.isEmpty()) {
            toast("没有可下载的项")
            return
        }
        val ctx = requireContext()
        val dir = outDir()
        if (dir == null) {
            toast("「迁移后」的目录不可用")
            return
        }
        toast("开始下载 ${pend.size} 个…")
        bg {
            var ok = 0
            for (f in pend) {
                val out = Downloader.download(ctx, f.latestUrl, dir, f.latestName)
                if (out != null) ok++
                safePost(handler) {
                    f.status = if (out == null) "下载失败" else "已下载，请自行上传"
                    adapter.notifyDataSetChanged()
                }
            }
            log("批量下载完成 $ok/${pend.size}，文件在目标 mods 目录，需自行上传")
            toast("完成 $ok/${pend.size}")
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
