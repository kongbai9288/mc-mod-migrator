package com.kongbai.modmigrator

import android.content.Context

/**
 * ModMarket 后端（Cloudflare Worker）客户端。
 *
 * 后端帮你做两件事，所以 App 侧不需要用户自备 CurseForge Key：
 *   1. GitHub OAuth 登录（HMAC 签名 Cookie，后端托管 Client Secret）
 *   2. CurseForge 代理（隐藏 API Key、绕过 CORS、代理下载流）
 *
 * 地址不写死：主地址 + 兜底地址依次尝试。后端 wrangler.toml 里
 * 保留了 workers.dev 降级入口，这里只做 App 侧兼容，不改后端任何配置。
 */
object BackendApi {

    const val DEFAULT_BASE = "https://api.kongbaisever.cc.cd"

    /**
     * 后端同时开了两个入口（wrangler.toml 里 workers_dev + 自定义域）：
     *   自定义域 https://api.kongbaisever.cc.cd
     *   workers  https://modmarket.3566500461.workers.dev
     * 后端用 ${url.origin} 拼 redirect_uri，所以走哪个域名访问，
     * 回调地址就是哪个域名——GitHub 白名单里必须登记你实际走的那个。
     */
    const val WORKERS_BASE = "https://modmarket.3566500461.workers.dev"

    /** 后端所有已知入口，供设置页展示与切换 */
    fun knownBases(): List<String> = listOf(DEFAULT_BASE, WORKERS_BASE)

    fun candidates(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val main = Prefs.get(ctx).getString(K.BACKEND_BASE, "")?.trim()?.trimEnd('/') ?: ""
        if (main.isNotBlank()) out.add(main)
        val backup = Prefs.get(ctx).getString(K.BACKEND_BACKUP, "")?.trim()?.trimEnd('/') ?: ""
        if (backup.isNotBlank() && !out.contains(backup)) out.add(backup)
        if (!out.contains(DEFAULT_BASE)) out.add(DEFAULT_BASE)
        if (!out.contains(WORKERS_BASE)) out.add(WORKERS_BASE)
        return out
    }

    fun base(ctx: Context): String = candidates(ctx).first()

    /** 依次尝试主地址与兜底地址，返回第一个成功的响应体 */
    private fun getAny(ctx: Context, path: String): String? {
        for (b in candidates(ctx)) {
            try {
                return Http.get(b + path)
            } catch (t: Throwable) {
                // 换下一个兜底地址
            }
        }
        return null
    }

    /** 依次尝试，返回「实际可用的 base」与「响应体」 */
    private fun getAnyWithBase(ctx: Context, path: String): Pair<String, String>? {
        for (b in candidates(ctx)) {
            try {
                return Pair(b, Http.get(b + path))
            } catch (t: Throwable) {
                // 换下一个兜底地址
            }
        }
        return null
    }

    data class Config(
        var githubClientId: String = "",
        var gameId: Int = 432,
        var curseforgeReady: Boolean = false
    )

    data class User(
        var login: String = "",
        var name: String = "",
        var avatarUrl: String = ""
    )

    fun config(ctx: Context): Config? {
        val o = Json.obj(getAny(ctx, "/api/config") ?: return null) ?: return null
        return Config(
            githubClientId = Json.s(o, "githubClientId"),
            gameId = Json.i(o, "gameId"),
            curseforgeReady = Json.b(o, "curseforgeReady")
        )
    }

    fun loginUrl(ctx: Context): String {
        val o = Json.obj(getAny(ctx, "/api/auth/login") ?: return "") ?: return ""
        return Json.s(o, "url")
    }

    /**
     * 后端实际使用的回调地址。
     * 后端按访问域名动态拼接，所以这里返回「当前连通的那个 base」对应的地址。
     */
    fun callbackUrl(ctx: Context): String {
        val r = getAnyWithBase(ctx, "/api/config")
        val b = r?.first ?: base(ctx)
        return "$b/api/auth/callback"
    }

    /** 后端每个入口各自对应的回调地址，都要在 GitHub OAuth App 里登记 */
    fun allCallbackUrls(): List<String> =
        knownBases().map { "$it/api/auth/callback" }

    fun me(ctx: Context): User? {
        val o = Json.obj(getAny(ctx, "/api/auth/me") ?: return null) ?: return null
        val u = o.asJsonObject.get("user")
        if (u == null || u.isJsonNull) return null
        return User(
            login = Json.s(u, "login"),
            name = Json.s(u, "name").ifBlank { Json.s(u, "login") },
            avatarUrl = Json.s(u, "avatar_url").ifBlank { Json.s(u, "avatarUrl") }
        )
    }

    fun logout(ctx: Context) {
        try {
            getAny(ctx, "/api/auth/logout")
        } catch (t: Throwable) {
        }
    }

