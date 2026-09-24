package com.kongbai.modmigrator

object ModrinthApi {

    // v2 搜索端点已迁移到 v3，统一用 v3
    private const val OFFICIAL = "https://api.modrinth.com/v3"
    private const val MIRROR = "https://mod.mcimirror.top/modrinth/v2"

    private fun base(): String = if (Prefs.mirror()) MIRROR else OFFICIAL
    private val titles = HashMap<String, String>()
    private val slugs = HashMap<String, String>()

    /**
 * 搜索。
 *
 * 两处关键修正（对照 Modrinth 官方 v3 文档）：
 *  1. **端点版本**：原来用的是 `/v2/search`，该端点已迁移到 v3。
 *     继续打 v2 会拿不到结果或返回旧结构——这是"商店搜不出东西"的根因之一。
 *  2. **过滤语法**：v2 的 `facets` 已废弃，v3 用 MeiliSearch 语法的 `new_filters`。
 *     写成 `project_types=["mod"] AND game_versions=["1.20.1"]`，
 *     继续传 facets 在新接口上会被忽略，导致过滤失效（比如不限版本）。
 */
fun search(query: String, mc: String, loader: String, limit: Int = 20): List<MarketMod> {
        val filters = ArrayList<String>()
        filters.add("""project_types=["mod"]""")
        if (mc.isNotBlank()) filters.add("""game_versions=["$mc"]""")
        if (loader.isNotBlank() && loader != "auto") filters.add("""categories=["$loader"]""")
        val nf = filters.joinToString(" AND ")
        val url = "${base()}/search?query=${Http.enc(query)}" +
            "&limit=$limit&index=downloads&new_filters=${Http.enc(nf)}"
        val root = Json.obj(Http.get(url)) ?: return emptyList()
        val hits = Json.a(root, "hits") ?: return emptyList()
        val out = mutableListOf<MarketMod>()
        for (h in hits) {
            val id = Json.s(h, "project_id")
            val title = Json.s(h, "title").ifBlank { Json.s(h, "slug") }
            if (id.isNotBlank()) titles[id] = title
            out.add(
                MarketMod(
                    id = id,
                    slug = Json.s(h, "slug"),
                    name = title,
                    summary = Json.s(h, "description"),
                    iconUrl = Json.s(h, "icon_url"),
                    pageUrl = "https://modrinth.com/mod/${Json.s(h, "slug")}",
                    downloads = Json.l(h, "downloads"),
                    source = "modrinth",
                    updated = Json.s(h, "date_modified").ifBlank { Json.s(h, "date_created") }
                )
            )
        }
        return out
    }

    private fun mirrorUrl(u: String): String =
        if (Prefs.mirror()) u.replace("cdn.modrinth.com", "mod.mcimirror.top") else u

    fun versions(projectId: String, mc: String, loader: String): List<ModFile> {
        var url = "${base()}/project/${Http.enc(projectId)}/version"
        val q = ArrayList<String>()
        if (mc.isNotBlank()) q.add("game_versions=${Http.enc("[\"$mc\"]")}")
        if (loader.isNotBlank() && loader != "auto") q.add("loaders=${Http.enc("[\"$loader\"]")}")
        if (q.isNotEmpty()) url = "$url?${q.joinToString("&")}"
        val arr = Json.arr(Http.get(url)) ?: return emptyList()
        val out = mutableListOf<ModFile>()
        for (v in arr) {
            val files = Json.a(v, "files") ?: continue
            var chosen: com.google.gson.JsonElement? = null
            for (f in files) {
                if (chosen == null || Json.s(f, "primary") == "true") chosen = f
            }
            if (chosen == null) continue
            out.add(
                ModFile(
                    name = Json.s(v, "name").ifBlank { Json.s(v, "version_number") },
                    version = Json.s(v, "version_number"),
                    url = mirrorUrl(Json.s(chosen, "url")),
                    fileName = Json.s(chosen, "filename")
                )
            )
        }
        return out
    }

    fun lookupHash(sha1: String): Triple<String, String, String>? {
        if (sha1.isBlank()) return null
        return try {
            val o = Json.obj(Http.get("${base()}/version_file/$sha1?algorithm=sha1")) ?: return null
            val pid = Json.s(o, "project_id")
            if (pid.isBlank()) return null
            refresh(pid)
            Triple(pid, Json.s(o, "version_number").ifBlank { Json.s(o, "name") }, slugs[pid] ?: "")
        } catch (t: Throwable) {
            null
        }
    }

    fun refresh(pid: String) {
        if (titles.containsKey(pid)) return
        try {
            val o = Json.obj(Http.get("${base()}/project/${Http.enc(pid)}"))
            if (o != null) {
                titles[pid] = Json.s(o, "title")
                slugs[pid] = Json.s(o, "slug")
            }
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun title(pid: String): String = titles[pid] ?: ""
}
