package com.kongbai.modmigrator

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import java.io.File
import java.util.concurrent.Executors

/**
 * 世界存档：查看信息 + 改个名。
 *
 * ⚠️ 这是**只读查看**为主的功能，不依赖数据包生成器、也不依赖 NBT 编辑器，
 * 单独就能用。真正要改数值才需要去 NBT 编辑器。
 *
 * ⚠️ 之前所有的目录操作都走 SAF（DocumentFile），
 * 但 SAF **进不去 Android/data**，而很多启动器的 .minecraft 就在那下面，
 * 结果就是"扫不到存档"。这里两条路都留：
 *   1. 有「所有文件访问」权限 → 直接用 File 访问真实路径（快且能进 Android/data）
 *   2. 没有 → 走已授权的 .minecraft（SAF），退而求其次
 */
class WorldsFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var box: LinearLayout
    private lateinit var tvState: TextView
    private var worlds: List<WorldInfo.World> = emptyList()

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
                "列出存档的世界名、版本、种子、最后游玩时间、难度等。\n" +
                    "只读取展示；要改具体数值请用下面的「NBT 编辑器」。"
            )
        )

        tvState = TextView(ctx).apply {
            text = "正在找存档…"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(R.color.textSecondary, null))
            setPadding(0, 8, 0, 8)
        }
        root.addView(tvState)

        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)

        load()
        return scroll
    }

    private fun load() {
        val ctx = context ?: return
        tvState.text = "正在找存档…"
        exec.execute {
            val list = ArrayList<WorldInfo.World>()
            for (d in findSavesDirs(ctx)) list.addAll(WorldInfo.listIn(d))
            // SAF 授权的目录（content://）走另一条路
            val gameUri = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""
            if (gameUri.startsWith("content://")) {
                list.addAll(loadFromSaf(ctx, gameUri))
            }
            // 按最后游玩时间排：正在玩的排最前
            list.sortByDescending { it.lastPlayed }
            safePost(handler) {
                worlds = list
                box.removeAllViews()
                if (list.isEmpty()) {
                    box.addView(
                        UiCards.emptyCard(
                            ctx, "没找到存档",
                            "先在设置 → 存储里把游戏目录指到启动器的 .minecraft，" +
                                "并且确认那个启动器至少启动过一次游戏。"
                        )
                    )
                    tvState.text = ""
                } else {
                    tvState.text = "共 ${list.size} 个存档"
                    for (w in list) box.addView(worldCard(ctx, w))
                }
            }
        }
    }

    /** 找所有可能的 saves 目录（File 优先，SAF 兜底） */
    private fun findSavesDirs(ctx: android.content.Context): List<File> {
        val out = LinkedHashSet<File>()
        val game = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""
        if (game.isNotBlank() && !game.startsWith("content://")) {
            val base = File(game)
            for (cand in listOf(
                File(base, "saves"), base,
                File(base, ".minecraft/saves")
            )) {
                if (cand.isDirectory && File(cand, "level.dat").exists()) out.add(cand)
            }
        }
        // 有「所有文件访问」权限时，直接扫已知启动器目录
        if (Perms.allFiles()) {
            for ((_, f) in LauncherDirs.detect(ctx)) {
                val s = File(f, "saves")
                if (s.isDirectory) out.add(s)
                // 版本隔离：mods 在 versions/<v>/ 下，saves 也可能在那
                val versions = File(f, "versions")
                versions.listFiles()?.forEach { v ->
                    val vs = File(v, "saves")
                    if (vs.isDirectory) out.add(vs)
                }
            }
        }
        return out.toList()
    }

    /**
     * SAF 授权目录（content://）下的存档。
     * SAF 进不去 Android/data，但只要用户授权的是公共目录就能读。
     * 这里逐个把 level.dat 读成字节再解析 —— SAF 拿不到真实 File。
     */
    private fun loadFromSaf(ctx: android.content.Context, uriStr: String): List<WorldInfo.World> {
        val tree = Fs.tree(ctx, uriStr) ?: return emptyList()
        val saves = tree.findFile("saves") ?: tree
        val out = ArrayList<WorldInfo.World>()
        for (d in saves.listFiles()) {
            if (!d.isDirectory) continue
            val lvl = d.findFile("level.dat") ?: continue
            val bytes = readSafBytes(ctx, lvl) ?: continue
            out.add(WorldInfo.fromBytes(d.name ?: "存档", bytes))
        }
        return out
    }

    private fun readSafBytes(ctx: android.content.Context, f: DocumentFile): ByteArray? {
        return try {
            ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            Err.ignore(t, "读取 SAF 文件")
            null
        }
    }

    private fun worldCard(ctx: android.content.Context, w: WorldInfo.World): View {
        val seed = w.seed?.toString() ?: "未公开"
        val desc = buildString {
            append("版本 ${w.mcVersion.ifBlank { "未知" }} · ")
            append("${w.gameTypeText()} · ${w.difficultyText()}\n")
            append("种子 $seed\n")
            append("上次游玩 ${WorldInfo.timeText(w.lastPlayed)}\n")
            append("游戏内 ${w.days} 天 · ${w.sizeText()}")
            if (w.allowCommands) append("\n允许作弊：是")
            if (w.dataVersion > 0) append("\n数据版本 ${w.dataVersion}")
        }
        // 这台机器上可能装着好几个启动器，存档目录名又各不相同，
        // 光看路径分不清这个存档属于谁。按路径认出品牌，配一枚图标。
        val brand = LauncherBrand.fromPath(w.dir)
        val card = UiCards.infoCard(
            ctx, R.drawable.ic_folder, w.name, desc, "详情",
            badge = brand.icon, badgeHint = "属于 ${brand.label}"
        ) {
            showDetail(ctx, w)
        }
        return card
    }

    private fun showDetail(ctx: android.content.Context, w: WorldInfo.World) {
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        w.iconPath?.let { p ->
            val img = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (160 * resources.displayMetrics.density).toInt()
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            }
            runCatching {
                img.setImageBitmap(
                    android.graphics.BitmapFactory.decodeFile(p)
                )
            }
            ll.addView(img)
        }
        val b2 = LauncherBrand.fromPath(w.dir)
        val rowBrand = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            addView(ImageView(ctx).apply {
                setImageResource(b2.icon)
                val s = (24 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
            })
            addView(TextView(ctx).apply {
                text = "属于 ${b2.label}"
                textSize = 12f
            })
        }
        ll.addView(rowBrand, 0)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(w.name)
            .setView(ll)
            .setPositiveButton("用 NBT 编辑器打开") { _, _ ->
                NbtViewerActivity.open(ctx, File(w.dir, "level.dat"))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun safePost(h: android.os.Handler, b: () -> Unit) {
        if (!isAdded) return
        h.post { if (isAdded) b() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        runCatching { handler.removeCallbacksAndMessages(null) }
    }
}
