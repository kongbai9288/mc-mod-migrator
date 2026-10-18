package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import coil.compose.AsyncImage

/**
 * 商店（模组市场）。
 *
 * 渲染改成 Compose，搜索/推荐/安装/翻译的业务逻辑原样保留。
 *
 * 几条容易踩的坑，写在对应的位置：
 * - 结果存在 [MarketState] 单例里，不放 Fragment 字段（底部导航是 replace，
 *   切页签会销毁重建，放字段等于白搜）
 * - 翻页偏移按来源分别记（Modrinth 的 20 和 CurseForge 的 20 不是一回事）
 * - 已安装判断走缓存集合，卡片每次绑定都去读目录会卡
 */
class MarketFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { MarketScreen(ctx) } }
        }
    }
}

/** 本页所有弹窗。用一个状态位驱动，不在回调里直接 show。 */
private sealed class MktDlg {
    object None : MktDlg()
    object Filter : MktDlg()
    data class Cats(val keys: List<String>, val counts: Map<String, Int>) : MktDlg()
    object AddLink : MktDlg()
    object OpenPage : MktDlg()
    data class Mismatch(val mod: MarketMod, val supported: String, val current: String) : MktDlg()
}

private val SORT_OPTS = listOf("下载量", "更新时间", "名称", "相关度")
private val CHIP_LIMIT = 10
private val PAGE_SIZE = 20

