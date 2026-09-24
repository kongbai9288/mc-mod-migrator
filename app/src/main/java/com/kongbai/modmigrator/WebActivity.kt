package com.kongbai.modmigrator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
 * 模组页面经常需要跳转（比如 CurseForge 跳到作者的外部站点）、
 * 有些下载还要登录，用外部浏览器会丢失上下文。
 * 内置一个 WebView 更顺手，同时保留「用外部浏览器打开」入口。
 */
class WebActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bar: ProgressBar

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun open(ctx: Context, url: String, title: String = "") {
            val i = Intent(ctx, WebActivity::class.java)
            i.putExtra(EXTRA_URL, url)
            i.putExtra(EXTRA_TITLE, title)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        bar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (3 * resources.displayMetrics.density).toInt()
            )
        }
        web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(web)
        root.addView(bar)
        setContentView(root)

        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "浏览器" }

        if (url.isBlank()) {
            Toast.makeText(this, "没有地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // 用移动端 UA，很多站点会返回移动版，加载快
            userAgentString = userAgentString + " ModMigrator"
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress >= 100) android.view.View.GONE
                else android.view.View.VISIBLE
            }
            override fun onReceivedTitle(view: WebView?, t: String?) {
                if (!t.isNullOrBlank() && title.isNullOrBlank()) title = t
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val u = request?.url?.toString() ?: return false
                // 外链如果是下载直链：先自动标记为下载地址，再交给系统处理
                return if (looksLikeDownload(u)) {
                    autoMark(u)
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request!!.url))
                    } catch (e: Throwable) {
                    }
                    true
                } else {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                bar.visibility = android.view.View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
            ) {
                // 加载失败不要白屏，给个提示
                if (request?.isForMainFrame == true) {
                    Toast.makeText(this@WebActivity, "页面加载失败，可点右上角用外部浏览器打开", Toast.LENGTH_LONG).show()
                }
            }
        }

        web.loadUrl(url)
    }

    /**
     * 自动标记下载地址。
     *
     * 只有当网站真的返回了下载链接（.jar/.zip 等）时才标记，
     * 不再靠猜——之前那种"一进页面就把所有链接都扒下来"的做法
     * 会抓到几十个根本不是下载的链接。
     */
    private fun autoMark(u: String) {
        try {
            val name = u.substringBefore("?").substringAfterLast("/")
            val added = Store.addLink(
                this@WebActivity,
                MarkedLink(title = name.ifBlank { u }, url = u)
            )
            if (added) {
                Toast.makeText(this@WebActivity, "已标记为下载地址：$name", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
        }
    }

    private fun looksLikeDownload(u: String): Boolean {
        val low = u.lowercase()
        return low.endsWith(".jar") || low.endsWith(".zip") ||
            low.endsWith(".apk") || low.contains("/download/")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "用浏览器打开")
        menu.add(0, 2, 0, "刷新")
        menu.add(0, 3, 0, "复制网址")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(web.url)))
                } catch (t: Throwable) {
                }
            }
            2 -> web.reload()
            3 -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("url", web.url))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        try {
            web.stopLoading()
            web.destroy()
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }
}
