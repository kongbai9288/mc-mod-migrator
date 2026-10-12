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

    /**
     * CurseForge 的 modLoaderType。
     * 数字编号统一放在 [Loaders.cfTypeId]，这里只转发，不再自己维护一份。
     */
    fun loaderType(loader: String): Int = Loaders.cfTypeId(loader)

    fun search(
        query: String, mc: String, loader: String, key: String,
        limit: Int = 20, index: Int = 0, classId: Int = 6
    ): List<MarketMod> {
        val b = base(key)
        val headers = if (key.isNotBlank()) mapOf("x-api-key" to key) else emptyMap()
        var url = "$b/mods/search?gameId=432&classId=$classId&searchFilter=${Http.enc(query)}" +
            "&pageSize=$limit&index=$index&sortField=6&sortOrder=desc"
        if (mc.isNotBlank()) url = "$url&gameVersion=${Http.enc(mc)}"
        val lt = loaderType(loader)
        if (lt != 0) url = "$url&modLoaderType=$lt"
        val root = Json.obj(Http.get(url, headers, timeout = Http.SHORT)) ?: return emptyList()
        val data = Json.a(root, "data") ?: return emptyList()
        val out = mutableListOf<MarketMod>()
        for (d in data) {
            val slug = Json.s(d, "slug")
            var fileId = ""
            var fileName = ""
            val lf = Json.a(d, "latestFiles")
            var cfLoaders: List<String> = emptyList()
            if (lf != null && lf.size() > 0) {
                val f0 = lf.get(0)
                fileId = Json.s(f0, "id")
                fileName = Json.s(f0, "fileName")
                val all = ArrayList<String>()
                for (i in 0 until lf.size()) {
                    val f = lf.get(i)
                    all.addAll(Json.sa(f, "gameVersions"))
                }
                // 用 Loaders.clean：归一化 + 丢掉认不出来的 + 去重 + 稳定排序，
                // 与 Modrinth 那条路径保持一致（不然两个来源显示顺序会不一样）
                cfLoaders = Loaders.clean(all)
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
                    updated = Json.s(d, "dateModified").ifBlank { Json.s(d, "dateReleased") },
                    loaders = cfLoaders,
                    categories = ModCats.clean(cfCats(d))
                )
            )
        }
        return out
    }

    /** CurseForge 的分类是对象数组，取出 name 组成字符串数组 */
    private fun cfCats(e: JsonElement?): List<String> {
        val arr = Json.a(e, "categories") ?: return emptyList()
        val out = ArrayList<String>()
        for (c in arr) {
            val n = Json.s(c, "slug").ifBlank { Json.s(c, "name") }
            if (n.isNotBlank()) out.add(n)
        }
        return out
    }

    private fun logo(e: JsonElement?): String {
        if (e == null || !e.isJsonObject) return ""
        val l = e.asJsonObject.get("logo") ?: return ""
        val thumb = Json.s(l, "thumbnailUrl")
        return thumb.ifBlank { Json.s(l, "url") }
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

    /** 取某个文件的真实下载地址（后台线程调用） */
    fun fileUrl(modId: String, fileId: String, fileName: String, key: String): String {
        val b = base(key)
        val headers = if (key.isNotBlank()) mapOf("x-api-key" to key) else emptyMap()
        if (modId.isNotBlank() && fileId.isNotBlank()) {
            val u = "$b/mods/$modId/files/$fileId/download-url"
            val real = runCatching {
                val root = Json.obj(Http.get(u, headers, timeout = Http.SHORT))
                if (root == null) "" else Json.s(root, "data")
            }.getOrDefault("")
            if (real.isNotBlank()) {
                return if (mirrorOn(key)) toMirror(real) else real
            }
        }
        val p = cdnPath(fileId, fileName)
        return if (p.isBlank()) "" else if (mirrorOn(key)) "$FILE_MIRROR/$p" else "$FILE_OFFICIAL/$p"
    }

    data class CfFile(
        var id: String = "",
        var fileName: String = "",
        var version: String = "",
        var date: String = ""
    )

    /** 取项目的文件列表，按发布时间倒序 */
    fun files(modId: String, mc: String, loader: String, key: String, limit: Int = 10): List<CfFile> {
        val b = base(key)
        val headers = if (key.isNotBlank()) mapOf("x-api-key" to key) else emptyMap()
        var url = "$b/mods/${Http.enc(modId)}/files?pageSize=$limit"
        if (mc.isNotBlank()) url += "&gameVersion=${Http.enc(mc)}"
        val lt = loaderType(loader)
        if (lt != 0) url += "&modLoaderType=$lt"
        val root = Json.obj(Http.get(url, headers, timeout = Http.SHORT)) ?: return emptyList()
        val data = Json.a(root, "data") ?: return emptyList()
        val out = mutableListOf<CfFile>()
        for (d in data) {
            val fn = Json.s(d, "fileName")
            if (!fn.endsWith(".jar", true)) continue
            out.add(
                CfFile(
                    id = Json.s(d, "id"),
                    fileName = fn,
                    version = Json.s(d, "displayName").ifBlank { fn },
                    date = Json.s(d, "fileDate")
                )
            )
        }
        return out.sortedByDescending { it.date }
    }

    /** 取真实下载地址（同步，必须在**后台线程**调用） */
    fun fetchDownloadUrl(mod: MarketMod): String {
        val ctx = Prefs.appCtx() ?: return downloadUrl(mod)
        val key = Prefs.get(ctx).getString(K.CF_KEY, "") ?: ""
        val u = fileUrl(mod.id, mod.fileId, mod.fileName, key)
        return u.ifBlank { downloadUrl(mod) }
    }

    private fun toMirror(url: String): String {
        return url
            .replace("https://edge.forgecdn.net", FILE_MIRROR_HOST)
            .replace("https://mediafilez.forgecdn.net", FILE_MIRROR_HOST)
    }

    private const val FILE_MIRROR_HOST = "https://mod.mcimirror.top"
}
