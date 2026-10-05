package com.kongbai.modmigrator

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * GitHub OAuth —— PKCE 登录（RFC 7636）。
 *
 * ## 为什么必须换这条路
 *
 * 之前的后端中转流程是这样的：
 *   1. 应用用 OkHttp 请求后端 /api/auth/login
 *   2. 后端在响应里 Set-Cookie: mm_oauth_state=xxx
 *      —— 这个 cookie 落在**应用内**的 WebView CookieManager 里
 *   3. 浏览器打开 GitHub 授权页，用户授权
 *   4. GitHub 重定向到后端 /api/auth/callback?code=..&state=..
 *      —— 这一步是**浏览器**发的，浏览器的 jar 里没有 mm_oauth_state
 *   5. 后端拿 URL 里的 state 和请求带来的 cookie 比对 → 对不上
 *      → 返回 {"error":"state 校验失败，请重新登录"}
 *
 * 只要第 2 步和第 4 步不在同一个 cookie jar 里，就必然失败。
 * 换成内置 WebView 能让两边同一个 jar（state 校验能过），
 * 但 WebView 不支持 passkey，GitHub 登录页又卡住 —— 两难。
 *
 * ## 别人的做法（也是 GitHub 官方现在推荐的）
 *
 * RFC 8252：原生应用**必须**用外部用户代理（Chrome Custom Tabs），
 * 且回调要回到应用本身，由应用自己去换 token。
 * GitHub 自 2025-07 起支持 PKCE，公开客户端（装在你手机上的 App
 * 守不住 client secret）**不需要 client_secret**，
 * 只要带上 code_verifier 就能换到 token。
 *
 * 于是整条链路变成：
 *   1. 应用自己生成 code_verifier / code_challenge
 *   2. 用 Chrome Custom Tabs 打开 GitHub 授权页（passkey 能用了）
 *   3. 授权后 GitHub 重定向到 mm://oauth/callback?code=..&state=..
 *      —— 自定义 scheme 会强制浏览器放弃导航、交给系统，
 *         系统按 intent-filter 把 URL 交回给我们
 *   4. 应用自己拿 code + verifier 去 GitHub 换 token
 *
 * **全程没有后端参与，也就没有 state cookie 在不同 jar 之间对不上的问题。**
 * 这是唯一一条同时满足「外部浏览器（passkey 可用）」和「登录态能回到应用」的路。
 */
object GhPkce {

    /**
     * 回调地址。
     *
     * ⚠️ 需要在 GitHub OAuth App 的「Authorization callback URL」里
     * 额外登记这一条（原有的后端那条保留即可，GitHub 允许多条）。
     * 没登记的话 GitHub 会报 redirect_uri 不匹配，
     * 界面上会给出明确提示而不是静默失败。
     */
    const val REDIRECT_URI = "mm://oauth/callback"
    const val SCHEME = "mm"
    const val HOST = "oauth"

    private const val KEY_VERIFIER = "gh_pkce_verifier"

    /** 生成一次性 code_verifier（43~128 字符，高熵随机） */
    fun newVerifier(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return b64url(b)
    }

    /** S256：BASE64URL(SHA256(verifier))，不带 padding */
    fun challenge(verifier: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return b64url(d)
    }

    /** 记住 verifier：授权过程中我们的进程可能被浏览器挤掉，得能恢复 */
    fun saveVerifier(ctx: android.content.Context, v: String) {
        Prefs.get(ctx).edit().putString(KEY_VERIFIER, v).apply()
    }

    fun takeVerifier(ctx: android.content.Context): String {
        val p = Prefs.get(ctx)
        val v = p.getString(KEY_VERIFIER, "") ?: ""
        p.edit().remove(KEY_VERIFIER).apply()
        return v
    }

    fun authorizeUrl(clientId: String, verifier: String): String {
        val state = newVerifier().take(16)
        return "https://github.com/login/oauth/authorize" +
            "?client_id=$clientId" +
            "&redirect_uri=" + java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8") +
            "&scope=" + java.net.URLEncoder.encode("read:user user:email", "UTF-8") +
            "&state=$state" +
            "&code_challenge=" + challenge(verifier) +
            "&code_challenge_method=S256"
    }

    /**
     * 用 code + verifier 换 access_token。
     *
     * 公开客户端用 PKCE 时**不需要 client_secret**，
     * 这也是它能在 App 里跑的原因（App 里藏 secret 等于公开）。
     */
    fun exchange(ctx: android.content.Context, code: String, verifier: String): String {
        val body = org.json.JSONObject().apply {
            put("client_id", Prefs.get(ctx).getString(K.GH_CLIENT_ID, "") ?: "")
            put("code", code)
            put("redirect_uri", REDIRECT_URI)
            put("code_verifier", verifier)
        }.toString()
        val r = Http.postJson(
            "https://github.com/login/oauth/access_token",
            body,
            mapOf("accept" to "application/json")
        )
        val o = Json.obj(r) ?: throw IllegalStateException("换 token 时返回的内容不是 JSON")
        val t = Json.s(o, "access_token")
        if (t.isBlank()) {
            throw IllegalStateException(
                "GitHub 拒绝换 token：" +
                    (Json.s(o, "error_description").ifBlank { Json.s(o, "error") }.ifBlank { "未知原因" })
            )
        }
        return t
    }

    private fun b64url(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
