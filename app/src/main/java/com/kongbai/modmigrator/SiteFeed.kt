package com.kongbai.modmigrator

import org.jsoup.Jsoup
import java.util.Locale

/**
 * 站点内容抓取（用于周报）。
 *
 * 规则：
 *  1. **必须标明来源**——每条内容都带来源站点名和原文链接，
 *     点进去是原站，不伪装成自己的内容。
 *  2. **只取标题与链接**这种摘要信息，不整页搬运正文。
 *  3. **每个源独立 try**，某一个挂了只影响它自己那一块，
 *     不会让整页空白。
 *  4. 结果有内存缓存，避免每次进周报都重复抓。
 *
 * 解析用的是 Jsoup（已是项目依赖），只取 a 标签的标题文本。
 */
object SiteFeed {

    data class Entry(
        val title: String,
        val url: String,
        val source: String,
        val sourceUrl: String
    )

    /**
     * 可抓取的源。
     * selector 是 CSS 选择器，用来圈出「条目」区域；
     * 站点改版后选择器可能失效，此时该源返回空，不影响其他源。
     */
    private data class Source(
        val name: String,
        val base: String,
        val url: String,
        val selector: String
    )

    private val SOURCES = listOf(
        Source("MC百科", "https://www.mcmod.cn", "https://www.mcmod.cn/", "a.mod-item, div.mod-item a, a[href^='/mod/']"),
        Source("中文 Minecraft Wiki", "https://zh.minecraft.wiki", "https://zh.minecraft.wiki/w/%E9%A6%96%E9%A1%B5", "a.mw-body-content-link, div.mw-body a[href^='/w/']"),
        Source("Modrinth", "https://modrinth.com", "https://modrinth.com/mods", "a[href^='/mod/']"),
        Source("红石计划", "https://www.redstoneplan.com", "https://www.redstoneplan.com/", "a[href*='/post/'], a[href*='/article/']")
    )

    @Volatile
    private var cache: List<Entry> = emptyList()

    @Volatile
    private var cacheAt = 0L

    /** 缓存 10 分钟 */
    private const val TTL = 10 * 60 * 1000L

    /** 单个源最多取几条 */
    private const val PER_SOURCE = 6

    fun cached(): List<Entry> =
        if (cache.isNotEmpty() && System.currentTimeMillis() - cacheAt < TTL) cache
        else emptyList()

    /**
     * 抓全部源。后台线程调用。
     * 每个源独立 try，失败的源直接跳过。
     */
    fun fetch(): List<Entry> {
        val cached = cached()
        if (cached.isNotEmpty()) return cached

        // 4 个源**并发**抓。
        // 之前是串行 for 循环：而 `Http.get` 用的是默认超时（读取 120 秒），
        // 4 个站点里只要有一个连不上，整页周报就要等好几分钟才出得来——
        // 这正是"周报半天刷不出来"的原因，跟网速无关，是串行+长超时叠加。
        // 并发之后总耗时 = 最慢那个源，而不是所有源之和。
        val out = java.util.Collections.synchronizedList(ArrayList<Entry>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(SOURCES.size)
        try {
            val futures = SOURCES.map { s ->
                pool.submit {
                    try {
                        out.addAll(fetchOne(s))
                    } catch (t: Throwable) {
                        // 这个源挂了，跳过，不影响其他源
                        Err.ignore(t, "抓取 ${s.name} 失败")
                    }
                }
            }
            for (f in futures) {
                try {
                    // 单个源最多等 20 秒，整体不会卡死
                    f.get(20, java.util.concurrent.TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    Err.ignore(t, "等待抓取结果超时")
                }
            }
        } finally {
            pool.shutdownNow()
        }
        if (out.isNotEmpty()) {
            cache = ArrayList(out)
            cacheAt = System.currentTimeMillis()
        }
        return out
    }

    private fun fetchOne(s: Source): List<Entry> {
        // 必须用短超时：这里抓的是资讯页面，不是下载，
        // 没有理由让用户为一个列表页等上两分钟。
        val html = Http.get(
            s.url,
            mapOf(
                "Accept" to "text/html,application/xhtml+xml",
                "User-Agent" to Http.UA
            ),
            Http.SHORT
        )
        val doc = Jsoup.parse(html, s.url)
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()

        val els = try {
            doc.select(s.selector)
        } catch (t: Throwable) {
            doc.select("a[href]")
        }

        for (el in els) {
            if (out.size >= PER_SOURCE) break
            val href = runCatching { el.absUrl("href") }.getOrNull()
            if (href.isNullOrBlank()) continue
            if (!href.startsWith("http")) continue

            val title = el.text().trim()
                .ifBlank { el.attr("title") }
                .ifBlank { el.attr("href") }
                .replace(Regex("\\s+"), " ")
            if (title.isBlank() || title.length < 2) continue
            // 过滤纯导航项
            if (isNav(title)) continue
            val key = title.lowercase(Locale.ROOT)
            if (seen.contains(key)) continue
            seen.add(key)

            out.add(
                Entry(
                    title = title.take(60),
                    url = href,
                    source = s.name,
                    sourceUrl = s.base
                )
            )
        }
        return out
    }

    /** 明显是导航/功能链接，不是内容条目 */
    private fun isNav(t: String): Boolean {
        val low = t.lowercase(Locale.ROOT)
        return low in setOf(
            "登录", "注册", "首页", "更多", "下一页", "上一页",
            "login", "sign in", "sign up", "home", "next", "previous",
            "下载", "download", "搜索", "search"
        ) || low.length <= 1
    }

    /** 按来源分组，周报里按站点分块展示 */
    fun groupBySource(list: List<Entry>): Map<String, List<Entry>> =
        list.groupBy { it.source }

    fun clearCache() {
        cache = emptyList()
        cacheAt = 0L
    }
}
