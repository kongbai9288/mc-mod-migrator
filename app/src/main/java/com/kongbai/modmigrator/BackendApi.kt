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
 *
 * ── 接口清单（依据后端 src/index.js 实际路由核对，不是猜的）──
 *   GET /api/config                 → {githubClientId, gameId, curseforgeReady}
 *   GET /api/auth/login             → {url} + 种 mm_oauth_state cookie
 *   GET /api/auth/callback          → 校验 state 后种 mm_session，302 → /?login=ok
 *   GET /api/auth/me                → {user} 或 {user:null}
 *   GET /api/auth/logout            → 清 mm_session
 *   GET /api/mods                   → {mods:[slimMod], pagination}
 *   GET /api/mods/:id               → {mod}
 *   GET /api/mods/:id/files         → {files:[...]}
 *   GET /api/download?modId&fileId  → 文件流
 *   GET /api/categories             → {categories:[...]}
 *
 * 后端**没有** /api/auth/token，也**没有** /recommend。
 * 这两个路径之前被凭空调用过，必然 404，详见 docs/接口对照-后端.md。
 *
 * 另外：后端 readSession() 只在 /api/auth/me 里被调用过一次，
 * 也就是除查询登录态外，所有业务接口都不要求登录。
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
                return Http.get(b + path, timeout = Http.SHORT)
            } catch (t: Throwable) {
                // 换下一个兜底地址
                     Err.ignore(t, "换下一个兜底地址")
                 }
        }
        return null
    }

    /** 依次尝试，返回「实际可用的 base」与「响应体」 */
    private fun getAnyWithBase(ctx: Context, path: String): Pair<String, String>? {
        for (b in candidates(ctx)) {
            try {
                return Pair(b, Http.get(b + path, timeout = Http.SHORT))
            } catch (t: Throwable) {
                // 换下一个兜底地址
                     Err.ignore(t, "换下一个兜底地址")
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

    /**
     * 登录入口固定走 workers.dev。
     * 原因：后端用 ${url.origin} 拼 redirect_uri，而 GitHub OAuth App 里
     * 只登记了 workers.dev 这一条回调地址。走自定义域会因回调未登记而报
     * redirect_uri 不匹配。搜索等接口仍可用自定义域并自动兜底。
     */
    fun authBase(): String = WORKERS_BASE

    /** 发起登录的结果：要么拿到授权地址，要么拿到一句人话错误 */
    data class LoginStart(
        val url: String = "",
        val error: String = "",
        /** 后端的 state cookie 是否真的写进了 CookieManager */
        val stateCookieOk: Boolean = false
    )

    /**
     * 取 GitHub 授权地址。
     *
     * 后端 /api/auth/login 一次干两件事（见后端源码）：
     *   1. 返回 `{url}` —— GitHub 授权页地址
     *   2. 在响应头里种 `mm_oauth_state` cookie
     *
     * 而 /api/auth/callback 会拿这个 state 做 CSRF 校验，
     * **对不上就直接返回 "state 校验失败，请重新登录"**。
     *
     * 也就是说：如果第 2 步的 cookie 没落到 WebView 的 cookie 存储里，
     * 用户会一路点到 GitHub、授权成功、然后回调失败 —— 全程没有任何提示，
     * 看起来就是"登录一点都登不了"。
     *
     * 所以这里拿到地址后立刻自检，把结果记进日志，让失败变得可诊断。
     */
    fun loginUrl(ctx: Context): LoginStart {
        val b = authBase()
        val body = try {
            Http.get(b + "/api/auth/login")
        } catch (t: Throwable) {
            return LoginStart(error = "连不上后端（${short(b)}）：${Http.describeError(t)}")
        }
        val o = Json.obj(body) ?: return LoginStart(error = "后端返回的内容不是 JSON")
        val url = Json.s(o, "url")
        if (url.isBlank()) return LoginStart(error = "后端没返回 GitHub 授权地址")
        if (!url.contains("github.com")) return LoginStart(error = "授权地址异常：$url")

        val ok = WebCookies.hasCookie(b, "mm_oauth_state")
        if (!ok) {
            LogCenter.w(
                "Backend",
                "mm_oauth_state 没写进 CookieManager" +
                    "（该域名下现有：${WebCookies.cookieNames(b)}），" +
                    "回调时很可能报 state 校验失败"
            )
        }
        return LoginStart(url = url, stateCookieOk = ok)
    }

    /**
     * 这里原来有个 fetchToken()，请求 /api/auth/token 想自动取回 GitHub token。
     *
     * 但后端源码的路由表里**根本没有这个路径**（见 docs/接口对照-后端.md），
     * 请求只会拿到 404 {"error":"not found"}，于是"自动获取 token"从来没成功过。
     * 而且后端把 Client Secret 托管在服务端，本来也不该把 token 下发给客户端。
     *
     * 现在删除。登录只作为"身份展示 + 将来可能的鉴权"，不再承诺能拿到 token。
     */

    /** 是否已经通过后端完成 GitHub 授权 */
    fun authed(ctx: Context): Boolean = me(ctx) != null

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
        // 登录态 cookie 绑定在 workers.dev 下，这里固定走同一域名
        val o = try {
            Json.obj(Http.get(authBase() + "/api/auth/me"))
        } catch (t: Throwable) {
            null
        } ?: return null
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
            Http.get(authBase() + "/api/auth/logout")
        } catch (t: Throwable) { Err.ignore(t, "Http.get(authBase() + \"/api/auth/logout\")") }
    }

    /** 后端搜索（CurseForge 代理）。全部地址都失败返回 emptyList。 */
    /**
     * 后端搜索（CurseForge 代理）。全部地址都失败返回 emptyList。
     *
     * @param offset **0 基起始偏移**，不是页码。
     *   后端源码：cf.searchParams.set('index', page)
     *   即把我们的 page 参数**原样透传**给 CurseForge 的 index，
     *   而 CF 的 index 是 0 基偏移。之前这里传的是 offset/20（0,1,2…），
     *   导致第 2 页取到 1..20 —— 和第 1 页几乎完全重复。现在传真实偏移。
     */
    fun search(
        ctx: Context, query: String, mc: String, loader: String,
        offset: Int = 0, pageSize: Int = 20, sort: String = "popularity"
    ): List<MarketMod> {
        // 后端对 /api/mods 有每 IP 每分钟 120 次的限流，超限返回 429
        // （响应体是中文"请求过于频繁"，头里带 retry-after）。
        // 之前 429 和"地址不通"一样被 continue 掉，最后返回空列表，
        // 上层当成"没结果"——用户只看到搜不出东西，
        // 既不知道是限流，也不知道等一会儿就好。这里单独识别。
        var rateLimited = false
        for (b in candidates(ctx)) {
            var url = "$b/api/mods?q=${Http.enc(query)}&page=$offset&pageSize=$pageSize&sort=$sort"
            if (mc.isNotBlank()) url = "$url&version=${Http.enc(mc)}"
            val body = try {
                // ⚠️ 必须走短超时（连接 6s / 读取 12s）。
                // 聚合搜索并发发多个源、线程池固定，
                // 后端卡住会一直占着线程，连搜几次池子就被占满，
                // 后面的搜索只能排队 —— 表现就是"越搜越慢"。
                // 之前没传 timeout，用的是默认 120 秒。
                Http.get(url, timeout = Http.SHORT)
            } catch (t: Throwable) {
                if ((t.message ?: "").contains("429")) rateLimited = true
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
                        // slimMod 里有 dateModified，之前没解析，
                        // 导致"按更新时间排序"时后端来源的项永远排在最后。
                        updated = Json.s(d, "dateModified"),
                        source = "backend"
                    )
                )
            }
            return out
        }
        // 所有候选地址都因限流失败：抛出去让上层显示"请求过于频繁"，
        // 不能静默返回空列表 —— 那会被当成"没搜到"，用户处理方式完全不同。
        if (rateLimited) {
            throw RuntimeException("后端限流（429）：请求过于频繁，等约 1 分钟再试")
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
                // ⚠️ 必须走短超时（连接 6s / 读取 12s）。
                // 聚合搜索并发发多个源、线程池固定，
                // 后端卡住会一直占着线程，连搜几次池子就被占满，
                // 后面的搜索只能排队 —— 表现就是"越搜越慢"。
                // 之前没传 timeout，用的是默认 120 秒。
                Http.get(url, timeout = Http.SHORT)
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

    /**
     * 后端推荐榜。
     *
     * 之前请求的是 `${base}/recommend` —— 后端源码里 recommend **一次都没出现过**，
     * 这个路径根本不存在。请求只会拿到 404，异常被 catch 吞掉返回 null，
     * 上层跳过这个源。结果就是：推荐功能从来没出过任何内容，而且毫无提示。
     *
     * 现在改用真实存在的 `/api/mods`：空关键词 + 按热度排序，拿到的就是热门榜单。
     *
     * 注意：后端 /api/mods 只支持 version 和 categoryId，**不支持按加载器过滤**，
     * 所以这里只能过滤游戏版本，加载器需要在客户端再筛一道（或直接不过滤）。
     */
    fun recommend(ctx: Context, mc: String, loader: String): List<MarketMod>? {
        return try {
            val list = search(ctx, "", mc, "", 0, 30, "popularity")
            if (list.isEmpty()) null else list
        } catch (t: Throwable) {
            Err.ignore(t, "后端推荐（走 /api/mods 热门榜）")
            null
        }
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

    /**
     * 连通性探测：给设置页用，失败时区分「被墙/超时」与「接口报错」，并给出安抚文案。
     *
     * ⚠️ 之前 `for (b in candidates(ctx)) { return try {...} catch {...} }`
     * 在循环体里**无条件 return** —— 无论成功失败都在**第一个**候选地址就返回了，
     * 后面的兜底地址**一次都不会被尝试**。
     * 于是提示里写的"可在设置里换兜底地址"根本没用：
     * 主地址（workers.dev）在国内连不上时，就直接判失败了，
     * 而自定义域那个能连通的入口压根没试过。
     *
     * 现在：**成功才返回**；失败就记下原因继续试下一个，
     * 全部失败时把每个地址的原因都列出来。
     */
    fun probe(ctx: Context): Pair<Boolean, String> {
        val reasons = ArrayList<String>()
        val all = candidates(ctx)
        if (all.isEmpty()) {
            return Pair(false, "没有配置后端地址，可在设置里填写自己的地址")
        }
        for (b in all) {
            try {
                val o = Json.obj(Http.get("$b/api/config", timeout = Http.SHORT))
                if (o == null) {
                    reasons.add("${short(b)} 返回异常")
                    continue
                }
                val ready = Json.b(o, "curseforgeReady")
                return Pair(
                    true,
                    "已连接 ${short(b)} · CurseForge ${if (ready) "已配置" else "未配置"}"
                )
            } catch (t: Throwable) {
                val m = t.message ?: ""
                reasons.add(
                    when {
                        m.contains("timeout", true) || m.contains("timed out", true) ->
                            "${short(b)} 连接超时（国内访问 Cloudflare 常被限速）"
                        m.contains("UnknownHost", true) || m.contains("resolve", true) ->
                            "${short(b)} 域名解析失败（可能被墙或地址写错）"
                        m.contains("HTTP 5", true) ->
                            "${short(b)} 后端暂时不可用（5xx）"
                        else -> "${short(b)} ${m.take(70)}"
                    }
                )
            }
        }
        val detail = reasons.joinToString("\n  · ")
        return Pair(
            false,
            "${all.size} 个后端地址都没连上：\n  · $detail\n" +
                "可在设置里填写自己的地址。"
        )
    }

    private fun short(u: String): String = u.removePrefix("https://").take(28)
}
