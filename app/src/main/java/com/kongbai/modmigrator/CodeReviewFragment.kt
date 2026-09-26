package com.kongbai.modmigrator

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * AI 代码审核：选一个源码目录，逐个文件交给 Groq 审查。
 *
 * 为什么要串行、还要显示等待：
 * Groq 免费层是 **30 RPM**（每分钟 30 次请求），而且是滑动窗口。
 * 113 个文件按这个速度约需 4 分钟。如果不做任何控制直接并发打，
 * 前 30 个之后会全部撞 429，用户看到的就是一堆失败。
 * 所以这里单文件顺序审核，并把"因限速等待 N 秒"如实显示出来，
 * 让用户知道是在等配额而不是卡死了。
 *
 * 视图全部用代码构建，不新增布局文件（和 MoreFragment 同一套做法）。
 */
class CodeReviewFragment : Fragment() {

    private var rootUri: String? = null

    /** (相对路径, 源码) */
    private val targets = ArrayList<Pair<String, String>>()

    @Volatile
    private var running = false

    @Volatile
    private var stop = false

    private lateinit var tvPath: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvQuota: TextView
    private lateinit var btnRun: Button
    private lateinit var etKey: EditText
    private lateinit var spModel: Spinner
    private lateinit var resultBox: LinearLayout
    private lateinit var hint: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()

        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad, pad, pad)
        scroll.addView(box)

        // ── 密钥 ──────────────────────────────────────
        box.addView(sectionTitle(ctx, "Groq 密钥"))
        etKey = EditText(ctx).apply {
            hint = "gsk_ 开头，只存在本机，不会上传源码以外的东西"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(GroqApi.key(ctx))
        }
        box.addView(etKey)

        val btnSaveKey = Button(ctx).apply {
            text = "保存密钥"
            setOnClickListener { saveKey() }
        }
        box.addView(btnSaveKey)

        hint = TextView(ctx).apply {
            textSize = 11f
            text = "密钥保存在本机加密存储里，不写进代码、不随应用分发。"
        }
        box.addView(hint)

        // ── 模型 ──────────────────────────────────────
        box.addView(sectionTitle(ctx, "模型"))
        spModel = Spinner(ctx).apply {
            adapter = ArrayAdapter(
                ctx,
                android.R.layout.simple_spinner_dropdown_item,
                GroqApi.MODELS.map { "${it.first}  ·  ${it.second}" }
            )
            val cur = GroqApi.model(ctx)
            val idx = GroqApi.MODELS.indexOfFirst { it.first == cur }
            setSelection(if (idx >= 0) idx else 0)
        }
        box.addView(spModel)

        // ── 选目录 ────────────────────────────────────
        box.addView(sectionTitle(ctx, "源码目录"))
        tvPath = TextView(ctx).apply {
            textSize = 12f
            text = "还没选择"
        }
        box.addView(tvPath)

        box.addView(Button(ctx).apply {
            text = "选择源码目录"
            setOnClickListener { pickDir() }
        })

        // ── 执行 ──────────────────────────────────────
        btnRun = Button(ctx).apply {
            text = "开始审核"
            setOnClickListener { start() }
        }
        box.addView(btnRun)

        box.addView(Button(ctx).apply {
            text = "停止"
            setOnClickListener { stop = true }
        })

        tvProgress = TextView(ctx).apply { textSize = 12f }
        box.addView(tvProgress)

        tvQuota = TextView(ctx).apply { textSize = 11f }
        box.addView(tvQuota)

        resultBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        box.addView(resultBox)

        return scroll
    }

    private fun sectionTitle(ctx: Context, t: String): TextView =
        TextView(ctx).apply {
            text = t
            textSize = 13f
            setPadding(0, (12 * ctx.resources.displayMetrics.density).toInt(), 0, 4)
        }

    private fun saveKey() {
        val ctx = context ?: return
        val v = etKey.text.toString().trim()
        if (!GroqApi.looksLikeKey(v)) {
            Toast.makeText(ctx, "密钥应以 gsk_ 开头且长度足够", Toast.LENGTH_SHORT).show()
            return
        }
        GroqApi.setKey(ctx, v)
        Toast.makeText(ctx, "已保存到本机", Toast.LENGTH_SHORT).show()
    }

    private fun pickDir() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(i, 91)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 91 || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = requireContext()
        // 授权持久化：不拿的话应用重启后就读不了这个目录
        if (!Perms.take(ctx, uri)) {
            Toast.makeText(ctx, "目录授权未持久化，重启后需要重选", Toast.LENGTH_LONG).show()
        }
        rootUri = uri.toString()
        tvPath.text = uri.toString()
        targets.clear()
        resultBox.removeAllViews()
        tvProgress.text = "扫描中…"
        bg { scan(uri.toString()) }
    }

    /** 递归收集源码文件 */
    private fun scan(uriStr: String) {
        val ctx = context ?: return
        val root = Fs.tree(ctx, uriStr)
        if (root == null) {
            main { tvProgress.text = "目录不可访问" }
            return
        }
        val out = ArrayList<Pair<String, String>>()
        collect(root, "", out, 0)

        // 大文件排前面意义不大，按路径排序让结果稳定好找
        out.sortBy { it.first }
        targets.clear()
        targets.addAll(out)
        main {
            tvProgress.text = if (out.isEmpty()) {
                "这个目录里没找到源码文件（.kt / .java / .gradle）"
            } else {
                "已找到 ${out.size} 个文件，点「开始审核」"
            }
        }
    }

    private fun collect(dir: DocumentFile, prefix: String, out: ArrayList<Pair<String, String>>, depth: Int) {
        if (depth > 6 || out.size > 400) return   // 别把整个磁盘扫进来
        val ctx = context ?: return
        for (c in Fs.children(dir)) {
            if (out.size > 400) return
            val n = c.name ?: continue
            if (c.isDirectory) {
                // 跳过常见的无关目录，省时间也省 token
                if (n in SKIP_DIRS) continue
                collect(c, if (prefix.isEmpty()) n else "$prefix/$n", out, depth + 1)
            } else if (c.isFile && (n.endsWith(".kt") || n.endsWith(".java") || n.endsWith(".gradle"))) {
                val txt = Fs.readText(ctx, c)
                if (txt.isBlank()) continue
                out.add((if (prefix.isEmpty()) n else "$prefix/$n") to txt)
            }
        }
    }

    private fun start() {
        val ctx = context ?: return
        if (running) { Toast.makeText(ctx, "正在审核中", Toast.LENGTH_SHORT).show(); return }
        if (!GroqApi.looksLikeKey(GroqApi.key(ctx))) {
            Toast.makeText(ctx, "先填一个 gsk_ 开头的密钥", Toast.LENGTH_SHORT).show()
            return
        }
        if (targets.isEmpty()) {
            Toast.makeText(ctx, "先选一个源码目录", Toast.LENGTH_SHORT).show()
            return
        }
        // 保存模型选择
        val m = GroqApi.MODELS.getOrNull(spModel.selectedItemPosition)?.first
        if (m != null) GroqApi.setModel(ctx, m)

        stop = false
        running = true
        resultBox.removeAllViews()
        btnRun.isEnabled = false
        bg { runAll() }
    }

    private fun runAll() {
        val ctx = context ?: return
        val total = targets.size
        var done = 0
        var failed = 0
        for ((path, src) in targets) {
            if (stop) break
            done++
            val i = done
            main { tvProgress.text = "审核 $i / $total：$path" }

            val r = try {
                GroqApi.review(ctx, path, src) { sec ->
                    main { tvQuota.text = "限速等待约 ${sec}s（免费层每分钟约 30 次）" }
                }
            } catch (t: Throwable) {
                GroqApi.Review(false, "审核失败：${Http.describeError(t)}")
            }

            if (!r.ok) failed++
            main {
                tvQuota.text = GroqApi.lastQuota.text()
                addResult(path, r)
            }
        }
        main {
            running = false
            btnRun.isEnabled = true
            tvProgress.text = if (stop) "已停止，完成 ${done - 1} / $total"
            else "完成：${done - failed} 成功，$failed 失败（共 $total）"
        }
    }

    private fun addResult(path: String, r: GroqApi.Review) {
        val ctx = context ?: return
        val pad = (10 * ctx.resources.displayMetrics.density).toInt()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bg_card)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = (8 * ctx.resources.displayMetrics.density).toInt()
        card.layoutParams = lp

        val title = TextView(ctx).apply {
            text = if (r.ok) path else "$path  ⚠ ${r.text.take(60)}"
            textSize = 13f
            setTextIsSelectable(true)
        }
        card.addView(title)

        if (r.ok) {
            val body = TextView(ctx).apply {
                text = buildString {
                    append(r.text)
                    if (r.truncated) append("\n\n（文件过长，只审查了开头部分）")
                }
                textSize = 12f
                setTextIsSelectable(true)
            }
            card.addView(body)

            card.setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(path)
                    .setMessage(r.text)
                    .setPositiveButton("复制") { _, _ -> copy(ctx, path, r.text) }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
        resultBox.addView(card)
    }

    private fun copy(ctx: Context, label: String, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Err.ignore(t, "copy review")
        }
    }

    /** 后台执行 */
    private fun bg(block: () -> Unit) {
        Thread {
            try { block() } catch (t: Throwable) { Err.ignore(t, "code review bg") }
        }.start()
    }

    /** 回到主线程；页面已销毁就什么都不做 */
    private fun main(block: () -> Unit) {
        val a = activity ?: return
        a.runOnUiThread {
            if (isAdded) {
                try { block() } catch (t: Throwable) { Err.ignore(t, "code review ui") }
            }
        }
    }

    override fun onDestroyView() {
        stop = true
        super.onDestroyView()
    }

    companion object {
        private val SKIP_DIRS = setOf(
            "build", ".git", ".gradle", ".idea", "node_modules",
            "caches", "outputs", "intermediates", "generated"
        )
    }
}
