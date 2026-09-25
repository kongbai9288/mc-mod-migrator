package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.view.Menu
import android.view.MenuItem
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
            try { block() } catch (t: Throwable) { Err.ignore(t, "try { block() }") }
        }
    }

    private fun toast(s: String) {
        handler.post {
            if (isFinishing || isDestroyed) return@post
            try {
                android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                // 界面已销毁，不弹
                     Err.ignore(t, "界面已销毁，不弹")
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
        // 注意：这里**只有本地方案**。
        //
        // 之前列了"微软翻译代理页""谷歌翻译代理页"两个选项，
        // 但 loadTranslated(engine) 里**根本没用 engine 这个参数**——
        // 不管选哪个，实际执行的都是同一套 ML Kit 本地翻译。
        // 也就是说选项名和行为完全不符：用户以为在开代理页，
        // 实际走的还是本地模型，选了跟没选一样。
        //
        // 代理页本身也是之前"一点翻译就白屏"的根源
        // （translate.google.com 国内基本打不开），所以干脆去掉，
        // 只留真正能用的本地方案，不摆没用的选项。
        val opts = arrayOf(
            "ML Kit 离线翻译整页（推荐）",
            "本地词典标注（不改文字，只加注释）",
            "恢复原文"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trans_page_title)
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> loadTranslated()
                    1 -> markLocal()
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
        toast("已开启本地标注，页面刷新后生效")
        web.loadUrl(url)
    }

    /** 页面加载完后要注入的脚本；null 表示不需要注入 */
    private var pendingScript: String? = null

    /**
     * 整页翻译。
     *
     * 不再跳 translate.google.com 那种代理页——国内十次有九次打不开，
     * 一失败就是白屏，这正是之前的毛病。
     * 现在：页面照常加载，加载完后把文字用 ML Kit 离线模型翻成中文再替换。
     * 离线也能翻，不外发页面内容，也不会白屏。
     */
    private fun loadTranslated() {
        if (url.isBlank()) return
        val offline = Prefs.get(this).getBoolean(K.OFFLINE, false)
        toast(if (offline) "离线翻译中（用本地模型）…" else "翻译中…")

        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                super.onPageFinished(view, u)
                if (view == null) return
                // 页面已正常显示，翻不翻出来都不会白屏
                WebTranslate.start(
                    this@ModPageActivity,
                    view,
                    onProgress = { done, _ ->
                        if (done % 20 == 0) toast("已翻译 $done 段")
                    },
                    onDone = { n ->
                        translated = n > 0
                        invalidateOptionsMenu()
                        toast(
                            if (n > 0) "翻译完成，共 $n 段（菜单里可还原原文）"
                            else "这段页面没有可翻译的文字，或模型还没下载好"
                        )
                    }
                )
            }
        }

        // 模型没下好时先下载，下载完用户再点一次即可；
        // 这里同时触发一次，下次就是秒翻
        Translator.ensureModel(this) { ready ->
            if (ready) toast("翻译模型已就绪")
        }
        web.loadUrl(url)
    }

    /** 是否已翻译过，用于显示「还原原文」入口 */
    private var translated = false

    /**
     * 自定义 WebViewClient：捕获加载失败与空白页，避免白屏。
     *
     * 之前这里有一整套"翻译代理页"回退逻辑（proxyMode 检测空白页后退回），
     * 但代理页选项已经去掉了，proxyMode 再也没有被置 true 的地方，
     * 那段检测成了永远不执行的死分支。清理掉，
     * 改成页面加载失败时正常给用户提示。
     */
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
                         Err.ignore(t, "注入失败不影响页面显示")
                     }
                pendingScript = null
                return
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(
            view: WebView?, errorCode: Int, description: String?, failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            // 页面加载不出来要明确告诉用户，而不是留个白屏让他猜
            toast("页面加载失败：${description ?: "未知错误"}")
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
                     Err.ignore(t, "后台异常不崩进程")
                 }
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 10, 0, "翻译本页")
        menu.add(0, 11, 0, "还原原文")
        menu.add(0, 12, 0, "用浏览器打开")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(11)?.isVisible = translated
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            10 -> loadTranslated()
            11 -> {
                WebTranslate.restore(web)
                translated = false
                invalidateOptionsMenu()
                toast("已还原原文")
            }
            12 -> {
                try {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(web.url)
                        )
                    )
                } catch (t: Throwable) { Err.ignore(t, ")") }
            }
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
