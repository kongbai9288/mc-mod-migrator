package com.kongbai.modmigrator

object ModrinthApi {

    private const val BASE = "https://api.modrinth.com/v2"
    private val titles = HashMap<String, String>()
    private val slugs = HashMap<String, String>()

    fun search(query: String, mc: String, loader: String, limit: Int = 20): List<MarketMod> {
        val facets = ArrayList<String>()
        facets.add("[\"project_type:mod\"]")
        if (mc.isNotBlank()) facets.add("[\"versions:$mc\"]")
        if (loader.isNotBlank() && loader != "auto") facets.add("[\"categories:$loader\"]")
        val f = facets.joinToString(",", "[", "]")
        val url = "$BASE/search?query=${Http.enc(query)}&limit=$limit&index=downloads&facets=${Http.enc(f)}"
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
                    source = "modrinth"
                )
            )
        }
        return out
    }

    fun versions(projectId: String, mc: String, loader: String): List<ModFile> {
        var url = "$BASE/project/${Http.enc(projectId)}/version"
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
                    url = Json.s(chosen, "url"),
                    fileName = Json.s(chosen, "filename")
                )
            )
        }
        return out
    }

    fun lookupHash(sha1: String): Triple<String, String, String>? {
        if (sha1.isBlank()) return null
        return try {
            val o = Json.obj(Http.get("$BASE/version_file/$sha1?algorithm=sha1")) ?: return null
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
            val o = Json.obj(Http.get("$BASE/project/${Http.enc(pid)}"))
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
