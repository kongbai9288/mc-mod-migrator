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
import java.util.concurrent.Executors

class MigrationFragment : Fragment() {

    private lateinit var tvSource: TextView
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

    private val progressHook: (Progress.State) -> Unit = { st ->
        pb.visibility = if (st.running) View.VISIBLE else View.GONE
        pb.isIndeterminate = st.total <= 0
        if (st.total > 0) pb.progress = st.percent
        tvProgress.text = st.text
        tvProgress.visibility = if (st.running && st.text.isNotBlank()) View.VISIBLE else View.GONE
    }

    private val logHook: (List<LogCenter.LogLine>) -> Unit = { refreshLogSummary() }

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
        v.findViewById<Button>(R.id.btnRun).setOnClickListener { runMigration() }

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
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (t: Throwable) {
            log("授权持久化失败：${t.message}")
        }
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
        safePost(handler) { Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show() }
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
                val idv = Json.s(ls[0], "id")
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
            var idx = 0
            for (f in files) {
                idx++
                Progress.update("正在识别模组 ${f.name}", idx, files.size)
                val e = ModEntry(fileName = f.name ?: "mod.jar", uri = f.uri.toString())
                e.sha1 = Fs.sha1(ctx, f)
                val info = ModrinthApi.lookupHash(e.sha1)
                if (info == null) {
                    e.status = "Modrinth 未识别（可去市场手动标记链接）"
                } else {
                    e.projectId = info.first
                    e.currentVersion = info.second
                    e.slug = info.third
                    e.name = ModrinthApi.title(info.first).ifBlank { e.fileName }
                    // 记录页面地址，列表里点整行就能打开模组详情页
                    e.pageUrl = if (e.slug.isNotBlank()) {
                        "https://modrinth.com/mod/${e.slug}"
                    } else {
                        "https://modrinth.com/mod/${e.projectId}"
                    }
                    val vers = ModrinthApi.versions(info.first, mc, loader)
                    val v0 = vers.firstOrNull()
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
                safePost(handler) {
                    mods.add(e)
                    adapter.notifyItemInserted(mods.size - 1)
                }
            }
            log("扫描完成")
            safePost(handler) {
                pb.visibility = View.GONE
                val ok = mods.count { it.targetUrl.isNotBlank() }
                toast("可迁移 $ok / ${mods.size}")
            }
        }
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
        if (mods.isEmpty()) {
            toast("请先扫描生成迁移方案")
            return
        }
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
                safePost(handler) { pb.visibility = View.GONE }
                return@bg
            }
            if (wantConfig) Fs.find(src, "config")?.let { Fs.copyInto(ctx, it, dst) { s -> log(s) } }
            if (wantScripts) Fs.find(src, "scripts")?.let { Fs.copyInto(ctx, it, dst) { s -> log(s) } }
            if (wantOptions) Fs.find(src, "options.txt")?.let { Fs.copyInto(ctx, it, dst) { s -> log(s) } }
            if (wantPacks) {
                Fs.find(src, "resourcepacks")?.let { Fs.copyInto(ctx, it, dst) { s -> log(s) } }
                Fs.find(src, "shaderpacks")?.let { Fs.copyInto(ctx, it, dst) { s -> log(s) } }
            }
            if (wantSaves) Fs.find(src, "saves")?.let { Fs.copyInto(ctx, it, dst) { s -> log(s) } }
            log("配置文件复制阶段结束")

            val dir = Fs.ensureDir(dst, "mods")
            var ok = 0
            var total = 0
            for (m in mods) {
                if (m.targetUrl.isBlank()) continue
                total++
                val name = m.targetFileName.ifBlank { Downloader.guessName(m.targetUrl) }
                val f = Downloader.download(ctx, m.targetUrl, dir, name)
                if (f != null) ok++
                log("${if (f == null) "失败" else "已安装"}：${m.name} ${m.targetVersion}")
                safePost(handler) {
                    m.status = if (f == null) "下载失败" else "已安装"
                    adapter.notifyDataSetChanged()
                }
            }
            log("迁移完成：模组 $ok/$total")
            safePost(handler) { pb.visibility = View.GONE }
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
}
