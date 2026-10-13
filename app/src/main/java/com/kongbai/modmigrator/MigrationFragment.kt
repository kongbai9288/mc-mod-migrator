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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.Executors


/** 日志弹窗里最多显示多少条（完整内容走「复制」） */
private const val MAX_SHOW_LINES = 40

/** 错误弹窗最多合并多少条（再多只更新计数，不继续堆文本） */
private const val MAX_ERR_LINES = 20

class MigrationFragment : Fragment() {

    private lateinit var tvSource: TextView
    private lateinit var tvDirState: android.widget.TextView
    private lateinit var cardSource: android.view.View
    private lateinit var cardServer: android.view.View
    private lateinit var tvServerState: android.widget.TextView
    private lateinit var tvScanHint: android.widget.TextView
    private lateinit var tvTarget: TextView
    private lateinit var etVersion: EditText
    private lateinit var spLoader: Spinner
    /** 迁移要复制哪些东西。由 [McParts] 动态生成，不再写死五个。id → 勾选框 */
    private val partBoxes = LinkedHashMap<String, android.widget.CheckBox>()
    private lateinit var pb: ProgressBar
    private lateinit var rvMods: RecyclerView
    private lateinit var tvLog: TextView
    private lateinit var tvLogSummary: TextView
    private val instances = mutableListOf<InstanceInfo>()

    private val mods = mutableListOf<ModEntry>()
    private lateinit var adapter: ModAdapter
    private val sb = StringBuilder()
    private val exec = Bg.io
    private val handler = Bg.ui

    /**
     * 进度回调。
     * 注意两件事：
     * 1. 这个 lambda 在 onResume 注册后可能立刻被回调一次，
     *    所以里面用到的 lateinit 字段必须先判断有没有初始化，否则会崩。
     * 2. 扫描是在 `bg {}`（后台线程）里跑的，`Progress.update()` 每处理一个
     *    文件就调一次，而 `fire()` 在调用方线程上直接执行 hook ——
     *    也就是说这个 lambda 是在后台线程上改 View。
     *    Android 不允许非 UI 线程操作 View，会抛
     *    CalledFromWrongThreadException；而 Progress.fire() 里是
     *    `catch { Err.ignore }`，异常被静默吞掉、没有任何提示。

     *    看上去像"卡住了"。文件越多越容易撞上。
     *    现在改成 post 到主线程执行。
     * 3. 另外扫描上千个文件时会连续触发上千次 UI 更新，
     *    这里加一个节流：同一个任务内最多每 120ms 刷新一次界面，
     *    最后一次（done/percent 到 100）必定刷新，不会丢最终状态。
     */
    private var lastProgressFlushAt = 0L

    private val progressHook: (Progress.State) -> Unit = { st ->
        if (::pb.isInitialized && ::tvProgress.isInitialized) {
            val now = android.os.SystemClock.uptimeMillis()
            val isFinal = !st.running || st.percent >= 100
            if (isFinal || now - lastProgressFlushAt >= 120) {
                lastProgressFlushAt = now
                applyProgress(st)
            }
        }
    }