@Composable
private fun MarketScreen(ctx: Context) {
    val results = remember { mutableStateListOf<MarketMod>() }
    var rev by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf(MarketState.query) }

    val loaderOpts = remember {
        Loaders.visible(Prefs.get(ctx).getBoolean(K.SHOW_EXTRA_LOADERS, false))
    }
    var loaderIdx by remember {
        val cur = Loaders.normalize(MarketState.loader.ifBlank { "auto" })
        mutableStateOf(loaderOpts.indexOf(cur).let { if (it < 0) 0 else it })
    }
    var version by remember { mutableStateOf(MarketState.mc) }
    var sortIdx by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(MarketState.tab) }
    var activeCats by remember { mutableStateOf(setOf<String>()) }
    var catList by remember { mutableStateOf(listOf<Pair<String, Int>>()) }
    val links = remember { mutableStateListOf<MarkedLink>() }
    var busy by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(MarketState.hasMore) }
    var loadingMore by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(setOf<String>()) }
    var scanRev by remember { mutableStateOf(0) }
    var dlg by remember { mutableStateOf<MktDlg>(MktDlg.None) }
    var hint by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    fun toast(s: String) {
        Bg.ui.post {
            runCatching { Tips.short(ctx, s) }
            hint = s
        }
    }

    fun bg(block: () -> Unit) {
        Bg.io.execute {
            try {
                block()
            } catch (t: Throwable) {
                toast("出错：${Err.humanMessage(t)}")
            } finally {
                // Progress 是全局单例，异常时不复位会永久卡在"运行中"
                try { Progress.done() } catch (_: Throwable) {}
            }
        }
    }

    fun curLoader(): String =
        loaderOpts.getOrNull(loaderIdx)?.let { Loaders.normalize(it) } ?: "auto"

    fun mcVersion(): String =
        version.ifBlank { Prefs.get(ctx).getString(K.DEF_VERSION, "") ?: "" }

    // 已安装文件名（小写、去扩展名）。缓存起来：卡片绑定时每行都问一次，
    // 每次都去读目录等于滑一屏做几十次 SAF 查询。
    fun reloadInstalled() {
        Bg.io.execute {
            val s = try {
                val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
                if (dir == null) emptySet()
                else Fs.children(dir)
                    .filter { it.name?.endsWith(".jar", true) == true }
                    .mapNotNull { it.name?.substringBeforeLast(".")?.lowercase() }
                    .toSet()
            } catch (_: Throwable) {
                emptySet<String>()
            }
            Bg.ui.post { installed = s }
        }
    }

    fun reloadLinks() {
        links.clear()
        links.addAll(Store.links(ctx))
    }

    fun isInstalled(m: MarketMod): Boolean {
        val k = m.fileName.substringBeforeLast(".").lowercase()
        return k.isNotBlank() && k in installed
    }

    /** 按当前结果里实际出现的分类算一遍，供筛选行使用 */
    fun refreshCats() {
        val src = if (tab == "search") MarketState.searchResults else MarketState.favResults
        val counts = LinkedHashMap<String, Int>()
        for (m in src) {
            for (c in m.categories) {
                if (c.isBlank()) continue
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        val keys = counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        // 选中的一定排在最前，否则筛了一半之后已选的被挤进「更多」，像失效了
        val ordered = keys.sortedWith(
            compareBy({ !activeCats.contains(it.first) }, { keys.indexOf(it) })
        )
        catList = ordered
    }

    fun sortList(list: MutableList<MarketMod>) {
        when (sortIdx) {
            0 -> list.sortByDescending { it.downloads }
            1 -> list.sortByDescending { it.updated }
            2 -> list.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            // 相关度 = 数据源返回的原顺序
            else -> {
                val src = if (tab == "search") MarketState.searchResults else MarketState.favResults
                list.clear()
                list.addAll(src)
            }
        }
    }

    fun refreshList() {
        val src = if (tab == "search") MarketState.searchResults else MarketState.favResults
        val filtered = if (activeCats.isEmpty()) src else src.filter { m ->
            val cs = m.categories.map { it.lowercase() }
            // 多选之间是「且」：选了「优化 + 科技」就只留两类都占的
            activeCats.all { a -> cs.any { it.contains(a.lowercase()) } }
        }
        results.clear()
        results.addAll(filtered)
        sortList(results)
        refreshCats()
        rev++
    }

    fun translate(mod: MarketMod) {
        if (mod.summaryZh.isNotBlank()) return
        OfflineTranslate.translate(ctx, mod.summary) { zh ->
            if (zh == null) {
                toast("翻译失败：模型还没下载好，或离线且无本地匹配")
                return@translate
            }
            mod.summaryZh = zh
            Bg.ui.post { rev++ }
        }
    }

    /**
     * 自动翻译。
     *
     * 之前只翻前 10 条而且是并发发出去的：10 条以后的永远不翻，
     * 并发调同一个引擎后面的容易失败。现在串行逐条翻，
     * 翻一条刷一条，失败的也不影响后面继续。
     */
    fun autoTranslate(list: List<MarketMod>) {
        if (!Prefs.get(ctx).getBoolean(K.AUTO_TRANS, true)) return
        val targets = list.filter { it.summary.isNotBlank() && it.summaryZh.isBlank() }
        if (targets.isEmpty()) return
        fun next(i: Int) {
            if (i >= targets.size) return
            OfflineTranslate.translate(ctx, targets[i].summary) { zh ->
                if (zh != null) targets[i].summaryZh = zh
                Bg.ui.post { rev++ }
                next(i + 1)
            }
        }
        next(0)
    }

    fun markInstalled(fileName: String?) {
        val k = fileName?.substringBeforeLast(".")?.lowercase()
        if (!k.isNullOrBlank()) installed = installed + k
    }

    fun startCfDownload(mod: MarketMod, url: String) {
        val n = mod.fileName.ifBlank { Downloader.guessName(url) }
        val d = ProgressDialog.show(ctx, n)
        bg {
            val dir = Targets.modsDir(ctx)
            val f = if (dir == null) null
            else Downloader.download(ctx, url, dir, n, CurseForgeApi.authHeaders()) { done, total ->
                Bg.ui.post { d.update(done, total) }
            }
            Bg.ui.post {
                d.dismiss()
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

    /**
     * 安装。
     *
     * CurseForge 的三条取址路径：① download-url 端点（不用读秒，
     * 官方就返回真实直链）② CDN 公式 ③ 隐藏 WebView 等读秒（最慢，兜底）。
     * 之前的版本反过来，一上来就等读秒，慢且经常等不到。
     */
    fun install(mod: MarketMod) {
        val mc = mcVersion()
        val ld = curLoader()
        if (mod.source == "curseforge") {
            toast("正在获取下载地址…")
            bg {
                val direct = CurseForgeApi.fetchDownloadUrl(mod)
                if (direct.isNotBlank()) {
                    Bg.ui.post { startCfDownload(mod, direct) }
                    return@bg
                }
                val page = DelayedDownload.curseForgePage(mod)
                if (page.isBlank()) {
                    Bg.ui.post { toast("没有下载地址") }
                    return@bg
                }
                Bg.ui.post {
                    toast("正在后台等待 CurseForge 读秒…")
                    DelayedDownload.capture(
                        ctx, page,
                        onGot = { real -> startCfDownload(mod, real) },
                        onFail = { why -> toast("没拿到下载地址（$why）") }
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
            Bg.ui.post {
                val d = ProgressDialog.show(ctx, n)
                bg {
                    val dir = Targets.modsDir(ctx)
                    val f = if (dir == null) null
                    else Downloader.download(ctx, url, dir, n, headers) { done, total ->
                        Bg.ui.post { d.update(done, total) }
                    }
                    Bg.ui.post {
                        d.dismiss()
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

    fun search() {
        val raw = query.trim()
        if (raw.isBlank()) {
            toast("请输入关键词")
            return
        }
        // 中文/简称 → 英文：Modrinth、CurseForge 只认英文关键词
        val q = ModAliases.translate(raw)
        val h = ModAliases.hint(raw)
        val mc = mcVersion()
        val ld = curLoader()
        toast("搜索中…$h")
        MarketState.searchResults.clear()
        MarketState.offsets.clear()
        tab = "search"
        MarketState.tab = "search"
        MarketState.offset = 0
        hasMore = true
        // 本轮各源累计返回多少条。用「最后一个源是否为空」判断还有没有下一页
        // 是错的——最后一个源可能覆盖不到这个词而返回空，其他源明明还有。
        var roundGot = 0
        MarketState.query = q
        MarketState.mc = mc
        MarketState.loader = ld
        refreshList()
        AggregateSearch.searchStreaming(ctx, q, mc, ld) { batch, source, finished ->
            if (batch.isNotEmpty()) {
                MarketState.searchResults.addAll(batch)
                roundGot += batch.size
                refreshList()
                toast("$source 返回 ${batch.size} 个（共 ${MarketState.searchResults.size}）")
                autoTranslate(batch)
            }
            if (finished) {
                val routes = AggregateSearch.lastRoutes
                if (MarketState.searchResults.isEmpty()) {
                    toast("没有结果${if (routes.isNotBlank()) "（$routes）" else ""}")
                } else {
                    if (roundGot == 0) hasMore = false
                    MarketState.hasMore = hasMore
                    val more = if (hasMore) "，下滑加载更多" else "（已全部加载）"
                    toast("共 ${MarketState.searchResults.size} 个${more}${if (routes.isNotBlank()) " · $routes" else ""}")
                }
            }
        }
    }

    /** 翻页：用 offset 而不是重新搜，重新搜会把已有结果冲掉 */
    fun loadMore() {
        if (loadingMore || !hasMore) return
        if (MarketState.query.isBlank()) return
        loadingMore = true
        var roundGot = 0
        AggregateSearch.searchStreaming(
            ctx, MarketState.query, MarketState.mc, MarketState.loader,
            MarketState.offset, PAGE_SIZE,
            { batch, source, finished ->
                if (batch.isNotEmpty()) {
                    // 跨页去重：AggregateSearch 内部的 seen 只在本轮生效，
                    // 翻页是新的一轮，上一页显示过的同名模组会再来一次
                    val fresh = batch.filter { m ->
                        val k = m.name.trim().lowercase()
                        k.isNotBlank() &&
                            MarketState.searchResults.none { it.name.trim().lowercase() == k }
                    }
                    if (fresh.isNotEmpty()) {
                        MarketState.searchResults.addAll(fresh)
                        refreshList()
                        toast("$source 又返回 ${fresh.size} 个（共 ${MarketState.searchResults.size}）")
                        autoTranslate(fresh)
                    }
                    roundGot += batch.size
                }
                if (finished) {
                    loadingMore = false
                    if (roundGot == 0) hasMore = false
                    MarketState.hasMore = hasMore
                }
            },
            MarketState.offsets.toMap()
        )
    }

    fun recommendEmptyReason(): String {
        val p = Prefs.get(ctx)
        if (p.getBoolean(K.OFFLINE, false)) return "已开启离线模式，推荐需要联网"
        if (p.getBoolean(K.OFFLINE_SEARCH, false)) return "已在设置里单独关闭了商店联网"
        if (!p.getBoolean(K.RECOMMEND, true)) return "推荐开关是关的（设置 → 搜索里打开）"
        val routes = AggregateSearch.lastRoutes
        if (routes.isNotBlank()) return "没获取到推荐（$routes）"
        return "没获取到推荐：各来源都没返回数据，检查网络或到设置里配置来源"
    }

    /** 已安装模组名（小写），推荐时用来过滤掉装过的 */
    fun installedNames(): Set<String> {
        return try {
            val dir = WorkDir.modsDir(ctx) ?: return emptySet()
            Fs.children(dir).mapNotNull { it.name }
                .filter { it.endsWith(".jar", true) }
                .map { it.substringBeforeLast(".").lowercase() }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun recommend() {
        val mc = mcVersion()
        val ld = curLoader()
        toast("正在获取推荐…")
        // installed 是异步填的，推荐要过滤掉装过的，这里现读一次
        val names = installed + installedNames()
        bg {
            val list = AggregateSearch.recommend(ctx, mc, ld, names)
            Bg.ui.post {
                MarketState.searchResults.clear()
                MarketState.searchResults.addAll(list)
                tab = "search"
                MarketState.tab = "search"
                refreshList()
                // 空结果的原因差别很大：开关关了、断网、没填 Key、还是三个源都失败？
                // 不说清楚用户只能一遍遍点，以为功能坏了
                if (list.isEmpty()) toast(recommendEmptyReason())
                else {
                    toast("推荐 ${list.size} 个")
                    autoTranslate(list)
                }
            }
        }
    }

    fun showFavorites() {
        MarketState.favResults.clear()
        MarketState.favResults.addAll(Favorites.list(ctx))
        toast(
            if (MarketState.favResults.isEmpty()) "收藏夹是空的（长按搜索结果即可收藏）"
            else "收藏 ${MarketState.favResults.size} 个"
        )
        tab = "fav"
        MarketState.tab = "fav"
        refreshList()
    }

    fun openPageWith(url: String, title: String) {
        Store.markViewed(ctx, url, title.ifBlank { url })
        val i = Intent(ctx, ModPageActivity::class.java)
        i.putExtra("url", url)
        ctx.startActivity(i)
    }

    fun downloadLink(l: MarkedLink) {
        // 自定义链接通常是模组页面地址而不是文件直链，
        // 直接拿页面地址去下载只会存下一个 HTML
        if (!PageParser.isFileLike(l.url)) {
            openPageWith(l.url, l.title)
            return
        }
        toast("下载中…")
        bg {
            val dir = Targets.modsDir(ctx)
            val name = Downloader.guessName(l.url)
            val f = if (dir == null) null else Downloader.download(ctx, l.url, dir, name)
            toast(if (f == null) "下载失败（可能是网盘/需浏览器页面）" else "已下载：${f.name}")
        }
    }

    LaunchedEffect(Unit) {
        reloadInstalled()
        reloadLinks()
        // 切页签回来时 Fragment 是新建的，
        // 什么都不做 → 列表空 → 用户看到"搜索结果（0）"，以为刚才白搜了
        if (MarketState.hasContent()) refreshList()
    }

    LaunchedEffect(scanRev) { reloadInstalled() }

    // 滑到接近底部自动加载下一页（距底 3 条就开始预取）
    LaunchedEffect(listState.firstVisibleItemIndex, results.size) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (last >= 0 && last >= info.totalItemsCount - 3) loadMore()
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // ── 搜索栏 ──────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("搜模组名（支持中文简称）") }
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { search() }) { Text("搜索") }
            TextButton(onClick = { dlg = MktDlg.Filter }) { Text("筛选") }
        }

        if (hint.isNotBlank()) {
            Text(
                hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // ── 结果标题 + 页签 ────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val suffix = when {
                tab != "search" -> ""
                loadingMore -> " · 加载中…"
                hasMore -> " · 下滑加载更多"
                else -> ""
            }
            Text(
                if (tab == "search") "搜索结果（${results.size}）$suffix"
                else "收藏夹（${results.size}）",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                if (tab == "search") showFavorites() else {
                    tab = "search"
                    MarketState.tab = "search"
                    refreshList()
                }
            }) { Text(if (tab == "search") "收藏夹" else "搜索结果") }
        }

        // ── 分类筛选行 ────────────────────────────────────
        if (catList.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    CatChip("全部", activeCats.isEmpty()) {
                        activeCats = emptySet()
                        refreshList()
                    }
                }
                items(catList.take(CHIP_LIMIT)) { (k, n) ->
                    val on = activeCats.contains(k)
                    CatChip("$k $n", on) {
                        activeCats = if (on) activeCats - k else activeCats + k
                        refreshList()
                    }
                }
                if (catList.size > CHIP_LIMIT) {
                    item {
                        CatChip("更多 ${catList.size - CHIP_LIMIT}", false) {
                            dlg = MktDlg.Cats(catList.map { it.first }, catList.toMap())
                        }
                    }
                }
            }
        }

        // ── 排序 ──────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("排序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                SORT_OPTS.forEachIndexed { i, s ->
                    CatChip(s, sortIdx == i) {
                        sortIdx = i
                        refreshList()
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }

        // ── 结果列表 ──────────────────────────────────────
        if (results.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("还没有内容", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "输入关键词搜索，或点「筛选」里的「推荐」",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                itemsIndexed(results, key = { i, _ -> i }) { _, m ->
                    // 翻译改的是 MarketMod 的字段而不是列表结构，
                    // 靠 rev 变化重建这一项来重绘
                    key(rev) {
                        MarketCard(
                            m = m,
                            installed = isInstalled(m),
                            fav = Favorites.has(ctx, m),
                            curLoader = curLoader(),
                            onOpen = { openPageWith(m.pageUrl, m.name) },
                            onInstall = { install(m) },
                            onMismatch = { sup, cur ->
                                dlg = MktDlg.Mismatch(m, sup, cur)
                            },
                            onTranslate = {
                                if (m.summaryZh.isNotBlank()) {
                                    m.summaryZh = ""
                                    rev++
                                } else translate(m)
                            },
                            onToggleFav = {
                                val added = Favorites.toggle(ctx, m)
                                toast(if (added) "已收藏：${m.name}" else "已取消收藏：${m.name}")
                                rev++
                            }
                        )
                    }
                }
            }
        }
    }

    // ── 弹窗 ──────────────────────────────────────────────
    when (val d = dlg) {
        is MktDlg.Filter -> FilterDialog(
            ctx = ctx,
            loaderOpts = loaderOpts,
            loaderIdx = loaderIdx,
            version = version,
            links = links,
            onLoader = { loaderIdx = it },
            onVersion = { version = it },
            onRecommend = { recommend(); dlg = MktDlg.None },
            onAddLink = { dlg = MktDlg.AddLink },
            onOpenPage = { dlg = MktDlg.OpenPage },
            onShare = { shareWithPosition(ctx) { toast(it) } },
            onDownload = { downloadLink(it) },
            onRemove = {
                Store.removeLink(ctx, it.url)
                reloadLinks()
            },
            onDismiss = { dlg = MktDlg.None }
        )
        is MktDlg.Cats -> CatsDialog(
            keys = d.keys, counts = d.counts, active = activeCats,
            onApply = { activeCats = it; refreshList(); dlg = MktDlg.None },
            onDismiss = { dlg = MktDlg.None }
        )
        is MktDlg.AddLink -> InputDialog(
            title = "标记链接", hint = "https://...",
            onDismiss = { dlg = MktDlg.None }
        ) { u ->
            val ok = Store.addLink(ctx, MarkedLink(u, u))
            toast(if (ok) "已标记" else "已经标记过了")
            reloadLinks()
        }
        is MktDlg.OpenPage -> InputDialog(
            title = "打开模组页", hint = "https://modrinth.com/mod/...",
            onDismiss = { dlg = MktDlg.None }
        ) { u -> if (u.isNotBlank()) openPageWith(u, "") }
        is MktDlg.Mismatch -> AlertDialog(
            onDismissRequest = { dlg = MktDlg.None },
            title = { Text("这个模组没有 ${Loaders.label(d.current)} 版") },
            text = {
                Text(
                    "《${d.mod.name}》支持的加载器：${d.supported}\n\n" +
                        "当前选的是 ${Loaders.label(d.current)}，装上去大概率进不去游戏。\n\n仍要安装吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = { dlg = MktDlg.None; install(d.mod) }) { Text("仍要安装") }
            },
            dismissButton = { TextButton(onClick = { dlg = MktDlg.None }) { Text("取消") } }
        )
        MktDlg.None -> Unit
    }
}

/** 轻量标签：Text + 描边，不用 Material 的 Chip（列表里几十个会拖慢滑动） */
@Composable
private fun CatChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    // Surface 在 BOM 2023.10 里没有 onClick 参数，用 Modifier.clickable
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(999.dp),
        color = if (on) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, c)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = if (on) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MarketCard(
    m: MarketMod,
    installed: Boolean,
    fav: Boolean,
    curLoader: String,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onMismatch: (supported: String, current: String) -> Unit,
    onTranslate: () -> Unit,
    onToggleFav: () -> Unit
) {
    val lds = Loaders.clean(m.loaders)
    val isMod = m.projectType.isBlank() || m.projectType == "mod"
    val cur = Loaders.normalize(curLoader)
    // 用"置灰 + 点弹警告"而不是禁用：禁用后点了没反应，用户只会以为按钮坏了
    val mismatch = lds.isNotEmpty() && cur != "auto" && !lds.contains(cur)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onOpen() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(10.dp)) {
            AsyncImage(
                model = m.iconUrl.ifBlank { null },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text((if (fav) "★ " else "") + m.name, fontWeight = FontWeight.Bold, maxLines = 2)
                // 只有 mod 才有加载器。数据包/资源包/光影这些 Modrinth 也会塞 loaders，
                // 画出来全是"自动"，等于在骗人
                if (lds.isEmpty() || !isMod) {
                    Text(
                        "${m.source} · ${mktCount(m.downloads)} 次下载",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(m.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.padding(top = 2.dp).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (ld in lds) {
                            Image(
                                painter = painterResource(Loaders.icon(ld)),
                                contentDescription = Loaders.label(ld),
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.size(15.dp).padding(end = 3.dp)
                            )
                        }
                        Text(
                            "下载 ${mktCount(m.downloads)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                val cats = ModCats.clean(m.categories)
                if (cats.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 3.dp).horizontalScroll(rememberScrollState())
                    ) {
                        for (c in cats) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Text(
                                    c,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                val sum = m.summaryZh.ifBlank { m.summary }
                if (sum.isNotBlank()) {
                    Text(
                        sum,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val (label, alpha) = when {
                    installed -> "已安装" to 0.6f
                    mismatch -> "安装?" to 0.45f
                    else -> "安装" to 1f
                }
                TextButton(
                    onClick = {
                        if (mismatch) onMismatch(
                            lds.joinToString("、") { Loaders.label(it) },
                            Loaders.label(cur)
                        ) else onInstall()
                    },
                    modifier = Modifier.alpha(alpha)
                ) { Text(label) }
                TextButton(onClick = onTranslate) {
                    Text(if (m.summaryZh.isNotBlank()) "原文" else "译")
                }
                TextButton(onClick = onToggleFav) { Text(if (fav) "取消收藏" else "收藏") }
            }
        }
    }
}

@Composable
private fun FilterDialog(
    ctx: Context,
    loaderOpts: List<String>,
    loaderIdx: Int,
    version: String,
    links: List<MarkedLink>,
    onLoader: (Int) -> Unit,
    onVersion: (String) -> Unit,
    onRecommend: () -> Unit,
    onAddLink: () -> Unit,
    onOpenPage: () -> Unit,
    onShare: () -> Unit,
    onDownload: (MarkedLink) -> Unit,
    onRemove: (MarkedLink) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("筛选") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("加载器", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    loaderOpts.forEachIndexed { i, s ->
                        CatChip(Loaders.label(s), loaderIdx == i) { onLoader(i) }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = version,
                    onValueChange = onVersion,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("游戏版本") }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRecommend) { Text("推荐") }
                    TextButton(onClick = onAddLink) { Text("标记链接") }
                    TextButton(onClick = onOpenPage) { Text("打开页面") }
                    TextButton(onClick = onShare) { Text("分享") }
                }
                if (links.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("已标记的链接", fontSize = 12.sp)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                        items(links, key = { it.url }) { l ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).clickable { onDownload(l) }) {
                                    Text(l.title, fontSize = 12.sp, maxLines = 1)
                                    Text(
                                        l.url, fontSize = 10.sp, maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { onRemove(l) }) { Text("移除") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CatsDialog(
    keys: List<String>,
    counts: Map<String, Int>,
    active: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var picked by remember { mutableStateOf(active) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类筛选（可多选，同时满足）") },
        text = {
            LazyColumn {
                items(keys) { k ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                picked = if (picked.contains(k)) picked - k else picked + k
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = picked.contains(k), onCheckedChange = {
                            picked = if (it) picked + k else picked - k
                        })
                        Text("$k（${counts[k] ?: 0}）", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(picked) }) { Text("应用") } },
        dismissButton = {
            TextButton(onClick = { onApply(emptySet()) }) { Text("清空") }
        }
    )
}

@Composable
private fun InputDialog(
    title: String, hint: String, onDismiss: () -> Unit, onOk: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(hint) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onOk(text.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 分享：带上"上次看到哪儿"，对方打开能直接回同一个页面 */
private fun shareWithPosition(ctx: Context, toast: (String) -> Unit) {
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
        ctx.startActivity(Intent.createChooser(i, "分享到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
        toast("分享失败")
    }
}

/** 大数字压成中文习惯的短写法：12.8万 / 1.2亿 */
private fun mktCount(n: Long): String {
    if (n <= 0) return "0"
    return when {
        n >= 100_000_000L -> "%.1f亿".format(n / 100_000_000.0)
        n >= 10_000L -> {
            val v = n / 10_000.0
            if (v >= 100) "%.0f万".format(v) else "%.1f万".format(v)
        }
        else -> java.text.NumberFormat.getIntegerInstance(java.util.Locale.CHINA).format(n)
    }
}
