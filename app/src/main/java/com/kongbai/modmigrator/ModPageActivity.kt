package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class ModPageActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var tvUrl: TextView
    private lateinit var tvMarked: TextView
    private var url = ""
    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mod_page)

        url = intent.getStringExtra("url") ?: ""
        web = findViewById(R.id.webView)
        tvUrl = findViewById(R.id.tvUrl)
        tvMarked = findViewById(R.id.tvMarked)
        tvUrl.text = url

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.webViewClient = PageClient()
        if (url.isNotBlank()) web.loadUrl(url)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        web.setOnLongClickListener { onLongPress() }

        findViewById<View>(R.id.btnMarkPage).setOnClickListener { markPage() }
        findViewById<View>(R.id.btnTrans).setOnClickListener { translateDialog() }
        findViewById<View>(R.id.btnDownloadMarked).setOnClickListener { downloadMarked() }
        updateMarked()
    }

    private fun safePostA(h: android.os.Handler, block: () -> Unit) {
        h.post {
            if (isFinishing || isDestroyed) return@post
            try { block() } catch (t: Throwable) { }
        }
    }

    private fun toast(s: String) {
        handler.post {
            if (isFinishing || isDestroyed) return@post
            try {
                android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                // 界面已销毁，不弹
            }
        }
    }

    private fun updateMarked() {
        val n = Store.links(this).size
        tvMarked.text = "已标记 $n 个链接"
    }

    private fun onLongPress(): Boolean {
        val r = web.hitTestResult
        val t = r.type
        if (t == WebView.HitTestResult.SRC_ANCHOR_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            val u = r.extra
            if (u.isNullOrBlank()) return false
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mark_link_title)
                .setMessage(u)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val ok = Store.addLink(this, MarkedLink(u, u))
                    toast(if (ok) "已标记为下载链接" else "已经标记过了")
                    updateMarked()
                }
                .show()
            return true
        }
        return false
    }

    private fun translateDialog() {
        // 默认走本地标注：不跳转、不发请求，绝不会白屏
        val opts = arrayOf(
            "本地词典标注（推荐，不会白屏）",
            "微软翻译代理页（国内可达）",
            "谷歌翻译代理页（需梯子）",
            "恢复原文"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trans_page_title)
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> markLocal()
                    1 -> loadTranslated("bing")
                    2 -> loadTranslated("google")
                    else -> web.loadUrl(url)
                }
            }
            .show()
    }

    /** 本地词典标注：页面加载完成后注入 JS，失败也不影响原页面 */
    private fun markLocal() {
        if (url.isBlank()) return
        val script = try {
            OfflineTranslate.webScript(this)
        } catch (t: Throwable) {
            toast("词典加载失败：${t.message}")
            return
        }
        pendingScript = script
        runOnMainScript = true
        toast("已开启本地标注，页面刷新后生效")
        web.loadUrl(url)
    }

    /** 页面加载完后要注入的脚本；null 表示不需要注入 */
    private var pendingScript: String? = null
    private var runOnMainScript: Boolean = false

    private fun loadTranslated(engine: String) {
        if (url.isBlank()) return
        // 离线模式：不加载任何代理页，改为页面加载完后注入本地词典做词级标注
        if (Prefs.get(this).getBoolean(K.OFFLINE, false)) {
            toast("离线：用本地词典标注页面（不会发起网络请求）")
            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                    view?.evaluateJavascript(OfflineTranslate.webScript(this@ModPageActivity), null)
                }
            }
            web.loadUrl(url)
            return
        }
        // 离线模式一律不跳代理页
        if (Prefs.get(this).getBoolean(K.OFFLINE, false)) {
            toast("离线模式：使用本地标注")
            markLocal()
            return
        }
        toast("正在加载翻译页，失败会自动退回原页面…")
        proxyMode = true
        web.loadUrl(Translator.pageProxy(engine, url))
    }

    /** 是否处于翻译代理页：用于失败回退 */
    private var proxyMode = false

    /** 自定义 WebViewClient：捕获加载失败与空白页，避免白屏 */
    private inner class PageClient : WebViewClient() {

        override fun onPageFinished(view: WebView?, u: String?) {
            super.onPageFinished(view, u)
            // 注入本地标注脚本
            val sc = pendingScript
            if (sc != null && view != null) {
                try {
                    view.evaluateJavascript(sc, null)
                } catch (t: Throwable) {
                    // 注入失败不影响页面显示
                }
                pendingScript = null
                return
            }
            // 代理页：检测是否是空白页（翻译站被墙时会返回空内容）
            if (proxyMode && view != null) {
                view.evaluateJavascript(
                    "(function(){return document.body?document.body.innerText.trim().length:0})()"
                ) { v ->
                    val len = v?.trim('"')?.toIntOrNull() ?: 0
                    if (len < 50) {
                        proxyMode = false
                        toast("翻译页没加载出来，已退回原页面")
                        view.loadUrl(url)
                    } else {
                        proxyMode = false
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(
            view: WebView?, errorCode: Int, description: String?, failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            if (proxyMode && view != null) {
                proxyMode = false
                toast("加载失败：${description ?: "未知错误"}，已退回原页面")
                view.loadUrl(url)
            }
        }
    }

    private fun markPage() {
        if (url.isBlank()) return
        toast("正在分析页面…")
        exec.execute {
            val list = try {
                PageParser.candidates(url, 8)
            } catch (t: Throwable) {
                emptyList<MarkedLink>()
            }
            if (list.isEmpty()) {
                safePostA(handler) { toast("页面上没找到像下载直链的链接，可长按链接手动标记") }
                return@execute
            }
            // 弹窗让用户挑，而不是无脑全加
            safePostA(handler) {
                val labels = list.map { it.title.ifBlank { it.url }.take(60) }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("选择要标记的下载链接（${list.size} 个候选）")
                    .setMultiChoiceItems(labels, null) { _, _, _ -> }
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok) { dlg, _ ->
                        val lv = (dlg as android.app.AlertDialog).listView
                        var added = 0
                        for (i in list.indices) {
                            if (lv.isItemChecked(i) && Store.addLink(this, list[i])) added++
                        }
                        updateMarked()
                        toast("已标记 $added 个")
                    }
                    .show()
            }
        }
    }

    private fun downloadMarked() {
        val list = Store.links(this)
        if (list.isEmpty()) {
            toast("还没有标记任何链接")
            return
        }
        toast("开始下载 ${list.size} 个…")
        exec.execute {
            try {
                val dir = Targets.modsDir(this)
                var ok = 0
                for (l in list) {
                    val f = if (dir == null) null else Downloader.download(this, l.url, dir, Downloader.guessName(l.url))
                    if (f != null) ok++
                }
                toast("下载完成 $ok/${list.size}")
                if (Prefs.get(this).getBoolean(K.AUTO_LAUNCH, false)) {
                    val pkg = Prefs.get(this).getString(K.LAUNCHER, "") ?: ""
                    if (pkg.isNotBlank()) LauncherHelper.launch(this, pkg)
                }

            } catch (t: Throwable) {
                // 后台异常不崩进程
            }
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
