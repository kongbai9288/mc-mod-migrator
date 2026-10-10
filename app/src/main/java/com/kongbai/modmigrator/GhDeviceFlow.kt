package com.kongbai.modmigrator

import org.json.JSONObject

/**
 * GitHub OAuth —— **设备流**（Device Flow，RFC 8628）。
 *
 * ## 为什么必须换成这条路
 *
 * 之前那套 PKCE 之所以一直报
 * `The client_id and/or client_secret is incorrect`，
 * 根因不在回调地址、也不在 Client ID —— 而是：
 *
 * **GitHub OAuth App 的换 token 端点仍然要求 client_secret，PKCE 只是附加项。**
 * GitHub 文档把 `client_secret` 标为「必需」，`code_verifier` 标为「强烈建议」；
 * 也就是说 PKCE 在 GitHub 的实现里是 *additive*，不是 *replacement*。
 * 而 App 里藏 secret 等于公开（反编译就能拿到），这条路从结构上走不通。
 *
 * 设备流则完全不同：整个流程**只需要 client_id**，不需要任何 secret。
 * 这正是它能在一个装在你手机上的 App 里跑通的原因。
 *
 * ## 流程
 *
 * ```
 * 1. POST https://github.com/login/device/code
 *      { client_id, scope }
 *    → device_code / user_code / verification_uri / expires_in / interval
 *
 * 2. 把 user_code 显示给用户（形如 WDJB-MJHT），
 *    让他自己打开 verification_uri 输入。
 *    这一步在**任何设备**上做都行 —— 手机浏览器、电脑都无所谓。
 *
 * 3. 按 interval 轮询 POST https://github.com/login/oauth/access_token
 *      { client_id, device_code, grant_type=…device_code }
 *    → access_token，或 error=authorization_pending（还没输，继续等）
 * ```
 *
 * ## 对用户的好处
 *
 * - **不用再配回调地址**：没有 redirect_uri，也就没有「redirect_uri 不匹配」
 * - **不用自定义 scheme**：不需要 `mm://`，不需要 intent-filter
 * - **进程被杀也不怕**：device_code 落在磁盘上，重开可以继续轮询
 * - **passkey 能正常用**：授权在用户自己的浏览器里完成，不受 WebView 限制
 *
 * ## 一个必须的前置条件
 *
 * OAuth App 设置页里的 **Device Flow 默认是关闭的**，要手动勾上
 * 「Enable Device Flow」。没勾的话第 1 步直接失败，
 * 界面上会明确提示这一点，而不是含糊地说「登录失败」。
 */
object GhDeviceFlow {

    const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

    private const val URL_CODE = "https://github.com/login/device/code"
    private const val URL_TOKEN = "https://github.com/login/oauth/access_token"

    /** 默认申请的权限：读账号信息。够用且最小。 */
    const val DEFAULT_SCOPE = "read:user user:email"

    private const val P_DEVICE_CODE = "gh_device_code"
    private const val P_USER_CODE = "gh_user_code"
    private const val P_VERIFY_URI = "gh_verify_uri"
    private const val P_INTERVAL = "gh_device_interval"
    private const val P_DEADLINE = "gh_device_deadline"
    private const val P_CLIENT_ID = "gh_device_client_id"
    private const val P_SCOPE = "gh_device_scope"

    /** 第 1 步的返回 */
    data class Start(
        val deviceCode: String,
        val userCode: String,
        val verifyUri: String,
        val expiresIn: Int,
        val interval: Int
    )

    /** 第 3 步的返回 */
    sealed class Poll {
        /** 拿到了令牌 */
        data class Ok(val token: String) : Poll()
        /** 用户还没在页面上输码，继续等 */
        object Pending : Poll()
        /** 被限流了：下一次要等更久 */
        data class SlowDown(val interval: Int) : Poll()
        /** 码过期了，必须重新起一轮 */
        object Expired : Poll()
        /** 用户在页面上点了取消 */
        object Denied : Poll()
        /** 别的错误 */
        data class Fail(val message: String) : Poll()
    }

    /**
     * 第 1 步：申请设备码。
     *
     * @throws IllegalStateException 失败原因（含「Device Flow 没启用」的明确提示）
     */
    fun start(clientId: String, scope: String = DEFAULT_SCOPE): Start {
        if (clientId.isBlank()) throw IllegalStateException("还没填 Client ID")

        val body = JSONObject().apply {
            put("client_id", clientId)
            put("scope", scope)
        }.toString()

        val raw = Http.postJson(
            URL_CODE, body,
            mapOf("accept" to "application/json")
        )

        // 设备流的错误响应是 200 + error 字段，不是 HTTP 错误码，
        // 所以这里必须自己看 error，不能只看有没有抛异常。
        val o = Json.obj(raw)
            ?: throw IllegalStateException("GitHub 返回的内容不是 JSON：${raw.take(120)}")
        val err = Json.s(o, "error")
        if (err.isNotBlank()) throw IllegalStateException(explain(err, Json.s(o, "error_description")))

        val device = Json.s(o, "device_code")
        val user = Json.s(o, "user_code")
        if (device.isBlank() || user.isBlank()) {
            throw IllegalStateException("GitHub 没返回设备码（Device Flow 可能没启用）")
        }

        val uri = Json.s(o, "verification_uri").ifBlank { "https://github.com/login/device" }
        val exp = Json.i(o, "expires_in", 900)
        val iv = Json.i(o, "interval", 5).coerceAtLeast(5)

        return Start(device, user, uri, exp, iv)
    }