    /** 后端搜索（CurseForge 代理）。全部地址都失败返回 emptyList。 */
    fun search(
        ctx: Context, query: String, mc: String, loader: String,
        page: Int = 0, pageSize: Int = 20, sort: String = "popularity"
    ): List<MarketMod> {
        for (b in candidates(ctx)) {
            var url = "$b/api/mods?q=${Http.enc(query)}&page=$page&pageSize=$pageSize&sort=$sort"
            if (mc.isNotBlank()) url = "$url&version=${Http.enc(mc)}"
            val body = try {
                Http.get(url)
            } catch (t: Throwable) {
                continue
            }
            val root = Json.obj(body) ?: continue
            val arr = Json.a(root, "mods") ?: continue
            val out = mutableListOf<MarketMod>()
            for (d in arr) {
                val id = Json.s(d, "id")
                val slug = Json.s(d, "slug")
                out.add(
                    MarketMod(
                        id = id,
                        slug = slug,
                        name = Json.s(d, "name"),
                        summary = Json.s(d, "summary"),
                        iconUrl = Json.s(d, "thumb"),
                        pageUrl = "https://www.curseforge.com/minecraft/mc-mods/$slug",
                        downloads = Json.l(d, "downloads"),
                        source = "backend"
                    )
                )
            }
            return out
        }
        return emptyList()
    }

    data class RemoteFile(
        var id: String = "",
        var name: String = "",
        var display: String = "",
        var size: Long = 0,
        var url: String = ""
    )

    fun files(ctx: Context, id: String, mc: String, loader: String): List<RemoteFile> {
        for (b in candidates(ctx)) {
            var url = "$b/api/mods/${Http.enc(id)}/files"
            val qs = mutableListOf<String>()
            if (mc.isNotBlank()) qs.add("version=${Http.enc(mc)}")
            if (loader.isNotBlank() && loader != "auto") qs.add("loader=${Http.enc(loader)}")
            if (qs.isNotEmpty()) url = "$url?${qs.joinToString("&")}"
            val body = try {
                Http.get(url)
            } catch (t: Throwable) {
                continue
            }
            val root = Json.obj(body) ?: continue
            val arr = Json.a(root, "files") ?: continue
            val out = mutableListOf<RemoteFile>()
            for (d in arr) {
                out.add(
                    RemoteFile(
                        id = Json.s(d, "id"),
                        name = Json.s(d, "name"),
                        display = Json.s(d, "display"),
                        size = Json.l(d, "size"),
                        url = Json.s(d, "url")
                    )
                )
            }
            return out
        }
        return emptyList()
    }

    /** files 里返回的 url 是相对路径，这里补成绝对地址 */
    fun absolute(ctx: Context, maybeRelative: String, usedBase: String = ""): String {
        if (maybeRelative.startsWith("http")) return maybeRelative
        val b = usedBase.ifBlank { base(ctx) }
        return b + if (maybeRelative.startsWith("/")) maybeRelative else "/$maybeRelative"
    }

    fun categories(ctx: Context): List<Pair<String, String>> {
        val root = Json.obj(getAny(ctx, "/api/categories") ?: return emptyList()) ?: return emptyList()
        val arr = when {
            root.has("categories") -> root.get("categories")
            root.has("data") -> root.get("data")
            else -> null
        }
        if (arr == null || !arr.isJsonArray) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (d in arr.asJsonArray) {
            val o = d.asJsonObject
            val id = Json.s(o, "id")
            val name = Json.s(o, "name")
            if (id.isNotBlank() && name.isNotBlank()) out.add(Pair(id, name))
        }
        return out
    }

    /** 连通性探测：给设置页用，失败时区分「被墙/超时」与「接口报错」，并给出安抚文案 */
    fun probe(ctx: Context): Pair<Boolean, String> {
        for (b in candidates(ctx)) {
            return try {
                val o = Json.obj(Http.get("$b/api/config"))
                if (o == null) {
                    Pair(false, "后端返回异常，稍后再试试就好")
                } else {
                    val ready = Json.b(o, "curseforgeReady")
                    Pair(true, "已连接 ${short(b)} · CurseForge ${if (ready) "已配置" else "未配置"}")
                }
            } catch (t: Throwable) {
                val m = t.message ?: ""
                when {
                    m.contains("timeout", true) || m.contains("timed out", true) ->
                        Pair(false, "${short(b)} 连接超时：国内访问 Cloudflare 常被限速，不是你的问题，换个网络或稍后再试")
                    m.contains("UnknownHost", true) || m.contains("resolve", true) ->
                        Pair(false, "${short(b)} 域名解析失败：可能被墙或地址写错了，可在设置里换兜底地址")
                    m.contains("HTTP 5", true) ->
                        Pair(false, "${short(b)} 后端暂时不可用（5xx），稍后会自动恢复")
                    else -> Pair(false, "${short(b)} ${m.take(70)}")
                }
            }
        }
        return Pair(false, "所有后端地址都连不上，可在设置里填写自己的地址")
    }

    private fun short(u: String): String = u.removePrefix("https://").take(28)
}
