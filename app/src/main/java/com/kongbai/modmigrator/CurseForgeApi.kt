package com.kongbai.modmigrator

import com.google.gson.JsonElement

object CurseForgeApi {

    private const val BASE = "https://api.curseforge.com/v1"

    fun loaderType(loader: String): Int =
        when (loader) {
            "forge" -> 1
            "fabric" -> 4
            "quilt" -> 5
            "neoforge" -> 6
            else -> 0
        }

    fun search(query: String, mc: String, loader: String, key: String, limit: Int = 20): List<MarketMod> {
        if (key.isBlank()) return emptyList()
        var url = "$BASE/mods/search?gameId=432&searchFilter=${Http.enc(query)}&pageSize=$limit"
        if (mc.isNotBlank()) url = "$url&gameVersion=${Http.enc(mc)}"
        val lt = loaderType(loader)
        if (lt != 0) url = "$url&modLoaderType=$lt"
        val root = Json.obj(Http.get(url, mapOf("x-api-key" to key))) ?: return emptyList()
        val data = Json.a(root, "data") ?: return emptyList()
        val out = mutableListOf<MarketMod>()
        for (d in data) {
            val slug = Json.s(d, "slug")
            var fileId = ""
            val lf = Json.a(d, "latestFiles")
            if (lf != null && lf.size() > 0) fileId = Json.s(lf[0], "id")
            out.add(
                MarketMod(
                    id = Json.s(d, "id"),
                    slug = slug,
                    name = Json.s(d, "name"),
                    summary = Json.s(d, "summary"),
                    iconUrl = logo(d),
                    pageUrl = "https://www.curseforge.com/minecraft/mc-mods/$slug",
                    downloads = Json.l(d, "downloadCount"),
                    fileId = fileId,
                    source = "curseforge"
                )
            )
        }
        return out
    }

    private fun logo(e: JsonElement?): String {
        if (e == null || !e.isJsonObject) return ""
        val l = e.asJsonObject.get("logo") ?: return ""
        return Json.s(l, "url")
    }

    fun downloadUrl(mod: MarketMod): String =
        "https://www.curseforge.com/minecraft/mc-mods/${mod.slug}/download/${mod.fileId}"
}
