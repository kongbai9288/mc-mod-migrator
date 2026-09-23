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

        resAdapter = MarketAdapter(
            results,
            { m -> install(m) },
            { m -> openPage(m.pageUrl) },
            { m -> translate(m) },
            { m -> toggleFav(m) },
            { m -> Favorites.has(requireContext(), m) }
        )
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
        v.findViewById<Button>(R.id.btnRecommend)?.setOnClickListener { recommend() }
        v.findViewById<Button>(R.id.btnFavorites)?.setOnClickListener { showFavorites() }
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
                toast("出错：${t.message}")
            }
        }
    }

    /** 后台线程里安全取 context：Fragment 已 detach 就返回 null */
    private fun ctx0(): android.content.Context? = try { context } catch (t: Throwable) { null }

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
        toast("搜索中…")
        bg {
            // 走统一入口：按设置决定 Modrinth / CurseForge(官方或后端) / 聚合
            val list = AggregateSearch.search(ctx, q, mc, ld)
            val routes = AggregateSearch.lastRoutes
            if (list.isEmpty() && Prefs.get(ctx).getBoolean(K.OFFLINE, false)) {
                toast("离线模式下无法搜索")
                return@bg
            }
            safePost(handler) {
                results.clear()
                results.addAll(list)
                resAdapter.notifyDataSetChanged()
                toast(
                    if (list.isEmpty()) "没有结果${if (routes.isNotBlank()) "（$routes）" else ""}"
                    else "找到 ${list.size} 个${if (routes.isNotBlank()) " · $routes" else ""}"
                )
                autoTranslate(list)
            }
        }
    }

    /** 已安装模组名（小写），用于推荐时过滤掉装过的 */
    private fun installedNames(): Set<String> {
        return try {
            val ctx = context ?: return emptySet()
            val dir = WorkDir.modsDir(ctx) ?: return emptySet()
            Fs.children(dir).mapNotNull { it.name }
                .filter { it.endsWith(".jar", true) }
                .map { it.substringBeforeLast(".").lowercase() }
                .toSet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    /** 长按收藏 / 取消收藏 */
    private fun toggleFav(mod: MarketMod) {
        val ctx = context ?: return
        val added = Favorites.toggle(ctx, mod)
        toast(if (added) "已收藏：${mod.name}" else "已取消收藏：${mod.name}")
        resAdapter.notifyDataSetChanged()
    }

    /** 推荐：按当前版本与加载器拉热门模组 */
    private fun recommend() {
        val ctx = context ?: return
        val mc = mcVersion()
        val ld = loader()
        toast("正在获取推荐…")
        bg {
            val list = AggregateSearch.recommend(ctx, mc, ld, installedNames())
            safePost(handler) {
                results.clear()
                results.addAll(list)
                resAdapter.notifyDataSetChanged()
                toast(if (list.isEmpty()) "没获取到推荐" else "推荐 ${list.size} 个")
            }
        }
    }

    /** 收藏夹 */
    private fun showFavorites() {
        val ctx = context ?: return
        val favs = Favorites.list(ctx)
        if (favs.isEmpty()) {
            toast("收藏夹是空的（长按搜索结果即可收藏）")
            return
        }
        results.clear()
        results.addAll(favs)
        resAdapter.notifyDataSetChanged()
        toast("收藏 ${favs.size} 个")
    }

    private fun translate(mod: MarketMod) {
        if (mod.summaryZh.isNotBlank()) return
        val c = ctx0() ?: return
        bg {
            val zh = OfflineTranslate.translate(c, mod.summary)
            if (zh == null) {
                toast("翻译失败：离线无匹配且网络不可用")
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
        val c = ctx0() ?: return
        if (!Prefs.get(c).getBoolean(K.AUTO_TRANS, true)) return
        bg {
            var n = 0
            for (m in list.take(10)) {
                val zh = OfflineTranslate.translate(c, m.summary)
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
            val url = when (mod.source) {
                "curseforge" -> {
                    headers = CurseForgeApi.authHeaders()
                    CurseForgeApi.downloadUrl(mod)
                }
                "backend" -> {
                    val fs = BackendApi.files(ctx, mod.id, mc, ld)
                    val f0 = fs.firstOrNull()
                    if (f0 != null) {
                        name = f0.name.ifBlank { f0.display }
                        BackendApi.absolute(ctx, f0.url)
                    } else ""
                }
                else -> {
                    val files = ModrinthApi.versions(mod.id, mc, ld)
                    val f0 = files.firstOrNull()
                    if (f0 != null) name = f0.fileName
                    f0?.url ?: ""
                }
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
