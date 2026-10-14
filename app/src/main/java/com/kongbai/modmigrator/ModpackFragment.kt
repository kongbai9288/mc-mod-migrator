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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * 整合包更新。
 *
 * 对应 PC 端 Prism / MultiMC 的「Mod updates」：
 * 逐个算出本地 jar 的 SHA-1，走 Modrinth 的 version_file 接口反查项目，
 * 再拉该项目在当前 MC 版本 + 加载器下的最新版本，比对出可更新项。
 * 更新是「下载到目标目录」，不会自动替换原文件，方便你先试再换。
 */
class ModpackFragment : Fragment() {

    private val exec = Bg.io
    private val handler = Bg.ui
    private lateinit var box: LinearLayout
    private lateinit var tvState: TextView
    private val results = mutableListOf<UpdateRow>()

    /**
     * 选一个 .mrpack 导入。
     *
     * 导入是「用户主动」这个前提的入口：只有走这里进来的包才会被登记，
     * 也只有登记过的包才会被更新检查碰。
     */
    private val openPack = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) doImport(uri)
    }

    data class UpdateRow(
        var file: String,
        var current: String,
        var latest: String,
        var url: String,
        var latestName: String,
        var status: String
    )

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
        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tvState = TextView(ctx).apply {
            text = "上面两个按钮是整合包：导入后才登记，可检查更新、一次装进多个启动器。" +
                "\n下面的「检查更新」是按目录查模组更新，不是整合包更新，也不登记。"
            textSize = 13f
            setTextColor(resources.getColor(R.color.textSecondary, null))
        }
        root.addView(tvState)
        root.addView(mkButton("导入整合包（.mrpack / .zip）") { pickPack() })
        root.addView(mkButton("已导入的整合包（更新 / 多实例安装）") { showImported() })
        root.addView(TextView(ctx).apply {
            text = "下面这一段是按目录检查模组更新，不属于整合包更新，也不会登记"
            textSize = 12f
            setTextColor(resources.getColor(R.color.textSecondary, null))
        })
        root.addView(mkButton("检查更新") { checkUpdates() })
        root.addView(mkButton("下载全部更新") { downloadAll() })
        root.addView(box)
        return scroll
    }

    private fun mkButton(text: String, act: () -> Unit): MaterialButton {
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
            safeClickUnit { act() }
        }
    }

    private fun toast(s: String) {
        handler.post {
            if (isAdded) Tips.short(context, s)
        }
    }

    private fun setState(s: String) {
        handler.post { if (isAdded) tvState.text = s }
    }

    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                setState("出错了：${t.message}")
            } finally {
                // 同 MigrationFragment：Progress 是全局单例，
                // 异常/提前 return 时不复位会永久卡在"运行中"，
                // 下次开 App 出现幽灵进度条。
                try { Progress.done() } catch (_: Throwable) {}
            }
        }
    }

    private fun checkUpdates() {
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        val src = p.getString(K.SRC_URI, null)
        if (src.isNullOrBlank()) {
            toast("请先在迁移页选择「迁移前」的版本目录")
            return
        }
        val mc = p.getString(K.DEF_VERSION, "") ?: ""
        val loader = p.getString(K.DEF_LOADER, "") ?: "auto"
        setState("正在算哈希并查询（离线模式下无法检查）…")
        if (p.getBoolean(K.OFFLINE, false)) {
            setState("离线模式：无法联网检查更新")
            return
        }
        bg {
            val root = Fs.tree(ctx, src)
            val mods = root?.let { Fs.find(it, McDirs.MODS, 2) }
            if (mods == null) {
                setState("源目录里没有找到 mods 文件夹")
                return@bg
            }
            val jars = Fs.children(mods).filter {
                it.isFile && (it.name ?: "").endsWith(".jar", true)
            }
            if (jars.isEmpty()) {
                setState("mods 目录里没有 jar")
                return@bg
            }

            // ⚠️ 之前这里是**两层串行逐个请求**：
            //   每个 jar → lookupHashSimple(1 次) → versions(1 次)
            //   50 个模组就是 100 次排队等待，检查一轮要好几分钟，
            //   中途还要 setState 刷新界面，看着像卡住了。
            // 现在改成三次批量：
            //   ① 本地算全部指纹  ② 一次批量反查项目  ③ 一次批量取最新版本
            setState("正在计算 ${jars.size} 个文件的指纹…")
            val shaOf = java.util.concurrent.ConcurrentHashMap<String, String>()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(
                (p.getInt(K.DOWNLOAD_PARALLEL, 3)).coerceIn(1, 4)
            )
            val latch = java.util.concurrent.CountDownLatch(jars.size)
            for (j in jars) {
                pool.execute {
                    try {
                        val s = try { Fs.sha1(ctx, j) } catch (t: Throwable) { "" }
                        shaOf[j.uri.toString()] = s
                    } catch (t: Throwable) {
                        Err.ignore(t, "计算整合包指纹")
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            pool.shutdown()

            val hashes = ArrayList<String>()
            for (j in jars) {
                val s = shaOf[j.uri.toString()].orEmpty()
                if (s.isBlank()) continue
                hashes.add(s.lowercase())
            }

            setState("批量反查 ${hashes.size} 个指纹…")
            val found = try {
                ModrinthApi.lookupHashes(hashes)
            } catch (t: Throwable) {
                emptyMap<String, ModrinthApi.LookupResult>()
            }
            // 顺带把项目名取回来（一次批量请求）
            ModrinthApi.refreshAll(found.values.map { it.projectId })

            setState("批量查询最新版本…")
            val latest = try {
                ModrinthApi.latestForHashes(hashes, mc, loader)
            } catch (t: Throwable) {
                emptyMap<String, ModFile>()
            }

            val rows = ArrayList<UpdateRow>()
            for (j in jars) {
                val name = j.name ?: ""
                val s = shaOf[j.uri.toString()].orEmpty()
                if (s.isBlank()) {
                    rows.add(UpdateRow(name, "", "", "", "", "哈希计算失败"))
                    continue
                }
                val hit = found[s.lowercase()]
                if (hit == null || !hit.found) {
                    rows.add(UpdateRow(name, "", "", "", "", "Modrinth 上没找到"))
                    continue
                }
                val f0 = latest[s.lowercase()]
                if (f0 == null) {
                    rows.add(UpdateRow(name, hit.version, "", "", "", "当前版本下无可用更新"))
                    continue
                }
                val same = f0.fileName.equals(name, true)
                rows.add(
                    UpdateRow(
                        file = name,
                        current = hit.version,
                        latest = f0.fileName.substringBeforeLast(".jar"),
                        url = f0.url,
                        latestName = f0.fileName,
                        status = if (same) "已是最新" else "可更新"
                    )
                )
            }
            handler.post {
                if (!isAdded) return@post
                results.clear()
                results.addAll(rows)
                render()
                val n = rows.count { it.status == "可更新" }
                setState("共 ${rows.size} 个模组，其中 $n 个可更新")
            }
        }
    }

    private fun render() {
        if (!isAdded) return
        val ctx = requireContext()
        box.removeAllViews()
        for (r in results) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, pad, 0, pad)
            }
            val t = TextView(ctx).apply {
                text = "${r.file}\n${r.status}${if (r.latest.isNotBlank()) " → ${r.latest}" else ""}"
                textSize = 13f
                setTextIsSelectable(true)
            }
            row.addView(t)
            if (r.url.isNotBlank() && r.status == "可更新") {
                row.addView(MaterialButton(
                    ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    this.text = "下载新版"
                    safeClickUnit { downloadOne(r) }
                })
            }
            box.addView(row)
        }
    }

    private fun downloadOne(r: UpdateRow) {
        val ctx = context ?: return
        toast("开始下载 ${r.latestName}")
        exec.execute {
            try {
                val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
                if (dir == null) {
                    toast("目标目录不可用，请先在设置里选工作目录")
                    return@execute
                }
                // ⚠️ 之前不看返回值就弹"下载完成"——下载失败也一样报成功。
                val out = Downloader.download(ctx, r.url, dir, r.latestName, emptyMap())
                handler.post {
                    if (!isAdded) return@post
                    if (out == null) {
                        toast("下载失败：${r.latestName}")
                        return@post
                    }
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("下载完成")
                        .setMessage("${r.latestName}\n\n已放到目标 mods 目录，替换旧文件前建议先备份。")
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            } catch (t: Throwable) {
                toast("下载失败：${Err.humanMessage(t)}")
            }
        }
    }

    private fun downloadAll() {
        val ctx = context ?: return
        val todo = results.filter { it.status == "可更新" && it.url.isNotBlank() }
        if (todo.isEmpty()) {
            toast("没有可更新的模组")
            return
        }
        toast("开始下载 ${todo.size} 个")
        // ⚠️ 两个问题：
        //  ① `exec` 是**单线程**池，所谓"下载全部"其实是一个一个排队下。
        //  ② `ok++` 不看 download 的返回值 —— 下载失败也计成成功，
        //     最后报"完成 20/20"，实际可能一个都没落地。
        // 改成真并发，并且只在真的拿到文件时才计数。
        val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
        if (dir == null) {
            toast("目标目录不可用，请先在设置里选工作目录")
            return
        }
        val ok = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            (Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3) / 2).coerceIn(1, 4)
        )
        val latch = java.util.concurrent.CountDownLatch(todo.size)
        for (r in todo) {
            pool.execute {
                try {
                    if (Downloader.download(ctx, r.url, dir, r.latestName, emptyMap()) != null) {
                        ok.incrementAndGet()
                    } else {
                        Err.ignore(RuntimeException("下载失败 ${r.latestName}"), "整合包批量下载")
                    }
                } catch (t: Throwable) {
                    Err.ignore(t, "整合包批量下载 ${r.latestName}")
                } finally {
                    latch.countDown()
                }
            }
        }
        java.lang.Thread {
            latch.await()
            pool.shutdown()
            handler.post {
                if (!isAdded) return@post
                setState("下载完成：${ok.get()}/${todo.size}")
                toast("完成 ${ok.get()}/${todo.size}")
            }
        }.start()
    }
    // ------------------------------------------------------------------
    // 整合包：导入 → 登记 → 更新检查 / 多实例统一安装
    // ------------------------------------------------------------------

    private fun pickPack() {
        runCatching {
            openPack.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }.onFailure {
            toast("打不开文件选择器：${it.message}")
        }
    }

    private fun doImport(uri: android.net.Uri) {
        val ctx = context ?: return
        // 之后安装还要从这个 uri 里读 overrides，权限必须留下来，
        // 只拿到 uri 不申请持久权限的话，重开应用就读不了了。
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        setState("正在读取清单…")
        bg {
            val msgs = ArrayList<String>()
            val pack = MrpackInstall.importFrom(ctx, uri) { msgs.add(it) }
            handler.post {
                if (!isAdded) return@post
                if (pack == null) {
                    setState(msgs.joinToString("\n").ifBlank { "导入失败：这个文件读不出清单，也不是能直接解压的 zip" })
                    return@post
                }
                setState("已导入：${pack.label()}（${pack.kindLabel()}）\n共 ${pack.fileCount} 个文件")
                showPackActions(pack)
            }
        }
    }

    private fun showImported() {
        val ctx = context ?: return
        val packs = MrpackInstall.list(ctx)
        if (packs.isEmpty()) {
            toast("还没有导入过整合包。点上面的「导入整合包」")
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("已导入的整合包")
            .setItems(
                packs.map { "${it.label()}（${it.kindLabel()}）\n${it.fileCount} 个文件" }
                    .toTypedArray()
            ) { _, w ->
                showPackActions(packs[w])
            }
            .show()
    }

    private fun showPackActions(pack: MrpackInstall.Pack) {
        val ctx = context ?: return
        val acts = arrayOf("检查更新", "安装到多个实例 / 启动器", "移除登记")
        MaterialAlertDialogBuilder(ctx)
            .setTitle(pack.name)
            .setItems(acts) { _, w ->
                when (w) {
                    0 -> checkPackUpdate(pack)
                    1 -> installPack(pack)
                    2 -> {
                        MrpackInstall.remove(ctx, pack.id)
                        setState("已移除登记：${pack.name}（之后不再检查它的更新）")
                    }
                }
            }
            .show()
    }

    private fun checkPackUpdate(pack: MrpackInstall.Pack) {
        val ctx = context ?: return
        if (Prefs.get(ctx).getBoolean(K.OFFLINE, false)) {
            setState("离线模式：无法联网检查更新")
            return
        }
        setState("正在检查「${pack.name}」的更新…")
        bg {
            val msgs = ArrayList<String>()
            val items = MrpackUpdate.check(ctx, pack) { msgs.add(it) }
            handler.post {
                if (!isAdded) return@post
                if (items == null) {
                    setState(msgs.joinToString("\n").ifBlank { "检查失败" })
                    return@post
                }
                if (items.isEmpty()) {
                    setState("「${pack.name}」都已是最新")
                    return@post
                }
                setState("${items.size} 个文件可更新")
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("可更新 ${items.size} 个")
                    .setMessage(items.take(12).joinToString("\n") {
                        "${it.currentName}\n  → ${it.latestName}"
                    })
                    .setPositiveButton("更新到全部实例") { _, _ ->
                        pickTargets { targets ->
                            bg {
                                val log = ArrayList<String>()
                                val n = MrpackUpdate.apply(ctx, items, targets) { log.add(it) }
                                handler.post {
                                    if (!isAdded) return@post
                                    setState("更新完成 $n 处\n" + log.takeLast(6).joinToString("\n"))
                                }
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun installPack(pack: MrpackInstall.Pack) {
        val ctx = context ?: return
        pickTargets { targets ->
            setState("正在安装到 ${targets.size} 个目标…")
            bg {
                val msgs = ArrayList<String>()
                val ok = MrpackInstall.install(
                    ctx, pack, targets, MrpackInstall.Side.CLIENT
                ) { msgs.add(it) }
                handler.post {
                    if (!isAdded) return@post
                    setState("安装完成 $ok/${targets.size}\n" + msgs.takeLast(8).joinToString("\n"))
                }
            }
        }
    }

    /**
     * 让用户挑安装目标。
     *
     * 候选来自**所有已授权的游戏目录**以及它们下面扫出来的实例 ——
     * 手机上装了 FCL 又装了 Zalith 时，两边都能出现在列表里，
     * 一个包可以同时装进多个启动器。
     */
    private fun pickTargets(onOk: (List<DocumentFile>) -> Unit) {
        val ctx = context ?: return
        setState("正在扫描可安装的目标…")
        bg {
            val cands = ArrayList<Pair<String, DocumentFile>>()
            val seen = HashSet<String>()
            for (e in GameDirs.list(ctx)) {
                val tree = runCatching {
                    DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(e.uri))
                }.getOrNull()
                if (tree != null && seen.add(tree.uri.toString())) {
                    cands.add(Pair("游戏目录 · ${e.label()}", tree))
                }
                val insts = runCatching {
                    InstanceScanner.scanFrom(ctx, e.uri) { }
                }.getOrDefault(emptyList())
                for (i in insts) {
                    val d = runCatching {
                        DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(i.uri))
                    }.getOrNull()
                    if (d != null && seen.add(d.uri.toString())) {
                        val from = i.source.ifBlank { e.label() }
                        cands.add(Pair("实例 · ${i.name}（来自 $from）", d))
                    }
                }
            }
            handler.post {
                if (!isAdded) return@post
                if (cands.isEmpty()) {
                    toast("没有可安装的目标。先在设置 → 存储里添加启动器目录")
                    return@post
                }
                val labels = cands.map { it.first }.toTypedArray()
                val checked = BooleanArray(cands.size)
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("装到哪里（可多选）")
                    .setMultiChoiceItems(labels, checked) { _, w, b -> checked[w] = b }
                    .setPositiveButton("安装") { _, _ ->
                        val picked = cands.filterIndexed { i, _ -> checked[i] }.map { it.second }
                        if (picked.isEmpty()) toast("没有选中任何目标") else onOk(picked)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
