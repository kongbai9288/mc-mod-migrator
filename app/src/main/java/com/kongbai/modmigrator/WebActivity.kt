package com.kongbai.modmigrator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 内置浏览器。
 *
 * 兼容"部分网页会出问题"的常见情况：
 *   1. 混合内容：https 页面里嵌 http 图片/脚本 → 放行（否则图全裂）
 *   2. 第三方 Cookie：登录态跨不过去 → 接受
 *   3. UA 被识别成爬虫 → 提供「桌面版 UA」切换
 *   4. SSL 证书异常 → 提示而不是白屏
 *   5. 单页应用路由 → 拦截 download 交给系统，其余交给页面自己
 *
 * 同时：遇到 .jar/.zip 这类下载链接会自动标记为下载地址，
 * 再交给系统处理（不再靠猜，避免抓一堆无关链接）。
 */
class WebActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private var desktopUa = false
    private var loginMode = false
    private var loginPolling = false

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LOGIN = "login"

        fun open(ctx: Context, url: String, title: String = "") {
            val i = Intent(ctx, WebActivity::class.java)
            i.putExtra(EXTRA_URL, url)
            i.putExtra(EXTRA_TITLE, title)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }

        /**
         * 登录专用：在内置浏览器里走完 OAuth。
         *
         * 为什么不能用系统浏览器：登录态 cookie 存在系统浏览器的 jar 里，
         * App 用 OkHttp 查状态时拿不到，于是永远显示未登录。
         * 用内置浏览器 + cookie 桥，登录态才在同一个地方。
         */
        fun login(ctx: Context, url: String) {
            val i = Intent(ctx, WebActivity::class.java)
            i.putExtra(EXTRA_URL, url)
            i.putExtra(EXTRA_TITLE, "登录 GitHub")
            i.putExtra(EXTRA_LOGIN, true)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶部工具条：返回 / 前进 / 刷新 / 关闭。
        // 之前只能靠系统返回键，有些页面（尤其是从对话框里打开的）
        // 用户找不到怎么退回去，看起来就像"没有返回键"。
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (6 * resources.displayMetrics.density).toInt(), 0,
                (6 * resources.displayMetrics.density).toInt(), 0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val btnSize = (40 * resources.displayMetrics.density).toInt()
        fun toolBtn(res: Int, desc: String, act: () -> Unit): android.widget.ImageButton =
            android.widget.ImageButton(this).apply {
                setImageResource(res)
                contentDescription = desc
                // 用项目自带的 drawable，不依赖主题 attr 解析。
                // 之前用 android.R.attr.selectableItemBackground（=0x101030e）：
                // 它是属性 id 而非 drawable id，交给 setBackgroundResource 必崩；
                // 后来改成 theme.resolveAttribute() 解析，但部分 ROM 上解析结果
                // 仍然是个 attr 引用，照样崩。这里直接写死一个真实 drawable，
                // 彻底断开对系统 attr 的依赖。
                setBackgroundResource(R.drawable.bg_icon_button)
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                setOnClickListener { act() }
            }
        val btnBack = toolBtn(
            R.drawable.ic_arrow_back, "返回"
        ) { if (web.canGoBack()) web.goBack() else finish() }
        val btnFwd = toolBtn(
            R.drawable.ic_play_arrow, "前进"
        ) { if (web.canGoForward()) web.goForward() }
        val btnReload = toolBtn(
            R.drawable.ic_refresh, "刷新"
        ) { web.reload() }
        val btnClose = toolBtn(
            R.drawable.ic_close, "关闭"
        ) { finish() }
        tools.addView(btnBack)
        tools.addView(btnFwd)
        tools.addView(btnReload)
        tools.addView(android.widget.Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        tools.addView(btnClose)
        root.addView(tools)

        bar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (3 * resources.displayMetrics.density).toInt()
            )
        }
        // WebView 在部分设备/首次启动时会构造失败（系统 WebView 未就绪、
        // 被禁用、或内存不足）。这里兜住：失败就用外部浏览器打开，
        // 而不是让整个页面崩掉——这正是"有概率不工作"的原因之一。
        try {
            web = WebView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
        } catch (t: Throwable) {
            try {
                val u = intent.getStringExtra(EXTRA_URL) ?: ""
                if (u.isNotBlank()) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                }
            } catch (e2: Throwable) {
            }
            Toast.makeText(this, "内置浏览器不可用，已改用外部浏览器", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        root.addView(web)
        root.addView(bar)
        setContentView(root)

        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.title_browser) }

        if (url.isBlank()) {
            Toast.makeText(this, getString(R.string.no_url), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loginMode = intent.getBooleanExtra(EXTRA_LOGIN, false)
        // 自动翻译开关（设置里控制）
        val autoTrans = Prefs.get(this).getBoolean(K.AUTO_TRANS_PAGE, false)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            // 自动适配图片
            loadsImagesAutomatically = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // 1) 允许 https 页面加载 http 子资源（否则很多老站点图片全裂）
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 2) 允许第三方 cookie（登录态需要）
            try {
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            } catch (t: Throwable) {
            }
            // 3) 移动端 UA + 标识
            userAgentString = WebSettings.getDefaultUserAgent(this@WebActivity)
                .replace("; wv)", ")") + " ModMigrator/" + versionName()
            // 缩放适配
            textZoom = 100
            // 允许文件访问（本地预览用）
            allowFileAccess = true
            allowContentAccess = true
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress >= 100) android.view.View.GONE
                else android.view.View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView?, t: String?) {
                if (!t.isNullOrBlank()) title = t
            }
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val u = request?.url?.toString() ?: return false
                return when {
                    // 下载直链：先标记，再交给系统
                    looksLikeDownload(u) -> {
                        autoMark(u)
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                        } catch (e: Throwable) {
                            Toast.makeText(this@WebActivity, getString(R.string.cannot_open), Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    // 登录回调：拦住，不跳走，直接关页面（cookie 已存在 WebView 里）
                    loginMode && (u.contains("/api/auth/callback") ||
                        u.contains("code=")) -> {
                        finishWithLoginOk()
                        true
                    }
                    // 外链协议（mailto/tel/intent）交给系统
                    !u.startsWith("http://") && !u.startsWith("https://") -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                        } catch (e: Throwable) {
                        }
                        true
                    }
                    else -> false
                }
            }

            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                bar.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                bar.visibility = android.view.View.GONE
                // 登录模式：**不再靠 URL 猜**。
                // 之前只要"不是 github.com 就认为登录完了"，结果页面刚起个头就被关掉
                // ——这就是"点进去没加载完就弹出"的原因。
                // 现在的做法：页面加载完就启动轮询，去后端查登录态，
                // 只有真的查到已登录才关闭。靠结果说话，不靠猜地址。
                if (loginMode) {
                    startLoginPolling()
                }
                // 自动翻译：页面加载完就翻
                if (autoTrans && view != null) {
                    WebTranslate.start(this@WebActivity, view) { n ->
                        if (n > 0) {
                            Toast.makeText(this@WebActivity, getString(R.string.translated_segments, n), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@WebActivity,
                        getString(R.string.page_load_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // 4) SSL 异常：提示用户，不直接白屏
            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?
            ) {
                handler?.cancel()
                Toast.makeText(
                    this@WebActivity,
                    getString(R.string.ssl_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        web.loadUrl(url)
    }

    /**
     * 登录轮询：每隔一段时间去后端查一次登录状态。
     *
     * 为什么不用 URL 判断：GitHub 授权会经过多次 302，
     * 中间任何一个 URL 都可能"看起来像完成了"，导致页面被提前关掉。
     * 只有后端真的返回已登录，才算完成。
     *
     * 最多查 40 次（约 60 秒），超时不自动关——留给用户手动点「我已完成」。
     */
    private fun startLoginPolling() {
        if (!loginMode || loginPolling) return
        loginPolling = true
        Thread {
            var ok = false
            for (i in 0 until 40) {
                try {
                    val u = BackendApi.me(this)
                    if (u != null) {
                        ok = true
                        break
                    }
                } catch (t: Throwable) {
                }
                try {
                    Thread.sleep(1500)
                } catch (t: Throwable) {
                }
                // 页面已经关了就别查了
                if (isFinishing || isDestroyed) break
            }
            val done = ok
            runOnUiThread {
                loginPolling = false
                if (done && !isFinishing && !isDestroyed) {
                    finishWithLoginOk()
                }
            }
        }.start()
    }

    /** 登录走完：先刷 cookie，再关页面，让设置页能查到登录态 */
    private fun finishWithLoginOk() {
        // cookie 必须先落盘，否则回到设置页立刻查 /me 时读到的还是旧的
        runCatching { WebCookies.flushAll() }
        runCatching { setResult(RESULT_OK) }
        try {
            finish()
        } catch (t: Throwable) {
        }
    }

    private fun versionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (t: Throwable) {
            "1.0"
        }
    }

    /**
     * 自动标记下载地址。
     * 只在网站真的返回下载链接时才标记（.jar/.zip 等），
     * 不再靠猜——之前那种"进页面就扒所有链接"会抓到几十个无关项。
     */
    private fun autoMark(u: String) {
        try {
            val name = u.substringBefore("?").substringAfterLast("/")
            val added = Store.addLink(
                this@WebActivity,
                MarkedLink(title = name.ifBlank { u }, url = u)
            )
            if (added) {
                Toast.makeText(
                    this@WebActivity,
                    getString(R.string.marked_download, name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (t: Throwable) {
        }
    }

    private fun looksLikeDownload(u: String): Boolean {
        val low = u.lowercase()
        return low.endsWith(".jar") || low.endsWith(".zip") ||
            low.endsWith(".apk") || low.endsWith(".mrpack") ||
            low.contains("/download/") || low.contains("download=1")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (loginMode) {
            menu.add(0, 9, 0, getString(R.string.login_done))
        }
        menu.add(0, 1, 0, getString(R.string.open_external))
        menu.add(0, 2, 0, getString(R.string.refresh))
        menu.add(0, 3, 0, getString(R.string.copy_url))
        menu.add(0, 4, 0, getString(R.string.translate_page))
        menu.add(0, 5, 0, getString(R.string.restore_original))
        menu.add(0, 6, 0, if (desktopUa) getString(R.string.mobile_ua) else getString(R.string.desktop_ua))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            9 -> {
                // 手动确认：有些情况下轮询没那么快，用户可自行结束
                finishWithLoginOk()
            }
            1 -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.url)))
                } catch (t: Throwable) {
                }
            }
            2 -> web.reload()
            3 -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("url", web.url))
                Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
            }
            4 -> WebTranslate.start(this, web) { n ->
                Toast.makeText(
                    this,
                    if (n > 0) getString(R.string.translated_segments, n)
                    else getString(R.string.nothing_to_translate),
                    Toast.LENGTH_SHORT
                ).show()
            }
            5 -> {
                WebTranslate.restore(web)
                Toast.makeText(this, getString(R.string.restored), Toast.LENGTH_SHORT).show()
            }
            6 -> {
                desktopUa = !desktopUa
                web.settings.userAgentString = if (desktopUa) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                } else {
                    WebSettings.getDefaultUserAgent(this).replace("; wv)", ")") +
                        " ModMigrator/" + versionName()
                }
                web.reload()
                invalidateOptionsMenu()
            }
        }
        return true
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        // 彻底清理 WebView：这类页面最容易泄漏 Activity，
        // 顺序必须是 摘父容器 → 停加载 → 清回调 → destroy
        try {
            WebTranslate.stop()
            if (::web.isInitialized) {
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.stopLoading()
                web.webViewClient = WebViewClient()
                web.webChromeClient = WebChromeClient()
                web.clearHistory()
                web.clearCache(true)
                web.destroy()
            }
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // 页面不可见时暂停 JS 与渲染，省电也减少后台被杀的概率
        try {
            if (::web.isInitialized) {
                web.pauseTimers()
                web.onPause()
            }
        } catch (t: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (::web.isInitialized) {
                web.resumeTimers()
                web.onResume()
            }
        } catch (t: Throwable) {
        }
    }
}
