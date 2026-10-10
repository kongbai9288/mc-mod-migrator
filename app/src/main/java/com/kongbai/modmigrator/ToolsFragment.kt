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
 * 工具箱：零散但常用的小工具入口。
 *
 * 分组按用途走，每组都有标题，不留孤立的一行。
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
        // 一屏尽量多放几个入口：竖排大卡片二十几个要滚很久。
        // 改成方格网格；每组都带标题，不留没有标题的孤行。
        // ── 属于迁移流程的两组放最前面 ──────────────────────────
        // 这些本来就是迁移过程中的步骤（体检 / 对比 / 开关 / 跨加载器 /
        // 代码级迁移），之前混在世界、存储那一堆里，看不出跟迁移的关系。
        // 标题直接写明「迁移」，并且在迁移页底部也有直达入口。
        root.addView(UiCards.sectionTitle(ctx, "迁移 · 检查"))
        root.addView(UiCards.grid(ctx, 3, listOf(
            UiCards.tile(ctx, R.drawable.ic_check_circle, "模组体检") { doctor() },
            UiCards.tile(ctx, R.drawable.ic_extension, "依赖体检") { depCheck() },
            UiCards.tile(ctx, R.drawable.ic_content_copy, "配置对比") { diff() },
            UiCards.tile(ctx, R.drawable.ic_info, "崩溃日志") { showCrashLog() }
        )))

        root.addView(UiCards.sectionTitle(ctx, "迁移 · 模组处理"))
        root.addView(UiCards.grid(ctx, 3, listOf(
            UiCards.tile(ctx, R.drawable.ic_warning, "模组开关") { modToggle() },
            UiCards.tile(ctx, R.drawable.ic_swap_horiz, "跨加载器") { crossLoader() },
            UiCards.tile(ctx, R.drawable.ic_bolt, "代码级迁移") { codeMigrate() }
        )))

        root.addView(UiCards.sectionTitle(ctx, "世界与数据"))
        root.addView(UiCards.grid(ctx, 3, listOf(
            UiCards.tile(ctx, R.drawable.ic_folder, "世界存档") { openWorlds() },
            UiCards.tile(ctx, R.drawable.ic_inventory, "区块编辑器") { openMca() },
            UiCards.tile(ctx, R.drawable.ic_edit, "NBT 编辑器") { openNbt() },
            UiCards.tile(ctx, R.drawable.ic_code, "数据包") { openDatapack() }
        )))

        root.addView(UiCards.sectionTitle(ctx, "实例与整合包"))
        root.addView(UiCards.grid(ctx, 3, listOf(
            UiCards.tile(ctx, R.drawable.ic_rocket_launch, "补齐实例") { createInstance() },
            UiCards.tile(ctx, R.drawable.ic_devices, "加载器支持器") {
                startActivity(android.content.Intent(ctx, LoaderForgeActivity::class.java))
            },
            UiCards.tile(ctx, R.drawable.ic_cloud_upload, "导出整合包") { exportMrpack() }
        )))

        root.addView(UiCards.sectionTitle(ctx, "存储与传输"))
        root.addView(UiCards.grid(ctx, 3, listOf(
            UiCards.tile(ctx, R.drawable.ic_cloud_sync, "存储用量") { usage() },
            UiCards.tile(ctx, R.drawable.ic_delete, "清理缓存") { clearCache() },
            UiCards.tile(ctx, R.drawable.ic_storefront, "云盘") { CloudDriveActivity.open(ctx) },
            UiCards.tile(ctx, R.drawable.ic_open_in_new, "打包发送") { shareMods() }
        )))

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
            Tips.short(ctx, "打不开世界存档页，请从「更多 → 工具箱」进入")
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
     * 从一个区域文件反推它所属的世界目录：往上找到带 level.dat 的那一层。
     *
     * 目录层级前后变过（`<世界>/region/` 与 `<世界>/dimensions/ns/dim/region/`），
     * 逐层上溯比按固定层数算稳妥。
     */
    private fun worldDirOf(f: java.io.File): java.io.File? {
        var p = f.parentFile ?: return null
        repeat(6) {
            if (p.isDirectory && java.io.File(p, "level.dat").isFile) return p
            p = p.parentFile ?: return null
        }
        return null
    }

    /**
     * 区块编辑器入口。
     *
     * .mca 在 `<世界>/region/`，所以扫描深度要比 .dat 更深一层，
     * 否则永远找不到（表现为"没找到区域文件"）。
     * 和 NBT 一样两条路都走：真实路径 + SAF 授权目录。
     */
    /**
     * 区块编辑器入口。
     *
     * ⚠️ 之前是「先扫 .mca 文件、再反推世界」，而且最多只取 40 个文件。
     * 于是世界一多，前 40 个文件可能全来自第一个世界 ——
     * 列表里只剩一个世界，看着像「世界数量不对」。
     * 另外 SAF 收集的是 Uri 而不是 File，反推那一步直接跳过，
     * 于是 SAF 下退化成单文件列表，压根没有世界级视图。
     *
     * 现在改为直接发现世界目录，不截断数量，SAF 也走世界级。
     */
    private fun openMca() {
        val ctx = context ?: return
        exec.execute {
            val game = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""

            // 1) 真实路径：saves 目录下所有带 level.dat 的子目录就是一个世界
            val worldFiles = LinkedHashMap<String, java.io.File>()
            fun collect(saves: java.io.File) {
                saves.listFiles()?.forEach { d ->
                    if (d.isDirectory && java.io.File(d, "level.dat").isFile) {
                        worldFiles.putIfAbsent(d.name, d)
                    }
                }
            }
            if (game.isNotBlank() && !game.startsWith("content://")) {
                for (s in savesDirsOf(java.io.File(game))) collect(s)
            }
            // 2) 有「所有文件访问」时补上已知启动器目录
            if (Perms.allFiles()) {
                for ((_, dir) in LauncherDirs.detect(ctx)) {
                    for (s in savesDirsOf(dir)) collect(s)
                }
            }
            // 3) SAF 授权目录
            val safWorlds = ArrayList<Pair<String, androidx.documentfile.provider.DocumentFile>>()
            if (game.startsWith("content://")) {
                runCatching {
                    val tree = Fs.tree(ctx, game) ?: return@runCatching
                    val saves = tree.findFile("saves") ?: tree
                    for (d in saves.listFiles()) {
                        if (d.isDirectory && d.findFile("level.dat") != null) {
                            safWorlds.add((d.name ?: "存档") to d)
                        }
                    }
                }
            }

            safePost(handler) {
                if (!isAdded) return@safePost
                val names = ArrayList<String>()
                names.addAll(worldFiles.keys)
                names.addAll(safWorlds.map { it.first + "（授权目录）" })
                if (names.isNotEmpty()) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("打开哪个世界（共 ${names.size} 个）")
                        .setItems(names.toTypedArray()) { _, w ->
                            if (w < worldFiles.size) {
                                ChunkMapActivity.open(ctx, worldFiles.values.elementAt(w))
                            } else {
                                val doc = safWorlds[w - worldFiles.size].second
                                val prog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                                    .setTitle("正在读取存档…")
                                    .setMessage("复制区域文件到本机，稍后可保存回去")
                                    .setCancelable(false)
                                    .show()
                                exec.execute {
                                    val dir = mirrorSafWorld(ctx, doc)
                                    safePost(handler) {
                                        prog.dismiss()
                                        if (dir == null) toast("这个存档里没有区域文件")
                                        else ChunkMapActivity.open(ctx, dir)
                                    }
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    return@safePost
                }

                // 找不到 level.dat（多半授权到了世界内部），退回按文件列
                val found = ArrayList<Pair<String, Any>>()
                if (game.isNotBlank() && !game.startsWith("content://")) {
                    runCatching {
                        java.io.File(game).walkTopDown()
                            .filter { it.isFile && it.name.endsWith(".mca") && isTerrain(it) }
                            .take(200)
                            .forEach { found.add(labelOf(it.name, it) to it) }
                    }
                }
                if (game.startsWith("content://")) {
                    runCatching { collectSafNbt(ctx, game, found, listOf(".mca"), 6) }
                }
                if (found.isEmpty()) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("没找到存档")
                        .setMessage(
                            "没找到带 level.dat 的世界目录，也没找到存地形的 .mca。\n\n" +
                                "存档在「游戏目录/saves/世界名/」下，区域文件在其 " +
                                "region/ 目录里。\n\n" +
                                "请在「设置 → 存储」把游戏目录指到 .minecraft" +
                                "（或直接指到 saves 那一层）。"
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    val fn = found.map { it.first }.toTypedArray()
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("打开哪个区域文件（共 ${found.size} 个）")
                        .setItems(fn) { _, w ->
                            val v = found[w].second
                            if (v is java.io.File) ChunkMapActivity.openFile(ctx, v)
                            else ChunkMapActivity.openUri(ctx, v as android.net.Uri)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    /** 一个游戏目录（.minecraft）里可能的存档根目录，按可能性排序 */
    private fun savesDirsOf(base: java.io.File): List<java.io.File> {
        val out = LinkedHashSet<java.io.File>()
        for (c in listOf(
            java.io.File(base, "saves"), base,
            java.io.File(base, ".minecraft/saves")
        )) {
            if (c.isDirectory) out.add(c)
        }
        // 版本隔离：saves 也可能在 versions/<版本>/ 下
        java.io.File(base, "versions").listFiles()?.forEach { v ->
            val vs = java.io.File(v, "saves")
            if (vs.isDirectory) out.add(vs)
        }
        return out.toList()
    }

    /**
     * SAF 世界 → 本地镜像目录。
     *
     * 区块地图整块逻辑基于 File，而 content:// 拿不到真实路径，
     * 所以先把 region 下的 .mca 复制过来，
     * 并在 [McaWorld.Mirror] 里记下每个文件的原始 Uri，保存时写回。
     * poi/ 与 entities/ 不复制 —— 它们不存方块，白占空间。
     */
    private fun mirrorSafWorld(
        ctx: android.content.Context,
        world: androidx.documentfile.provider.DocumentFile
    ): java.io.File? {
        val name = (world.name ?: "存档").replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val root = java.io.File(ctx.filesDir, "mca-mirror").apply { mkdirs() }
        // 旧镜像不清会和别的世界混在一起
        root.listFiles()?.forEach { it.deleteRecursively() }
        val dir = java.io.File(root, name)
        McaWorld.Mirror.clear()
        var n = 0
        fun walk(d: androidx.documentfile.provider.DocumentFile, depth: Int, rel: String) {
            if (depth > 5) return
            for (c in d.listFiles()) {
                val cn = c.name ?: continue
                if (c.isDirectory) {
                    if (cn == "poi" || cn == "entities") continue
                    walk(c, depth + 1, if (rel.isEmpty()) cn else "$rel/$cn")
                } else if (cn.endsWith(".mca")) {
                    val t = java.io.File(dir, if (rel.isEmpty()) cn else "$rel/$cn")
                    t.parentFile?.mkdirs()
                    runCatching {
                        ctx.contentResolver.openInputStream(c.uri)?.use { src ->
                            t.outputStream().use { src.copyTo(it) }
                        }
                    } ?: continue
                    if (!t.isFile || t.length() <= 0) continue
                    McaWorld.Mirror.put(t, c.uri)
                    n++
                }
            }
        }
        walk(world, 0, "")
        return if (n > 0) dir else null
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
        // 两个入口：完整的 137 个生成器（离线网页版），以及内置的 9 张表单。
        // 前者种类全但要下载对应版本的数据；后者填完就能写，种类少。
        MaterialAlertDialogBuilder(ctx)
            .setTitle("用哪个生成器")
            .setItems(arrayOf("全部 137 个（推荐）", "内置表单（9 个，不需要额外数据）")) { _, i ->
                if (i == 0) MisodeActivity.open(ctx)
                else ctx.startActivity(android.content.Intent(ctx, DatapackActivity::class.java))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        handler.post { if (isAdded) Tips.short(context, s) }
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
