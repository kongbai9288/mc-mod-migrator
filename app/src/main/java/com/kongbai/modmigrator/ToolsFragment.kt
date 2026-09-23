package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * 工具箱：把 PC 端启动器里那些零散但实用的小工具收在一起。
 *
 * 目前有：
 *   - 模组体检（重复、可疑文件名、超大文件）
 *   - 配置对比（迁移前后差异）
 *   - 清理缓存（翻译缓存、下载缓存）
 *   - 存储用量（工作目录各子目录大小）
 */
class ToolsFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = android.widget.ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)
        root.addView(UiCards.hint(ctx, "迁移前后的检查与清理工具。"))

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_check_circle, "模组体检",
            "查重复模组、可疑文件名、超大文件", "运行"
        ) { doctor() })

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_content_copy, "配置对比",
            "对比迁移前后 config 的差异，看会被覆盖哪些", "运行"
        ) { diff() })

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_delete, "清理缓存",
            "清掉翻译缓存与下载缓存", "清理"
        ) { clearCache() })

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_folder, "存储用量",
            "看工作目录各子目录占了多少空间", "查看"
        ) { usage() })

        return scroll
    }

    private fun btn(text: String, act: () -> Unit): MaterialButton {
        val ctx = requireContext()
        return MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = text
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            setOnClickListener { act() }
        }
    }

    private fun toast(s: String) {
        handler.post { if (isAdded) Toast.makeText(context, s, Toast.LENGTH_SHORT).show() }
    }

    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                toast("出错了：${t.message}")
            }
        }
    }

    private fun show(title: String, text: String) {
        handler.post {
            if (!isAdded) return@post
            val ctx = requireContext()
            val sv = android.widget.ScrollView(ctx)
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 13f
                setPadding(24, 16, 24, 16)
                setTextIsSelectable(true)
            }
            sv.addView(tv)
            MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton("复制") { _, _ ->
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText(title, text)
                    )
                    toast("已复制")
                }
                .show()
        }
    }

    private fun doctor() {
        val ctx = requireContext()
        val src = Prefs.get(ctx).getString(K.SRC_URI, null)
        if (src.isNullOrBlank()) {
            toast("请先在迁移页选择「迁移前」的版本目录")
            return
        }
        toast("体检中…")
        bg {
            val rep = ModTools.doctor(ctx, src)
            show(rep.title, rep.text)
        }
    }

    private fun diff() {
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        val src = p.getString(K.SRC_URI, null)
        val dst = p.getString(K.DST_URI, null)
        if (src.isNullOrBlank() || dst.isNullOrBlank()) {
            toast("请同时选择「迁移前」与「迁移后」的目录")
            return
        }
        toast("对比中…")
        bg {
            val rep = ModTools.diffConfig(ctx, src, dst)
            show(rep.title, rep.text)
        }
    }

    private fun clearCache() {
        val ctx = requireContext()
        bg {
            var n = 0
            runCatching {
                val c = WorkDir.cache(ctx)
                if (c != null) {
                    for (f in Fs.children(c)) {
                        if (f.isFile && f.delete()) n++
                    }
                }
                val tc = WorkDir.translate(ctx)
                if (tc != null) {
                    for (f in Fs.children(tc)) {
                        if (f.isFile && f.name?.endsWith(".json") == true && f.delete()) n++
                    }
                }
            }
            toast("已清理 $n 个缓存文件")
        }
    }

    private fun usage() {
        val ctx = requireContext()
        bg {
            val sb = StringBuilder()
            val root = WorkDir.root(ctx)
            if (root == null) {
                show("存储用量", "还没设置工作目录。去「设置 → 存储」选一个目录即可。")
                return@bg
            }
            var total = 0L
            for (name in listOf("packs", "mods", "configs", "cache", "translate", "logs", "data")) {
                val d = Fs.find(root, name, 1)
                if (d == null) continue
                var size = 0L
                var count = 0
                fun walk(dir: androidx.documentfile.provider.DocumentFile, depth: Int) {
                    if (depth > 4) return
                    for (c in Fs.children(dir)) {
                        if (c.isDirectory) walk(c, depth + 1)
                        else {
                            size += c.length()
                            count++
                        }
                    }
                }
                walk(d, 0)
                total += size
                sb.append("$name：$count 个文件，${fmt(size)}\n")
            }
            sb.append("\n合计：${fmt(total)}")
            show("存储用量", sb.toString())
        }
    }

    private fun fmt(size: Long): String {
        if (size < 1024) return "${size}B"
        if (size < 1024 * 1024) return "${size / 1024}KB"
        return String.format(java.util.Locale.ROOT, "%.1fMB", size / 1024.0 / 1024.0)
    }
}
