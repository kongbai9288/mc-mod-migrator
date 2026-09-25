package com.kongbai.modmigrator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MigrationFragment : Fragment() {

    private lateinit var tvSource: TextView
    private lateinit var tvScanHint: android.widget.TextView
    private lateinit var tvTarget: TextView
    private lateinit var etVersion: EditText
    private lateinit var spLoader: Spinner
    private lateinit var cbConfig: CheckBox
    private lateinit var cbScripts: CheckBox
    private lateinit var cbOptions: CheckBox
    private lateinit var cbPacks: CheckBox
    private lateinit var cbSaves: CheckBox
    private lateinit var pb: ProgressBar
    private lateinit var rvMods: RecyclerView
    private lateinit var tvLog: TextView
    private lateinit var tvLogSummary: TextView
    private val instances = mutableListOf<InstanceInfo>()

    private val mods = mutableListOf<ModEntry>()
    private lateinit var adapter: ModAdapter
    private val sb = StringBuilder()
    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 进度回调。
     * 注意：这个 lambda 在 onResume 注册后可能立刻被回调一次，
     * 所以里面用到的 lateinit 字段必须先判断有没有初始化，否则会崩。
     */
    private val progressHook: (Progress.State) -> Unit = { st ->
        if (::pb.isInitialized && ::tvProgress.isInitialized) {
            pb.visibility = if (st.running) View.VISIBLE else View.GONE
            pb.isIndeterminate = st.total <= 0
            if (st.total > 0) pb.progress = st.percent
            // 主行：正在做什么（第几个/共几个）
            // 副行：百分比 + 已用 + 预计剩余 + 速度
            val main = st.text
            val det = st.detail
            tvProgress.text = if (det.isBlank()) main else "$main\n$det"
            tvProgress.visibility = if (st.running && main.isNotBlank()) View.VISIBLE else View.GONE
        }
    }

    private val logHook: (List<LogCenter.LogLine>) -> Unit = {
        if (::tvLogSummary.isInitialized) refreshLogSummary()
    }

    private val errorHook: (LogCenter.LogLine) -> Unit = { line ->
        val ctx = context
        if (ctx != null && isAdded) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("操作失败")
                .setMessage(line.msg)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton("查看全部日志") { _, _ -> showAllLogs() }
                .show()
        }
    }

    private lateinit var tvProgress: TextView

    /** 展示完整运行日志 */
    private fun showAllLogs() {
        val ctx = context ?: return
        val txt = LogCenter.all().joinToString("\n") { it.toString() }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("运行日志（共 ${LogCenter.count()} 条）")
            .setMessage(txt.ifBlank { "暂无日志" }.let { if (it.length > 6000) it.takeLast(6000) else it })
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton("清空") { _, _ -> LogCenter.clear(); refreshLogSummary() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        Progress.addHook(progressHook)
        LogCenter.addListener(logHook)
        LogCenter.addErrorHook(errorHook)
        refreshLogSummary()
    }

    override fun onPause() {
        Progress.removeHook(progressHook)
        LogCenter.removeListener(logHook)
        LogCenter.removeErrorHook(errorHook)
        super.onPause()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_migration, container, false)
        tvSource = v.findViewById(R.id.tvSource)
        tvScanHint = v.findViewById(R.id.tvScanHint)
        tvTarget = v.findViewById(R.id.tvTarget)
        etVersion = v.findViewById(R.id.etTargetVersion)
        spLoader = v.findViewById(R.id.spLoader)
        cbConfig = v.findViewById(R.id.cbConfig)
        cbScripts = v.findViewById(R.id.cbScripts)
        cbOptions = v.findViewById(R.id.cbOptions)
        cbPacks = v.findViewById(R.id.cbPacks)
        cbSaves = v.findViewById(R.id.cbSaves)
        pb = v.findViewById(R.id.pb)
        rvMods = v.findViewById(R.id.rvMods)
        tvLog = v.findViewById(R.id.tvLog)

        tvLogSummary = v.findViewById(R.id.tvLogSummary)
        tvProgress = v.findViewById(R.id.tvProgress)
        adapter = ModAdapter(mods, { m -> downloadOne(m) }, { m -> openModDetail(m) })
        // 顶部摘要可点开看完整日志
        tvLogSummary.setOnClickListener { showAllLogs() }
        rvMods.layoutManager = LinearLayoutManager(requireContext())
        rvMods.adapter = adapter
        rvMods.isNestedScrollingEnabled = false

        v.findViewById<Button>(R.id.btnScanLocal)?.setOnClickListener { scanLocal() }
        v.findViewById<Button>(R.id.btnPickLauncher)?.setOnClickListener { pickLauncher() }
        v.findViewById<Button>(R.id.btnPickSource).setOnClickListener { pickDir(11) }
        v.findViewById<Button>(R.id.btnPickTarget).setOnClickListener { pickDir(12) }
        v.findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
        v.findViewById<Button>(R.id.btnDownloadAll)?.setOnClickListener { downloadAll() }
        v.findViewById<Button>(R.id.btnDownloadAll)?.setOnClickListener { downloadAll() }
        v.findViewById<Button>(R.id.btnRun).setOnClickListener { runMigration() }
        v.findViewById<Button>(R.id.btnDoctor).setOnClickListener { runDoctor() }
        v.findViewById<Button>(R.id.btnDiff).setOnClickListener { runDiff() }

        val p = Prefs.get(requireContext())
        etVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")
        selectLoader(p.getString(K.DEF_LOADER, "") ?: "auto")
        refreshPaths()
        return v
    }

    /** 点迁移列表里的某一行 → 打开该模组的叙述页 */
    private fun openModDetail(m: ModEntry) {
        val ctx = context ?: return
        val url = if (m.pageUrl.isNotBlank()) {
            m.pageUrl
        } else {
            // 没有解析出页面地址时，按模组名去 Modrinth 搜索页兜底
            val q = Http.enc(m.name.ifBlank { m.fileName.substringBeforeLast(".jar") })
            "https://modrinth.com/mods?q=$q"
        }
        val i = Intent(ctx, ModPageActivity::class.java)
        i.putExtra("url", url)
        startActivity(i)
    }

    /** 模组体检：迁移前先看有没有重复、可疑文件名 */
    private fun runDoctor() {
        val ctx = requireContext()
        val src = Prefs.get(ctx).getString(K.SRC_URI, null)
        if (src.isNullOrBlank()) {
            toast("请先选择「迁移前」的版本目录")
            return
        }
        toast("体检中…")
        bg {
            val rep = ModTools.doctor(ctx, src)
            safePost(handler) { showReport(rep) }
        }
    }

    /** 配置对比：看看迁移后会覆盖/缺失哪些配置 */
    private fun runDiff() {
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
            safePost(handler) { showReport(rep) }
        }
    }

    /** 把体检/对比结果放进可滚动的对话框里展示 */
    private fun showReport(rep: ModTools.Report) {
        val ctx = context ?: return
        val sv = android.widget.ScrollView(ctx)
        val tv = android.widget.TextView(ctx)
        tv.text = rep.text
        tv.textSize = 13f
        tv.setPadding(24, 16, 24, 16)
        tv.setTextIsSelectable(true)
        sv.addView(tv)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(rep.title)
            .setView(sv)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("复制结果") { _, _ ->
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText(rep.title, rep.text))
                toast("已复制")
            }
            .show()
    }

    private fun refreshPaths() {
        val p = Prefs.get(requireContext())
        tvSource.text = p.getString(K.SRC_URI, null) ?: getString(R.string.empty_hint)
        tvTarget.text = p.getString(K.DST_URI, null) ?: getString(R.string.empty_hint)
    }

    private fun selectLoader(name: String) {
        val arr = resources.getStringArray(R.array.loaders)
        val i = arr.indexOf(name)
        if (i >= 0) spLoader.setSelection(i)
    }

    private fun pickDir(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(i, code)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = requireContext()
        val ok = Perms.take(ctx, uri)
        if (!ok) log("授权持久化失败，可到设置 → 存储里重新选择目录")
        val p = Prefs.get(ctx)
        when (requestCode) {
            11 -> {
                p.edit().putString(K.SRC_URI, uri.toString()).apply()
                refreshPaths()
                val root = Fs.tree(ctx, uri.toString())
                if (root != null) {
                    log("「迁移前」的版本已选择，正在识别版本…")
                    bg { detectSource(root) }
                }
            }
            12 -> {
                p.edit().putString(K.DST_URI, uri.toString()).apply()
                refreshPaths()
                log("「迁移后」的版本已选择")
            }
            31 -> {
                val pkg = Prefs.get(requireContext()).getString(K.LAUNCHER, "") ?: ""
                if (pkg.isNotBlank()) {
                    log("已选启动器：$pkg")
                    scanLauncherData(pkg)
                }
            }
            13 -> {
                p.edit().putString(K.SCAN_ROOT, uri.toString()).apply()
                log("已选择目录，正在里面找各个版本…")
                scanLocal()
            }
        }
    }

    /** 选好启动器后，按它的包名去拉数据目录并自动扫描 */
    private fun pickLauncher() {
        val i = android.content.Intent(requireContext(), AppPickerActivity::class.java)
        startActivityForResult(i, 31)
    }

    private fun scanLauncherData(pkg: String) {
        val ctx = requireContext()
        val dirs = LauncherHelper.dataDirs(ctx, pkg)
        if (dirs.isEmpty()) {
            toast("没找到 ${pkg} 的数据目录，可手动指定目录")
            return
        }
        toast("按 ${pkg} 去找各个版本…")
        bg {
            val found = mutableListOf<InstanceInfo>()
            for (d in dirs) {
                log("检查数据目录：$d")
                found.addAll(InstanceScanner.scanFilesFrom(d) { m -> log(m) })
            }
            safePost(handler) {
                instances.clear()
                instances.addAll(found)
                if (found.isEmpty()) {
                    toast("该目录下没找到版本，可手动选择目录")
                } else {
                    toast("找到 ${found.size} 个版本")
                if (::tvScanHint.isInitialized) tvScanHint.text = "找到 ${found.size} 个版本，可点上面的框选择"
                    showInstancePicker()
                }
            }
        }
    }

    /** 申请「所有文件访问」：Android 11+ 只有这样才能读 Android/data 里的启动器实例 */
    private fun askAllFiles() {
        try {
            if (InstanceScanner.hasAllFilesAccess()) {
                toast("已有全部文件访问权限")
                return
            }
            val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            i.data = android.net.Uri.parse("package:" + requireContext().packageName)
            startActivity(i)
            toast("请打开「允许访问所有文件」，回来后重新扫描")
        } catch (t: Throwable) {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (t2: Throwable) {
                toast("无法打开权限设置页，请手动在系统设置里授权")
            }
        }
    }

    private fun scanLocal() {
        val ctx = requireContext()
        toast("扫描中…")
        if (::tvScanHint.isInitialized) tvScanHint.text = "正在扫描…"
        bg {
            // 主力路径：直接扫文件系统（能进 Android/data），SAF 只能作为补充
            var list = mutableListOf<InstanceInfo>()
            if (InstanceScanner.hasAllFilesAccess()) {
                list.addAll(InstanceScanner.scanFiles(log = { m -> log(m) }))
            } else {
                log("未获得「所有文件访问」权限，先试 SAF 目录扫描…")
            }
            if (list.isEmpty()) {
                val root = Prefs.get(ctx).getString(K.SCAN_ROOT, null)
                if (!root.isNullOrBlank()) {
                    // 以选中的目录为根（多数启动器把各版本收在这一个文件夹里）
                    list.addAll(InstanceScanner.scanFrom(ctx, root) { m -> log(m) })
                }
            }
            val finalList = list
            if (finalList.isEmpty() && !InstanceScanner.hasAllFilesAccess()) {
                safePost(handler) {
                    log("提示：启动器实例通常在 Android/data 下，需要「所有文件访问」权限才能读到")
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("扫不到实例")
                        .setMessage("启动器数据多在 Android/data 目录，Android 11 起必须授予「所有文件访问」权限才能读取。要现在去授权吗？")
                        .setPositiveButton("去授权") { _, _ -> askAllFiles() }
                        .setNegativeButton("改用手动选目录") { _, _ ->
                            val i2 = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
                            startActivityForResult(i2, 13)
                        }
                        .setNeutralButton("取消", null)
                        .show()
                }
            }
            safePost(handler) {
                instances.clear()
                instances.addAll(finalList)
                toast(if (finalList.isEmpty()) "没找到实例" else "找到 ${finalList.size} 个实例")
                if (finalList.isNotEmpty()) showInstancePicker()
            }
        }
    }

    private fun showInstancePicker() {
        val labels = instances.map { "${it.name} · MC ${it.mcVersion.ifBlank { "?" }} · ${it.modCount} 模组" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pick_instance)
            .setItems(labels) { _, w ->
                val inst = instances[w]
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("把 ${inst.name} 设为？")
                    .setPositiveButton("迁移前的版本") { _, _ -> setAs(inst, true) }
                    .setNegativeButton("迁移后的版本") { _, _ -> setAs(inst, false) }
                    .show()
            }
            .show()
    }

    private fun setAs(inst: InstanceInfo, asSource: Boolean) {
        val p = Prefs.get(requireContext())
        if (asSource) {
            p.edit().putString(K.SRC_URI, inst.uri).apply()
            log("迁移前的版本：${inst.name}（同设备）")
        } else {
            p.edit().putString(K.DST_URI, inst.uri).apply()
            log("迁移后的版本：${inst.name}（同设备）")
        }
        if (inst.mcVersion.isNotBlank() && !asSource) {
            etVersion.setText(inst.mcVersion)
            selectLoader(inst.loader)
        }
        refreshPaths()
        if (asSource) bg { val root = Fs.tree(requireContext(), inst.uri); if (root != null) detectSource(root) }
    }

    private fun log(s: String) {
        LogCenter.i("迁移", s)
        safePost(handler) {
            tvLog.append("$s\n")
            refreshLogSummary()
        }
    }

    /** 顶部日志摘要：条数 + 最新一条，点开看完整日志 */
    private fun refreshLogSummary() {
        val n = LogCenter.count()
        val last = LogCenter.recent(1).firstOrNull()
        tvLogSummary.text = if (n == 0) {
            "暂无日志"
        } else {
            "共 $n 条日志 · 最新：${last?.msg ?: ""}"
        }
    }


    private fun toast(s: String) {
        handler.post {
            if (!isAdded) return@post
            try {
                context?.let { android.widget.Toast.makeText(it, s, android.widget.Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                // 界面已销毁，不弹
                     Err.ignore(t, "界面已销毁，不弹")
                 }
        }
    }

    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("错误：${t.message}")
            }
        }
    }

    private fun detectSource(root: DocumentFile) {
        val ctx = requireContext()
        val mmc = Fs.find(root, "mmc-pack.json")
        if (mmc != null) {
            val o = Json.obj(Fs.readText(ctx, mmc))
            val comps = Json.a(o, "components")
            var mc = ""
            var loader = "auto"
            if (comps != null) {
                for (c in comps) {
                    val uid = Json.s(c, "uid")
                    val ver = Json.s(c, "version")
                    when {
                        uid == "net.minecraft" -> mc = ver
                        uid.startsWith("net.fabricmc") || uid.startsWith("org.quiltmc") ->
                            if (uid.contains("quilt")) loader = "quilt" else loader = "fabric"
                        uid.startsWith("net.minecraftforge") -> loader = "forge"
                        uid.startsWith("net.neoforged") -> loader = "neoforge"
                    }
                }
            }
            if (mc.isNotBlank()) {
                safePost(handler) {
                    etVersion.setText(mc)
                    selectLoader(loader)
                }
                log("识别到 MC $mc / $loader（MultiMC/Prism 实例）")
                return
            }
        }
        val man = Fs.find(root, "manifest.json")
        if (man != null) {
            val o = Json.obj(Fs.readText(ctx, man))
            val mcObj = o?.asJsonObject?.get("minecraft")
            val mc = Json.s(mcObj, "version")
            var loader = "auto"
            val ls = Json.a(mcObj, "modLoaders")
            if (ls != null && ls.size() > 0) {
                val idv = if (ls.size() > 0) Json.s(ls.get(0), "id") else ""
                loader = when {
                    idv.startsWith("forge") -> "forge"
                    idv.startsWith("neoforge") -> "neoforge"
                    idv.startsWith("fabric") -> "fabric"
                    idv.startsWith("quilt") -> "quilt"
                    else -> "auto"
                }
            }
            if (mc.isNotBlank()) {
                safePost(handler) {
                    etVersion.setText(mc)
                    selectLoader(loader)
                }
                log("识别到 MC $mc / $loader（CurseForge 清单）")
                return
            }
        }
        val vj = Fs.find(root, "version.json")
        if (vj != null) {
            val o = Json.obj(Fs.readText(ctx, vj))
            val id = Json.s(o, "id")
            if (id.isNotBlank()) {
                safePost(handler) { etVersion.setText(id) }
                log("识别到 MC $id（version.json）")
                return
            }
        }
        log("未能自动识别版本，请手动填写目标 MC 版本")
    }

    private fun scan() {
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        val srcUri = p.getString(K.SRC_URI, null)
        if (srcUri == null) {
            toast(getString(R.string.no_source))
            return
        }
        val mc = etVersion.text.toString().trim()
        val loader = spLoader.selectedItem?.toString() ?: "auto"
        if (mc.isBlank()) {
            toast("请填写目标 MC 版本")
            return
        }
        p.edit().putString(K.DEF_VERSION, mc).putString(K.DEF_LOADER, loader).apply()
        pb.visibility = View.VISIBLE
        mods.clear()
        adapter.notifyDataSetChanged()
        log("开始扫描…目标 MC $mc / $loader")

        bg {
            val root = Fs.tree(ctx, srcUri)
            if (root == null) {
                log("「迁移前」的目录不可访问")
                safePost(handler) { pb.visibility = View.GONE }
                return@bg
            }
            val dir = Fs.find(root, "mods")
            if (dir == null) {
                log("「迁移前」的目录里没有找到 mods 文件夹")
                safePost(handler) { pb.visibility = View.GONE }
                return@bg
            }
            val files = Fs.children(dir).filter { it.isFile && (it.name ?: "").endsWith(".jar", true) }
            log("发现 ${files.size} 个 jar")
            // 统计：区分"真的没收录"和"网络问题没查成"
            var netFail = 0
            val netFailNames = ArrayList<String>()
            var notFound = 0

            if (files.isEmpty()) {
                log("这个目录里没有 jar 文件")
            }

            // ── 第 1 步：本地算指纹（纯 IO，不需要联网）──────────────
            // 之前这一步和网络请求交错在一个循环里，一个慢全部慢。
            // 先把所有指纹算完，网络请求就能合并成整批发出去。
            val entries = ArrayList<ModEntry>(files.size)
            var idx = 0
            for (f in files) {
                idx++
                Progress.update("计算文件指纹（$idx/${files.size}）", idx, files.size)
                val e = ModEntry(fileName = f.name ?: "mod.jar", uri = f.uri.toString())
                e.sha1 = Fs.sha1(ctx, f)
                if (e.sha1.isBlank()) {
                    // 打不开的文件（权限/损坏）：单独标出来，
                    // 不能让空哈希混进批量请求里污染结果。
                    e.status = "读不出文件内容（权限或损坏）"
                    e.netError = true
                    notFound++
                    log("× ${e.fileName}：无法读取")
                }
                entries.add(e)
            }
            val hashes = entries.map { it.sha1.lowercase() }

            // ── 第 2 步：一次批量反查（替代 N 次逐个请求）──────────
            Progress.update("正在识别 ${entries.size} 个模组…", 1, 3)
            log("批量识别 ${entries.size} 个模组…")
            val found = ModrinthApi.lookupHashes(hashes)

            // ── 第 3 步：一次批量取目标版本 ────────────────────────
            // /version_files/update 本身就返回"该 MC 版本 + 加载器下的最新版"，
            // 不必先反查再逐个拉版本列表。
            val recognized = entries.filter { found[it.sha1.lowercase()]?.found == true }
            Progress.update("正在获取目标版本…", 2, 3)
            val latests = if (recognized.isEmpty()) {
                emptyMap()
            } else {
                ModrinthApi.latestForHashes(recognized.map { it.sha1 }, mc, loader)
            }

            // ── 第 4 步：一次批量取标题与 slug ─────────────────────
            Progress.update("正在取模组名称…", 3, 3)
            ModrinthApi.refreshAll(recognized.map { it.projectId })

            // ── 第 5 步：回填每个条目的状态（不再有网络请求）────────
            for (e in entries) {
                if (e.sha1.isBlank()) {
                    safePost(handler) {
                        mods.add(e)
                        adapter.notifyItemInserted(mods.size - 1)
                    }
                    continue
                }
                val key = e.sha1.lowercase()
                val info = found[key]
                when {
                    info == null -> {
                        // 批量接口里查不到的哈希根本不返回，这就是"确实没收录"
                        notFound++
                        e.status = "Modrinth 未收录（可去市场手动标记链接）"
                    }
                    info.netError -> {
                        netFail++
                        netFailNames.add(e.fileName)
                        e.status = "网络问题未能识别（${info.msg}）"
                        e.netError = true
                        log("× ${e.fileName}：${info.msg}，已跳过")
                    }
                    else -> {
                        e.projectId = info.projectId
                        e.currentVersion = info.version
                        e.slug = ModrinthApi.slug(info.projectId)
                        e.name = ModrinthApi.title(info.projectId).ifBlank { e.fileName }
                        // 记录页面地址，列表里点整行就能打开模组详情页
                        e.pageUrl = if (e.slug.isNotBlank()) {
                            "https://modrinth.com/mod/${e.slug}"
                        } else {
                            "https://modrinth.com/mod/${e.projectId}"
                        }
                        val v0 = latests[key]
                        if (v0 == null) {
                            // 目标加载器上没有构建时，查已知平替（如 Sodium → Embeddium）
                            val alt = Loaders.equivalent(e.name.ifBlank { e.fileName }, loader)
                            e.status = when {
                                alt == null -> "「迁移后」的版本无可用文件"
                                alt.isBlank() -> "该加载器下不需要此组件"
                                else -> "建议改用：$alt"
                            }
                            if (!alt.isNullOrBlank()) {
                                // 把替代名也写进去，方便用户照着去搜
                                e.targetFileName = alt
                                log("${e.name} 在 ${Loaders.label(loader)} 上无构建，平替：${alt}")
                            }
                        } else {
                            e.targetVersion = v0.version
                            e.targetUrl = v0.url
                            e.targetFileName = v0.fileName
                            e.status = "可迁移"
                        }
                    }
                }
                safePost(handler) {
                    mods.add(e)
                    adapter.notifyItemInserted(mods.size - 1)
                }
            }
            log("扫描完成（网络请求：识别 1 次 + 取版本 1 次 + 取名 1 次）")
            val nf = netFail
            val nfNames = ArrayList(netFailNames)
            val nfound = notFound
            safePost(handler) {
                pb.visibility = View.GONE
                val ok = mods.count { it.targetUrl.isNotBlank() }
                toast("可迁移 $ok / ${mods.size}")

                // 连不上的必须明确告诉用户，而不是混在"未识别"里糊过去
                if (nf > 0) {
                    log("⚠ $nf 个因网络问题没能识别，其余已正常处理")
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("$nf 个模组没识别成功")
                        .setMessage(
                            buildString {
                                append("这些是因为**网络问题**没查到（不是没收录）：\n\n")
                                nfNames.take(10).forEach { append("  · $it\n") }
                                if (nf > 10) append("  …等 $nf 个\n")
                                append("\n另外有 $nfound 个是 Modrinth 确实没收录，")
                                append("这类重试也没用，需手动标记链接。\n\n")
                                append("建议：换个网络或稍后再「重试失败项」。")
                            }
                        )
                        .setPositiveButton("重试失败项") { _, _ -> retryFailed() }
                        .setNegativeButton(R.string.ok, null)
                        .show()
                }
            }
        }
    }

    /** 只重下"下载失败"的项，成功的不再动 */
    private fun retryDownloads(count: Int) {
        val ctx = context ?: return
        val todo = mods.filter { it.status.startsWith("下载失败") }
        if (todo.isEmpty()) {
            toast("没有需要重试的项")
            return
        }
        val dstUri = Prefs.get(ctx).getString(K.DST_URI, null)
        if (dstUri == null) {
            toast("「迁移后」的目录不可用")
            return
        }
        toast("重试 ${todo.size} 个…")
        pb.visibility = View.VISIBLE
        bg {
            val dst = Fs.tree(ctx, dstUri)
            val dir = if (dst != null) Fs.ensureDir(dst, "mods") else null
            if (dir == null) {
                safePost(handler) {
                    pb.visibility = View.GONE
                    toast("目标目录不可用")
                }
                return@bg
            }
            var ok = 0
            val still = ArrayList<String>()
            for (m in todo) {
                val name = m.targetFileName.ifBlank { Downloader.guessName(m.targetUrl) }
                var err = ""
                val f = try {
                    Downloader.download(ctx, m.targetUrl, dir, name)
                } catch (t: Throwable) {
                    err = Http.describeError(t)
                    null
                }
                if (f != null) {
                    ok++
                    safePost(handler) {
                        m.status = "已安装"
                        adapter.notifyDataSetChanged()
                    }
                } else {
                    still.add("${m.name.ifBlank { m.fileName }}：$err")
                    safePost(handler) {
                        m.status = "下载失败（$err）"
                        adapter.notifyDataSetChanged()
                    }
                }
            }
            safePost(handler) {
                pb.visibility = View.GONE
                toast("重试完成：成功 $ok / ${todo.size}")
                log("下载重试：成功 $ok / ${todo.size}")
                if (still.isNotEmpty()) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("仍有 ${still.size} 个失败")
                        .setMessage(still.take(10).joinToString("\n") { "  · $it" })
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
        }
    }

    /** 只重试上次因网络问题失败的项，不用全部重扫 */
    private fun retryFailed() {
        val ctx = context ?: return
        val todo = mods.filter { it.netError }
        if (todo.isEmpty()) {
            toast("没有需要重试的项")
            return
        }
        toast("重试 ${todo.size} 个…")
        // 目标版本/加载器沿用扫描时保存的，不重新读控件（避免控件还没初始化）
        val pp = Prefs.get(ctx)
        val mc = pp.getString(K.DEF_VERSION, "") ?: ""
        val loader = pp.getString(K.DEF_LOADER, "auto") ?: "auto"
        bg {
            var fixed = 0
            // 重试同样走批量接口：之前是逐个请求，
            // 失败项可能有几十个，逐个重试又会慢到像死机。
            val hashes = todo.map { it.sha1.lowercase() }
            val found = ModrinthApi.lookupHashes(hashes)
            val recognized = todo.filter { found[it.sha1.lowercase()]?.found == true }
            val latests = if (recognized.isEmpty()) {
                emptyMap()
            } else {
                ModrinthApi.latestForHashes(recognized.map { it.sha1 }, mc, loader)
            }
            ModrinthApi.refreshAll(recognized.map { it.projectId })

            for (e in todo) {
                val key = e.sha1.lowercase()
                val info = found[key]
                when {
                    info == null -> {
                        safePost(handler) {
                            e.status = "Modrinth 未收录"
                            adapter.notifyDataSetChanged()
                        }
                    }
                    info.netError -> {
                        safePost(handler) {
                            e.status = "仍连不上（${info.msg}）"
                            adapter.notifyDataSetChanged()
                        }
                    }
                    else -> {
                        val v0 = latests[key]
                        safePost(handler) {
                            if (v0 != null) {
                                e.projectId = info.projectId
                                e.name = ModrinthApi.title(info.projectId).ifBlank { e.fileName }
                                e.targetVersion = v0.version
                                e.targetUrl = v0.url
                                e.targetFileName = v0.fileName
                                e.status = "可迁移"
                                e.netError = false
                                fixed++
                            } else {
                                e.status = "重试后仍无可用文件"
                                e.netError = false
                            }
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
            safePost(handler) {
                toast("重试完成，成功 $fixed / ${todo.size}")
                log("重试完成：成功 $fixed / ${todo.size}")
            }
        }
    }

    /**
     * 一键下载全部：把迁移方案里所有能适配的模组一次性下完。
     *
     * 走 DownloadService（后台服务）：
     *   - 切到后台不会被杀
     *   - 中途断网/关掉 App 后，再点一次是**续传**，不用从头下
     *   - 通知栏能取消
     */
    private fun downloadAll() {
        val ctx = context ?: return
        val list = mods.filter { it.targetUrl.isNotBlank() }
        if (list.isEmpty()) {
            toast("没有可下载的模组（可能还没扫描，或都没有适配版本）")
            return
        }
        val dir = Targets.modsDir(ctx)
        if (dir == null) {
            toast("「迁移后」的目录不可用")
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("一键下载")
            .setMessage(getString(R.string.download_all_confirm, list.size))
            .setPositiveButton("开始") { _, _ ->
                for (m in list) {
                    DownloadService.start(
                        ctx, m.targetUrl,
                        m.targetFileName.ifBlank { Downloader.guessName(m.targetUrl) },
                        "mods"
                    )
                }
                toast(getString(R.string.download_all_bg, list.size))
                log("已在后台开始下载 ${list.size} 个模组")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadOne(m: ModEntry) {
        val ctx = requireContext()
        bg {
            val dir = Targets.modsDir(ctx)
            if (dir == null) {
                toast("「迁移后」的目录不可用")
                return@bg
            }
            safePost(handler) {
                m.status = "下载中"
                adapter.notifyDataSetChanged()
            }
            val name = m.targetFileName.ifBlank { Downloader.guessName(m.targetUrl) }
            val f = Downloader.download(ctx, m.targetUrl, dir, name)
            safePost(handler) {
                m.status = if (f == null) "下载失败" else "已安装"
                adapter.notifyDataSetChanged()
            }
            Notifier.show(ctx, "模组迁移", m.name)
        }
    }

    /**
     * 执行迁移。
     *
     * 两个关键改动：
     * 1. 模组下载改成并发（默认 3 个同时下），之前是串行一个一个下，
     *    几十个模组要等很久，这是「迁移非常慢」的主因。
     * 2. 全程汇报进度：阶段名 + 第几个/共几个 + 已用时间 + 预计剩余，
     *    之前只有个转圈，用户不知道进行到哪、还要多久。
     */
    private fun runMigration() {
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        val srcUri = p.getString(K.SRC_URI, null)
        val dstUri = p.getString(K.DST_URI, null)
        if (srcUri == null) {
            toast(getString(R.string.no_source))
            return
        }
        if (dstUri == null) {
            toast(getString(R.string.no_target))
            return
        }
        // 同实例保护：源和目标一样的话，等于把文件复制给自己，
        // 既没意义又可能把原文件写坏（同名覆盖时读写的可能是同一个文件）
        if (srcUri == dstUri) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("这是同一个实例")
                .setMessage(
                    "迁移前和迁移后选的是同一个目录：\n${
                        Fs.tree(ctx, srcUri)?.name ?: srcUri
                    }\n\n" +
                        "把配置迁到自己身上没有任何效果，" +
                        "而且同名文件覆盖时可能把原文件写坏。\n\n" +
                        "请到下面重新选一个「迁移后的版本」。"
                )
                .setPositiveButton("知道了", null)
                .setNegativeButton("仍然继续") { _, _ -> doMigrate(srcUri, dstUri) }
                .show()
            return
        }
        if (mods.isEmpty()) {
            toast("请先扫描生成迁移方案")
            return
        }
        doMigrate(srcUri, dstUri)
    }

    /** 真正的迁移执行（同实例检查通过后才会走到这里） */
    private fun doMigrate(srcUri: String, dstUri: String) {
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        val wantConfig = cbConfig.isChecked
        val wantScripts = cbScripts.isChecked
        val wantOptions = cbOptions.isChecked
        val wantPacks = cbPacks.isChecked
        val wantSaves = cbSaves.isChecked
        pb.visibility = View.VISIBLE
        log("开始迁移…")

        bg {
            val src = Fs.tree(ctx, srcUri)
            val dst = Fs.tree(ctx, dstUri)
            if (src == null || dst == null) {
                log("目录不可访问，请重新选择并授权")
                Progress.done("目录不可访问")
                safePost(handler) { pb.visibility = View.GONE }
                return@bg
            }

            // ---- 阶段一：复制配置文件 ----
            val steps = ArrayList<Pair<String, String>>()
            if (wantConfig) steps.add(Pair("config", "配置文件"))
            if (wantScripts) steps.add(Pair("scripts", "脚本"))
            if (wantOptions) steps.add(Pair("options.txt", "游戏设置"))
            if (wantPacks) {
                steps.add(Pair("resourcepacks", "资源包"))
                steps.add(Pair("shaderpacks", "光影包"))
            }
            if (wantSaves) steps.add(Pair("saves", "存档"))

            for ((idx, step) in steps.withIndex()) {
                Progress.update("复制${step.second}", idx, steps.size)
                log("复制${step.second}…")
                Fs.find(src, step.first)?.let {
                    Fs.copyInto(ctx, it, dst) { m -> log(m) }
                }
            }
            if (steps.isNotEmpty()) {
                Progress.done("配置复制完成（${steps.size} 项）")
                log("配置文件复制阶段结束")
            }

            // ---- 阶段二：并发下载模组 ----
            val todo = mods.filter { it.targetUrl.isNotBlank() }
            if (todo.isEmpty()) {
                Progress.done("没有需要下载的模组")
                safePost(handler) { pb.visibility = View.GONE }
                log("没有需要下载的模组")
                return@bg
            }

            val dir = Fs.ensureDir(dst, "mods")
            if (dir == null) {
                Progress.done("目标 mods 目录创建失败")
                safePost(handler) { pb.visibility = View.GONE }
                log("目标 mods 目录创建失败")
                return@bg
            }

            // 并发数：设置里可调，默认 3。性能差的设备可以调成 1（等于串行）
            val parallel = Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3).coerceIn(1, 8)
            val doneCnt = java.util.concurrent.atomic.AtomicInteger(0)
            val okCnt = java.util.concurrent.atomic.AtomicInteger(0)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(parallel)

            Progress.start("下载模组", todo.size)
            log("开始下载 ${todo.size} 个模组（并发 $parallel）")

            // 记录每一项失败的原因，最后统一告诉用户哪些没下成、为什么
            val failed = java.util.Collections.synchronizedList(ArrayList<String>())
            for (m in todo) {
                pool.submit {
                    val name = m.targetFileName.ifBlank { Downloader.guessName(m.targetUrl) }
                    var err = ""
                    val f = try {
                        Downloader.download(ctx, m.targetUrl, dir, name)
                    } catch (t: Throwable) {
                        err = Http.describeError(t)
                        null
                    }
                    val n = doneCnt.incrementAndGet()
                    if (f != null) okCnt.incrementAndGet()
                    else failed.add("${m.name.ifBlank { m.fileName }}：$err")
                    log("${if (f == null) "失败（$err）" else "已安装"}：${m.name} ${m.targetVersion}")
                    Progress.update("下载模组", n, todo.size)
                    safePost(handler) {
                        m.status = if (f == null) "下载失败（$err）" else "已安装"
                        adapter.notifyDataSetChanged()
                    }
                }
            }
            pool.shutdown()
            try {
                pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES)
            } catch (t: Throwable) { Err.ignore(t, "pool.awaitTermination(30, java.util.concurrent.Tim") }

            val ok = okCnt.get()
            val total = todo.size
            val bad = ArrayList(failed)
            Progress.done("迁移完成：模组 $ok/$total")
            log("迁移完成：模组 $ok/$total")
            safePost(handler) {
                pb.visibility = View.GONE
                // 有失败的必须说清楚，不能只报个总数让用户自己猜
                if (bad.isNotEmpty()) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("有 ${bad.size} 个没下成")
                        .setMessage(
                            buildString {
                                append("成功 $ok / $total。失败的：\n\n")
                                bad.take(10).forEach { append("  · $it\n") }
                                if (bad.size > 10) append("  …等 ${bad.size} 个\n")
                                append("\n这些通常是网络问题或源站临时不可用，")
                                append("可以点「重试」只重下失败的，其余不受影响。")
                            }
                        )
                        .setPositiveButton("重试失败的") { _, _ -> retryDownloads(bad.size) }
                        .setNegativeButton(R.string.ok, null)
                        .show()
                }
            }
            Notifier.show(ctx, "迁移完成", "模组 $ok/$total")
            if (p.getBoolean(K.AUTO_LAUNCH, false)) {
                val pkg = p.getString(K.LAUNCHER, "") ?: ""
                if (pkg.isNotBlank()) {
                    val launched = LauncherHelper.launch(ctx, pkg)
                    log(if (launched) "已启动 $pkg" else "启动失败 $pkg")
                }
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
