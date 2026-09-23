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
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etKey: EditText
    private lateinit var rgAuth: android.widget.RadioGroup
    private val cred = ServerPanelApi.Cred()
    private lateinit var etDir: EditText
    private lateinit var etVersion: EditText
    private lateinit var spLoader: Spinner
    private lateinit var tvServer: TextView
    private lateinit var tvLog: TextView
    private lateinit var rv: RecyclerView

    private val servers = mutableListOf<PanelServer>()
    private val files = mutableListOf<PanelFile>()
    private lateinit var adapter: UpdateAdapter
    private var chosen: PanelServer? = null

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_server, container, false)
        etBase = v.findViewById(R.id.etPanelBase)
        etUser = v.findViewById(R.id.etPanelUser)
        etPass = v.findViewById(R.id.etPanelPass)
        etKey = v.findViewById(R.id.etPanelKey)
        rgAuth = v.findViewById(R.id.rgAuth)
        etDir = v.findViewById(R.id.etPanelDir)
        etVersion = v.findViewById(R.id.etVersion)
        spLoader = v.findViewById(R.id.spLoader)
        tvServer = v.findViewById(R.id.tvServer)
        tvLog = v.findViewById(R.id.tvLog)
        rv = v.findViewById(R.id.rvUpdates)

        adapter = UpdateAdapter(files) { f -> downloadOne(f) }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val p = Prefs.get(requireContext())
        etBase.setText(p.getString(K.PANEL_BASE, "") ?: "")
        etUser.setText(p.getString(K.PANEL_USER, "") ?: "")
        etPass.setText(p.getString(K.PANEL_PASS, "") ?: "")
        etKey.setText(p.getString(K.PANEL_KEY, "") ?: "")
        if (p.getBoolean(K.PANEL_MODE_KEY, false)) {
            rgAuth.check(R.id.rbKey)
        } else {
            rgAuth.check(R.id.rbLogin)
        }
        syncAuthFields()
        etDir.setText(p.getString(K.PANEL_DIR, "/mods") ?: "/mods")
        etVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")

        v.findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }
        v.findViewById<Button>(R.id.btnScanFiles).setOnClickListener { scanFiles() }
        v.findViewById<Button>(R.id.btnProbeDir)?.setOnClickListener { probeDirs() }
        rgAuth.setOnCheckedChangeListener { _, _ -> syncAuthFields() }
        v.findViewById<Button>(R.id.btnCheckUpdates).setOnClickListener { checkUpdates() }
        v.findViewById<Button>(R.id.btnDownloadAll).setOnClickListener { downloadAll() }
        return v
    }

    /** 按认证方式显示/隐藏对应输入框，避免"只有一个框"的困惑 */
    private fun syncAuthFields() {
        val useKey = rgAuth.checkedRadioButtonId == R.id.rbKey
        etKey.visibility = if (useKey) View.VISIBLE else View.GONE
        etUser.visibility = if (useKey) View.GONE else View.VISIBLE
        etPass.visibility = if (useKey) View.GONE else View.VISIBLE
    }

    /** 收集当前凭据：账号密码模式会先登录换 token */
    private fun cred(): ServerPanelApi.Cred {
        val p = Prefs.get(requireContext())
        val c = ServerPanelApi.Cred(
            base = etBase.text.toString().trim(),
            mode = if (rgAuth.checkedRadioButtonId == R.id.rbKey)
                ServerPanelApi.Mode.KEY else ServerPanelApi.Mode.LOGIN,
            key = etKey.text.toString().trim(),
            user = etUser.text.toString().trim(),
            pass = etPass.text.toString().trim()
        )
        // 保存（密码走加密 Prefs）
        p.edit()
            .putString(K.PANEL_BASE, c.base)
            .putString(K.PANEL_USER, c.user)
            .putString(K.PANEL_PASS, c.pass)
            .putString(K.PANEL_KEY, c.key)
            .putBoolean(K.PANEL_MODE_KEY, c.mode == ServerPanelApi.Mode.KEY)
            .apply()
        return c
    }

    /** 自动找目录：面板下各服务器根目录结构不统一，逐个试候选 */
    private fun probeDirs() {
        val srv = chosen
        if (srv == null) {
            toast("请先连接并选一台服务器")
            return
        }
        val c = cred()
        toast("正在找模组目录…")
        bg {
            val token = try {
                ServerPanelApi.tokenOf(c)
            } catch (t: Throwable) {
                log("认证失败：${t.message}")
                toast("认证失败：${t.message}")
                return@bg
            }
            val dirs = ServerPanelApi.probeDirs(c.base, token, srv.uuid.ifBlank { srv.id })
            safePost(handler) {
                if (dirs.isEmpty()) {
                    log("没自动找到含 jar 的目录，可手动填路径（常见：/mods、/plugins）")
                    toast("没找到，请手动填目录")
                } else {
                    log("找到目录：${dirs.joinToString("、")}")
                    val arr = dirs.toTypedArray()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.panel_dir_pick)
                        .setItems(arr) { _, w ->
                            etDir.setText(arr[w])
                            scanFiles()
                        }
                        .show()
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
            }
        }
    }

    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("错误：${t.message}")
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
        val c = cred()
        if (c.base.isBlank()) {
            toast("请填写面板地址")
            return
        }
        if (c.mode == ServerPanelApi.Mode.KEY && c.key.isBlank()) {
            toast("请填写 Client API Key")
            return
        }
        if (c.mode == ServerPanelApi.Mode.LOGIN && (c.user.isBlank() || c.pass.isBlank())) {
            toast("请填写面板账号和密码")
            return
        }
        toast("正在连接面板…")
        bg {
            val token = try {
                ServerPanelApi.tokenOf(c)
            } catch (t: Throwable) {
                log("认证失败：${t.message}")
                safePost(handler) { toast("认证失败：${t.message}") }
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
                } else {
                    val names = list.map { "${it.name}（${it.id}）" }.toTypedArray()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("选择服务器")
                        .setItems(names) { _, w ->
                            chosen = list[w]
                            tvServer.text = "服务器：${list[w].name} · ${list[w].id}"
                            log("已选择 ${list[w].name}")
                            // 选完服务器立刻找目录，省得用户自己去猜路径
                            probeDirs()
                        }
                        .show()
                    toast("找到 ${list.size} 台服务器")
                }
            }
        }
    }

    private fun scanFiles() {
        val s = chosen
        if (s == null) {
            toast("请先连接并选择服务器")
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
                log("认证失败：${t.message}")
                safePost(handler) { toast("认证失败：${t.message}") }
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
        if (mc.isBlank()) {
            toast("请填写服务器 MC 版本，用于匹配更新")
            return
        }
        log("开始检测更新（MC $mc / $loader）")
        bg {
            var found = 0
            for (f in files) {
                val q = f.name
                    .replace(Regex("\\.jar$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[-_](fabric|forge|neoforge|quilt)$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[-_]mc1?[._-]?\\d+.*$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[._-]"), " ")
                    .trim()
                val res = try {
                    ModrinthApi.search(q, mc, loader, 5)
                } catch (t: Throwable) {
                    emptyList<MarketMod>()
                }
                val hit = res.firstOrNull()
                if (hit == null) {
                    f.status = "未匹配到项目（可手动搜索下载）"
                } else {
                    val vers = try {
                        ModrinthApi.versions(hit.id, mc, loader)
                    } catch (t: Throwable) {
                        emptyList<ModFile>()
                    }
                    val v0 = vers.firstOrNull()
                    if (v0 == null) {
                        f.status = "无 ${mc} 版本"
                    } else {
                        f.projectId = hit.id
                        f.latestVersion = v0.version
                        f.latestUrl = v0.url
                        f.latestName = v0.fileName.ifBlank { Downloader.guessName(v0.url) }
                        f.status = "可更新 → ${v0.version}"
                        found++
                    }
                }
            }
            safePost(handler) {
                adapter.notifyDataSetChanged()
                toast("可更新 $found / ${files.size}")
            }
            log("检测完成：可更新 $found")
        }
    }

    private fun downloadOne(f: PanelFile) {
        if (f.latestUrl.isBlank()) {
            toast("这个还没有可用的更新地址")
            return
        }
        val ctx = requireContext()
        val dir = Targets.modsDir(ctx)
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
        val dir = Targets.modsDir(ctx)
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
}
