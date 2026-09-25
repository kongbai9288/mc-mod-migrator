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
import android.widget.LinearLayout
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

    // ---- 分页 ----
    // 之前只能拿第一页（默认 10~20 条），翻不到后面，
    // 而 Modrinth/CurseForge 都支持 offset。这里记录当前偏移，
    // 滑到底自动再拉一页，直到源返回空为止。
    private var searchOffset = 0
    private val PAGE_SIZE = 20
    private var hasMore = false
    private var loadingMore = false
    private var lastQuery = ""
    private var lastMc = ""
    private var lastLoader = ""
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

        // 滑到列表底部自动加载下一页
        rvMods.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return   // 只在上滑时触发
                val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
                val last = lm.findLastVisibleItemPosition()
                val total = lm.itemCount
                // 距底部还剩 3 条时就开始预取，别等用户真的滑到底
                if (last >= total - 3) loadMore()
            }
        })
        v.findViewById<Button>(R.id.btnSearch).setOnClickListener { search() }
        v.findViewById<Button>(R.id.btnRecommend)?.setOnClickListener { recommend() }
        v.findViewById<Button>(R.id.btnAddLink).setOnClickListener { addLinkDialog() }
        v.findViewById<Button>(R.id.btnOpenPage).setOnClickListener { openPageDialog() }
        v.findViewById<Button>(R.id.btnShare)?.setOnClickListener { shareWithPosition() }

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
                     Err.ignore(t, "界面已销毁，不弹")
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
        val raw = etQuery.text.toString().trim()
        if (raw.isBlank()) {
            toast("请输入关键词")
            return
        }
        // 中文/简称 → 英文：Modrinth、CurseForge 只认英文关键词，
        // 直接拿中文去查只会返回空结果。
        val q = ModAliases.translate(raw)
        val hint = ModAliases.hint(raw)
        val mc = mcVersion()
        val ld = loader()
        val ctx = requireContext()
        toast("搜索中…$hint")
        // 分批：先清空搜索结果列表，但每个源回来就立刻追加显示，
        // 不会因为某一个源慢或挂掉而整页卡住。
        // 结果存在 searchResults 里，切到收藏夹再回来依然在。
        searchResults.clear()
        currentTab = "search"
        // 新一轮搜索：偏移归零，并记录这次的查询条件供"加载更多"复用
        searchOffset = 0
        hasMore = true
        // 本轮各源累计返回了多少条。
        // 之前用「最后一个源的批次是否为空」来判断还有没有下一页——
        // 但最后一个源可能因为覆盖不到这个关键词而返回空，
        // 其他源明明还有结果，却被误判成"已全部加载"，翻页直接断掉。
        var roundGot = 0
        lastQuery = q
        lastMc = mc
        lastLoader = ld
        refreshList()
        AggregateSearch.searchStreaming(ctx, q, mc, ld) { batch, source, finished ->
            if (!isAdded) return@searchStreaming
            if (batch.isNotEmpty()) {
                searchResults.addAll(batch)
                roundGot += batch.size
                refreshList()
                toast("$source 返回 ${batch.size} 个（共 ${searchResults.size}）")
                autoTranslate(batch)
            }
            if (finished) {
                val routes = AggregateSearch.lastRoutes
                if (searchResults.isEmpty()) {
                    toast("没有结果${if (routes.isNotBlank()) "（$routes）" else ""}")
                } else {
                    // 按「本轮实际拿到的条数」推进偏移。
                    // 之前固定加 PAGE_SIZE：源返回不足一页时，
                    // 偏移会跳过中间那段没返回的数据，翻页会漏条目。
                    val advance = roundGot.coerceAtLeast(1)
                    searchOffset += advance
                    // 本轮一条都没拿到 → 后面也不会有了
                    if (roundGot == 0) hasMore = false
                    val moreHint = if (hasMore) "，下滑加载更多" else "（已全部加载）"
                    toast("共 ${searchResults.size} 个${moreHint}${if (routes.isNotBlank()) " · $routes" else ""}")
                    updateLoadMoreHint()
                }
            }
        }
    }

    /**
     * 加载下一页。
     *
     * 用 offset 翻页，而不是重新搜一遍——重新搜会把已有结果冲掉。
     * 各源独立返回，和首次搜索走同一条流式通道。
     */
    private fun loadMore() {
        if (loadingMore || !hasMore) return
        if (lastQuery.isBlank()) return
        loadingMore = true
        val ctx = context ?: run { loadingMore = false; return }
        // 同样按「本轮实际拿到多少条」推进，不用固定页大小
        var roundGot = 0
        AggregateSearch.searchStreaming(
            ctx, lastQuery, lastMc, lastLoader, searchOffset, PAGE_SIZE
        ) { batch, source, finished ->
            if (!isAdded) return@searchStreaming
            if (batch.isNotEmpty()) {
                searchResults.addAll(batch)
                roundGot += batch.size
                refreshList()
                toast("$source 又返回 ${batch.size} 个（共 ${searchResults.size}）")
                autoTranslate(batch)
            }
            if (finished) {
                loadingMore = false
                if (roundGot == 0) hasMore = false
                else searchOffset += roundGot
                updateLoadMoreHint()
            }
        }
    }

    /** 列表底部提示：还有更多 / 已全部加载 / 加载中 */
    private fun updateLoadMoreHint() {
        if (!::tvListTitle.isInitialized) return
        if (currentTab != "search") return
        val suffix = when {
            loadingMore -> " · 加载中…"
            hasMore -> " · 下滑加载更多"
            else -> ""
        }
        tvListTitle.text = getString(R.string.tab_search_results) + "（${results.size}）$suffix"
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
        openPageWith(url, "")
    }

    /**
     * 打开模组页并记录"看到哪儿"。
     * 之后分享时可以把这个位置一起带过去。
     */
    private fun openPageWith(url: String, title: String) {
        val ctx = requireContext()
        // 记录上次查看位置（分享时可带上）
        Store.markViewed(ctx, url, title.ifBlank { url })
        val i = Intent(ctx, ModPageActivity::class.java)
        i.putExtra("url", url)
        startActivity(i)
    }

    /**
     * 分享：带上"上次看到哪儿"。
     *
     * 收到的人（或你的另一台设备）打开这条分享，能直接回到同一个页面，
     * 不用再从一堆搜索结果里重新找。
     */
    private fun shareWithPosition() {
        val ctx = requireContext()
        val v = Store.lastViewed(ctx)
        if (v == null) {
            toast("还没有查看过任何模组页")
            return
        }
        val (url, title, _) = v
        val text = buildString {
            append("我在看这个模组：").append(title.ifBlank { url }).append('\n')
            append(url).append('\n')
            append("（用 ModMigrator 打开可直接回到这个页面）")
        }
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ModMigrator 分享")
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(i, "分享到（蓝牙/附近分享/其他应用）")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        } catch (t: Throwable) {
            toast("分享失败")
        }
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
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
