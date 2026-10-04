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

    fun search(query: String, mc: String, loader: String, key: String, limit: Int = 20, index: Int = 0): List<MarketMod> {
        val b = base(key)
        val headers = if (key.isNotBlank()) mapOf("x-api-key" to key) else emptyMap()
        // index 是**基于 0 的起始偏移**，不是页码。官方约束 index + pageSize <= 10000。
        // 之前只传 pageSize 不传 index，部分镜像/网关会按默认偏移返回，导致结果异常。
        var url = "$b/mods/search?gameId=432&classId=6&searchFilter=${Http.enc(query)}" +
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
            // 先判空再取 [0]：之前直接 lf[0]，空数组时 IndexOutOfBounds 直接崩
            //
            // CurseForge 搜索结果**没有**独立的 loaders 字段，
            // 加载器混在 gameVersions 里（形如 ["1.20.1","Fabric","Forge"]）。
            // 这里把它们挑出来：命中已知加载器的留下，
            // 版本号会 normalize 成 "auto"，正好被过滤掉。
            var cfLoaders: List<String> = emptyList()
            if (lf != null && lf.size() > 0) {
                val f0 = lf.get(0)
                fileId = Json.s(f0, "id")
                fileName = Json.s(f0, "fileName")
                // 用 Loaders.clean：归一化 + 丢掉认不出来的 + 去重 + 稳定排序，
                // 与 Modrinth 那条路径保持一致（不然两个来源显示顺序会不一样）
                cfLoaders = Loaders.clean(Json.sa(f0, "gameVersions"))
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
                    // CurseForge 的 categories 是 [{id,name,slug,...}] 的对象数组，
                    // 与 Modrinth 的字符串数组不同，这里先取 name 再走同一套过滤。
                    // 之前没填，于是搜 CurseForge 时卡片上永远没有分类标签，
                    // 看起来就像"tag 分类功能没做"。
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

    /**
     * 取模组图标。
     *
     * CurseForge 的 logo 对象同时有 `url`（**原图**，动辄几百 KB 甚至几 MB）
     * 和 `thumbnailUrl`（256px 缩略图）。
     * 之前取的是 `url`：列表里 20 条同时加载原图，流量和内存都吃不消，
     * 列表滑动会卡。列表场景必须用缩略图。
     */
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

    /**
     * 取真实下载地址（同步，必须在**后台线程**调用）。
     *
     * ⚠️ 之前的做法问题很大：直接拿 CDN 公式拼地址去下载，
     * 而 CF 的下载页实际要先过「读秒」，下到的往往只是一个 HTML 页面
     * ——代码却当成成功，装进 mods 目录的是个废文件。
     *
     * 联网核实后确认：官方有专门端点
     *   `GET /v1/mods/{modId}/files/{fileId}/download-url`
     * 它直接返回 **{"data": "<真实直链>"}**，**不需要读秒、不需要 WebView**。
     * 这才是正解，之前的 WebView 后台等读秒只能当最后兜底。
     *
     * 三级兜底：
     *   ① download-url 端点（最可靠，官方/镜像都支持）
     *   ② CDN 公式直连
     *   ③ 调用方再走 WebView 等读秒
     *
     * @return 真实下载地址；拿不到时返回用 CDN 公式拼的地址（不会返回空，
     *         让调用方至少还有得试）
     */
    fun fetchDownloadUrl(mod: MarketMod): String {
        val ctx = Prefs.appCtx() ?: return downloadUrl(mod)
        val key = Prefs.get(ctx).getString(K.CF_KEY, "") ?: ""
        val b = base(key)
        val headers = if (key.isNotBlank()) mapOf("x-api-key" to key) else emptyMap()
        // 端点需要 modId 和 fileId，缺任一就只能用公式
        if (mod.id.isNotBlank() && mod.fileId.isNotBlank()) {
            val u = "$b/mods/${mod.id}/files/${mod.fileId}/download-url"
            val real = runCatching {
                val root = Json.obj(Http.get(u, headers, timeout = Http.SHORT))
                if (root == null) "" else Json.s(root, "data")
            }.getOrDefault("")
            if (real.isNotBlank()) {
                // 镜像模式下把拿到的官方直链换成镜像域名，否则国内下不动
                return if (mirrorOn(key)) toMirror(real) else real
            }
        }
        return downloadUrl(mod)
    }

    /**
     * 把官方 CDN 域名换成镜像域名。
     *
     * MCIM 官方给的替换规则是：
     *   api.curseforge.com          → mod.mcimirror.top/curseforge
     *   edge.forgecdn.net           → mod.mcimirror.top
     *   mediafilez.forgecdn.net     → mod.mcimirror.top
     * 注意是**整体替换域名**，路径保持不动，
     * 所以不能简单地在前面拼 "/files"。
     */
    private fun toMirror(url: String): String {
        return url
            .replace("https://edge.forgecdn.net", FILE_MIRROR_HOST)
            .replace("https://mediafilez.forgecdn.net", FILE_MIRROR_HOST)
    }

    private const val FILE_MIRROR_HOST = "https://mod.mcimirror.top"
}