    /** 真正更新进度 UI，必须在主线程调用 */
    private fun applyProgress(st: Progress.State) {
        val doIt = {
            if (::pb.isInitialized && ::tvProgress.isInitialized && isAdded) {
                pb.visibility = if (st.running) View.VISIBLE else View.GONE
                pb.isIndeterminate = st.total <= 0
                if (st.total > 0) pb.progress = st.percent
                // 主行：正在做什么（第几个/共几个）
                // 副行：百分比 + 已用 + 预计剩余 + 速度
                val main = st.text
                val det = st.detail
                tvProgress.text = if (det.isBlank()) main else "$main\n$det"
                tvProgress.visibility =
                    if (st.running && main.isNotBlank()) View.VISIBLE else View.GONE
                rowPause?.visibility = if (st.running) View.VISIBLE else View.GONE
                btnPause?.text = if (st.paused) "继续" else "暂停"
                btnCancelTask?.visibility = if (st.running) View.VISIBLE else View.GONE
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) doIt() else handler.post { doIt() }
    }

    private val logHook: (List<LogCenter.LogLine>) -> Unit = {
        if (::tvLogSummary.isInitialized) refreshLogSummary()
    }

    /**
     * 严重错误时弹窗提示。
     * 这里必须切到主线程，真实崩溃日志为证：
     * ```
     * java.lang.IllegalStateException: Method addObserver must be called on the main thread
     *   at androidx.lifecycle.LifecycleRegistry.addObserver(LifecycleRegistry.kt:181)
     *   at androidx.savedstate.SavedStateRegistryController.performAttach(...)
     *   at androidx.activity.ComponentDialog.onCreate(ComponentDialog.kt:74)
     *   at android.app.Dialog.show(Dialog.java:922)
     *   at com.kongbai.modmigrator.MigrationFragment$errorHook$1.invoke(...)
     *   at com.kongbai.modmigrator.LogCenter.push(LogCenter.kt:113)
     * ```
     * 日志可能从任意后台线程写入（网络回调、下载线程、WorkManager），
     * 而 errorHook 是直接被 push() 同步调用的 —— 于是 `Dialog.show()`
     * 跑在后台线程上。AlertDialog 内部要经 SavedStateRegistry → Lifecycle
     * 注册观察者，只允许主线程，直接抛异常。
     * 更糟的是这次异常又被 push 的 catch 捕获、再次走一遍日志链路，
     * 于是每弹一次失败就再触发一次 → 递归，日志滚到 8 万字符。
     */
    /**
 * 用户点了「本次不再提示」后，错误只进日志、不再弹窗。只影响本次会话。
 */
    private var errDialogMuted = false

    private val errorHook: (LogCenter.LogLine) -> Unit = { line ->
        val ctx = context
        if (!errDialogMuted && ctx != null && isAdded) {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showErrorDialog(ctx, line)
                }
            } else {
                showErrorDialog(ctx, line)
            }
        }
    }

    /**
 * 错误弹窗的节流时间戳。3 秒内只弹一次，后续错误追加进已弹的那个对话框。
 */
    private var lastErrDialogAt = 0L
    private var pendingErrText: StringBuilder? = null
    private var pendingErrCount = 0
    private var pendingErrDialog: androidx.appcompat.app.AlertDialog? = null

    private fun showErrorDialog(ctx: android.content.Context, line: LogCenter.LogLine) {
        try {
            // 二次校验：切到主线程的过程中 Fragment 可能已经 detach，
            // 或者 Activity 正在销毁。这两种情况下弹窗都会崩
            // （"Can not perform this action after onSaveInstanceState"）。
            if (!isAdded || context == null) return

            val now = System.currentTimeMillis()
            // ⚠️ 上一版这里写的是 `now - lastErrDialogAt < 3000L`，
            // 也就是**只认 3 秒内**的旧对话框。但批量操作会持续几十秒，
            // 用户还没来得及点掉第一个，3 秒一过就又新建一个叠上去 ——
            // 结果仍然是一叠关不完的弹窗，底下的「复制」根本点不到。
            // 判断依据应该是"对话框**还开着**吗"，而不是"距今多久"。
            val buf = pendingErrText
            val dlg = pendingErrDialog
            if (buf != null && dlg != null && runCatching { dlg.isShowing }.getOrDefault(false)) {
                pendingErrCount++
                // 上限 20 条：没有上限的话文本会一直涨，
                // 而对话框的 TextView 要对整段做测量排版，容易把内存吃紧的设备拖垮
                if (buf.length < 3000 && pendingErrCount <= MAX_ERR_LINES) {
                    buf.append("\n\n").append(line.msg)
                }
                // ⚠️ 光改 StringBuilder 是不会刷新的：
                // 对话框 show 时已经把文本设进 TextView 了，
                // 必须直接改 TextView 才能看到新内容。
                runCatching {
                    val shown = if (pendingErrCount > MAX_ERR_LINES) {
                        "（共 $pendingErrCount 条错误，仅显示前 $MAX_ERR_LINES 条，" +
                            "完整内容请到运行日志里看）\n\n$buf"
                    } else {
                        "（共 $pendingErrCount 条错误）\n\n$buf"
                    }
                    dlg.findViewById<android.widget.TextView>(android.R.id.message)?.text = shown
                }
                // 刷新节流时间戳，让"对话框刚关又立刻来一条错误"时不会误判
                lastErrDialogAt = now
                return
            }

            val sb = StringBuilder(line.msg)
            pendingErrText = sb
            pendingErrCount = 1
            lastErrDialogAt = now

            val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("操作失败")
                .setMessage(sb)
                .setPositiveButton(R.string.ok) { _, _ -> pendingErrText = null }
                .setNegativeButton("查看全部日志") { _, _ ->
                    pendingErrText = null
                    showAllLogs()
                }
                .setNeutralButton("本次不再提示") { _, _ ->
                    errDialogMuted = true
                    pendingErrText = null
                    toast("后续错误只记录到日志，不再弹窗")
                }
                .show()
            pendingErrDialog = d
            d.setOnDismissListener {
                pendingErrText = null
                pendingErrDialog = null
            }
        } catch (t: Throwable) {
            // 弹窗失败绝不能再走 Err.ignore：那会写日志、再次触发 errorHook，
            // 形成无限递归。用原生 Log，它不碰我们的日志系统。
            android.util.Log.w("errorHook", "错误弹窗失败", t)
        }
    }

    private lateinit var tvProgress: TextView
    private var rowPause: LinearLayout? = null
    private var btnPause: Button? = null
    private var btnCancelTask: Button? = null

    /** 展示运行日志 */
    private fun showAllLogs() {
        val ctx = context ?: return
        val all = LogCenter.all()
        // 完整内容只用于「复制」，不塞进对话框
        val full = all.joinToString("\n") { it.toString() }.ifBlank { "暂无日志" }

        // ⚠️ 对话框里显示的内容必须受控。
        // 上一版我直接 setMessage(全部日志)，这是个错误：
        // 装它的 Material message TextView 要对整段文本做测量和换行排版，
        // 300 条日志（含堆栈）能到 MB 级 —— 而这台设备已经因为 OOM 崩过
        // （堆只剩 1.9MB，系统线程连 64 字节都分配不到）。
        // 显示层截断、复制层给全文，两者分开。
        val shown = if (all.size > MAX_SHOW_LINES) {
            "（仅显示最近 $MAX_SHOW_LINES 条，共 ${all.size} 条；点「复制」可拿到完整日志）\n\n" +
                all.take(MAX_SHOW_LINES).joinToString("\n") { it.toString() }
        } else {
            all.joinToString("\n") { it.toString() }
        }
        val txt = shown.ifBlank { "暂无日志" }

        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("运行日志（共 ${LogCenter.count()} 条）")
            .setMessage(txt)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("复制") { _, _ -> copyLogs(full) }
            .setNegativeButton("清空") { _, _ -> LogCenter.clear(); refreshLogSummary() }
            .show()
        // 让正文可以长按选中，方便只复制某一段堆栈
        runCatching {
            dlg.findViewById<android.widget.TextView>(android.R.id.message)
                ?.setTextIsSelectable(true)
        }
    }


    private fun copyLogs(txt: String) {
        val ctx = context ?: return
        runCatching {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
            if (cm == null) {
                toast("复制失败：拿不到剪贴板")
                return
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("运行日志", txt))
            toast("已复制全部日志（${txt.length} 字符）")
        }.onFailure { toast("复制失败：${Err.humanMessage(it)}") }
    }

    override fun onResume() {
        super.onResume()
        Progress.addHook(progressHook)
        LogCenter.addListener(logHook)
        LogCenter.addErrorHook(errorHook)
        refreshLogSummary()
        if (::cardSource.isInitialized) applyMode()
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
        tvDirState = v.findViewById(R.id.tvDirState)
        tvScanHint = v.findViewById(R.id.tvScanHint)
        cardSource = v.findViewById(R.id.cardSource)
        cardServer = v.findViewById(R.id.cardServer)
        tvServerState = v.findViewById(R.id.tvServerState)
        v.findViewById<Button>(R.id.btnOpenServer).setOnClickListener {
            (activity as? MainActivity)?.showTab("server")
        }
        tvTarget = v.findViewById(R.id.tvTarget)
        etVersion = v.findViewById(R.id.etTargetVersion)
        spLoader = v.findViewById(R.id.spLoader)
        // 换成带加载器图标的下拉（原来 android:entries 是纯文字）
        LoaderSpinner.attachByPref(spLoader)
        buildPartBoxes(v)
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
        // 原来这里重复写了两遍（复制粘贴残留），第二遍覆盖第一遍。
        // 保留一行。
        v.findViewById<Button>(R.id.btnDownloadAll)?.setOnClickListener { downloadAll() }
        v.findViewById<Button>(R.id.btnRun).setOnClickListener { runMigration() }
        rowPause = v.findViewById(R.id.rowPause)
        btnPause = v.findViewById(R.id.btnPause)
        btnCancelTask = v.findViewById(R.id.btnCancelTask)
        v.findViewById<Button>(R.id.btnMigTools)?.setOnClickListener { openMigrationTools() }
        btnPause?.setOnClickListener { togglePause() }
        btnCancelTask?.setOnClickListener { cancelTask() }
        v.findViewById<Button>(R.id.btnDoctor).setOnClickListener { runDoctor() }
        v.findViewById<Button>(R.id.btnDiff).setOnClickListener { runDiff() }

        val p = Prefs.get(requireContext())
        etVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")
        selectLoader(p.getString(K.DEF_LOADER, "") ?: "auto")
        refreshPaths()
        applyMode()
        keepVersionAndLoader()
        return v
    }

    /**
 * 手动改的版本 / 加载器要立刻存下来，不等扫描。
 */
    private fun keepVersionAndLoader() {
        val ctx = context ?: return
        val p = Prefs.get(ctx)
        etVersion.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                p.edit().putString(K.DEF_VERSION, s?.toString()?.trim() ?: "").apply()
            }
        })
        spLoader.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    p.edit().putString(K.DEF_LOADER, LoaderSpinner.value(spLoader)).apply()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
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
        // 显示人能看懂的路径，而不是 content://...%3A... 那串 URI
        tvSource.text = DirGuide.human(p.getString(K.SRC_URI, "") ?: "")
            .ifBlank { getString(R.string.empty_hint) }
        tvTarget.text = DirGuide.human(p.getString(K.DST_URI, "") ?: "")
            .ifBlank { getString(R.string.empty_hint) }
        if (::tvDirState.isInitialized) {
            tvDirState.text = DirGuide.state(requireContext(), p.getString(K.SRC_URI, "") ?: "")
        }
    }

    /**
     * 切换客户端 / 服务器端模式。
     *
     * 两种模式要填的东西不一样：客户端从本地目录取，服务器端从面板或 FTP 取。
     * 所以只显示当前模式用得上的那张卡片，另一张整块藏掉 ——
     * 留着不相关的输入框，用户会以为也要填。
     */
    private fun applyMode() {
        val server = Prefs.get(requireContext()).getInt(K.APP_MODE, 0) == 1
        cardSource.visibility = if (server) android.view.View.GONE else android.view.View.VISIBLE
        cardServer.visibility = if (server) android.view.View.VISIBLE else android.view.View.GONE
        if (server) refreshServerState()
    }

    private fun refreshServerState() {
        val p = Prefs.get(requireContext())
        val host = p.getString(K.REMOTE_HOST, "") ?: ""
        tvServerState.text = if (host.isBlank()) {
            getString(R.string.server_not_connected)
        } else {
            val kind = when (p.getInt(K.REMOTE_KIND, 0)) {
                1 -> "FTP"; 2 -> "FTPS"; 3 -> "SFTP"; else -> "面板"
            }
            "$kind · $host"
        }
    }

    private fun selectLoader(name: String) {
        LoaderSpinner.select(spLoader, name)
    }

    private fun pickDir(code: Int) {
        //

        // 用户面对整台手机的目录树，完全不知道该选到哪一层 ——
        // 选错的表现是"扫出 0 个实例""识别不出版本"，
        // 界面上却只说"请选择目录"，没有任何指引。
        // 这里先说明该选哪一层，并给出常见启动器的实际路径。
        //
        val purpose = when (code) {
            11 -> DirGuide.Purpose.SOURCE
            12 -> DirGuide.Purpose.TARGET
            13 -> DirGuide.Purpose.SCAN_ROOT
            else -> DirGuide.Purpose.FREE
        }
        DirGuide.pick(requireActivity(), code, purpose)
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
                // 选错层级不会报错，只会"识别不出版本"，
                // 这里当场校验并提示，不然后面用户完全联想不到是选错了。
                DirGuide.check(ctx, uri, DirGuide.Purpose.SOURCE)?.let { log("⚠ $it") }
                val root = Fs.tree(ctx, uri.toString())
                if (root != null) {
                    log("「迁移前」的版本已选择，正在识别版本…")
                    bg { detectSource(root) }
                }
            }
            12 -> {
                p.edit().putString(K.DST_URI, uri.toString()).apply()
                refreshPaths()
                DirGuide.check(ctx, uri, DirGuide.Purpose.TARGET)?.let { log("⚠ $it") }
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
                DirGuide.check(ctx, uri, DirGuide.Purpose.SCAN_ROOT)?.let { log("⚠ $it") }
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
            // 用户在设置里添加的每个启动器目录都单独再扫一遍。

            val fromDirs = InstanceScanner.scanAll(ctx) { m -> log(m) }
            val known = list.map { it.uri }.toHashSet()
            for (info in fromDirs) {
                val old = list.firstOrNull { it.uri == info.uri }
                if (old != null) {
                    // 已经扫到了，只把来源补上，不重复列出
                    if (old.source.isBlank()) old.source = info.source
                    continue
                }
                list.add(info)
            }
            if (known.isNotEmpty() || fromDirs.isNotEmpty()) {
                log("合计 ${list.size} 个版本${if (known.isNotEmpty()) "（其中 ${known.size} 个已扫到过）" else ""}")
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
        val labels = instances.map {
            val src = if (it.source.isBlank()) "" else " · ${it.source}"
            "${it.name} · MC ${it.mcVersion.ifBlank { "?" }} · ${it.modCount} 模组$src"
        }.toTypedArray()
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
                context?.let { Tips.short(it, s) }
            } catch (t: Throwable) {
                // 界面已销毁，不弹
                     Err.ignore(t, "界面已销毁，不弹")
                 }
        }
    }

    /**
 * 后台执行一段任务。block 抛异常或提前 return 时也要复位 Progress，否则 running 永久卡在 true。
 */
    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("错误：${t.message}")
            } finally {
                // 无论正常结束、提前 return 还是抛异常，都必须复位进度状态
                try { Progress.done() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 从已装模组的文件名推断加载器。
     * 手机启动器（FCL / Pojav / Zalith）的实例目录里通常没有
     * mmc-pack.json / manifest.json 这类清单文件，加载器信息只体现在
     * mods 目录里的文件名上（sodium-fabric-xxx.jar、jei-1.20.1-forge.jar）。

     * 后面的版本匹配也就跟着错。
     * 按文件名投票，出现最多的那个胜出。
     */
    private fun detectLoaderFromMods(root: DocumentFile): String {
        val mods = runCatching { Fs.find(root, McDirs.MODS) }.getOrNull() ?: return "auto"
        val files = runCatching { mods.listFiles() }.getOrNull() ?: return "auto"
        val votes = HashMap<String, Int>()
        for (f in files) {
            val n = (f.name ?: "").lowercase(Locale.ROOT)
            if (!n.endsWith(".jar") && !n.endsWith(".jar.disabled")) continue
            val ld = Loaders.normalize(n)
            if (ld == "auto") continue
            votes[ld] = (votes[ld] ?: 0) + 1
        }
        if (votes.isEmpty()) return "auto"
        return votes.entries.maxByOrNull { it.value }?.key ?: "auto"
    }

    private fun detectSource(root: DocumentFile) {
        val ctx = requireContext()
        // 清单文件常常不存在，先从 mods 目录拿一个兜底结论
        val modsLoader = detectLoaderFromMods(root)
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
            val finalLoader = if (loader == "auto") modsLoader else loader
            if (mc.isNotBlank()) {
                safePost(handler) {
                    etVersion.setText(mc)
                    selectLoader(finalLoader)
                }
                log("识别到 MC $mc / $finalLoader（MultiMC/Prism 实例）")
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
            val finalLoader = if (loader == "auto") modsLoader else loader
            if (mc.isNotBlank()) {
                safePost(handler) {
                    etVersion.setText(mc)
                    selectLoader(finalLoader)
                }
                log("识别到 MC $mc / $finalLoader（CurseForge 清单）")
                return
            }
        }
        val vj = Fs.find(root, "version.json")
        if (vj != null) {
            val o = Json.obj(Fs.readText(ctx, vj))
            val id = Json.s(o, "id")
            if (id.isNotBlank()) {
                safePost(handler) {
                    etVersion.setText(id)
                    // 清单里没有加载器信息，用 mods 目录的推断结果补上
                    if (modsLoader != "auto") selectLoader(modsLoader)
                }
                log("识别到 MC $id / $modsLoader（version.json + 模组推断）")
                return
            }
        }
        // 清单都没有，但模组文件名能认出加载器的话，至少把加载器定下来
        if (modsLoader != "auto") {
            safePost(handler) { selectLoader(modsLoader) }
            log("未找到版本清单，按已装模组推断加载器：$modsLoader。MC 版本仍需手动填写")
            return
        }
        log("未能自动识别版本，请手动填写目标 MC 版本")
    }

    /**
     * 把一条已经摆在列表里的结果刷新一下。
     *
     * 第 1 步算指纹时就先把条目加进去了，所以第 5 步回填时
     * 应该**改那一行**而不是再插一条——否则同一个模组会出现两次。
     * 找不到（比如中途清过列表）才退回插入。
     */
    private fun refreshRow(e: ModEntry) {
        val at = mods.indexOfFirst { it === e }
        if (at >= 0) {
            adapter.notifyItemChanged(at)
        } else {
            mods.add(e)
            adapter.notifyItemInserted(mods.size - 1)
        }
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
        // ⚠️ 不能读 selectedItem.toString()：Spinner 拿到的是适配器里那一项，
        // 一旦适配器换成显示「Fabric/Forge」这类标签文字（而不是标准名），
        // 传出去的就是 "Fabric" 而不是 "fabric"，
        // API 过滤时直接查不到任何结果 —— 表现为"扫描出来 0 个"
        // 或"给的全是另一个加载器的版本"，也就是加载器不对。
        // 统一走 LoaderSpinner.value()，它返回的一定是标准名。
        val loader = LoaderSpinner.value(spLoader)
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
            val dir = Fs.find(root, McDirs.MODS)
            if (dir == null) {
                log("「迁移前」的目录里没有找到 mods 文件夹")
                safePost(handler) { pb.visibility = View.GONE }
                return@bg
            }
            val files = Fs.children(dir).filter { it.isFile && (it.name ?: "").endsWith(".jar", true) }
            log("发现 ${files.size} 个 jar")
            // 统计：区分"真的没收录"和"网络问题没查成"。
            // 另外把"文件根本读不出来"单独记一项——它和"没收录"不是一回事，
            // 混在一起会让最后的提示文案说错话（告诉用户"没收录"，
            // 实际是权限/损坏，用户就会白白去重试）。
            var netFail = 0
            val netFailNames = ArrayList<String>()
            var notFound = 0
            var unreadable = 0
            val unreadableNames = ArrayList<String>()

            if (files.isEmpty()) {
                log("这个目录里没有 jar 文件")
            }

            // ── 第 1 步：本地算指纹（纯 IO，不需要联网）──────────────

            // 先把所有指纹算完，网络请求就能合并成整批发出去。
            val entries = ArrayList<ModEntry>(files.size)
            var idx = 0
            for (f in files) {
                idx++
                Progress.update("计算文件指纹（$idx/${files.size}）", idx, files.size)
                val e = ModEntry(fileName = f.name ?: "mod.jar", uri = f.uri.toString())
                e.status = "正在识别…"
                e.sha1 = Fs.sha1(ctx, f)
                if (e.sha1.isBlank()) {
                    // 打不开的文件（权限/损坏）：单独标出来，
                    // 不能让空哈希混进批量请求里污染结果。
                    e.status = "读不出文件内容（权限或损坏）"
                    // 不是网络问题，重试（重新联网查）也没用，
                    // 所以 **不能** 标 netError，否则会被算进"重试失败项"
                    e.netError = false
                    unreadable++
                    unreadableNames.add(e.fileName)
                    log("× ${e.fileName}：无法读取")
                }
                entries.add(e)
                // 算完一条就先摆进列表，别让用户对着空列表干等后面的网络请求。

                // 动辄十几秒，页面一直空着，看着就像“扫描没反应”。
                safePost(handler) {
                    mods.add(e)
                    adapter.notifyItemInserted(mods.size - 1)
                }
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
            // 必须**从 found 里取 projectId**，不能用 `recognized.map { it.projectId }`：
            // e.projectId 要到第 5 步回填时才被赋值，这里还是空字符串。
            // 传一堆空 id 给 refreshAll，等于什么都没取 ——
            // 于是下面的 title()/slug() 全为空，模组名只能退化成文件名显示，
            // 页面地址也拼不出 slug。这就是"扫描出来了但名字不对"的原因。
            val recognizedPids = entries
                .mapNotNull { found[it.sha1.lowercase()]?.projectId }
                .filter { it.isNotBlank() }
                .distinct()
            ModrinthApi.refreshAll(recognizedPids)

            // ── 第 5 步：回填每个条目的状态（不再有网络请求）────────
            for (e in entries) {
                if (e.sha1.isBlank()) {
                    safePost(handler) { refreshRow(e) }
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
                safePost(handler) { refreshRow(e) }
            }
            log("扫描完成（网络请求：识别 1 次 + 取版本 1 次 + 取名 1 次）")
            val nf = netFail
            val nfNames = ArrayList(netFailNames)
            val nfound = notFound
            val nUnread = unreadable
            val unreadNames = ArrayList(unreadableNames)
            safePost(handler) {
                pb.visibility = View.GONE
                val ok = mods.count { it.targetUrl.isNotBlank() }
                toast("可迁移 $ok / ${mods.size}")

                // 连不上的必须明确告诉用户，而不是混在"未识别"里糊过去
                if (nf > 0 || nUnread > 0) {
                    if (nf > 0) log("⚠ $nf 个因网络问题没能识别，其余已正常处理")
                    if (nUnread > 0) log("⚠ $nUnread 个文件读不出来（权限或损坏），与网络无关")
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("有 ${nf + nUnread} 个模组没识别成功")
                        .setMessage(
                            buildString {
                                if (nf > 0) {
                                    append("因**网络问题**没查到（可重试）：\n\n")
                                    nfNames.take(10).forEach { append("  · $it\n") }
                                    if (nf > 10) append("  …等 $nf 个\n")
                                    append("\n")
                                }
                                if (nUnread > 0) {
                                    append("**读不出内容**（权限或文件损坏，重试没用）：\n\n")
                                    unreadNames.take(10).forEach { append("  · $it\n") }
                                    if (nUnread > 10) append("  …等 $nUnread 个\n")
                                    append("\n")
                                }
                                append("另外有 $nfound 个是 Modrinth 确实没收录，")
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

    /**
     * 只重下"下载失败"的项，成功的不再动。
     */
    private fun retryDownloads() {
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
            val dir = if (dst != null) Fs.ensureDir(dst, McDirs.MODS) else null
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

            // 失败项可能有几十个，逐个重试又会慢到像死机。
            val hashes = todo.map { it.sha1.lowercase() }
            val found = ModrinthApi.lookupHashes(hashes)
            val recognized = todo.filter { found[it.sha1.lowercase()]?.found == true }
            val latests = if (recognized.isEmpty()) {
                emptyMap()
            } else {
                ModrinthApi.latestForHashes(recognized.map { it.sha1 }, mc, loader)
            }
            // 和 scan() 里踩过的是同一个坑：这里也不能用 `it.projectId`。
            // 重试的这批正是因为网络失败才进来的，它们的 projectId
            // **从来没被赋值过**（全为空），传下去同样取不到名字。
            ModrinthApi.refreshAll(
                todo.mapNotNull { found[it.sha1.lowercase()]?.projectId }
                    .filter { it.isNotBlank() }
                    .distinct()
            )

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
 * 执行迁移。模组下载并发（默认 3 个），全程汇报阶段名、第几个/共几个、已用与预计剩余。
 */
    /**
 * 打开工具箱里属于迁移的那些步骤，省去退回「更多」再找一层。
 */
    private fun openMigrationTools() {
        val ctx = context ?: return
        val items = listOf(
            "模组体检" to { runDoctor() },
            "配置对比" to { runDiff() },
            "跨加载器转换" to { crossLoader() },
            "代码级迁移" to { codeMigrate() },
            "打开完整工具箱" to { openAllTools() }
        )
        try {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("迁移相关工具")
                .setItems(items.map { it.first }.toTypedArray()) { _, w ->
                    items[w].second.invoke()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (t: Throwable) {
            Err.fail(t, "打开迁移工具")
            openAllTools()
        }
    }

    private fun openAllTools() {
        val ctx = context ?: return
        try {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ToolsFragment())
                .addToBackStack("tools")
                .commit()
        } catch (t: Throwable) {
            Err.fail(t, "打开工具箱")
            Tips.short(ctx, "打不开工具箱，请从「更多」进入")
        }
    }

    /**
 * 跨加载器转换。放在迁移页：这边已有目标 MC 版本与加载器，不用再填一遍。
 */
    private fun crossLoader() {
        val ctx = context ?: return
        val mc = etVersion.text.toString().trim()
        if (mc.isBlank()) {
            toast("请先填写目标 MC 版本")
            return
        }
        val targetLoader = LoaderSpinner.value(spLoader)
        val pairs = listOf(
            "forge" to "fabric", "fabric" to "forge",
            "forge" to "neoforge", "fabric" to "quilt"
        )
        // 通往当前目标加载器的那一条排前面；没选中具体加载器时保持原顺序
        val ordered = if (targetLoader == "auto") pairs
        else pairs.sortedByDescending { it.second == targetLoader }
        val labels = ordered.map {
            "${Loaders.label(it.first)} → ${Loaders.label(it.second)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("跨加载器转换（MC $mc）")
            .setMessage(
                "把 mods 目录里的模组换成另一个加载器的版本。\n\n" +
                    "只在同一个 MC 版本内替换；跨 MC 版本不做，" +
                    "那种需求用「检查更新」找新版。"
            )
            .setItems(labels) { _, w ->
                val (from, to) = ordered[w]
                runCrossLoader(from, to, mc)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runCrossLoader(from: String, to: String, mc: String) {
        val ctx = context ?: return
        toast("正在分析…")
        bg {
            val dir = DirResolve.modsDir(ctx)
            if (dir == null) {
                toast("没有可用的 mods 目录")
                return@bg
            }
            // 排除已禁用的（x.jar.disabled）：禁用的没被装载，
            // 给它做跨加载器替换没有意义，还会多下载一堆装不上的文件
            val names = dir.listFiles()
                .filter { (it.name ?: "").endsWith(".jar", true) && !ModToggle.isDisabled(it) }
                .map { it.name ?: "" }
            if (names.isEmpty()) {
                toast("mods 目录是空的（或全部被禁用）")
                return@bg
            }
            val plans = CrossLoader.plan(ctx, names, from, to, mc)
            handler.post {
                if (!isAdded) return@post
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("${Loaders.label(from)} → ${Loaders.label(to)}（MC $mc）")
                    .setMessage(CrossLoader.report(plans))
                    .setPositiveButton("下载可替换的") { _, _ -> doCrossDownload(plans) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** 按方案下载替换用的模组；原文件留在 mods 目录，由用户自行清理 */
    private fun doCrossDownload(plans: List<CrossLoader.Plan>) {
        val ctx = context ?: return
        val todo = plans.filter { it.url.isNotBlank() }
        if (todo.isEmpty()) {
            toast("没有可替换的项")
            return
        }
        toast("开始下载 ${todo.size} 个…")
        bg {
            val dir = DirResolve.modsDir(ctx)
            if (dir == null) {
                toast("目标目录不可用")
                return@bg
            }
            var ok = 0
            val failed = ArrayList<String>()
            // 变量名不能用 to —— 与标准库的中缀函数 to 同名，
            // 在字符串模板里 $to 会被解析成函数调用而报编译错。
            val targetLoader = plans.firstOrNull()?.toLoader ?: ""
            for (p in todo) {
                val name = p.url.substringAfterLast('/').ifBlank { "${p.modName}.jar" }
                val f = try {
                    Downloader.download(ctx, p.url, dir, name)
                } catch (t: Throwable) {
                    null
                }
                if (f != null) ok++
                else failed.add(p.modName)
            }
            handler.post {
                if (!isAdded) return@post
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("替换完成")
                    .setMessage(
                        buildString {
                            append("已下载 $ok / ${todo.size} 个 ${Loaders.label(targetLoader)} 版本。\n\n")
                            append("原来的文件还留在 mods 目录里，")
                            append("请到「模组管理」里删掉旧的那份（会进回收站，可还原）。")
                            if (failed.isNotEmpty()) {
                                append("\n\n失败的：").append(failed.joinToString("、"))
                            }
                        }
                    )
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    /** 代码级迁移：按官方改名表改源码里的包名/类名 */
    private fun codeMigrate() {
        val ctx = context ?: return
        val pairs = CodeMigrator.supportedPairs()
        val labels = pairs.map { "${it.first} → ${it.second}" }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("代码级迁移")
            .setItems(labels) { _, w ->
                val (from, to) = pairs[w]
                val root = WorkDir.root(ctx)
                val dir = java.io.File(ctx.getExternalFilesDir(null), "code")
                val target = if (root != null && root.uri.scheme == "file") {
                    java.io.File(root.uri.path ?: "")
                } else dir
                toast("正在扫描…")
                bg {
                    val hits = CodeMigrator.scan(target, from, to)
                    handler.post {
                        if (!isAdded) return@post
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("$from → $to")
                            .setMessage(CodeMigrator.report(hits))
                            .setPositiveButton("全部应用") { _, _ ->
                                bg {
                                    val (ok, bad) = CodeMigrator.apply(target, hits)
                                    toast("已改 $ok 个文件${if (bad > 0) "，$bad 个失败" else ""}")
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 暂停 / 继续。只改标志位，正在进行的那一个下载会写完再停。 */
    private fun togglePause() {
        if (!Progress.get().running) return
        if (Progress.isPaused()) {
            Progress.resume()
            log("已继续")
        } else {
            Progress.pause()
            log("已暂停（当前这一步完成后停下）")
        }
        syncPauseButtons()
    }

    /** 中止：停止后续所有步骤。已复制/已下载的文件保留。 */
    private fun cancelTask() {
        if (!Progress.get().running) return
        Progress.cancel()
        log("已请求中止，正在收尾…")
        syncPauseButtons()
    }

    private fun syncPauseButtons() {
        val st = Progress.get()
        val btn = btnPause ?: return
        val row = rowPause ?: return
        val show = st.running
        safePost(handler) {
            row.visibility = if (show) View.VISIBLE else View.GONE
            btn.text = if (Progress.isPaused()) getString(R.string.btn_resume)
            else getString(R.string.btn_pause)
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

    /**
 * 生成「复制哪些东西」的勾选框。清单来自 McParts，增删分类只改那里。
 */
    private fun buildPartBoxes(v: android.view.View) {
        val ctx = requireContext()
        val box = v.findViewById<android.widget.LinearLayout>(R.id.partsBox) ?: return
        box.removeAllViews()
        partBoxes.clear()
        val saved = Prefs.get(ctx).getString(McParts.PREF_MIGRATE, "") ?: ""
        val on = McParts.resolve(saved, false)
        for (p in McParts.ALL) {
            val cb = android.widget.CheckBox(ctx)
            cb.text = if (p.hint.isBlank()) p.label else p.label + "\n" + p.hint
            cb.isChecked = on.contains(p)
            cb.setOnCheckedChangeListener { _, _ ->
                Prefs.get(ctx).edit()
                    .putString(McParts.PREF_MIGRATE, McParts.toPref(currentParts()))
                    .apply()
            }
            box.addView(cb)
            partBoxes[p.id] = cb
        }
    }

    private fun currentParts(): List<McParts.Part> =
        McParts.ALL.filter { partBoxes[it.id]?.isChecked == true }

    /** 真正的迁移执行（同实例检查通过后才会走到这里） */
    private fun doMigrate(srcUri: String, dstUri: String) {
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        val chosen = currentParts()
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
            for (p in chosen) {
                if (p.id == McDirs.MODS) continue // 模组走下载阶段，不在这里复制
                steps.add(Pair(p.name, p.label))
            }

            for ((idx, step) in steps.withIndex()) {
                // 每项之间检查一次：暂停就挂起，取消就退出
                if (Progress.awaitIfPaused()) {
                    log("已中止：${step.second} 未复制")
                    Progress.done("已中止")
                    safePost(handler) { pb.visibility = View.GONE }
                    return@bg
                }
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

            val dir = Fs.ensureDir(dst, McDirs.MODS)
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

            // ── 断点续做 ───────────────────────────────────────
            // 这一阶段动辄几十个文件、十几分钟，最容易被划掉或关机打断。
            // 把待办清单落盘、每完成一个勾掉一个，
            // 下次打开就能接着下，已下好的不重复。
            // key 用下载地址：续做时靠它就能重新下载，
            // 不必依赖内存里的 mods 列表（进程被杀后那些早没了）
            val todoItems = todo.map {
                it.targetUrl to (it.targetFileName.ifBlank { Downloader.guessName(it.targetUrl) })
            }
            Resume.begin(ctx, Resume.KIND_MIGRATE, "迁移：下载模组", todoItems)

            // 记录每一项失败的原因，最后统一告诉用户哪些没下成、为什么
            val failed = java.util.Collections.synchronizedList(ArrayList<String>())
            for (m in todo) {
                // 已经在跑的那些会写完，但不再往池子里投新的
                if (Progress.awaitIfPaused()) {
                    log("已中止：还剩 ${todo.size - doneCnt.get()} 个没下载")
                    break
                }
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
                    if (f != null) {
                        okCnt.incrementAndGet()
                        // 只勾成功的：失败的留着，续做时会再试一次。
                        // key 用 url，与登记时一致。
                        Resume.mark(ctx, m.targetUrl)
                    } else failed.add("${m.name.ifBlank { m.fileName }}：$err")
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
            } catch (t: Throwable) {
                // 线程池关闭等待被打断。这里本来就是收尾动作，
                // 等不到不影响结果，但至少要留一条记录。

                Err.ignore(t, "等待下载线程池关闭")
            }

            val ok = okCnt.get()
            val total = todo.size
            val bad = ArrayList(failed)
            // ⚠️ 只有真正跑完才清断点记录。
            // 被中止时磁盘上必须留着记录：下次打开才问"要不要接着做"，
            // 已下好的不重复下。
            // 等于把"续做"这个功能废掉了。
            if (Progress.isCanceled()) {
                Progress.done("已中止")
                log("已中止：已完成 $ok/$total")
                safePost(handler) { pb.visibility = View.GONE }
                Notifier.show(ctx, "迁移已中止", "已完成 $ok/$total，下次可继续")
                return@bg
            }
            Resume.finish(ctx)
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
                        .setPositiveButton("重试失败的") { _, _ -> retryDownloads() }
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
