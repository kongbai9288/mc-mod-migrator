package com.kongbai.modmigrator

import org.jsoup.Jsoup
import java.util.Locale

object PageParser {

    private val HOSTS = listOf(
        "mediafilez.forgecdn.net", "media.forgecdn.net", "cdn.modrinth.com",
        "github.com", "objects.githubusercontent.com", "mediafire.com",
        "mega.nz", "drive.google.com", "gitee.com", "lanzou", "1drv.ms"
    )

    fun candidates(pageUrl: String): List<MarkedLink> {
        val html = Http.get(pageUrl, mapOf("Accept" to "text/html,application/xhtml+xml"))
        val doc = Jsoup.parse(html, pageUrl)
        val scored = ArrayList<Pair<Int, MarkedLink>>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href")
            if (href.isBlank() || seen.contains(href)) continue
            val text = a.text().trim().ifBlank { a.attr("href") }
            val s = score(href, text)
            if (s > 0) {
                seen.add(href)
                scored.add(Pair(s, MarkedLink(text, href)))
            }
        }
        scored.sortByDescending { it.first }
        return scored.take(30).map { it.second }
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
