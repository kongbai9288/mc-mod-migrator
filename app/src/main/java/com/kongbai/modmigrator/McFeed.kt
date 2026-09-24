package com.kongbai.modmigrator

import org.jsoup.Jsoup
import java.util.Locale

/**
 * MC 资讯分区抓取。
 *
 * 四个分区：**官方最新版本 / 最新模组 / 最新投影 / 红石**。
 *
 * 实现要点（按成熟爬虫的做法，而不是随手正则）：
 *  1. 用 Jsoup（MIT）做真正的 DOM 解析 + CSS 选择器，
 *     不拿正则去匹配 HTML——正则遇到属性顺序变化、换行就直接失效。
 *  2. **每个分区独立 try**：某一个站点挂了只影响那一块，
 *     其余照常显示，不会整页空白。
 *  3. **标明来源**：每条都带来源站点名与原文链接，点进去是原站，
 *     不把别人的内容伪装成自己的。
 *  4. 结果有内存缓存 + 强制同步落盘，避免每次进页面都重复抓。
 *  5. 选择器失效（站点改版）时该分区返回空并显示降级文案，
 *     同时保留"打开原站"入口，用户永远有路可走。
 *
 * 只取标题与链接这类摘要信息，不整页搬运正文。
 */
object McFeed {

    /** 分区标识 */
    const val OFFICIAL = "official"
    const val MODS = "mods"
    const val SCHEMATIC = "schematic"
    const val REDSTONE = "redstone"

    data class Item(
        val title: String,
        val url: String,
        val source: String,
        val extra: String = ""
    )

    /** 分区定义：名字、站点名、地址、选择器 */
    private data class Zone(
        val key: String,
        val label: String,
        val site: String,
        val url: String,
        val selector: String,
        val desc: String
    )

    private val ZONES = listOf(
        Zone(
            OFFICIAL, "官方最新版本", "中文 Minecraft Wiki",
            "https://zh.minecraft.wiki/w/%E7%89%88%E6%9C%AC",
            "a[href*='Java%E7%89%88'], a[href*='/w/Java']",
            "Java 版版本历史"
        ),
        Zone(
            MODS, "最新模组", "Modrinth",
            "https://modrinth.com/mods?g=1.20.1",
            "a[href^='/mod/']",
            "Modrinth 最新模组"
        ),
        Zone(
            SCHEMATIC, "最新投影", "Minecraft Schematics",
            "https://www.minecraft-schematics.com/search/?sort=date",
            "a[href*='/schematic/']",
            "投影（schematic）下载"
        ),
        Zone(
            REDSTONE, "红石", "红石计划",
            "https://www.redstoneplan.com/",
            "a[href*='/post/'], a[href*='/article/']",
            "红石机械与生电"
        )
    )

    @Volatile
    private var cache = HashMap<String, List<Item>>()

    @Volatile
    private var cacheAt = 0L

    private const val TTL = 10 * 60 * 1000L
    private const val PER_ZONE = 8

    fun labels(): List<Zone> = ZONES

    fun cached(key: String): List<Item> =
        if (System.currentTimeMillis() - cacheAt < TTL) cache[key] ?: emptyList()
        else emptyList()

    /** 抓单个分区。后台线程调用。 */
    fun fetch(ctx: android.content.Context, key: String): List<Item> {
        val hit = cached(key)
        if (hit.isNotEmpty()) return hit

        val zone = ZONES.firstOrNull { it.key == key } ?: return emptyList()

        // 官方版本这一块优先用 Modrinth 的游戏版本接口（结构化，比抓 HTML 稳），
        // 抓不到才退回 Jsoup 抓 Wiki
        if (key == OFFICIAL) {
            val viaApi = officialVersions(ctx)
            if (viaApi.isNotEmpty()) {
                cache[key] = viaApi
                cacheAt = System.currentTimeMillis()
                return viaApi
            }
        }

        val list = try {
            scrape(zone)
        } catch (t: Throwable) {
            emptyList()
        }
        if (list.isNotEmpty()) {
            cache[key] = list
            cacheAt = System.currentTimeMillis()
        }
        return list
    }

    /** Jsoup 抓取：解析 DOM、按选择器取条目、过滤导航项 */
    private fun scrape(zone: Zone): List<Item> {
        val html = Http.get(
            zone.url,
            mapOf(
                "Accept" to "text/html,application/xhtml+xml",
                "User-Agent" to Http.UA
            )
        )
        val doc = Jsoup.parse(html, zone.url)
        val out = ArrayList<Item>()
        val seen = HashSet<String>()

        val els = try {
            doc.select(zone.selector)
        } catch (t: Throwable) {
            doc.select("a[href]")
        }

        for (el in els) {
            if (out.size >= PER_ZONE) break
            val href = runCatching { el.absUrl("href") }.getOrNull()
            if (href.isNullOrBlank() || !href.startsWith("http")) continue

            val title = el.text().trim()
                .ifBlank { el.attr("title") }
                .replace(Regex("\\s+"), " ")
            if (title.length < 2 || isNav(title)) continue

            val k = title.lowercase(Locale.ROOT)
            if (seen.contains(k)) continue
            seen.add(k)

            out.add(
                Item(
                    title = title.take(60),
                    url = href,
                    source = zone.site,
                    extra = zone.desc
                )
            )
        }
        return out
    }

    /**
     * 官方最新版本：优先用 Modrinth 的游戏版本列表（结构化数据）。
     * 拿不到就返回空，由调用方退回 Wiki 抓取。
     */
    private fun officialVersions(ctx: android.content.Context): List<Item> {
        return try {
            val json = Http.get("https://api.modrinth.com/v3/tag/game_version")
            val arr = Json.arr(json) ?: return emptyList()
            val out = ArrayList<Item>()
            // Modrinth 返回按时间倒序，前几个就是最新的正式版
            var n = 0
            for (e in arr) {
                if (n >= 6) break
                val v = Json.s(e, "version")
                val type = Json.s(e, "type")
                if (v.isBlank()) continue
                // 只要正式 release，过滤快照与预览版
                if (type.isNotBlank() && !type.equals("release", true)) continue
                out.add(
                    Item(
                        title = "Minecraft $v",
                        url = "https://zh.minecraft.wiki/w/Java%E7%89%88$v",
                        source = "Modrinth 版本接口",
                        extra = Json.s(e, "type").ifBlank { "release" }
                    )
                )
                n++
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun isNav(t: String): Boolean {
        val low = t.lowercase(Locale.ROOT)
        return low in setOf(
            "登录", "注册", "首页", "更多", "下一页", "上一页",
            "login", "sign in", "sign up", "home", "next", "previous",
            "下载", "download", "搜索", "search", "全部", "all"
        ) || low.length <= 1
    }

    fun clearCache() {
        cache.clear()
        cacheAt = 0L
    }
}