    /** 把这一轮的设备码记下来：进程被杀后重开还能接着轮询 */
    fun save(ctx: android.content.Context, clientId: String, scope: String, s: Start) {
        Prefs.get(ctx).edit()
            .putString(P_CLIENT_ID, clientId)
            .putString(P_SCOPE, scope)
            .putString(P_DEVICE_CODE, s.deviceCode)
            .putString(P_USER_CODE, s.userCode)
            .putString(P_VERIFY_URI, s.verifyUri)
            .putInt(P_INTERVAL, s.interval)
            .putLong(P_DEADLINE, System.currentTimeMillis() + s.expiresIn * 1000L)
            .apply()
    }

    /** 上次那轮还在有效期内吗 */
    fun hasPending(ctx: android.content.Context): Boolean {
        val p = Prefs.get(ctx)
        val code = p.getString(P_DEVICE_CODE, "") ?: ""
        val dead = p.getLong(P_DEADLINE, 0L)
        return code.isNotBlank() && dead > System.currentTimeMillis()
    }

    /** 取出上次那轮（用于界面显示「已经在进行中」） */
    fun peek(ctx: android.content.Context): Triple<String, String, String>? {
        if (!hasPending(ctx)) return null
        val p = Prefs.get(ctx)
        return Triple(
            p.getString(P_USER_CODE, "") ?: "",
            p.getString(P_VERIFY_URI, "") ?: "",
            p.getString(P_DEVICE_CODE, "") ?: ""
        )
    }

    fun clear(ctx: android.content.Context) {
        Prefs.get(ctx).edit()
            .remove(P_DEVICE_CODE).remove(P_USER_CODE).remove(P_VERIFY_URI)
            .remove(P_INTERVAL).remove(P_DEADLINE)
            .apply()
    }

    /**
     * 第 3 步：用设备码换 token。
     *
     * ⚠️ 这里的响应**几乎总是 HTTP 200**，
     * 「还没输码 / 太频繁 / 已过期 / 被拒绝」全都是靠 body 里的 error 字段区分的。
     * 当成 HTTP 错误处理的话，会把「还没输码」误判成真失败、直接中断轮询。
     */
    fun poll(ctx: android.content.Context): Poll {
        val p = Prefs.get(ctx)
        val clientId = p.getString(P_CLIENT_ID, "") ?: ""
        val device = p.getString(P_DEVICE_CODE, "") ?: ""
        if (clientId.isBlank() || device.isBlank()) return Poll.Fail("没有进行中的设备码")

        if (p.getLong(P_DEADLINE, 0L) <= System.currentTimeMillis()) {
            clear(ctx)
            return Poll.Expired
        }

        val body = JSONObject().apply {
            put("client_id", clientId)
            put("device_code", device)
            put("grant_type", GRANT_TYPE)
        }.toString()

        val raw = try {
            Http.postJson(URL_TOKEN, body, mapOf("accept" to "application/json"))
        } catch (t: Throwable) {
            // 网络抖动不能当成「用户拒绝了」，返回 Fail 让调用方重试
            return Poll.Fail(Http.describeError(t))
        }

        val o = Json.obj(raw) ?: return Poll.Fail("GitHub 返回的内容不是 JSON")
        val token = Json.s(o, "access_token")
        if (token.isNotBlank()) {
            clear(ctx)
            return Poll.Ok(token)
        }

        return when (Json.s(o, "error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown(Json.i(o, "interval", 5).coerceAtLeast(5))
            "expired_token" -> { clear(ctx); Poll.Expired }
            "access_denied" -> { clear(ctx); Poll.Denied }
            "unsupported_grant_type" -> Poll.Fail("grant_type 不对（应为 $GRANT_TYPE）")
            "incorrect_client_credentials" -> Poll.Fail("Client ID 不对，请重填")
            "incorrect_device_code" -> { clear(ctx); Poll.Fail("设备码无效，请重新开始") }
            else -> Poll.Fail(
                Json.s(o, "error_description")
                    .ifBlank { Json.s(o, "error") }
                    .ifBlank { "未知原因：${raw.take(120)}" }
            )
        }
    }

    fun currentInterval(ctx: android.content.Context): Int =
        Prefs.get(ctx).getInt(P_INTERVAL, 5).coerceAtLeast(5)

    /**
     * 把设备流那些不太直观的错误翻成人话。
     *
     * `device_flow_disabled` 是最容易撞上的一个：
     * OAuth App 设页里那个开关默认关着，不勾就一直失败，
     * 而 GitHub 只回一句干巴巴的错误码。
     */
    private fun explain(code: String, desc: String): String {
        val d = desc.ifBlank { code }
        return when (code) {
            "device_flow_disabled" ->
                "这个 OAuth App 没启用设备流。\n\n" +
                    "去 GitHub → Settings → Developer settings → OAuth Apps → " +
                    "你的应用，勾选「Enable Device Flow」再试。"
            "incorrect_client_credentials", "bad_verification_code" ->
                "Client ID 不对：$d"
            "access_denied" -> "授权被拒绝了：$d"
            else -> "GitHub 拒绝了设备码申请：$d"
        }
    }
}
