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
        etKey = v.findViewById(R.id.etPanelKey)
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
        etKey.setText(p.getString(K.PANEL_KEY, "") ?: "")
        etDir.setText(p.getString(K.PANEL_DIR, "/mods") ?: "/mods")
        etVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")

        v.findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }
        v.findViewById<Button>(R.id.btnScanFiles).setOnClickListener { scanFiles() }
        v.findViewById<Button>(R.id.btnCheckUpdates).setOnClickListener { checkUpdates() }
        v.findViewById<Button>(R.id.btnDownloadAll).setOnClickListener { downloadAll() }
        return v
    }

    private fun log(s: String) {
        safePost(handler) { tvLog.append("$s\n") }
    }

    private fun toast(s: String) {
        safePost(handler) { Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show() }
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
        save()
        val base = etBase.text.toString().trim()
        val key = etKey.text.toString().trim()
        if (base.isBlank() || key.isBlank()) {
            toast("请填写面板地址和 API Key")
            return
        }
        toast("正在连接面板…")
        bg {
            val list = try {
                ServerPanelApi.servers(base, key)
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
                            tvServer.text = "服务器：${chosen!!.name} · ${chosen!!.id}"
                            log("已选择 ${chosen!!.name}")
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
        val base = etBase.text.toString().trim()
        val key = etKey.text.toString().trim()
        val dir = etDir.text.toString().trim().ifBlank { "/mods" }
        val ctx = requireContext()
        toast("正在列出 $dir …")
        bg {
            val list = try {
                ServerPanelApi.listFiles(base, key, s.uuid.ifBlank { s.id }, dir)
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
