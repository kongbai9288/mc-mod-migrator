package com.kongbai.modmigrator

import android.content.Context
import android.webkit.CookieManager

/**
 * 登录诊断。
 *
 * 之前"登录不了"只能靠猜——因为整个链路有 5 个环节，
 * 任何一个断了表现都一样（就是登不上）。这 5 个环节是：
 *
 *   1. 后端能不能连通（/api/config）
 *   2. /api/auth/login 能不能拿到 GitHub 授权地址
 *   3. GitHub 回调回来后，后端有没有下发会话 cookie（mm_session）
 *   4. WebView 的 cookie 里到底有没有这个会话
 *   5. /api/auth/me 带上 cookie 后能不能读到用户
 *
 * 这里把每一步**分开测、逐个报**，用户一眼就知道卡在第几步、
 * 是该换网络还是该重新授权，不用再来回猜。
 *
 * 同时把「手动填 token」作为兜底路径：后端这条路走不通时，
 * 用户自己有 GitHub Token 也能直接用上大部分功能。
 */
object LoginDiag {

    data class Step(
        val name: String,
        val ok: Boolean,
        val detail: String
    )

    data class Report(
        val steps: List<Step>,
        val loggedIn: Boolean,
        val user: String = ""
    ) {
        /** 卡在第几步（0 起）；全部通过则返回 -1 */
        val failedAt: Int get() = steps.indexOfFirst { !it.ok }
    }

    /**
     * 逐步诊断。后台线程调用。
     * @param onStep 每测完一步回报一次，界面可以逐条显示
     */
    fun run(ctx: Context, onStep: ((Step) -> Unit)? = null): Report {
        val steps = ArrayList<Step>()
        fun add(name: String, ok: Boolean, detail: String) {
            val s = Step(name, ok, detail)
            steps.add(s)
            onStep?.invoke(s)
        }

        val base = BackendApi.authBase()

        // ---- 第 1 步：后端连通性 ----
        var cfgOk = false
        var cfgDetail = ""
        try {
            val body = Http.get("$base/api/config", timeout = Http.SHORT)
            cfgOk = body.isNotBlank() && body.trimStart().startsWith("{")
            cfgDetail = if (cfgOk) "响应正常（${body.length} 字节）" else "返回内容异常：${body.take(80)}"
        } catch (t: Throwable) {
            cfgDetail = Http.describeError(t)
        }
        add("1. 后端连通（$base）", cfgOk, cfgDetail)

        // ---- 第 2 步：拿授权地址 ----
        var loginUrl = ""
        var urlDetail = ""
        if (cfgOk) {
            try {
                val body = Http.get("$base/api/auth/login", timeout = Http.SHORT)
                val o = Json.obj(body)
                loginUrl = o?.let { Json.s(it, "url") } ?: ""
                urlDetail = when {
                    loginUrl.isNotBlank() && loginUrl.contains("github.com") ->
                        "拿到 GitHub 授权地址"
                    loginUrl.isNotBlank() -> "地址异常：$loginUrl"
                    else -> "后端没返回地址（返回：${body.take(80)}）"
                }
            } catch (t: Throwable) {
                urlDetail = Http.describeError(t)
            }
        } else {
            urlDetail = "跳过：后端没连通"
        }
        add("2. 获取 GitHub 授权地址", loginUrl.contains("github.com"), urlDetail)

        // ---- 第 2.5 步：state cookie 有没有真的写进浏览器 ----
        // 后端 /api/auth/callback 要拿它做 CSRF 校验（见后端源码），
        // 缺了它，用户会在 GitHub 上授权成功、然后回调报 "state 校验失败"。
        // 这一步专门抓这个失败模式，否则现象会非常迷惑。
        if (loginUrl.contains("github.com")) {
            try {
                val raw = CookieManager.getInstance()?.getCookie(base) ?: ""
                val hasState = raw.contains("mm_oauth_state")
                add(
                    "2.5 浏览器里的 state cookie",
                    hasState,
                    if (hasState) "已写入 mm_oauth_state，回调校验能通过"
                    else "缺少 mm_oauth_state —— 授权成功后回调会报 state 校验失败"
                )
            } catch (t: Throwable) {
                add("2.5 浏览器里的 state cookie", false, "读不到：${t.message?.take(60)}")
            }
        }

        // ---- 第 3 步：会话 cookie 是否存在（WebView 侧） ----
        var hasCookie = false
        var cookieDetail = ""
        try {
            val cm = CookieManager.getInstance()
            val raw = cm?.getCookie(base) ?: ""
            hasCookie = raw.contains("mm_session")
            cookieDetail = if (hasCookie) {
                "已存在会话 cookie（mm_session）"
            } else if (raw.isBlank()) {
                "浏览器里没有任何 cookie —— 说明授权流程还没走完，或者被浏览器拦了"
            } else {
                "有 cookie 但没有会话：${raw.take(80)}"
            }
        } catch (t: Throwable) {
            cookieDetail = "读不到 cookie：${t.message?.take(60)}"
        }
        add("3. 浏览器里的会话 cookie", hasCookie, cookieDetail)

        // ---- 第 4 步：带 cookie 查登录状态 ----
        var meOk = false
        var meDetail = ""
        var user = ""
        try {
            val u = BackendApi.me(ctx)
            if (u != null) {
                meOk = true
                user = u.login
                meDetail = "已登录：${u.login}"
            } else {
                meDetail = "后端返回未登录"
            }
        } catch (t: Throwable) {
            meDetail = Http.describeError(t)
        }
        add("4. 查询登录状态", meOk, meDetail)

        return Report(steps, meOk, user)
    }

    /** 把报告拼成可读文字，并给出针对当前卡点的建议 */
    fun format(r: Report): String {
        val sb = StringBuilder()
        for (s in r.steps) {
            sb.append(if (s.ok) "✓ " else "✗ ").append(s.name).append('\n')
            sb.append("    ").append(s.detail).append('\n')
        }
        sb.append('\n')

        val at = r.failedAt
        val tip = when {
            r.loggedIn -> "已经登录了，可以正常使用后端功能。"
            at == 0 ->
                "卡在后端连通。workers.dev 在国内经常访问不稳定，" +
                    "建议：换个网络（比如切到移动数据）再试；" +
                    "或者先离线用——离线模式下 Modrinth 与镜像源仍然可用。"
            at == 1 ->
                "后端连得上，但拿不到授权地址。多半是后端还没配置好 " +
                    "GitHub OAuth 的 Client ID / Secret。"
            at == 2 ->
                "授权流程还没走完。常见原因：① 在 GitHub 页面点了取消 " +
                    "② 回调地址不匹配被 GitHub 拒绝 ③ 浏览器拦截了跳转。\n" +
                    "建议：回到登录页重新走一次授权，完成后点「我已完成授权」。"
            at == 3 ->
                "有会话 cookie，但后端说没登录。会话可能已过期（7 天），" +
                    "重新登录一次即可。"
            else -> "按上面的提示处理即可。"
        }
        sb.append("建议：").append(tip)
        return sb.toString()
    }
}
