package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 周报。
 *
 * 两部分内容，都不依赖单一数据源，某一部分拿不到不影响另一部分：
 *  1. 应用动态：从仓库 release 列表拉最近的更新说明
 *  2. 本机动态：从日志中心统计最近做了多少迁移、下载、出错
 *  3. 社区动态：各站点的条目摘要
 *
 * 三块各自 try，失败就显示各自的降级文案，不会整页空白。
 *
 * ⚠️ 三块是**并发**抓取的，谁先回来谁排前面会导致每次进页面顺序都不一样。
 * 这里顺序写死：社区 → 本机 → 应用。
 */
class WeeklyReportFragment : Fragment() {

    private var onReload: (() -> Unit)? = null
    private var refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val compose = ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                McTheme(ctx) {
                    WeeklyScreen(
                        ctx,
                        register = { onReload = it },
                        onSettled = { refresh?.setRefreshing(false) }
                    )
                }
            }
        }
        return androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx).apply {
            addView(
                compose,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnRefreshListener { onReload?.invoke() }
            setRefreshing(true)
            refresh = this
        }
    }
}

@Composable
private fun WeeklyScreen(
    ctx: android.content.Context,
    register: (() -> Unit) -> Unit,
    onSettled: () -> Unit
) {
    var gen by remember { mutableStateOf(0) }
    var stateText by remember { mutableStateOf("正在生成周报…") }
    var hintOpen by remember { mutableStateOf(false) }
    // null = 还没回来
    var localText by remember { mutableStateOf<String?>(null) }
    var sites by remember { mutableStateOf<List<SiteFeed.Entry>?>(null) }
    var releases by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    DisposableEffect(Unit) {
        register { gen++ }
        onDispose { register({}) }
    }

    LaunchedEffect(gen) {
        stateText = "正在生成周报…"
        localText = null
        sites = null
        releases = null
        // 两个联网分区都回来才收尾；本机那块是本地计算，不参与计数
        val done = AtomicInteger(0)
        Bg.io.execute {
            val text = try {
                buildLocal()
            } catch (t: Throwable) {
                ""
            }
            Bg.ui.post { localText = text }
        }
        Bg.io.execute {
            val list = try {
                SiteFeed.fetch()
            } catch (t: Throwable) {
                emptyList<SiteFeed.Entry>()
            }
            Bg.ui.post {
                sites = list
                if (done.incrementAndGet() >= 2) finish(onSettled) { stateText = it }
            }
        }
        Bg.io.execute {
            val list = try {
                fetchReleases(ctx)
            } catch (t: Throwable) {
                emptyList<Pair<String, String>>()
            }
            Bg.ui.post {
                releases = list
                if (done.incrementAndGet() >= 2) finish(onSettled) { stateText = it }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item {
            McExpand("最近发生了什么", hintOpen, { hintOpen = !hintOpen }) {
                McNote("下拉刷新或重进本页可重新拉取。")
            }
        }
        item { McNote(stateText) }

        // ==== 社区动态 ====
        item { McSectionTitle("社区动态（来自各站点）") }
        if (sites == null) {
            item { McSkeleton(2) }
        } else if (sites?.isEmpty() == true) {
            item {
                McNote("暂时抓不到社区动态：可能网络不通，或站点改版导致解析失效。不影响其他功能。")
            }
        } else {
            val grouped = SiteFeed.groupBySource(sites ?: emptyList())
            items(grouped.entries.toList()) { entry ->
                val source = entry.key
                val entries = entry.value
                val first = entries.first()
                val body = buildString {
                    for (e in entries) append("· ").append(e.title).append('\n')
                }
                // 点整张卡片应该跳到**第一条内容的原文**，而不是站点首页 ——
                // 传首页的话用户点进去等于没跳转，还得自己在列表里找刚才那条。
                McModCard(
                    iconUrl = "",
                    title = source,
                    summary = body.trimEnd(),
                    action = "查看原文",
                    onAction = { WebActivity.open(ctx, first.url, first.title) }
                )
            }
            item { McNote("以上内容分别来自各站点，版权归原作者所有。点击可跳转原文。") }
        }

        // ==== 本机动态 ====
        item { McSectionTitle("本机动态") }
        if (localText == null) {
            item { McSkeleton(2) }
        } else if (localText.isNullOrBlank()) {
            item { McNote("暂无本地数据：用一阵子再来看就有了。") }
        } else {
            item { WeeklyCard("使用情况", localText ?: "") }
        }

        // ==== 应用动态 ====
        item { McSectionTitle("应用动态") }
        if (releases == null) {
            item { McSkeleton(2) }
        } else if (releases?.isEmpty() == true) {
            item {
                McNote("暂时拉不到更新记录：可能网络不通或仓库没发布过 release，不影响使用。")
            }
        } else {
            items((releases ?: emptyList()).take(6)) { pair ->
                WeeklyCard(pair.first, pair.second.take(300).ifBlank { "（无说明）" })
            }
        }
    }
}

private fun finish(onSettled: () -> Unit, setText: (String) -> Unit) {
    onSettled()
    setText(
        "生成于 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
    )
}

@Composable
private fun WeeklyCard(title: String, body: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                body,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 本机动态：从内存里的日志统计 */
private fun buildLocal(): String {
    val lines = LogCenter.all()
    val total = lines.size
    val errors = lines.count { it.level.equals("E", true) }
    val migrate = lines.count { it.msg.contains("迁移") }
    val download = lines.count {
        it.msg.contains("已安装") || it.msg.contains("下载")
    }
    val sb = StringBuilder()
    sb.append("日志条目：$total 条\n")
    sb.append("其中错误：$errors 条\n")
    sb.append("迁移相关：$migrate 次\n")
    sb.append("下载安装：$download 次\n")
    if (errors > 0) {
        sb.append("\n最近的错误：\n")
        lines.filter { it.level.equals("E", true) }
            .takeLast(3)
            .forEach { sb.append("· ${it.msg.take(80)}\n") }
    }
    return sb.toString()
}

/** 拉 release 列表 */
private fun fetchReleases(ctx: android.content.Context): List<Pair<String, String>> {
    val o = Prefs.get(ctx).getString(K.OWNER, "").orEmpty()
        .ifBlank { UpdateChecker.defaultOwner() }
    val r = Prefs.get(ctx).getString(K.REPO, "").orEmpty()
        .ifBlank { UpdateChecker.defaultRepo() }
    val token = Prefs.get(ctx).getString(K.TOKEN, "") ?: ""
    val url = "https://api.github.com/repos/${Http.enc(o)}/${Http.enc(r)}/releases?per_page=6"
    // 短超时 + GitHub 官方推荐的 Accept 头。默认读取超时是 120 秒，
    // 网络不通时整页要干等两分钟才显示"暂时拉不到更新记录"。
    val headers = mutableMapOf("Accept" to "application/vnd.github+json")
    if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
    val json = Http.get(url, headers, Http.SHORT)
    val arr = Json.arr(json) ?: return emptyList()
    val out = ArrayList<Pair<String, String>>()
    for (e in arr) {
        val tag = Json.s(e, "tag_name")
        if (tag.isBlank()) continue
        out.add(Pair(tag, Json.s(e, "body")))
    }
    return out
}
