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

    // 搜索/推荐的结果存在 MarketState 单例里：
    // 底部导航是 replace()，切页签时本 Fragment 会被销毁重建，
    // 结果放在实例字段里会全部清零（表现为"切回来列表空了"）。
    private val searchResults get() = MarketState.searchResults
    private val favResults get() = MarketState.favResults

    /** 当前展示的列表（adapter 绑定它） */
    private val results = mutableListOf<MarketMod>()
    private val links = mutableListOf<MarkedLink>()
    private lateinit var resAdapter: MarketAdapter
    private lateinit var linkAdapter: LinkAdapter

    /** 当前页签：search=搜索结果 / fav=收藏夹（存在单例里，切页不丢） */
    private var currentTab: String
        get() = MarketState.tab
        set(v) { MarketState.tab = v }

    // ---- 分页 ----
    // 之前只能拿第一页（默认 10~20 条），翻不到后面，
    // 而 Modrinth/CurseForge 都支持 offset。这里记录当前偏移，
    // 滑到底自动再拉一页，直到源返回空为止。
    private val PAGE_SIZE = 20
    private var searchOffset: Int
        get() = MarketState.offset
        set(v) { MarketState.offset = v }
    private var hasMore: Boolean
        get() = MarketState.hasMore
        set(v) { MarketState.hasMore = v }
    private var loadingMore = false
    private var lastQuery: String
        get() = MarketState.query
        set(v) { MarketState.query = v }
    private var lastMc: String
        get() = MarketState.mc
        set(v) { MarketState.mc = v }
    private var lastLoader: String
        get() = MarketState.loader
        set(v) { MarketState.loader = v }
    private lateinit var tvListTitle: android.widget.TextView

    /**
     * 分类筛选行。
     *
     * ⚠️ 之前卡片上会画出分类标签，但那些标签**只是装饰**：点了没反应，
     * 用户没法按"只要优化类"来筛。这里按当前结果里实际出现的分类
     * 动态生成可点标签，点一下选中、再点取消，可多选（同时满足）。
     */
    private lateinit var rowFilter: android.widget.HorizontalScrollView
    private lateinit var chipBox: android.widget.LinearLayout
    private val activeCats = LinkedHashSet<String>()
    /** 一行最多放几个分类标签，其余收进「更多」 */
    private val CHIP_LIMIT = 10
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
        // 带加载器图标的下拉
        LoaderSpinner.attachByPref(spLoader)
        rvMods = v.findViewById(R.id.rvMods)
        rvLinks = v.findViewById(R.id.rvLinks)

        resAdapter = MarketAdapter(
            results,
            { m -> install(m) },
            { m -> openPage(m.pageUrl) },
            { m -> translate(m) },
            { m -> toggleFav(m) },
            { m -> Favorites.has(requireContext(), m) },
            // 已安装：下载完会把名字加进 installedSet 并刷新，
            // 卡片按钮变成"已安装"，不用再自己去目录里确认
            { m -> isInstalled(m) },
            // 当前加载器用 lambda 传：切下拉后 notifyDataSetChanged 就能重画，
            // 不用重建 Adapter（重建会丢滚动位置）
            { loader() },
            // 明知道加载器不匹配仍要装：确认后走同一个 install，
            // 但**不经过**批量下载路径，所以不会打断批量任务
            { m -> install(m) }
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
        // 筛选行插在标题下面：没有分类时整行隐藏，不留空白
        //
        // ⚠️ 之前插在 tvListTitle.parent，也就是**标题那一行**。
        // 那行是 horizontal 的，塞一个 MATCH_PARENT 的子 View 进去，
        // 会把同一行右边的「排序」标签和排序下拉整个挤出屏幕 ——
        // 表现就是"排序功能没了"。
        // 而且 WrapRow 会自动换行，分类一多就叠成好几行，
        // 把下面的列表挤到只剩一条缝（"屏幕挡完了"）。
        //
        // 现在两处都改：
        //   1. 插到标题行的**外层**（垂直容器），独占一行，不再挤走排序；
        //   2. 容器换成横向滚动的单行，高度恒定，分类再多也不会挡屏幕，
        //      放不下的收进「更多」里。
        //
        chipBox = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val pd = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, pd, 0, pd)
        }
        rowFilter = android.widget.HorizontalScrollView(requireContext()).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            addView(
                chipBox,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val titleRow = tvListTitle.parent as? android.widget.LinearLayout
        val outer = titleRow?.parent as? android.widget.LinearLayout
        (outer ?: titleRow)?.let { host ->
            val anchor = if (outer != null) titleRow!! else tvListTitle
            val idx = host.indexOfChild(anchor)
            host.addView(
                rowFilter, idx + 1,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
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
                resAdapter.notifyDataSetChanged()
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }

        val p = Prefs.get(requireContext())
        etVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")
        // ⚠️ 切页签回来时 Fragment 是**新建**的，
        // 之前什么都不做 → 列表空 → 用户看到"搜索结果（0）"，
        // 以为刚才白搜了。这里把单例里存的结果灌回去。
        if (MarketState.hasContent()) refreshList()
        reloadLinks()
        return v
    }

    override fun onResume() {
        super.onResume()
        // 回到页面时重扫一次 mods 目录：
        // 用户可能在别处删了 jar、或者用别的途径装了模组，
        // 不重扫的话"已安装"标记会和真实情况对不上。
        installedSet = null
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
                toast("出错：${Err.humanMessage(t)}")
            } finally {
                // 同 MigrationFragment：Progress 是全局单例，
                // 异常/提前 return 时不复位会永久卡在"运行中"，
                // 下次开 App 出现幽灵进度条。
                try { Progress.done() } catch (_: Throwable) {}
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
        MarketState.offsets.clear()
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
                refreshList(batch.size)
                toast("$source 返回 ${batch.size} 个（共 ${searchResults.size}）")
                autoTranslate(batch)
            }
            if (finished) {
                val routes = AggregateSearch.lastRoutes
                if (searchResults.isEmpty()) {
                    toast("没有结果${if (routes.isNotBlank()) "（$routes）" else ""}")
                } else {
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
            ctx, lastQuery, lastMc, lastLoader, searchOffset, PAGE_SIZE,
            { batch, source, finished ->
            if (!isAdded) return@searchStreaming
            if (batch.isNotEmpty()) {
                //
                // 跨页去重：AggregateSearch 内部的 seen 只在**本轮**生效，
                // 翻页时是新的一轮，上一页已经显示过的同名模组会再来一次。
                // 用户看到的就是"往下翻出现一堆重复的"。
                //
                val fresh = batch.filter { m ->
                    val k = m.name.trim().lowercase()
                    k.isNotBlank() && searchResults.none { it.name.trim().lowercase() == k }
                }
                if (fresh.isNotEmpty()) {
                    searchResults.addAll(fresh)
                    refreshList(fresh.size)
                    toast("$source 又返回 ${fresh.size} 个（共 ${searchResults.size}）")
                    autoTranslate(fresh)
                }
                roundGot += batch.size
            }
            if (finished) {
                loadingMore = false
                if (roundGot == 0) hasMore = false
                updateLoadMoreHint()
            }
            // 各源自己的偏移：保证每个源只从自己上次的位置往后翻，
            // 见 MarketState.offsets 的说明。
            },
            MarketState.offsets.toMap()
        )
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

    /**
     * 已安装文件名缓存（小写、去扩展名）。
     *
     * 为什么不每次都去读目录：卡片 bind 时每一行都会问一次"装没装"，
     * 每次都 Fs.children() 等于滑一屏做几十次 SAF 查询，会卡。
     * 装完新模组时手动加进集合即可；目录在外面被改动时调
     * [refreshInstalled] 重扫。
     */
    private var installedSet: MutableSet<String>? = null

    private fun installedNow(): MutableSet<String> {
        installedSet?.let { return it }
        val s = try {
            val c = context
            val dir = if (c == null) null else (WorkDir.modsDir(c) ?: Targets.modsDir(c))
            if (dir == null) mutableSetOf()
            else Fs.children(dir)
                .filter { it.name?.endsWith(".jar", true) == true }
                .mapNotNull { it.name?.substringBeforeLast(".")?.lowercase() }
                .toMutableSet()
        } catch (t: Throwable) {
            mutableSetOf()
        }
        installedSet = s
        return s
    }

    /** 卡片用：这个模组是不是已经装过了 */
    private fun isInstalled(m: MarketMod): Boolean {
        val key = m.fileName.substringBeforeLast(".").lowercase()
        if (key.isBlank()) return false
        return key in installedNow()
    }

    /**
     * 下载完成后调用。
     *
     * ⚠️ 之前下载完**没有任何反馈**：按钮还是"安装"，
     * 用户不知道到底装没装成，只能自己去目录里翻 ——
     * 这就是反馈里说的"没法正常标记"。
     */
    private fun markInstalled(fileName: String?) {
        // DocumentFile.name 是可空的，这里收一下可空性
        val key = fileName?.substringBeforeLast(".")?.lowercase()
        if (!key.isNullOrBlank()) installedNow().add(key)
        safePost(handler) { resAdapter.notifyDataSetChanged() }
    }

    /** 目录可能在别处被改过（比如手动删了 jar），重新扫一遍 */
    private fun refreshInstalled() {
        installedSet = null
        safePost(handler) { resAdapter.notifyDataSetChanged() }
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
                // ⚠️ 之前只说一句"没获取到推荐"。
                // 但空结果的原因差别很大：是开关关了、断网了、
                // CurseForge 没填 Key，还是三个源都失败了？
                // 不说清楚，用户只能一遍遍点，以为功能坏了。
                if (list.isEmpty()) {
                    toast(recommendEmptyReason(ctx))
                } else {
                    toast("推荐 ${list.size} 个")
                    autoTranslate(list)
                }
            }
        }

    }

    /** 推荐为空时，说清楚到底是为什么 */
    private fun recommendEmptyReason(ctx: android.content.Context): String {
        val p = Prefs.get(ctx)
        if (p.getBoolean(K.OFFLINE, false)) return "已开启离线模式，推荐需要联网"
        if (p.getBoolean(K.OFFLINE_SEARCH, false)) return "已在设置里单独关闭了商店联网"
        if (!p.getBoolean(K.RECOMMEND, true)) return "推荐开关是关的（设置 → 搜索里打开）"
        val routes = AggregateSearch.lastRoutes
        if (routes.isNotBlank()) return "没获取到推荐（$routes）"
        return "没获取到推荐：各来源都没返回数据，检查网络或到设置里配置来源"
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
    /**
     * 刷新列表。
     *
     * ⚠️ 之前每来一批就整表重排 + 全量重绘。
     * 翻页时批次多、列表已经很长，一次 notifyDataSetChanged 会把
     * 屏幕上所有卡片全部重新绑定一遍（图片重新解码、图标重新染
     * 色），滑动和加载都跟着卡。
     *
     * 现在按需分发：只追加新条目时用 `notifyItemRangeInserted`，
     * 老卡片原地不动；只有排序/筛选条件真的变了才整体重排。
     *
     * @param appended 本次新增的条目数（>0 时走增量插入）
     * @param structural 排序或筛选条件变了，必须整表重排
     */
    private fun refreshList(appended: Int = 0, structural: Boolean = false) {
        val src = if (currentTab == "search") searchResults else favResults
        val filtered = if (activeCats.isEmpty()) src else src.filter { m ->
            val cs = m.categories.map { it.lowercase() }
            // 多选之间是「且」：选了「优化 + 科技」就只留两类都占的
            activeCats.all { a -> cs.any { it.contains(a.lowercase()) } }
        }
        //
        // 增量插入的前提是"顺序本来就是对的"。
        // 只有选了「相关度（原顺序）」才成立；选了下载量/更新时间/名称时，
        // 新追加的一批是按数据源顺序塞进去的，会把排好的结果搅乱 ——
        // 表现就是"排序没了"。所以非原序时一律走整表重排。
        //
        val keepOrder = ::spSort.isInitialized && spSort.selectedItemPosition == 3
        if (!structural && appended > 0 && activeCats.isEmpty() && keepOrder &&
            results.size + appended == filtered.size
        ) {
            val start = results.size
            val add = filtered.subList(start, filtered.size).toMutableList()
            results.addAll(add)
            resAdapter.notifyItemRangeInserted(start, add.size)
            updateTitle()
            updateFilterChips()
            return
        }
        results.clear()
        results.addAll(filtered)
        applySort()
        resAdapter.notifyDataSetChanged()
        updateTitle()
        updateFilterChips()
    }

    /**
     * 按当前结果里实际出现的分类生成可点标签。
     *
     * 只列**结果里真的有**的分类：列一堆点了之后啥也没有的标签，
     * 比没有筛选更让人困惑。
     */
    private fun updateFilterChips() {
        if (!::rowFilter.isInitialized) return
        val counts = LinkedHashMap<String, Int>()
        val src = if (currentTab == "search") searchResults else favResults
        for (m in src) {
            for (c in m.categories) {
                if (c.isBlank()) continue
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        // 按出现次数排序，多的排前面
        val keys = counts.entries.sortedByDescending { it.value }.map { it.key }
        chipBox.removeAllViews()
        if (keys.isEmpty()) {
            rowFilter.visibility = View.GONE
            return
        }
        rowFilter.visibility = View.VISIBLE
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density

        // 「全部」：一键清掉筛选
        chipBox.addView(chip(ctx, d, "全部", activeCats.isEmpty()) {
            activeCats.clear()
            refreshList(structural = true)
        })

        // 选中的一定排在最前，否则筛了一半之后，
        // 已选的标签可能被挤到「更多」里看不见，像是筛选失效了。
        val ordered = keys.sortedWith(compareBy({ !activeCats.contains(it) }, { keys.indexOf(it) }))
        val shown = ordered.take(CHIP_LIMIT)
        for (k in shown) {
            val on = activeCats.contains(k)
            chipBox.addView(chip(ctx, d, "$k ${counts[k]}", on) {
                if (on) activeCats.remove(k) else activeCats.add(k)
                refreshList(structural = true)
            })
        }
        // 放不下的收进「更多」，弹窗里全列出并可多选
        if (ordered.size > shown.size) {
            chipBox.addView(chip(ctx, d, "更多 ${ordered.size - shown.size}", false) {
                showAllCats(ctx, ordered, counts)
            })
        }
    }

    /** 「更多」弹窗：列出全部分类，可多选 */
    private fun showAllCats(
        ctx: android.content.Context, keys: List<String>, counts: Map<String, Int>
    ) {
        val labels = keys.map { "$it（${counts[it] ?: 0}）" }.toTypedArray()
        val checked = keys.map { activeCats.contains(it) }.toBooleanArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("分类筛选（可多选，同时满足）")
            .setMultiChoiceItems(labels, checked) { _, w, isChecked ->
                if (isChecked) activeCats.add(keys[w]) else activeCats.remove(keys[w])
            }
            .setPositiveButton("应用") { _, _ -> refreshList(structural = true) }
            .setNeutralButton("清空") { _, _ ->
                activeCats.clear()
                refreshList(structural = true)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> refreshList(structural = true) }
            .show()
    }

    private fun chip(
        ctx: android.content.Context, d: Float, text: String, on: Boolean, click: () -> Unit
    ): android.widget.TextView {
        return android.widget.TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setPadding((d * 7).toInt(), (d * 3).toInt(), (d * 7).toInt(), (d * 3).toInt())
            val tint = try {
                if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(
                    if (on) R.color.primary else R.color.textSecondary
                ) else @Suppress("DEPRECATION") ctx.resources.getColor(
                    if (on) R.color.primary else R.color.textSecondary
                )
            } catch (t: Throwable) { android.graphics.Color.GRAY }
            setTextColor(tint)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(
                    if (on) tint and 0x00FFFFFF or 0x22000000
                    else android.graphics.Color.TRANSPARENT
                )
                cornerRadius = d * 10
                setStroke((d * 1).toInt().coerceAtLeast(1), tint)
            }
            setOnClickListener { click() }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (d * 5).toInt(); topMargin = (d * 3).toInt() }
        }
    }

    private fun updateTitle() {
        if (!::tvListTitle.isInitialized) return
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
     * CurseForge 特殊处理：它的下载页要先过「读秒」，
     * 直接拿 API 给的地址去下载，下到的往往只是一个 HTML 页面
     * ——代码却当成成功，装进 mods 目录的是个废文件。
     *
     * ⚠️ 联网核实后确认：官方有
     *   `GET /v1/mods/{modId}/files/{fileId}/download-url`
     * 直接返回真实直链，**不用读秒**。所以顺序改成：
     *   ① 后台调该端点拿直链（快，且不依赖 WebView）
     *   ② 拿不到才用 CDN 公式直连
     *   ③ 再不行才开隐藏 WebView 等读秒（最慢，兜底）
     * 之前的版本**反过来**：一上来就等读秒，慢且经常等不到。
     */
    private fun install(mod: MarketMod) {
        val ctx = requireContext()
        val mc = mcVersion()
        val ld = loader()

        if (mod.source == "curseforge") {
            toast("正在获取下载地址…")
            bg {
                // ①② 都在后台做，拿到地址后再回到主线程起进度条
                val direct = CurseForgeApi.fetchDownloadUrl(mod)
                if (direct.isNotBlank()) {
                    safePost(handler) { startCfDownload(mod, direct) }
                    return@bg
                }
                // ③ 兜底：隐藏 WebView 等读秒
                val page = DelayedDownload.curseForgePage(mod)
                if (page.isBlank()) {
                    safePost(handler) { toast("没有下载地址") }
                    return@bg
                }
                safePost(handler) {
                    toast("正在后台等待 CurseForge 读秒…")
                    DelayedDownload.capture(
                        ctx, page,
                        onGot = { real -> startCfDownload(mod, real) },
                        onFail = { why ->
                            toast("没拿到下载地址（$why）")
                        }
                    )
                }
            }
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
                        if (f == null) {
                            toast("下载失败")
                        } else {
                            toast("已安装：${f.name}")
                            markInstalled(f.name)
                            Notifier.show(ctx, "下载完成", mod.name)
                        }
                    }
                }
            }
        }
    }

    /**
     * 拿到真实地址后开始下载（CurseForge 专用）。
     *
     * 抽成一个方法是因为三条取址路径
     * （download-url 端点 / CDN 公式 / WebView 等读秒）
     * 最后都要走同一套下载流程，之前是三份重复代码，
     * 其中有两份**不看返回值就报"已安装"**，失败了也说成功。
     */
    private fun startCfDownload(mod: MarketMod, url: String) {
        val ctx = context ?: return
        val n = mod.fileName.ifBlank { Downloader.guessName(url) }
        val dlg = ProgressDialog.show(ctx, n)
        bg {
            val dir = Targets.modsDir(ctx)
            val f = if (dir == null) null
            else Downloader.download(
                ctx, url, dir, n, CurseForgeApi.authHeaders()
            ) { done, total ->
                safePost(handler) { dlg.update(done, total) }
            }
            safePost(handler) {
                dlg.dismiss()
                if (f == null) {
                    toast("下载失败")
                } else {
                    toast("已安装：${f.name}")
                    // 标记出来，卡片按钮变成"已安装"
                    markInstalled(f.name)
                    Notifier.show(ctx, "下载完成", mod.name)
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
