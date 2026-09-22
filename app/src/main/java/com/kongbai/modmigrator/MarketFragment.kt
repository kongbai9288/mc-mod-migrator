package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MarketFragment : Fragment() {

    private lateinit var etQuery: EditText
    private lateinit var etVersion: EditText
    private lateinit var spLoader: Spinner
    private lateinit var rvMods: RecyclerView
    private lateinit var rvLinks: RecyclerView

    private val results = mutableListOf<MarketMod>()
    private val links = mutableListOf<MarkedLink>()
    private lateinit var resAdapter: MarketAdapter
    private lateinit var linkAdapter: LinkAdapter

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_market, container, false)
        etQuery = v.findViewById(R.id.etQuery)
        etVersion = v.findViewById(R.id.etVersion)
        spLoader = v.findViewById(R.id.spLoader)
        rvMods = v.findViewById(R.id.rvMods)
        rvLinks = v.findViewById(R.id.rvLinks)

        resAdapter = MarketAdapter(results, { m -> install(m) }, { m -> openPage(m.pageUrl) }, { m -> translate(m) })
        linkAdapter = LinkAdapter(links, { l -> downloadLink(l) }, { l ->
            Store.removeLink(requireContext(), l.url)
            reloadLinks()
        })

        rvMods.layoutManager = LinearLayoutManager(requireContext())
        rvMods.adapter = resAdapter
        rvLinks.layoutManager = LinearLayoutManager(requireContext())
        rvLinks.adapter = linkAdapter
        rvLinks.isNestedScrollingEnabled = false

        v.findViewById<Button>(R.id.btnSearch).setOnClickListener { search() }
        v.findViewById<Button>(R.id.btnAddLink).setOnClickListener { addLinkDialog() }
        v.findViewById<Button>(R.id.btnOpenPage).setOnClickListener { openPageDialog() }

        val p = Prefs.get(requireContext())
        etVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")
        reloadLinks()
        return v
    }

    override fun onResume() {
        super.onResume()
        reloadLinks()
    }

    private fun reloadLinks() {
        links.clear()
        links.addAll(Store.links(requireContext()))
        linkAdapter.notifyDataSetChanged()
    }

    private fun toast(s: String) {
        safePost(handler) { Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show() }
    }

    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                toast("出错：${t.message}")
            }
        }
    }

    private fun mcVersion(): String {
        val s = etVersion.text.toString().trim()
        return s.ifBlank { Prefs.get(requireContext()).getString(K.DEF_VERSION, "") ?: "" }
    }

    private fun loader(): String = spLoader.selectedItem?.toString() ?: "auto"

    private fun search() {
        val q = etQuery.text.toString().trim()
        if (q.isBlank()) {
            toast("请输入关键词")
            return
        }
        val mc = mcVersion()
        val ld = loader()
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        val src = p.getString(K.SOURCE, "Modrinth") ?: "Modrinth"
        val key = p.getString(K.CF_KEY, "") ?: ""
        toast("搜索中…")
        bg {
            val list = if (src == "CurseForge") {
                val r = CurseForgeApi.search(q, mc, ld, key)
                if (r.isEmpty()) ModrinthApi.search(q, mc, ld) else r
            } else {
                ModrinthApi.search(q, mc, ld)
            }
            safePost(handler) {
                results.clear()
                results.addAll(list)
                resAdapter.notifyDataSetChanged()
                toast("找到 ${list.size} 个")
                autoTranslate(list)
            }
        }
    }

    private fun translate(mod: MarketMod) {
        if (mod.summaryZh.isNotBlank()) return
        bg {
            val zh = Translator.toZh(mod.summary)
            if (zh == null) {
                toast("翻译失败，可能是网络或额度限制")
                return@bg
            }
            mod.summaryZh = zh
            safePost(handler) {
                val i = results.indexOf(mod)
                if (i >= 0) resAdapter.notifyItemChanged(i)
            }
        }
    }

    private fun autoTranslate(list: List<MarketMod>) {
        if (!Prefs.get(requireContext()).getBoolean(K.AUTO_TRANS, true)) return
        bg {
            var n = 0
            for (m in list.take(10)) {
                val zh = Translator.toZh(m.summary)
                if (zh != null) {
                    m.summaryZh = zh
                    n++
                }
            }
            if (n > 0) safePost(handler) { resAdapter.notifyDataSetChanged() }
        }
    }

    private fun install(mod: MarketMod) {
        val ctx = requireContext()
        val mc = mcVersion()
        val ld = loader()
        toast("准备下载…")
        bg {
            var name = ""
            var headers: Map<String, String> = emptyMap()
            val url = if (mod.source == "curseforge") {
                headers = CurseForgeApi.authHeaders()
                CurseForgeApi.downloadUrl(mod)
            } else {
                val files = ModrinthApi.versions(mod.id, mc, ld)
                val f0 = files.firstOrNull()
                if (f0 != null) name = f0.fileName
                f0?.url ?: ""
            }
            if (url.isBlank()) {
                toast("没有可下载的文件")
                return@bg
            }
            if (name.isBlank()) name = Downloader.guessName(url)
            val dir = Targets.modsDir(ctx)
            val f = if (dir == null) null else Downloader.download(ctx, url, dir, name, headers)
            toast(if (f == null) "下载失败" else "已安装：${f.name}")
            Notifier.show(ctx, getString(R.string.downloading), mod.name)
        }
    }

    private fun downloadLink(l: MarkedLink) {
        val ctx = requireContext()
        toast("下载中…")
        bg {
            val dir = Targets.modsDir(ctx)
            val name = Downloader.guessName(l.url)
            val f = if (dir == null) null else Downloader.download(ctx, l.url, dir, name)
            toast(if (f == null) "下载失败（可能是网盘/需浏览器页面）" else "已下载：${f.name}")
        }
    }

    private fun openPage(url: String) {
        if (url.isBlank()) {
            toast("没有页面地址")
            return
        }
        val i = Intent(requireContext(), ModPageActivity::class.java)
        i.putExtra("url", url)
        startActivity(i)
    }

    private fun addLinkDialog() {
        val ctx = requireContext()
        val et = EditText(ctx)
        et.hint = "https://..."
        et.setSingleLine(true)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.btn_add_link)
            .setView(et)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val u = et.text.toString().trim()
                if (u.isBlank()) return@setPositiveButton
                val ok = Store.addLink(ctx, MarkedLink(u, u))
                toast(if (ok) "已标记" else "已经标记过了")
                reloadLinks()
            }
            .show()
    }

    private fun openPageDialog() {
        val ctx = requireContext()
        val et = EditText(ctx)
        et.hint = "https://modrinth.com/mod/..."
        et.setSingleLine(true)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.btn_open_page)
            .setView(et)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val u = et.text.toString().trim()
                if (u.isNotBlank()) openPage(u)
            }
            .show()
    }
}
