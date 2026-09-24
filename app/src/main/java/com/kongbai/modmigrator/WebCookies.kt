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
        } catch (t: Throwable) {
        }
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
        } catch (t: Throwable) {
        }
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
            } catch (t: Throwable) {
            }
        }

        /** 预热：在主线程调用一次，避免首次使用时初始化慢 */
        fun warmUp() {
            try {
                CookieManager.getInstance()
            } catch (t: Throwable) {
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
            } catch (t: Throwable) {
            }
        }
    }
}
