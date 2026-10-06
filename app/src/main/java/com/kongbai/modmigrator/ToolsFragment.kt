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

    private val exec = Bg.io
    private val handler = Bg.ui

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
            ctx, R.drawable.ic_cloud_upload, "云盘",
            "一个入口接多家网盘：阿里云盘、百度网盘、夸克、天翼、123、115、WebDAV 等", "打开"
        ) { CloudDriveActivity.open(ctx) })

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_folder, "存储用量",
            "看工作目录各子目录占了多少空间", "查看"
        ) { usage() })

        // ---------- 依赖与冲突 ----------
        root.addView(UiCards.sectionTitle(ctx, "依赖与冲突"))

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_extension, "依赖体检（递归）",
            "逐级追查缺失的前置，不只查一层；检测重复 ID、加载器冲突", "运行"
        ) { depCheck() })

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_warning, "模组开关",
            "直接启用/禁用模组，不移动文件（改扩展名，加载器原生支持）", "打开"
        ) { modToggle() })

        // ---------- 跨加载器 ----------
        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_swap_horiz, "跨加载器迁移",
            "同一 MC 版本下把 Forge 模组换成 Fabric 版等；跨 MC 版本不做", "开始"
        ) { crossLoader() })

        // ---------- 实例与加载器 ----------
        root.addView(UiCards.sectionTitle(ctx, "实例与加载器"))

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_rocket_launch, "补齐实例结构",
            "建好 mods/config/saves 等目录，写入 Prism 可识别的实例描述", "创建"
        ) { createInstance() })

        // ---------- 整合包 ----------
        root.addView(UiCards.sectionTitle(ctx, "整合包"))

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_cloud_upload, "导出 .mrpack",
            "按 Modrinth 格式导出，可分享给别人或自己备份", "导出"
        ) { exportMrpack() })

        // ---------- 存档与数据包 ----------
        root.addView(UiCards.sectionTitle(ctx, "存档与数据包", R.drawable.ic_inventory))

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_folder, "世界存档",
            "列出存档的世界名、版本、种子、最后游玩时间、难度（只读查看）", "查看"
        ) { openWorlds() })

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_edit, "NBT 编辑器",
            "打开 .dat / .nbt，树形查看 + SNBT 编辑，写回前自动备份", "打开"
        ) { openNbt() })

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_extension, "数据包生成器（离线）",
            "137 个生成器，无需联网；结果可直接写进存档", "打开"
        ) { openDatapack() })

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_inventory, "区块编辑器（.mca）",
            "打开存档的区域文件，图形化查看并修改区块方块，保存前自动备份", "打开"
        ) { openMca() })

        // ---------- 跨设备传输 ----------
        // QuickTransfer.shareFiles() 一直**没有任何调用方**——
        // 打包分享的代码写好了，界面上却点不到，等于没做。
        // 这里补入口：把 mods 打成 zip 走系统分享面板（蓝牙/附近分享/微信均可）。
        root.addView(UiCards.sectionTitle(ctx, "跨设备传输"))

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_bolt, "打包发送到别的设备",
            "把 mods 目录打包，走系统分享（蓝牙、附近分享、微信、网盘都行）", "发送"
        ) { shareMods() })

        // ---------- 开发者 ----------
        root.addView(UiCards.sectionTitle(ctx, "开发者", R.drawable.ic_code))

        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_code, "代码级迁移",
            "Forge↔Fabric↔NeoForge 工程转换，先扫描出报告再改", "打开"
        ) { codeMigrate() })

        // 崩溃日志：排查问题时最常要的东西，之前只能去设置里翻
        root.addView(UiCards.infoCard(
            ctx, R.drawable.ic_info, "崩溃日志",
            "查看历次崩溃记录，可复制或分享给开发者", "查看"
        ) { showCrashLog() })

        return scroll
    }

    // ---------------- 存档与数据包 ----------------

    private fun openWorlds() {
        val ctx = context ?: return
        // 世界存档是独立页面，直接压进当前容器的返回栈
        try {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, WorldsFragment())
                .addToBackStack("worlds")
                .commit()
        } catch (t: Throwable) {
            // 容器 id 变了（页面结构调整）时不能静默失败，给个明确提示
            Err.fail(t, "打开世界存档页")
            Toast.makeText(ctx, "打不开世界存档页，请从「更多 → 工具箱」进入", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * NBT 编辑器入口。
     *
     * ⚠️ 之前只从「游戏目录」里用 File 扫 .dat/.nbt。
     * 而现在游戏目录几乎都是 **SAF 授权 URI**（Android 11+ 下 File API
     * 进不去 Android/data，启动器的 .minecraft 基本都在那儿），
     * 于是候选永远是空的，只能退化成"手填完整路径"——
     * 用户根本不知道路径，功能等于不可用。
     *
     * 现在两条路都扫：
     *   1. 有「所有文件访问」权限 → File 扫真实路径（能进 Android/data）
     *   2. SAF 授权目录 → 用 DocumentFile 递归找（限制深度与数量）
     * 找到就列出来点选，再不济才手填路径。
     */
    private fun openNbt() {
        val ctx = context ?: return
        exec.execute {
            val found = ArrayList<Pair<String, Any>>()
            // 路 1：真实路径
            val game = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""
            if (game.isNotBlank() && !game.startsWith("content://")) {
                runCatching {
                    java.io.File(game).walkTopDown()
                        .filter { it.isFile && (it.name.endsWith(".dat") || it.name.endsWith(".nbt")) }
                        .take(40)
                        .forEach { found.add(labelOf(it.name, it) to it) }
                }
            }
            // 路 2：SAF 授权目录
            if (game.startsWith("content://")) {
                runCatching { collectSafNbt(ctx, game, found) }
            }
            // 路 3：已知启动器目录（需要「所有文件访问」）
            if (found.isEmpty() && Perms.allFiles()) {
                for ((_, dir) in LauncherDirs.detect(ctx)) {
                    runCatching {
                        dir.walkTopDown()
                            .filter { it.isFile && (it.name.endsWith(".dat") || it.name.endsWith(".nbt")) }
                            .take(40)
                            .forEach { found.add(labelOf(it.name, it) to it) }
                    }
                }
            }
            safePost(handler) {
                if (!isAdded) return@safePost
                if (found.isEmpty()) {
                    askNbtPath(ctx)
                } else {
                    val names = found.map { it.first }.toTypedArray()
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("打开哪个文件（共 ${found.size} 个）")
                        .setItems(names) { _, w ->
                            val v = found[w].second
                            if (v is java.io.File) NbtViewerActivity.open(ctx, v)
                            else NbtViewerActivity.openUri(ctx, v as android.net.Uri)
                        }
                        .setNeutralButton("手填路径") { _, _ -> askNbtPath(ctx) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    /**
     * 在 SAF 树里递归找指定后缀的文件。
     *
     * @param exts 后缀集合，如 listOf(".dat", ".nbt")
     * @param maxDepth 目录层级上限。默认 4 对 .dat 够用，
     *   但 .mca 藏在 saves/<世界>/region/ 下，需要放宽到 6。
     */
    private fun collectSafNbt(
        ctx: android.content.Context, uriStr: String, out: ArrayList<Pair<String, Any>>,
        exts: List<String> = listOf(".dat", ".nbt"), maxDepth: Int = 4
    ) {
        val tree = Fs.tree(ctx, uriStr) ?: return
        fun walk(d: androidx.documentfile.provider.DocumentFile, depth: Int) {
            if (out.size >= 40 || depth > maxDepth) return
            for (c in d.listFiles()) {
                if (out.size >= 40) return
                if (c.isDirectory) walk(c, depth + 1)
                else {
                    val n = c.name ?: continue
                    if (!exts.any { n.endsWith(it) }) continue
                    // 找 .mca 时排除 poi/ 与 entities/：它们不存方块，
                    // 点开只能看到「没有方块数据」
                    val pn = c.parentFile?.name
                    if (exts.contains(".mca") && (pn == "poi" || pn == "entities")) continue
                    out.add(labelOf(n, c) to c.uri)
                }
            }
        }
        walk(tree, 0)
    }

    /**
     * 只有 `region/` 下的 .mca 才存方块。
     *
     * `poi/` 存兴趣点、`entities/` 存实体，两者里面**没有 sections**，
     * 打开必然是「没有方块数据」。而一个世界里这三类是同样数量
     * （这份 26.2 存档就是 region / poi / entities 各 4 个），
     * 全都列出来的话，随手一点有三分之二是空的 ——
     * 这正是反馈里「点进去全提示无数据」的一个直接来源。
     */
    private fun isTerrain(f: java.io.File): Boolean {
        val p = f.parentFile?.name
        return p != "poi" && p != "entities"
    }

    /**
     * 列表里怎么称呼一个文件。
     *
     * ⚠️ 之前直接显示文件名：level.dat / r.0.0.mca 在每个世界里都叫这个，
     * 一排同名摆在一起根本分不清是哪个世界的。
     * 现在带上所属世界名，形如「新的世界 / level.dat」。
     */
    private fun labelOf(fileName: String, f: java.io.File): String {
        val p0 = f.parentFile
        val p0n = p0?.name
        // region/ / poi/ / entities/ 这层没有信息量，再往上一层才是世界名
        val wn = if (p0n == "region" || p0n == "poi" || p0n == "entities")
            p0?.parentFile?.name else p0n
        return if (wn.isNullOrBlank()) fileName else "$wn / $fileName"
    }

    private fun labelOf(fileName: String, d: androidx.documentfile.provider.DocumentFile): String {
        val parent = d.parentFile?.name
        val wn = if (parent == "region" || parent == "poi" || parent == "entities")
            d.parentFile?.parentFile?.name else parent
        return if (wn.isNullOrBlank()) fileName else "$wn / $fileName"
    }

    /**
     * 区块编辑器入口。
     *
     * .mca 在 `<世界>/region/`，所以扫描深度要比 .dat 更深一层，
     * 否则永远找不到（表现为"没找到区域文件"）。
     * 和 NBT 一样两条路都走：真实路径 + SAF 授权目录。
     */
    private fun openMca() {
        val ctx = context ?: return
        exec.execute {
            val found = ArrayList<Pair<String, Any>>()
            val game = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""
            if (game.isNotBlank() && !game.startsWith("content://")) {
                runCatching {
                    java.io.File(game).walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".mca") && isTerrain(it) }
                        .take(40)
                        .forEach { found.add(labelOf(it.name, it) to it) }
                }
            }
            if (game.startsWith("content://")) {
                runCatching {
                    collectSafNbt(ctx, game, found, listOf(".mca"), 6)
                }
            }
            if (found.isEmpty() && Perms.allFiles()) {
                for ((_, dir) in LauncherDirs.detect(ctx)) {
                    runCatching {
                        dir.walkTopDown()
                            .filter { it.isFile && it.name.endsWith(".mca") && isTerrain(it) }
                            .take(40)
                            .forEach { found.add(labelOf(it.name, it) to it) }
                    }
                }
            }
            safePost(handler) {
                if (!isAdded) return@safePost
                if (found.isEmpty()) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("没找到区域文件")
                        .setMessage(
                            "没在游戏目录里找到存地形的 .mca。\n\n" +
                                "它在「存档目录/世界名/region/」下。\n" +
                                "同一个世界里还有 poi/ 与 entities/ 两个目录，里面同样" +
                                "是 .mca，但存的是兴趣点和实体，没有方块，所以不列出。\n\n" +
                                "请先在「设置 → 存储」把游戏目录指到 .minecraft。"
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    val names = found.map { it.first }.toTypedArray()
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("打开哪个区域文件（共 ${found.size} 个）")
                        .setItems(names) { _, w ->
                            val v = found[w].second
                            if (v is java.io.File) ChunkMapActivity.open(ctx, v)
                            else ChunkMapActivity.openUri(ctx, v as android.net.Uri)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun askNbtPath(ctx: android.content.Context) {
        val et = android.widget.EditText(ctx).apply { setSingleLine(true) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("NBT 文件路径")
            .setMessage("没在游戏目录里找到 .dat。\n" +
                "先在「设置 → 存储」把游戏目录指到 .minecraft，\n" +
                "或在这里直接填完整路径。")
            .setView(et)
            .setPositiveButton("打开") { _, _ ->
                val p = et.text.toString().trim()
                if (p.isBlank()) return@setPositiveButton
                NbtViewerActivity.open(ctx, java.io.File(p))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDatapack() {
        val ctx = context ?: return
        ctx.startActivity(android.content.Intent(ctx, DatapackActivity::class.java))
    }

    // ---------------- 跨加载器迁移 ----------------

    /**
     * 跨加载器迁移。
     * 关键约束：只在**同一个 MC 版本**内换加载器；
     * 跨 MC 版本一律拒绝（版本间 API 不兼容，搬过去必崩）。
     */
    private fun crossLoader() {
        val ctx = context ?: return
        val pairs = listOf(
            "forge" to "fabric", "fabric" to "forge",
            "forge" to "neoforge", "fabric" to "quilt"
        )
        val labels = pairs.map { "${it.first} → ${it.second}" }.toTypedArray()

        MaterialAlertDialogBuilder(ctx)
            .setTitle("跨加载器迁移")
            .setMessage(
                "把选定模组换成另一个加载器的版本。\n\n" +
                    "只在同一个 MC 版本内替换；跨 MC 版本的情况不做，" +
                    "那种需求请用「检查更新」找新版。"
            )
            .setItems(labels) { _, w ->
                val (from, to) = pairs[w]
                askMcThenPlan(from, to)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askMcThenPlan(from: String, to: String) {
        val ctx = context ?: return
        val et = android.widget.EditText(ctx).apply {
            setText(Prefs.get(ctx).getString(K.MC_VERSION, "") ?: "")
            hint = "例如 1.20.1（必须与源模组同版本）"
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("目标 MC 版本")
            .setView(et)
            .setPositiveButton("开始分析") { _, _ ->
                val mc = et.text.toString().trim()
                if (mc.isBlank()) {
                    toast("请填写 MC 版本")
                    return@setPositiveButton
                }
                Prefs.get(ctx).edit().putString(K.MC_VERSION, mc).apply()
                runCrossLoader(from, to, mc)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runCrossLoader(from: String, to: String, mc: String) {
        val ctx = context ?: return
        toast("正在分析…")
        bg {
            val dir = Targets.modsDir(ctx) ?: WorkDir.modsDir(ctx)
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
                    .setTitle("$from → $to（MC $mc）")
                    .setMessage(CrossLoader.report(plans))
                    .setPositiveButton("下载可替换的") { _, _ ->
                        doCrossDownload(plans)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** 按方案下载替换用的模组；原文件进回收站（可还原） */
    private fun doCrossDownload(plans: List<CrossLoader.Plan>) {
        val ctx = context ?: return
        val todo = plans.filter { it.url.isNotBlank() }
        if (todo.isEmpty()) {
            toast("没有可替换的项")
            return
        }
        toast("开始下载 ${todo.size} 个…")
        bg {
            val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
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
                            append("已下载 $ok / ${todo.size} 个 $targetLoader 版本。\n\n")
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

    // ---------------- 依赖体检 ----------------

    private fun depCheck() {
        val ctx = context ?: return
        toast("正在读取本地模组…")
        bg {
            val dir = Targets.modsDir(ctx) ?: WorkDir.modsDir(ctx)
            if (dir == null) {
                toast("没有可用的 mods 目录")
                return@bg
            }
            val files = dir.listFiles().filter {
                // 必须排除"已禁用"的（x.jar.disabled）。
                // 禁用的模组**不会被加载器装载**，它既不提供依赖、
                // 也不参与冲突，算进去只会让体检报告出现一堆
                // 根本不存在的"缺失依赖"和"加载器冲突"——
                // 用户照着去补装，白忙一场。
                val n = it.name ?: return@filter false
                n.endsWith(".jar", true) && !ModToggle.isDisabled(it)
            }
            if (files.isEmpty()) {
                toast("mods 目录是空的（或全部被禁用）")
                return@bg
            }
            val mods = ArrayList<ModDepGraph.Mod>()
            for (f in files) {
                val meta = ModMeta.read(ctx, f.uri)
                val name = f.name ?: ""
                val id = meta.id.ifBlank { name.substringBeforeLast('.') }
                mods.add(
                    ModDepGraph.Mod(
                        file = name,
                        id = id,
                        name = meta.name.ifBlank { id },
                        version = meta.version,
                        loader = meta.loader,
                        depends = meta.depends.keys.toList(),
                        breaks = meta.breaks.keys.toList()
                    )
                )
            }
            val report = ModDepGraph.analyze(mods)
            show("依赖体检（${mods.size} 个模组）", ModDepGraph.format(report))
        }
    }

    // ---------------- 模组开关 ----------------

    private fun modToggle() {
        val ctx = context ?: return
        bg {
            val dir = Targets.modsDir(ctx) ?: WorkDir.modsDir(ctx)
            if (dir == null) {
                toast("没有可用的 mods 目录")
                return@bg
            }
            val files = dir.listFiles().filter {
                val n = (it.name ?: "").lowercase()
                n.endsWith(".jar") || n.endsWith(".jar.disabled")
            }
            if (files.isEmpty()) {
                toast("mods 目录里没有模组")
                return@bg
            }
            val names = files.map { ModToggle.displayName(it) }.toTypedArray()
            val states = files.map { !ModToggle.isDisabled(it) }.toBooleanArray()

            handler.post {
                if (!isAdded) return@post
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("模组开关（点一下切换）")
                    .setMultiChoiceItems(names, states) { _, which, checked ->
                        bg {
                            val f = files[which]
                            val wantDisabled = !checked
                            val ok = if (wantDisabled) ModToggle.disable(f)
                            else ModToggle.enable(f)
                            handler.post {
                                if (ok != null) {
                                    toast(if (wantDisabled) "已禁用" else "已启用")
                                } else {
                                    toast("操作失败（目录可能只读）")
                                }
                            }
                        }
                    }
                    .setPositiveButton(R.string.ok, null)
                    .setNeutralButton("说明") { _, _ ->
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("这是怎么生效的")
                            .setMessage(
                                "禁用 = 把 xxx.jar 改名成 xxx.jar.disabled。\n\n" +
                                    "加载器只加载 .jar 结尾的文件，改了名它就不加载了。\n\n" +
                                    "好处：文件还在原地，随时能改回来，比挪走安全。"
                            )
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                    .show()
            }
        }
    }

    // ---------------- 实例结构 ----------------

    private fun createInstance() {
        val ctx = context ?: return
        val dstUri = Prefs.get(ctx).getString(K.DST_URI, "") ?: ""
        if (dstUri.isBlank()) {
            toast("请先在迁移页设置「迁移后」的目录")
            return
        }
        val opts = arrayOf("Fabric", "Forge", "Quilt", "NeoForge", "先不装加载器")
        val keys = arrayOf("fabric", "forge", "quilt", "neoforge", "")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("目标实例用哪个加载器")
            .setItems(opts) { _, w ->
                val loader = keys[w]
                bg {
                    val dst = Fs.tree(ctx, dstUri)
                    if (dst == null) {
                        toast("目标目录不可访问")
                        return@bg
                    }
                    val made = InstanceCreator.scaffold(ctx, dst)
                    val mc = Prefs.get(ctx).getString(K.MC_VERSION, "") ?: ""
                    val name = dst.name ?: "新实例"
                    InstanceCreator.writeInstanceCfg(ctx, dst, name, mc)
                    InstanceCreator.writeMmcPack(ctx, dst, name, mc, loader)

                    handler.post {
                        if (!isAdded) return@post
                        val sb = StringBuilder()
                        sb.append("已补齐实例结构。\n\n")
                        if (made.isNotEmpty()) sb.append("新建目录：${made.joinToString("、")}\n")
                        sb.append("已写入 instance.cfg 与 mmc-pack.json，")
                        sb.append("Prism / MultiMC 可直接识别。\n")
                        if (loader.isBlank()) {
                            sb.append("\n未选择加载器，之后可以随时再装。")
                            show("已创建", sb.toString())
                        } else {
                            sb.append("\n接下来需要你自己装 ${opts[w]}：")
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("安装 ${opts[w]}")
                                .setMessage(
                                    sb.toString() + "\n\n" +
                                        InstanceCreator.installGuide(loader, mc)
                                )
                                .setPositiveButton("打开官方下载页") { _, _ ->
                                    val page = InstanceCreator.loaderPage(loader)
                                    if (page.isNotBlank()) {
                                        WebActivity.open(ctx, page, "${opts[w]} 下载")
                                    }
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 导出 mrpack ----------------

    private fun exportMrpack() {
        val ctx = context ?: return
        val srcUri = Prefs.get(ctx).getString(K.SRC_URI, "") ?: ""
        if (srcUri.isBlank()) {
            toast("请先在迁移页设置「迁移前」的目录")
            return
        }
        toast("正在打包…")
        bg {
            val src = Fs.tree(ctx, srcUri)
            if (src == null) {
                toast("源目录不可访问")
                return@bg
            }
            val modsDir = Fs.find(src, "mods")
            val configDir = Fs.find(src, "config")
            val mc = Prefs.get(ctx).getString(K.MC_VERSION, "") ?: ""
            val loader = Prefs.get(ctx).getString(K.LOADER, "") ?: ""
            val name = (src.name ?: "整合包")

            val out = java.io.File(ctx.cacheDir, "export/${name}.mrpack")
            out.parentFile?.mkdirs()

            val f = MrpackExport.export(
                ctx,
                MrpackExport.Options(
                    name = name,
                    mcVersion = mc,
                    loader = loader
                ),
                modsDir, configDir, out
            )
            handler.post {
                if (!isAdded) return@post
                if (f == null || !f.exists()) {
                    toast("导出失败")
                    return@post
                }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("已导出")
                    .setMessage(
                        "$name.mrpack\n${f.length() / 1024} KB\n\n" +
                            "包含 mods 与 config，离线可用（模组本体已打包进 overrides）。"
                    )
                    .setPositiveButton("分享") { _, _ ->
                        QuickTransfer.shareOne(ctx, f, "$name.mrpack")
                    }
                    .setNegativeButton(R.string.ok, null)
                    .show()
            }
        }
    }

    // ---------------- 代码级迁移 ----------------

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

    /**
     * 打包 mods 目录并走系统分享面板发送到别的设备。
     *
     * 打包必须在**后台线程**做：mods 目录动辄几百 MB，
     * 在主线程压缩会直接 ANR（界面卡死）。
     * 但分享（startActivity）必须在主线程，所以压缩完再切回来。
     */
    private fun shareMods() {
        val ctx = context ?: return
        bg {
            val dir = Targets.modsDir(ctx) ?: WorkDir.modsDir(ctx)
            if (dir == null) {
                toast("没有可用的 mods 目录")
                return@bg
            }
            val files = dir.listFiles().filter {
                (it.name ?: "").endsWith(".jar", true)
            }
            if (files.isEmpty()) {
                toast("mods 目录里没有模组")
                return@bg
            }
            toast("正在打包 ${files.size} 个模组…")

            // DocumentFile 不能直接给 ZipOutputStream，先落到本地缓存再打包
            val tmp = java.io.File(ctx.cacheDir, "share_mods").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val locals = ArrayList<java.io.File>()
            for (f in files) {
                val out = java.io.File(tmp, f.name ?: continue)
                try {
                    ctx.contentResolver.openInputStream(f.uri)?.use { raw ->
                        out.outputStream().use { o ->
                            // 同 MrpackExport：SAF 流必须包缓冲，
                            // 否则每次 read 都是一次跨进程调用
                            java.io.BufferedInputStream(raw, 1 shl 18).use {
                                it.copyTo(o, 1 shl 18)
                            }
                        }
                    }
                    if (out.exists() && out.length() > 0) locals.add(out)
                } catch (t: Throwable) {
                    Err.ignore(t, "复制 ${f.name} 到临时目录")
                }
            }
            if (locals.isEmpty()) {
                toast("没有可打包的文件（可能读不到）")
                return@bg
            }
            handler.post {
                if (!isAdded) return@post
                QuickTransfer.shareFiles(ctx, locals, "mods.zip")
            }
        }
    }

    /**
     * 查看崩溃日志。
     * 放在后台读：CrashHandler.readAll 会把历次崩溃文件全读一遍，是磁盘 IO。
     */
    private fun showCrashLog() {
        val ctx = context ?: return
        toast("正在读取崩溃日志…")
        bg {
            val txt = CrashHandler.readAll(ctx)
            handler.post {
                if (!isAdded) return@post
                val body = txt.ifBlank { "没有记录到崩溃" }
                val sv = android.widget.ScrollView(ctx)
                val tv = TextView(ctx).apply {
                    text = body
                    textSize = 12f
                    setPadding(24, 16, 24, 16)
                    setTextIsSelectable(true)
                }
                sv.addView(tv)
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("崩溃日志")
                    .setView(sv)
                    .setPositiveButton(R.string.ok, null)
                    .setNeutralButton("复制") { _, _ ->
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                        if (cm == null) {
                            toast("复制失败：拿不到剪贴板")
                        } else {
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("崩溃日志", body)
                            )
                            toast("已复制")
                        }
                    }
                    .setNegativeButton("清空") { _, _ ->
                        CrashHandler.clear(ctx)
                        toast("已清空")
                    }
                    .show()
            }
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
            } finally {
                // 同 MigrationFragment：Progress 是全局单例，
                // 异常/提前 return 时不复位会永久卡在"运行中"，
                // 下次开 App 出现幽灵进度条。
                try { Progress.done() } catch (_: Throwable) {}
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
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
