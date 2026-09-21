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
        web.webViewClient = WebViewClient()
        if (url.isNotBlank()) web.loadUrl(url)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        web.setOnLongClickListener { onLongPress() }

        findViewById<View>(R.id.btnMarkPage).setOnClickListener { markPage() }
        findViewById<View>(R.id.btnTrans).setOnClickListener { translateDialog() }
        findViewById<View>(R.id.btnDownloadMarked).setOnClickListener { downloadMarked() }
        updateMarked()
    }

    private fun toast(s: String) {
        handler.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
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
        val opts = arrayOf("微软翻译（国内可达）", "谷歌翻译（需梯子）", "恢复原文")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trans_page_title)
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> loadTranslated("bing")
                    1 -> loadTranslated("google")
                    else -> web.loadUrl(url)
                }
            }
            .show()
    }

    private fun loadTranslated(engine: String) {
        if (url.isBlank()) return
        toast("正在加载翻译页…")
        web.loadUrl(Translator.pageProxy(engine, url))
    }

    private fun markPage() {
        if (url.isBlank()) return
        toast("正在分析页面…")
        exec.execute {
            val list = try {
                PageParser.candidates(url)
            } catch (t: Throwable) {
                emptyList<MarkedLink>()
            }
            var added = 0
            for (l in list.take(5)) {
                if (Store.addLink(this, l)) added++
            }
            handler.post {
                updateMarked()
                toast("从页面识别出 ${list.size} 个候选，已添加 $added 个")
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
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
