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
    private lateinit var cbAll: android.widget.CheckBox
    private lateinit var btnBatchDel: View
    private lateinit var btnBatchOff: View
    private lateinit var btnBatchOn: View
    private lateinit var btnBatchUp: View
    /** 当前列表里的全部文件，供批量操作取用 */
    private val allFiles = ArrayList<androidx.documentfile.provider.DocumentFile>()
    /** 已勾选的文件名 */
    private val picked = HashSet<String>()

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

        // ---- 批量操作栏 ----
        // 之前只能一个个点，装几十个模组时非常折磨。
        // 现在：勾选 → 批量删除 / 批量禁用 / 批量启用 / 批量升级。
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        cbAll = android.widget.CheckBox(ctx).apply {
            text = "全选"
            textSize = 13f
        }
        bar.addView(cbAll)
        bar.addView(android.widget.Space(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })

        fun barBtn(t: String, act: () -> Unit): View =
            UiCards.outlinedButton(ctx, t).apply {
                setOnClickListener { act() }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = (4 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }

        btnBatchDel = barBtn("删除") { batchDelete() }
        btnBatchOff = barBtn("禁用") { batchToggle(true) }
        btnBatchOn = barBtn("启用") { batchToggle(false) }
        btnBatchUp = barBtn("升级") { batchUpgrade() }
        bar.addView(btnBatchDel)
        bar.addView(btnBatchOff)
        bar.addView(btnBatchOn)
        bar.addView(btnBatchUp)
        root.addView(bar)

        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)
        return scroll
    }

    // ---------------- 批量操作 ----------------

    private fun pickedFiles(): List<androidx.documentfile.provider.DocumentFile> =
        allFiles.filter { picked.contains(it.name ?: "") }

    private fun updateBatchBar(count: Int) {
        if (!::btnBatchDel.isInitialized) return
        val has = count > 0
        for (b in listOf(btnBatchDel, btnBatchOff, btnBatchOn, btnBatchUp)) {
            b.isEnabled = has
            b.alpha = if (has) 1f else 0.4f
        }
        cbAll.setOnCheckedChangeListener(null)
        cbAll.isChecked = has && count == allFiles.size && allFiles.isNotEmpty()
        cbAll.setOnCheckedChangeListener { _, on ->
            picked.clear()
            if (on) picked.addAll(allFiles.map { it.name ?: "" })
            render(allFiles)
        }
        tvState.text = buildString {
            val mb = allFiles.sumOf { it.length() } / 1048576.0
            append("共 ${allFiles.size} 个 · ").append(String.format("%.1f MB", mb))
            if (has) append(" · 已选 $count 个")
        }
    }

    private fun batchDelete() {
        val ctx = context ?: return
        val list = pickedFiles()
        if (list.isEmpty()) return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("批量删除")
            .setMessage("${list.size} 个模组会放进回收站，可以随时还原。")
            .setPositiveButton("删除") { _, _ ->
                val dlg = ProgressDialog.show(ctx, "删除 ${list.size} 个模组")
                exec.execute {
                    val res = BatchModOps.delete(ctx, list) { i, n, doing ->
                        safePost(handler) { dlg.update(i.toLong(), n.toLong()) }
                    }
                    safePost(handler) {
                        dlg.dismiss()
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("完成")
                            .setMessage(BatchModOps.summary(res, "删除"))
                            .setPositiveButton(R.string.ok, null)
                            .show()
                        picked.clear()
                        load()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun batchToggle(disabled: Boolean) {
        val ctx = context ?: return
        val list = pickedFiles()
        if (list.isEmpty()) return
        exec.execute {
            val res = BatchModOps.toggle(list, disabled)
            safePost(handler) {
                Toast.makeText(
                    ctx,
                    BatchModOps.summary(res, if (disabled) "禁用" else "启用"),
                    Toast.LENGTH_LONG
                ).show()
                picked.clear()
                load()
            }
        }
    }

    /** 批量升级：对选中的模组查最新版并下载 */
    private fun batchUpgrade() {
        val ctx = context ?: return
        val list = pickedFiles()
        if (list.isEmpty()) return
        val mc = Prefs.get(ctx).getString(K.MC_VERSION, "") ?: ""
        val loader = Prefs.get(ctx).getString(K.LOADER, "") ?: "auto"
        if (mc.isBlank()) {
            val et = android.widget.EditText(ctx).apply {
                hint = "目标 MC 版本，例如 1.20.1"
                setSingleLine(true)
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("需要 MC 版本")
                .setView(et)
                .setPositiveButton("继续") { _, _ ->
                    val v = et.text.toString().trim()
                    if (v.isBlank()) {
                        toast("请填写版本")
                        return@setPositiveButton
                    }
                    Prefs.get(ctx).edit().putString(K.MC_VERSION, v).apply()
                    doBatchUpgrade(list, v, loader)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        doBatchUpgrade(list, mc, loader)
    }

    private fun doBatchUpgrade(
        list: List<androidx.documentfile.provider.DocumentFile>, mc: String, loader: String
    ) {
        val ctx = context ?: return
        val dlg = ProgressDialog.show(ctx, "检查 ${list.size} 个模组的更新")
        exec.execute {
            val tasks = ArrayList<Pair<String, String>>() // url to name
            var i = 0
            for (f in list) {
                i++
                val n = f.name ?: continue
                safePost(handler) { dlg.update(i.toLong(), list.size.toLong()) }
                val clean = CrossLoader.cleanName(n)
                val hits = try {
                    ModrinthApi.search(clean, mc, loader, 3)
                } catch (t: Throwable) {
                    emptyList<MarketMod>()
                }
                val proj = hits.firstOrNull() ?: continue
                val vers = try {
                    ModrinthApi.versions(proj.id, mc, loader)
                } catch (t: Throwable) {
                    emptyList<ModFile>()
                }
                val pick = vers.firstOrNull() ?: continue
                if (pick.url.isNotBlank()) {
                    tasks.add(Pair(pick.url, pick.fileName.ifBlank { pick.name }))
                }
            }
            safePost(handler) { dlg.dismiss() }
            if (tasks.isEmpty()) {
                safePost(handler) { toast("没找到可用的更新") }
                return@execute
            }
            safePost(handler) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("发现 ${tasks.size} 个更新")
                    .setMessage("下载到工作目录的 mods 里，不会自动替换原文件。")
                    .setPositiveButton("下载") { _, _ ->
                        val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
                        if (dir == null) {
                            toast("目标目录不可用")
                            return@setPositiveButton
                        }
                        val d2 = ProgressDialog.show(ctx, "下载 ${tasks.size} 个更新")
                        exec.execute {
                            val res = BatchModOps.download(ctx, dir, tasks) { a, b, _ ->
                                safePost(handler) { d2.update(a.toLong(), b.toLong()) }
                            }
                            safePost(handler) {
                                d2.dismiss()
                                MaterialAlertDialogBuilder(ctx)
                                    .setTitle("完成")
                                    .setMessage(BatchModOps.summary(res, "下载"))
                                    .setPositiveButton(R.string.ok, null)
                                    .show()
                                load()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
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

    private fun toast(s: String) {
        handler.post {
            if (isAdded) android.widget.Toast.makeText(context, s, android.widget.Toast.LENGTH_SHORT).show()
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
        allFiles.clear()
        allFiles.addAll(list)
        picked.retainAll(list.map { it.name ?: "" }.toSet())

        for (f in list) {
            val name = f.name ?: continue
            val size = f.length()
            val disabled = ModToggle.isDisabled(f)
            val card = UiCards.infoCard(
                ctx, if (disabled) R.drawable.ic_close else R.drawable.ic_extension,
                ModToggle.displayName(f).substringBeforeLast("."),
                buildString {
                    append("${size / 1024} KB")
                    if (disabled) append(" · 已禁用")
                    append(" · 点击查看信息，长按删除")
                },
                "详情"
            ) { showInfo(f) }
            card.setOnLongClickListener { confirmDelete(f) }

            // 勾选框 + 卡片，支持批量
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            line.addView(android.widget.CheckBox(ctx).apply {
                isChecked = picked.contains(name)
                setOnCheckedChangeListener { _, on ->
                    if (on) picked.add(name) else picked.remove(name)
                    updateBatchBar(picked.size)
                }
            })
            line.addView(card)
            box.addView(line)

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
        syncBatchBar()
    }

    /** 详情：读 jar 里的清单文件 */
    private fun syncBatchBar() {
        updateBatchBar(picked.size)
    }

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
