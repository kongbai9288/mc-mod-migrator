package com.kongbai.modmigrator

object ModrinthApi {

    // v2 搜索端点已迁移到 v3，统一用 v3
    private const val OFFICIAL = "https://api.modrinth.com/v3"
    private const val MIRROR = "https://mod.mcimirror.top/modrinth/v2"

    /**
     * 批量接口（/version_files、/version_files/update、/projects）走 **v2**。
     *
     * 搜索端点已迁到 v3，但这几个批量端点官方仍定义在 v2，
     * 而镜像的路径也是 `/modrinth/v2`，所以两者用同一套 base。
     * 不要拿 v3 的 base 去拼这些路径。
     */
    private const val OFFICIAL_V2 = "https://api.modrinth.com/v2"
    private const val MIRROR_V2 = "https://mod.mcimirror.top/modrinth/v2"

    private fun batchBase(): String = if (Prefs.mirror()) MIRROR_V2 else OFFICIAL_V2

    /**
     * 官方限制单次最多提交的哈希数量。
     * 文档没写死上限，社区实践是 500，这里保守取 400 分批发。
     */
    private const val BATCH = 400

    private fun base(): String = if (Prefs.mirror()) MIRROR else OFFICIAL
    /**
     * 项目标题/别名的本地缓存。
     *
     * 这是 object 单例，而搜索**是并发的**（聚合搜索会同时查多个源、
     * 分页时也会并发），之前用普通 HashMap，多线程同时 put 会导致
     * 内部结构损坏甚至死循环（HashMap 并发扩容的经典问题）。
     * 现在用 ConcurrentHashMap。
     */
    private val titles = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val slugs = java.util.concurrent.ConcurrentHashMap<String, String>()

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
fun search(query: String, mc: String, loader: String, limit: Int = 20, offset: Int = 0): List<MarketMod> {
        val filters = ArrayList<String>()
        filters.add("""project_types=["mod"]""")
        if (mc.isNotBlank()) filters.add("""game_versions=["$mc"]""")
        // 加载器字段是 loaders，不是 categories。
        // 官方 v3 文档里两者是不同字段：categories 是内容分类（optimization 等），
        // loaders 才是 fabric/forge/quilt。之前写成 categories 导致加载器过滤无效。
        if (loader.isNotBlank() && loader != "auto") filters.add("""loaders=["$loader"]""")
        val nf = filters.joinToString(" AND ")
        val url = "${base()}/search?query=${Http.enc(query)}" +
            "&limit=$limit&offset=$offset&index=downloads&new_filters=${Http.enc(nf)}"
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

    /**
 * 按哈希反查项目。
 *
 * **关键改动**：之前 catch 把所有异常都吞成 null，
 * 于是「网络连不上 / 超时」和「这个模组确实没被收录」混为一谈，
 * 用户看到的都是"Modrinth 未识别"——根本分不清是该重试还是该死心。
 *
 * 现在返回结构化结果：
 *  - found=true  → 查到了
 *  - netError=true → 网络问题（可重试），msg 里说明是什么错
 *  - found=false 且 netError=false → 确实没收录（不用重试）
 */
data class LookupResult(
    val found: Boolean,
    val projectId: String = "",
    val version: String = "",
    val slug: String = "",
    val netError: Boolean = false,
    val msg: String = ""
)

fun lookupHash(sha1: String): LookupResult {
    if (sha1.isBlank()) return LookupResult(false)
    return try {
        val o = Json.obj(
            Http.get("${base()}/version_file/$sha1?algorithm=sha1", timeout = Http.SHORT)
        )
        if (o == null) {
            // 返回体解析不出来：多半是网关返回了 HTML 错误页
            LookupResult(false, netError = true, msg = "返回内容不是 JSON（可能被网关拦截）")
        } else {
            val pid = Json.s(o, "project_id")
            if (pid.isBlank()) {
                LookupResult(false)   // 正常响应但没有匹配 → 确实没收录
            } else {
                refresh(pid)
                LookupResult(
                    found = true, projectId = pid,
                    version = Json.s(o, "version_number").ifBlank { Json.s(o, "name") },
                    slug = slugs[pid] ?: ""
                )
            }
        }
    } catch (t: Throwable) {
        LookupResult(false, netError = true, msg = Http.describeError(t))
    }
}

/** 兼容旧调用：只关心"查到没有"的地方 */
fun lookupHashSimple(sha1: String): Triple<String, String, String>? {
    val r = lookupHash(sha1)
    return if (r.found) Triple(r.projectId, r.version, r.slug) else null
}

    // ------------------------------------------------------------------
    // 批量接口
    //
    // 之前扫描 50 个模组要发 150 次串行网络请求
    // （逐个反查 + 逐个拉版本列表 + 逐个取标题），慢得像死机。
    // 官方其实提供了三个批量端点（Prism Launcher 就是这么用的）：
    //   POST /version_files          批量按哈希反查
    //   POST /version_files/update   批量拿最新兼容版本
    //   GET  /projects?ids=[...]     批量取项目标题
    // 50 个模组的请求数从 150 降到 3。
    // ------------------------------------------------------------------

    /**
     * 批量按哈希反查。
     *
     * @param hashes **必须小写**。官方 Issue #2611 实测：大写哈希匹配不上，
     *   请求会静默返回空对象，看起来就像"所有模组都没收录"。
     * @return key 是原始传入的哈希（小写），value 是查到的信息；
     *         **查不到的哈希不会出现在 map 里**（这就是"未收录"的判定依据）。
     */
    fun lookupHashes(hashes: List<String>): Map<String, LookupResult> {
        val out = java.util.concurrent.ConcurrentHashMap<String, LookupResult>()
        val uniq = hashes.map { it.lowercase() }.filter { it.isNotBlank() }.distinct()
        if (uniq.isEmpty()) return out

        for (chunk in uniq.chunked(BATCH)) {
            val body = org.json.JSONObject().apply {
                put("algorithm", "sha1")
                put("hashes", org.json.JSONArray(chunk))
            }.toString()
            val resp = try {
                Http.postJson("${batchBase()}/version_files", body)
            } catch (t: Throwable) {
                // 整批失败：把这一批都标成网络问题，让上层能重试
                val msg = Http.describeError(t)
                for (h in chunk) {
                    out[h] = LookupResult(false, netError = true, msg = msg)
                }
                continue
            }
            val o = Json.obj(resp)
            if (o == null) {
                val msg = "返回内容不是 JSON（可能被网关拦截）"
                for (h in chunk) out[h] = LookupResult(false, netError = true, msg = msg)
                continue
            }
            // 返回的是 Map<hash, Version>，只含匹配上的
            for (h in chunk) {
                val v = o.get(h)
                if (v == null || !v.isJsonObject) continue   // 没收录，不放进 map
                val pid = Json.s(v, "project_id")
                if (pid.isBlank()) continue
                out[h] = LookupResult(
                    found = true,
                    projectId = pid,
                    version = Json.s(v, "version_number").ifBlank { Json.s(v, "name") }
                )
            }
        }
        return out
    }

    /**
     * 批量检查「指定 MC 版本 + 加载器」下的最新版本。
     *
     * 这个端点本身就是为迁移/更新场景设计的：
     * 传一批哈希 + 目标条件，直接返回每个哈希对应的**最新可用版本**，
     * 不用先反查再逐个拉版本列表。
     *
     * @return key 是小写哈希，value 是可下载的文件信息。
     */
    fun latestForHashes(
        hashes: List<String>, mc: String, loader: String
    ): Map<String, ModFile> {
        val out = java.util.concurrent.ConcurrentHashMap<String, ModFile>()
        val uniq = hashes.map { it.lowercase() }.filter { it.isNotBlank() }.distinct()
        if (uniq.isEmpty()) return out

        val body = org.json.JSONObject().apply {
            put("algorithm", "sha1")
            put("hashes", org.json.JSONArray(uniq))
            put("loaders", org.json.JSONArray().apply {
                if (loader.isNotBlank() && loader != "auto") put(loader)
            })
            put("game_versions", org.json.JSONArray().apply {
                if (mc.isNotBlank()) put(mc)
            })
        }.toString()

        val resp = try {
            Http.postJson("${batchBase()}/version_files/update", body)
        } catch (t: Throwable) {
            Err.ignore(t, "批量检查最新版本")
            return out
        }
        val o = Json.obj(resp) ?: return out
        for (h in uniq) {
            val v = o.get(h) ?: continue
            if (!v.isJsonObject) continue
            val files = Json.a(v, "files") ?: continue
            // 优先取 primary 文件，没有就取第一个
            var chosen: com.google.gson.JsonElement? = null
            for (f in files) {
                if (chosen == null || Json.s(f, "primary") == "true") chosen = f
            }
            if (chosen == null) continue
            out[h] = ModFile(
                name = Json.s(v, "name").ifBlank { Json.s(v, "version_number") },
                version = Json.s(v, "version_number"),
                url = mirrorUrl(Json.s(chosen, "url")),
                fileName = Json.s(chosen, "filename")
            )
        }
        return out
    }

    /**
     * 批量取项目标题与 slug。
     *
     * 之前每识别一个模组就单独请求一次 /project/{id}，
     * 50 个模组就是 50 次。官方支持一次传多个 id。
     */
    fun refreshAll(pids: Collection<String>) {
        val need = pids.filter { it.isNotBlank() }.distinct().filter { !titles.containsKey(it) }
        if (need.isEmpty()) return
        for (chunk in need.chunked(100)) {
            try {
                val ids = org.json.JSONArray(chunk).toString()
                val arr = Json.arr(
                    Http.get("${batchBase()}/projects?ids=${Http.enc(ids)}")
                ) ?: continue
                for (p in arr) {
                    val id = Json.s(p, "id")
                    if (id.isBlank()) continue
                    titles[id] = Json.s(p, "title")
                    slugs[id] = Json.s(p, "slug")
                }
            } catch (t: Throwable) {
                Err.ignore(t, "批量取项目信息（${chunk.size} 个）")
            }
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
                 Err.ignore(t, "ignore")
             }
    }

    fun title(pid: String): String = titles[pid] ?: ""

    /** 项目 slug（页面地址用）。批量 refreshAll 之后才有值。 */
    fun slug(pid: String): String = slugs[pid] ?: ""
}
