package com.kongbai.modmigrator

import org.jsoup.Jsoup
import java.util.Locale

object PageParser {

    private val HOSTS = listOf(
        "mediafilez.forgecdn.net", "media.forgecdn.net", "cdn.modrinth.com",
        "github.com", "objects.githubusercontent.com", "mediafire.com",
        "mega.nz", "drive.google.com", "gitee.com", "lanzou", "1drv.ms"
    )

    /**
     * 只保留「真的像下载链接」的候选：
     *  - 必须是 jar/zip，或命中已知下载域名
     *  - 分数要够高（>= 6，把正文里的普通链接全滤掉）
     *  - 最多返回 8 条，且同一文件名只留一个
     */
    fun candidates(pageUrl: String, limit: Int = 8): List<MarkedLink> {
        val html = Http.get(pageUrl, mapOf("Accept" to "text/html,application/xhtml+xml"))
        val doc = Jsoup.parse(html, pageUrl)
        val scored = ArrayList<Pair<Int, MarkedLink>>()
        val seenUrl = HashSet<String>()
        val seenFile = HashSet<String>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href")
            if (href.isBlank() || seenUrl.contains(href)) continue
            val text = a.text().trim().ifBlank { a.attr("href") }
            val s = score(href, text)
            if (s < 6) continue
            // 必须真的是文件直链或已知下载站
            val h = href.lowercase(Locale.ROOT)
            val isFile = h.substringBefore('?').endsWith(".jar") ||
                h.substringBefore('?').endsWith(".zip")
            val isHost = HOSTS.any { h.contains(it) }
            if (!isFile && !isHost) continue
            val fname = href.substringBefore('?').substringAfterLast('/')
            if (fname.isNotBlank() && seenFile.contains(fname)) continue
            seenUrl.add(href)
            if (fname.isNotBlank()) seenFile.add(fname)
            scored.add(Pair(s, MarkedLink(text.ifBlank { fname }, href)))
        }
        scored.sortByDescending { it.first }
        return scored.take(limit).map { it.second }
    }

    fun score(href: String, text: String): Int {
        val h = href.lowercase(Locale.ROOT)
        val t = text.lowercase(Locale.ROOT)
        var s = 0
        if (h.endsWith(".jar") || h.endsWith(".zip")) s += 5
        for (x in HOSTS) {
            if (h.contains(x)) {
                s += 4
                break
            }
        }
        if (t.contains("下载") || t.contains("download") || t.contains("mirror") || t.contains("镜像")) s += 3
        if (h.contains("download") || h.contains("/files/") || h.contains("release")) s += 2
        if (h.contains("curseforge.com/minecraft/mc-mods")) s -= 3
        if (h.endsWith(".html") || h.endsWith(".htm")) s -= 1
        return s
    }
}
