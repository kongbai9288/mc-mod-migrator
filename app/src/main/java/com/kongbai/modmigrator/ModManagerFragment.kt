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
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * 模组管理。
 *
 * 像 Mod Menu 那样看每个模组的详情（名称/版本/加载器/作者/描述），
 * 可以删除——但**不直接删**，先挪进回收站：
 *   - 刚删完可以「撤销」
 *   - 回收站里能还原或彻底删除
 *   - 超过设定天数自动清掉
 *
 * 图标优先从本地 jar 提取（ModIcons），离线可用。
 */
class ModManagerFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var tvState: TextView

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

        root.addView(
            UiCards.hint(
                ctx,
                "点击卡片看详情，长按删除（先进回收站，可撤销）。\n" +
                    "当前目录：${WorkDir.describe(ctx)}"
            )
        )

        tvState = TextView(ctx).apply {
            textSize = 12f
            setTextColor(resources.getColor(R.color.textSecondary, null))
            setPadding(0, 4, 0, 8)
        }
        root.addView(tvState)

        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)
        return scroll
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val ctx = context ?: return
        tvState.text = "扫描中…"
        box.removeAllViews()
        exec.execute {
            val dir = try {
                WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
            } catch (t: Throwable) {
                null
            }
            val list = if (dir == null) emptyList()
            else try {
                Fs.children(dir).filter {
                    it.isFile && (it.name?.endsWith(".jar", true) == true ||
                        it.name?.endsWith(".zip", true) == true)
                }
            } catch (t: Throwable) {
                emptyList()
            }
            safePost(handler) {
                render(list)
                tvState.text = if (list.isEmpty()) "这个目录里没有模组" else "共 ${list.size} 个模组"
            }
        }
    }

    private fun render(list: List<DocumentFile>) {
        val ctx = context ?: return
        box.removeAllViews()
        if (list.isEmpty()) {
            box.addView(
                UiCards.emptyCard(
                    ctx, "没有找到模组",
                    "到「设置 → 存储」授权你的 mods 目录，或先把模组下载进来。"
                )
            )
            return
        }
        for (f in list) {
            val name = f.name ?: continue
            val size = f.length()
            val card = UiCards.infoCard(
                ctx, R.drawable.ic_extension,
                name.substringBeforeLast("."),
                "${size / 1024} KB · 点击查看信息，长按删除",
                "详情"
            ) { showInfo(f) }
            card.setOnLongClickListener { confirmDelete(f) }
            box.addView(card)

            // 图标异步加载
            val iv = (card as? ViewGroup)?.getChildAt(0) as? android.widget.ImageView
            if (iv != null) {
                exec.submit {
                    val bmp = try {
                        ModIcons.of(ctx, f)
                    } catch (t: Throwable) {
                        null
                    }
                    if (bmp != null) handler.post { iv.setImageBitmap(bmp) }
                }
            }
        }
    }

    /** 详情：读 jar 里的清单文件 */
    private fun showInfo(f: DocumentFile) {
        val ctx = context ?: return
        val name = f.name ?: return
        Thread {
            val info = readJarInfo(ctx, f)
            handler.post {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(name.substringBeforeLast("."))
                    .setMessage(info)
                    .setPositiveButton("删除") { _, _ -> confirmDelete(f) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    /** 从 jar 里读 fabric.mod.json / mods.toml / mcmod.info */
    private fun readJarInfo(ctx: android.content.Context, f: DocumentFile): String {
        val sb = StringBuilder()
        sb.appendLine("文件：${f.name}")
        sb.appendLine("大小：${f.length() / 1024} KB")
        try {
            ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                java.util.zip.ZipInputStream(input).use { zip ->
                    var e: java.util.zip.ZipEntry?
                    var count = 0
                    while (zip.nextEntry.also { e = it } != null && count < 120) {
                        val en = e ?: break
                        count++
                        val n = en.name
                        when {
                            n.equals("fabric.mod.json", true) -> {
                                val t = String(zip.readBytes(), Charsets.UTF_8)
                                sb.appendLine()
                                sb.appendLine("【Fabric 模组】")
                                grepJson(t, sb, "id", "名称 ID")
                                grepJson(t, sb, "version", "版本")
                                grepJson(t, sb, "name", "名称")
                                grepJson(t, sb, "description", "说明")
                                grepAuthors(t, sb)
                                grepDeps(t, sb)
                            }
                            n.equals("quilt.mod.json", true) -> {
                                val t = String(zip.readBytes(), Charsets.UTF_8)
                                sb.appendLine()
                                sb.appendLine("【Quilt 模组】")
                                grepJson(t, sb, "version", "版本")
                                grepJson(t, sb, "description", "说明")
                            }
                            n.equals("META-INF/mods.toml", true) -> {
                                val t = String(zip.readBytes(), Charsets.UTF_8)
                                sb.appendLine()
                                sb.appendLine("【Forge 模组】")
                                grepToml(t, sb, "modId", "ID")
                                grepToml(t, sb, "displayName", "名称")
                                grepToml(t, sb, "version", "版本")
                                grepToml(t, sb, "description", "说明")
                            }
                            n.equals("mcmod.info", true) -> {
                                val t = String(zip.readBytes(), Charsets.UTF_8)
                                sb.appendLine()
                                sb.appendLine("【旧版 Forge】")
                                grepJson(t, sb, "modid", "ID")
                                grepJson(t, sb, "name", "名称")
                                grepJson(t, sb, "version", "版本")
                                grepJson(t, sb, "description", "说明")
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            sb.appendLine()
            sb.appendLine("（读取 jar 内容失败：${t.message}）")
        }
        if (sb.length < 60) sb.appendLine("\n没有读到模组清单信息。")
        return sb.toString()
    }

    private fun grepJson(t: String, sb: StringBuilder, key: String, label: String) {
        val m = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(t)
        if (m != null) sb.appendLine("$label：${m.groupValues[1]}")
    }

    private fun grepToml(t: String, sb: StringBuilder, key: String, label: String) {
        val m = Regex("$key\\s*=\\s*\"([^\"]+)\"").find(t)
        if (m != null) sb.appendLine("$label：${m.groupValues[1]}")
    }

    private fun grepAuthors(t: String, sb: StringBuilder) {
        val m = Regex("\"authors\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(t)
        if (m != null) {
            val names = Regex("\"([^\"]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }
                .filter { !it.startsWith("$") }.toList()
            if (names.isNotEmpty()) sb.appendLine("作者：${names.joinToString("、")}")
        }
    }

    private fun grepDeps(t: String, sb: StringBuilder) {
        val deps = Regex("\"depends\"\\s*:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(t)
        if (deps != null) {
            val keys = Regex("\"([^\"]+)\"\\s*:").findAll(deps.groupValues[1])
                .map { it.groupValues[1] }.toList()
            if (keys.isNotEmpty()) sb.appendLine("依赖：${keys.joinToString("、")}")
        }
    }

    /** 确认删除 → 进回收站 */
    private fun confirmDelete(f: DocumentFile): Boolean {
        val ctx = context ?: return false
        val name = f.name ?: return false
        val days = Trash.days(ctx)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("删除模组")
            .setMessage("「$name」会先放进回收站（保留 $days 天），期间可以还原。")
            .setPositiveButton("删除") { _, _ ->
                Thread {
                    val ok = Trash.moveToTrash(ctx, f)
                    handler.post {
                        if (ok) {
                            Toast.makeText(ctx, "已放进回收站", Toast.LENGTH_SHORT).show()
                            // 撤销入口
                            showUndo(ctx, name)
                            load()
                        } else {
                            Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    /** 撤销提示 */
    private fun showUndo(ctx: android.content.Context, name: String) {
        val item = Trash.items(ctx).firstOrNull { it.name == name } ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // 用对话框给撤销机会
            try {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("已删除「$name」")
                    .setMessage("要撤销吗？")
                    .setPositiveButton("撤销") { _, _ ->
                        Thread {
                            val ok = Trash.restore(ctx, item)
                            handler.post {
                                Toast.makeText(
                                    ctx,
                                    if (ok) "已还原" else "还原失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                                load()
                            }
                        }.start()
                    }
                    .setNegativeButton("不用", null)
                    .show()
            } catch (t: Throwable) {
            }
        }, 300)
    }
}
