package com.kongbai.modmigrator

import com.google.gson.JsonElement

object CurseForgeApi {

    private const val OFFICIAL = "https://api.curseforge.com/v1"
    private const val MIRROR = "https://mod.mcimirror.top/curseforge/v1"
    private const val FILE_MIRROR = "https://mod.mcimirror.top/files"
    private const val FILE_OFFICIAL = "https://edge.forgecdn.net/files"

    /** 没填 Key 就强制走镜像；填了 Key 则按设置开关决定 */
    private fun mirrorOn(key: String): Boolean = key.isBlank() || Prefs.mirror()

    private fun base(key: String): String = if (mirrorOn(key)) MIRROR else OFFICIAL

    fun loaderType(loader: String): Int =
        when (loader) {
            "forge" -> 1
            "fabric" -> 4
            "quilt" -> 5
            "neoforge" -> 6
            else -> 0
        }

    fun search(query: String, mc: String, loader: String, key: String, limit: Int = 20): List<MarketMod> {
        val b = base(key)
        val headers = if (key.isNotBlank()) mapOf("x-api-key" to key) else emptyMap()
        // index 是**基于 0 的起始偏移**，不是页码。官方约束 index + pageSize <= 10000。
        // 之前只传 pageSize 不传 index，部分镜像/网关会按默认偏移返回，导致结果异常。
        var url = "$b/mods/search?gameId=432&classId=6&searchFilter=${Http.enc(query)}" +
            "&pageSize=$limit&index=0&sortField=6&sortOrder=desc"
        if (mc.isNotBlank()) url = "$url&gameVersion=${Http.enc(mc)}"
        val lt = loaderType(loader)
        if (lt != 0) url = "$url&modLoaderType=$lt"
        val root = Json.obj(Http.get(url, headers)) ?: return emptyList()
        val data = Json.a(root, "data") ?: return emptyList()
        val out = mutableListOf<MarketMod>()
        for (d in data) {
            val slug = Json.s(d, "slug")
            var fileId = ""
            var fileName = ""
            val lf = Json.a(d, "latestFiles")
            if (lf != null && lf.size() > 0) {
                fileId = Json.s(lf[0], "id")
                fileName = Json.s(lf[0], "fileName")
            }
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
                    fileName = fileName,
                    source = "curseforge",
                    updated = Json.s(d, "dateModified").ifBlank { Json.s(d, "dateReleased") }
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

    /** CDN 路径规则：files/{fileId 前 4 位}/{剩余}/{文件名} */
    private fun cdnPath(fileId: String, fileName: String): String {
        if (fileId.length < 5 || fileName.isBlank()) return ""
        val a = fileId.substring(0, 4)
        val b = fileId.substring(4)
        return "$a/$b/" + fileName.replace(" ", "%20")
    }

    /** 官方 CDN 现在强制要求 Key 认证，所以走官方时要用 header 带上 */
    fun authHeaders(): Map<String, String> {
        val ctx = Prefs.appCtx() ?: return emptyMap()
        val key = Prefs.get(ctx).getString(K.CF_KEY, "") ?: ""
        val mirror = mirrorOn(key)
        if (mirror || key.isBlank()) return emptyMap()
        return mapOf("x-api-key" to key)
    }

    fun downloadUrl(mod: MarketMod): String {
        val ctx = Prefs.appCtx()
        val key = if (ctx == null) "" else Prefs.get(ctx).getString(K.CF_KEY, "") ?: ""
        val p = cdnPath(mod.fileId, mod.fileName)
        if (p.isNotBlank()) {
            return if (mirrorOn(key)) "$FILE_MIRROR/$p" else "$FILE_OFFICIAL/$p"
        }
        return "https://www.curseforge.com/minecraft/mc-mods/${mod.slug}/download/${mod.fileId}"
    }
}
