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

    // 搜索/推荐的结果单独存一份，切到收藏夹再切回来还在，
    // 不会像之前那样被 clear() 冲掉
    private val searchResults = mutableListOf<MarketMod>()
    private val favResults = mutableListOf<MarketMod>()

    /** 当前展示的列表（adapter 绑定它） */
    private val results = mutableListOf<MarketMod>()
    private val links = mutableListOf<MarkedLink>()
    private lateinit var resAdapter: MarketAdapter
    private lateinit var linkAdapter: LinkAdapter

    /** 当前页签：search=搜索结果 / fav=收藏夹 */
    private var currentTab = "search"
    private lateinit var tvListTitle: android.widget.TextView
    private lateinit var spSort: Spinner
    private lateinit var btnFav: Button

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

        tvListTitle = v.findViewById(R.id.tvListTitle)
        spSort = v.findViewById(R.id.spSort)
        btnFav = v.findViewById(R.id.btnFavorites)

        v.findViewById<Button>(R.id.btnSearch).setOnClickListener { search() }
        v.findViewById<Button>(R.id.btnRecommend)?.setOnClickListener { recommend() }
        v.findViewById<Button>(R.id.btnAddLink).setOnClickListener { addLinkDialog() }
        v.findViewById<Button>(R.id.btnOpenPage).setOnClickListener { openPageDialog() }

        // 收藏按钮 = 页签切换：在收藏夹和搜索结果之间来回切，
        // 两边各自保留，不会互相覆盖
        btnFav.setOnClickListener { toggleTab() }

        // 排序
        spSort.setSelection(0, false)
        spSort.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                p0: android.widget.AdapterView<*>?, p1: View?, pos: Int, p3: Long
            ) {
                applySort()
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }

        val p = Prefs.get(requireContext())
        etVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")
        reloadLinks()
        return v
    }

    override fun onResume() {
        super.onResume()
        // 首次进入商店页自动跑一次推荐，让页面一打开就有内容（不是空白）
        // 只在「本次打开应用后的第一次」执行，之后进出不再自动跑
        val ctx = context ?: return
        if (!Prefs.get(ctx).getBoolean(K.FIRST_MARKET_VISIT, false)) {
            Prefs.get(ctx).edit().putBoolean(K.FIRST_MARKET_VISIT, true).apply()
            if (results.isEmpty()) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isAdded && results.isEmpty()) recommend()
                }, 400)
            }
        }
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
        // 分批：先清空搜索结果列表，但每个源回来就立刻追加显示，
        // 不会因为某一个源慢或挂掉而整页卡住。
        // 结果存在 searchResults 里，切到收藏夹再回来依然在。
        searchResults.clear()
        currentTab = "search"
        refreshList()
        AggregateSearch.searchStreaming(ctx, q, mc, ld) { batch, source, finished ->
            if (!isAdded) return@searchStreaming
            if (batch.isNotEmpty()) {
                searchResults.addAll(batch)
                refreshList()
                toast("$source 返回 ${batch.size} 个（共 ${searchResults.size}）")
                autoTranslate(batch)
            }
            if (finished) {
                val routes = AggregateSearch.lastRoutes
                if (searchResults.isEmpty()) {
                    toast("没有结果${if (routes.isNotBlank()) "（$routes）" else ""}")
                } else {
                    toast("共 ${searchResults.size} 个${if (routes.isNotBlank()) " · $routes" else ""}")
                }
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
                searchResults.clear()
                searchResults.addAll(list)
                currentTab = "search"
                refreshList()
                toast(if (list.isEmpty()) "没获取到推荐" else "推荐 ${list.size} 个")
                if (list.isNotEmpty()) autoTranslate(list)
            }
        }
    }

    /** 收藏夹页签：加载并显示，搜索结果原样保留在内存里 */
    private fun showFavorites() {
        val ctx = context ?: return
        favResults.clear()
        favResults.addAll(Favorites.list(ctx))
        if (favResults.isEmpty()) {
            toast("收藏夹是空的（长按搜索结果即可收藏）")
        } else {
            toast("收藏 ${favResults.size} 个")
        }
        currentTab = "fav"
        refreshList()
    }

    /** 切回搜索结果页签 */
    private fun showSearchTab() {
        currentTab = "search"
        refreshList()
    }

    /** 两个页签之间来回切 */
    private fun toggleTab() {
        if (currentTab == "search") showFavorites()
        else showSearchTab()
    }

    /**
     * 把当前页签对应的列表灌进展示列表，并应用排序。
     * 搜索结果和收藏各自独立存放，切换不会丢。
     */
    private fun refreshList() {
        val src = if (currentTab == "search") searchResults else favResults
        results.clear()
        results.addAll(src)
        applySort()
        // 标题随页签变，让用户知道自己在看哪个列表
        tvListTitle.text = if (currentTab == "search")
            getString(R.string.tab_search_results) + "（${results.size}）"
        else
            getString(R.string.tab_favorites) + "（${results.size}）"
        btnFav.text = if (currentTab == "search")
            getString(R.string.tab_favorites)
        else
            getString(R.string.tab_search_results)
    }

    /** 排序：下载量 / 更新时间 / 名称 / 原顺序 */
    private fun applySort() {
        when (spSort.selectedItemPosition) {
            0 -> results.sortByDescending { it.downloads }
            1 -> results.sortByDescending { it.updated }
            2 -> results.sortWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            else -> {
                // 相关度 = 数据源返回的原顺序，先恢复原序再排序没意义，
                // 这里按来源原始顺序：用 searchResults/favResults 的顺序重灌
                val src = if (currentTab == "search") searchResults else favResults
                results.clear()
                results.addAll(src)
            }
        }
        resAdapter.notifyDataSetChanged()
    }

    private fun translate(mod: MarketMod) {
        if (mod.summaryZh.isNotBlank()) return
        val c = ctx0() ?: return
        OfflineTranslate.translate(c, mod.summary) { zh ->
            if (zh == null) {
                toast("翻译失败：模型还没下载好，或离线且无本地匹配")
                return@translate
            }
            mod.summaryZh = zh
            safePost(handler) {
                val i = results.indexOf(mod)
                if (i >= 0) resAdapter.notifyItemChanged(i)
            }
        }
    }

    /**
     * 自动翻译。
     *
     * 之前只翻前 10 条、而且是同时并发发出去的：
     *   - 10 条以后的永远不翻 → 看起来"有的翻了有的没翻"
     *   - 并发调用同一个翻译引擎，后面的容易失败
     * 现在改成**串行队列**，逐条翻，翻多少取决于实际条数，
     * 每条翻完单独刷新，失败的不影响后面的继续。
     */
    private fun autoTranslate(list: List<MarketMod>) {
        val c = ctx0() ?: return
        if (!Prefs.get(c).getBoolean(K.AUTO_TRANS, true)) return
        val targets = list.filter { it.summary.isNotBlank() && it.summaryZh.isBlank() }
        if (targets.isEmpty()) return

        fun next(i: Int) {
            if (i >= targets.size || !isAdded) return
            val m = targets[i]
            OfflineTranslate.translate(c, m.summary) { zh ->
                if (zh != null) m.summaryZh = zh
                // 翻一条刷一条，用户能看着逐步出中文
                safePost(handler) {
                    val idx = results.indexOf(m)
                    if (idx >= 0) resAdapter.notifyItemChanged(idx)
                }
                next(i + 1)
            }
        }
        next(0)
    }

    /**
     * 安装模组。
     *
     * CurseForge 特殊处理：它的下载要先过「读秒页面」，
     * 直接拿 API 给的地址去下载，下到的只是一个 HTML。
     * 所以这里改成：后台开一个隐藏页面等它读秒，
     * 真实地址出现时截获，再拿去下载（用户全程不用盯着）。
     */
    private fun install(mod: MarketMod) {
        val ctx = requireContext()
        val mc = mcVersion()
        val ld = loader()

        if (mod.source == "curseforge") {
            val page = DelayedDownload.curseForgePage(mod)
            if (page.isBlank()) {
                toast("没有下载地址")
                return
            }
            toast("CurseForge 需要等待读秒，正在后台获取真实地址…")
            DelayedDownload.capture(
                ctx, page,
                onGot = { real ->
                    val n = mod.fileName.ifBlank { Downloader.guessName(real) }
                    val dlg = ProgressDialog.show(ctx, n)
                    bg {
                        val dir = Targets.modsDir(ctx)
                        val f = if (dir == null) null
                        else Downloader.download(ctx, real, dir, n) { done, total ->
                            safePost(handler) { dlg.update(done, total) }
                        }
                        safePost(handler) {
                            dlg.dismiss()
                            toast(if (f == null) "下载失败" else "已安装：${f.name}")
                        }
                        if (f != null) Notifier.show(ctx, "下载完成", mod.name)
                    }
                },
                onFail = { why ->
                    toast("没拿到下载地址（$why），改用直连试试…")
                    // 兜底：还是用 API 给的地址试一次
                    val u = CurseForgeApi.downloadUrl(mod)
                    if (u.isBlank()) {
                        toast("下载失败")
                        return@capture
                    }
                    val n = mod.fileName.ifBlank { Downloader.guessName(u) }
                    val dlg = ProgressDialog.show(ctx, n)
                    bg {
                        val dir = Targets.modsDir(ctx)
                        val f = if (dir == null) null
                        else Downloader.download(
                            ctx, u, dir, n, CurseForgeApi.authHeaders()
                        ) { done, total -> safePost(handler) { dlg.update(done, total) } }
                        safePost(handler) {
                            dlg.dismiss()
                            toast(if (f == null) "下载失败" else "已安装：${f.name}")
                        }
                    }
                }
            )
            return
        }

        toast("准备下载…")
        bg {
            var name = ""
            val headers: Map<String, String> = emptyMap()
            val url = when (mod.source) {
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
            val n = name
            safePost(handler) {
                val dlg = ProgressDialog.show(ctx, n)
                bg {
                    val dir = Targets.modsDir(ctx)
                    val f = if (dir == null) null
                    else Downloader.download(ctx, url, dir, n, headers) { done, total ->
                        safePost(handler) { dlg.update(done, total) }
                    }
                    safePost(handler) {
                        dlg.dismiss()
                        toast(if (f == null) "下载失败" else "已安装：${f.name}")
                        if (f != null) Notifier.show(ctx, "下载完成", mod.name)
                    }
                }
            }
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
