package com.kongbai.modmigrator

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 让 OkHttp 和 WebView 共用同一份 Cookie。
 *
 * 这是「登录功能登录不了」的根因所在：
 *   登录是在**浏览器**里完成的（GitHub OAuth 跳转），
 *   后端下发的登录态 cookie 存在浏览器的 cookie jar 里；
 *   而 App 后续查登录状态用的是 **OkHttp**，它有自己的一套 cookie 存储，
 *   **跟浏览器完全不通** —— 于是 /api/auth/me 永远返回未登录。
 *
 * 解决：登录改在**内置 WebView** 里做，同时给 OkHttp 装上这个桥，
 * 让它直接读写 WebView 的 CookieManager。这样 WebView 里登录成功，
 * OkHttp 立刻就能带上同一个 cookie 去查状态。
 */
class WebCookies : CookieJar {

    private val manager: CookieManager? by lazy {
        try {
            CookieManager.getInstance()
        } catch (t: Throwable) {
            null
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = manager ?: return
        try {
            for (c in cookies) {
                cm.setCookie(url.toString(), c.toString())
            }
            flush()
        } catch (t: Throwable) { Err.ignore(t, "flush()") }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cm = manager ?: return emptyList()
        return try {
            val raw = cm.getCookie(url.toString()) ?: return emptyList()
            if (raw.isBlank()) return emptyList()
            // CookieManager 返回 "a=1; b=2" 这种串，逐条解析
            raw.split(";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { Cookie.parse(url, it) }.getOrNull() }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun flush() {
        try {
            manager?.flush()
        } catch (t: Throwable) { Err.ignore(t, "manager?.flush()") }
    }

    companion object {
        /** 清掉某个域名下的 cookie（退出登录用） */
        fun clear(host: String) {
            try {
                val cm = CookieManager.getInstance()
                val raw = cm.getCookie("https://$host") ?: return
                // 同名置空过期
                for (pair in raw.split(";")) {
                    val name = pair.substringBefore("=").trim()
                    if (name.isNotBlank()) {
                        cm.setCookie("https://$host", "$name=; Max-Age=0; path=/")
                    }
                }
                cm.flush()
            } catch (t: Throwable) { Err.ignore(t, "cm.flush()") }
        }

        /**
         * 预热：必须在 Application.onCreate 里调一次。
         *
         * 光调 `CookieManager.getInstance()` 是不够的 ——
         * 在不少设备上，必须**真正创建过一个 WebView 实例**，
         * CookieManager 的底层存储才会就绪。在那之前调 setCookie()
         * 会被静默丢弃，表现就是：登录页跳到 GitHub、回调回来报
         * "state 校验失败"（因为 state cookie 根本没写进去）。
         *
         * 所以这里在主线程创建一个 WebView 再销毁，把 cookie 存储带起来。
         */
        fun warmUp(ctx: android.content.Context) {
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
            } catch (t: Throwable) {
                Err.ignore(t, "预热 CookieManager")
            }
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        // 用 applicationContext，绝不持有 Activity
                        android.webkit.WebView(ctx.applicationContext).destroy()
                    } catch (t: Throwable) {
                        Err.ignore(t, "预热 WebView 实例以激活 cookie 存储")
                    }
                }
            } catch (t: Throwable) {
                Err.ignore(t, "投递 WebView 预热任务")
            }
        }

        /**
         * 检查某个 cookie 是否真的写进去了。
         * 用于登录前自检：state cookie 没写进去的话，
         * 后面回调必然失败，与其让用户看到莫名其妙的错误，
         * 不如在这里就把问题记进日志。
         */
        fun hasCookie(url: String, name: String): Boolean {
            return try {
                val raw = CookieManager.getInstance().getCookie(url) ?: return false
                raw.split(";").any { it.trim().substringBefore("=") == name }
            } catch (t: Throwable) {
                Err.ignore(t, "检查 cookie $name")
                false
            }
        }

        /** 列出某 URL 下所有 cookie 名，给诊断用 */
        fun cookieNames(url: String): List<String> {
            return try {
                val raw = CookieManager.getInstance().getCookie(url) ?: return emptyList()
                raw.split(";").map { it.trim().substringBefore("=") }.filter { it.isNotBlank() }
            } catch (t: Throwable) {
                emptyList()
            }
        }

        /**
         * 强制把 WebView 的 cookie 落盘。
         * 登录刚完成时必须调一次——否则 OkHttp 可能读到的是刷新前的旧 cookie，
         * 导致"登录成功了但后续操作都报未登录"。
         */
        fun flushAll() {
            try {
                CookieManager.getInstance().flush()
            } catch (t: Throwable) { Err.ignore(t, "CookieManager.getInstance().flush()") }
        }
    }
}
