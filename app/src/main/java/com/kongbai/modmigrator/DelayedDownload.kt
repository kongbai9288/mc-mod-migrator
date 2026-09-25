package com.kongbai.modmigrator

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 延迟下载链接捕获。
 *
 * 解决的问题：CurseForge 的下载不是直接给直链的——
 * 先跳到一个「读秒页面」，等几秒后才放行真实下载地址。
 * 直接拿 API 给的 download 页 URL 去下载，拿到的只是一个 HTML 页面。
 *
 * 做法：用一个**不挂载到界面上**的 WebView 在后台打开那个读秒页，
 * 让它自己走完读秒，等真实地址出现时截获，然后立刻销毁 WebView。
 *
 * 内存泄漏防护（这类后台 WebView 最容易泄漏，特意做了这些）：
 *   1. 用 **applicationContext** 创建，不持有 Activity
 *   2. 从不 addView 到任何父容器
 *   3. 硬超时（默认 45 秒）到点强制销毁
 *   4. 拿到链接 / 失败 / 页面销毁，任何一条路径都会 destroy + 置空
 *   5. 回调只执行一次（AtomicBoolean 保证），不会重复持有
 */
object DelayedDownload {

    /** 默认最多等多久（毫秒）。读秒一般 5 秒，给足余量 */
    private const val DEFAULT_TIMEOUT = 45_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前是否有一个捕获任务在跑（避免同时开多个 WebView） */
    @Volatile
    private var busy = false

    /** 兜底引用：异常路径也能清掉 */
    @Volatile
    private var activeWebView: WebView? = null

    // 注意：这里**不能**放一个 object 级别共享的 Runnable。
    // 之前就放了一个，它只能调 cleanup(null)，
    // 而 cleanup 只是销毁 WebView、并不会回调 onFail ——
    // 于是「等待超时」这条路径上，调用方**永远收不到任何回调**：
    // 界面停在"正在后台获取真实地址…"，进度条一直转，用户不知道该怎么办。
    // 超时必须走 finish(null)，让 onFail 真的被调用。
    // 所以改成每次 capture 现场创建一个（要闭包捕获 finish），见下面。

    /**
     * 在后台打开页面，等真实下载地址出现。
     *
     * @param ctx 任意 context，内部会用 applicationContext
     * @param pageUrl 读秒页地址
     * @param onGot 拿到直链（后台线程之外，注意切回主线程再用）
     * @param onFail 失败或超时
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun capture(
        ctx: Context,
        pageUrl: String,
        timeoutMs: Long = DEFAULT_TIMEOUT,
        onGot: (String) -> Unit,
        onFail: (String) -> Unit
    ) {
        if (busy) {
            onFail("已经有一个捕获任务在进行")
            return
        }
        busy = true

        val appCtx = ctx.applicationContext
        var web: WebView? = null
        val done = AtomicBoolean(false)

        // 先声明后赋值：finish 要移除它，它又要捕获 finish，
        // 两者互相依赖，只能用一个可空变量把它们拆开。
        var timeoutTask: Runnable? = null

        fun finish(url: String?) {
            if (!done.compareAndSet(false, true)) return
            timeoutTask?.let { mainHandler.removeCallbacks(it) }
            mainHandler.post {
                try {
                    if (url != null) onGot(url)
                    else onFail("等待超时，没等到下载地址")
                } catch (t: Throwable) { Err.ignore(t, "else onFail(\"等待超时，没等到下载地址\")") }
                cleanup(web)
            }
        }

        // 超时任务必须在这里创建：它要闭包捕获 finish，
        // 才能在超时时真正调用 onFail（否则调用方永远等不到结果）。
        timeoutTask = Runnable { finish(null) }

        mainHandler.post {
            try {
                web = WebView(appCtx).also { w ->
                    activeWebView = w
                    w.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // 不加载图片，省流量也更快
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        userAgentString = Http.UA
                    }
                    w.webChromeClient = WebChromeClient()

                    w.webViewClient = object : WebViewClient() {

                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString()
                            if (u != null && isRealDownload(u)) {
                                // 拿到了：就是它
                                finish(u)
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            // 有些页面是通过 JS 跳转的，加载完再检查一次当前地址
                            val cur = view?.url ?: url
                            if (cur != null && isRealDownload(cur)) {
                                finish(cur)
                            }
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            // 读秒结束后资源请求里也会出现真实地址
                            if (url != null && isRealDownload(url)) {
                                finish(url)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                if (!done.get()) finish(null)
                            }
                        }
                    }
                }
                web?.loadUrl(pageUrl)
                // 硬超时兜底：无论如何都要收尾，不能把 WebView 挂着。
                // 到点走 finish(null) → 调用方的 onFail 会被调用，
                // 进而走"改用直连试试"的兜底，不会干等。
                mainHandler.postDelayed(timeoutTask!!, timeoutMs)
            } catch (t: Throwable) {
                timeoutTask?.let { mainHandler.removeCallbacks(it) }
                mainHandler.post {
                    onFail("后台页面创建失败：${t.message}")
                    busy = false
                    activeWebView = null
                }
            }
        }
    }

    /** 判断是不是真正的可下载文件地址（不是 HTML 页面） */
    private fun isRealDownload(u: String): Boolean {
        val low = u.substringBefore("?").lowercase()
        return low.endsWith(".jar") ||
            low.endsWith(".zip") ||
            low.endsWith(".mrpack") ||
            low.contains("forgecdn.net") ||
            low.contains("media.forgecdn") ||
            low.contains("edge.forgecdn")
    }

    /** 统一收尾：停加载、销毁、置空、解锁 */
    private fun cleanup(w: WebView?) {
        try {
            val target = w ?: activeWebView
            target?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                // 先从父容器摘掉（虽然我们没 addView，但兜底）
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
        } catch (t: Throwable) { Err.ignore(t, "destroy()") }
        activeWebView = null
        busy = false
    }

    /**
     * 给 CurseForge 用：拼出读秒页地址。
     * 有 API 返回的 downloadUrl 就用它，没有就用 slug + fileId 拼。
     */
    fun curseForgePage(mod: MarketMod): String {
        if (mod.pageUrl.isNotBlank() && mod.fileId.isNotBlank()) {
            return "${mod.pageUrl.trimEnd('/')}/download/${mod.fileId}"
        }
        return mod.pageUrl
    }
}
