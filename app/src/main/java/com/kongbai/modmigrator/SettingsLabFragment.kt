package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * 实验室 / 高级设置。
 *
 * 集中放那些「要么是新加的、要么是实验性」的开关，
 * 主设置页保持干净。包含：
 *   - 深色模式
 *   - 动画速率（慢/正常/快）
 *   - 下载并发速率
 *   - 流量提醒阈值
 *   - 回收站保留天数 + 残留清理
 *   - 公告管理（再弹一次 / 清除）
 *   - 崩溃日志（查看 / 分享 / 保存）
 *   - 快速传输说明
 *   - 实验性功能（默认隐藏，彩蛋里激活后才显示）
 */
class SettingsLabFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)
        build()
        return scroll
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun section(t: String) {
        root.addView(TextView(requireContext()).apply {
            text = t
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        })
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(requireContext()).apply {
            this.text = text
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }

    private fun build() {
        val ctx = requireContext()
        root.removeAllViews()

        // ---------- 外观 ----------
        section("外观")
        root.addView(button("深色模式：${ThemePrefs.nightLabel(ctx)}") { pickNight() })
        root.addView(button("主题配色：${ThemePrefs.themes()[ThemePrefs.index(ctx)].name}") {
            Egg.showPalette(ctx)
        })
        root.addView(button("动画速率：${AnimPrefs.speedLabel(ctx)}") { pickSpeed() })

        // ---------- 速率 ----------
        section("速率")
        root.addView(button("下载并发：${parallelLabel(ctx)}") { pickParallel() })
        root.addView(button("流量提醒阈值：${Traffic.thresholdMb(ctx)} MB") { pickTraffic() })

        // ---------- 存储与清理 ----------
        section("存储与清理")
        root.addView(button("回收站保留天数：${Trash.days(ctx)} 天") { pickTrashDays() })
        root.addView(button("清理卸载残留（${Trash.count(ctx)} 项在回收站）") { cleanResidue() })

        // ---------- 公告 ----------
        section("公告")
        root.addView(button("再弹一次公告") { reopenAnnouncement() })
        root.addView(button("清除全部公告") { clearAnnouncement() })

        // ---------- 诊断 ----------
        section("诊断")
        root.addView(button("查看崩溃日志") { viewCrash() })
        root.addView(button("分享崩溃日志（系统分享）") { shareCrash() })
        root.addView(button("保存崩溃日志到工作目录") { saveCrash() })
        root.addView(button("流量使用情况") { showTraffic() })

        // ---------- 传输 ----------
        section("快速传输")
        root.addView(TextView(ctx).apply {
            text = QuickTransfer.describe(ctx)
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })

        // ---------- 实验性（彩蛋激活后才显示） ----------
        if (Prefs.get(ctx).getBoolean(K.EXPERIMENTAL_UPGRADE, false)) {
            section("实验性（可能不稳定）")
            root.addView(button("⚠ 自动升级到最新版") { experimentalUpgrade() })
            root.addView(button("⚠ 更新手机启动器版本") { launcherUpdate() })
            root.addView(TextView(ctx).apply {
                text = "这两项仍在试验阶段，失败不会损坏已有文件，但可能下载中断或装不上。"
                textSize = 11f
                gravity = Gravity.START
            })
        }
    }

    private fun refresh() {
        if (::root.isInitialized) handler.post { build() }
    }

    // ---------------- 各项选择 ----------------

    private fun pickNight() {
        val ctx = requireContext()
        val opts = arrayOf("跟随系统", "浅色", "深色")
        val cur = ThemePrefs.nightMode(ctx)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("深色模式")
            .setSingleChoiceItems(opts, cur) { d, w ->
                ThemePrefs.setNightMode(ctx, w)
                d.dismiss()
                refresh()
                Toast.makeText(ctx, "已切换，正在应用…", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ activity?.recreate() }, 300)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickSpeed() {
        val ctx = requireContext()
        val opts = arrayOf("慢（看得清）", "正常", "快（干脆利落）")
        val cur = AnimPrefs.speed(ctx)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("动画速率")
            .setSingleChoiceItems(opts, cur) { d, w ->
                AnimPrefs.setSpeed(ctx, w)
                d.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun parallelLabel(ctx: android.content.Context): String =
        when (Prefs.get(ctx).getInt(K.DOWNLOAD_SPEED, 1)) {
            0 -> "单线程（省内存）"
            2 -> "最大并发（快）"
            else -> "适中（推荐）"
        }

    private fun pickParallel() {
        val ctx = requireContext()
        val opts = arrayOf("单线程（省内存）", "适中（推荐）", "最大并发（快）")
        val cur = Prefs.get(ctx).getInt(K.DOWNLOAD_SPEED, 1)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("下载并发")
            .setSingleChoiceItems(opts, cur) { d, w ->
                Prefs.get(ctx).edit().putInt(K.DOWNLOAD_SPEED, w).apply()
                d.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickTraffic() {
        val ctx = requireContext()
        val opts = arrayOf("50 MB", "100 MB", "200 MB", "500 MB", "1 GB")
        val vals = intArrayOf(50, 100, 200, 500, 1024)
        val cur = Traffic.thresholdMb(ctx)
        val idx = vals.indexOfFirst { it == cur }.let { if (it >= 0) it else 2 }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("移动网络流量提醒阈值")
            .setSingleChoiceItems(opts, idx) { d, w ->
                Traffic.setThresholdMb(ctx, vals[w])
                d.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickTrashDays() {
        val ctx = requireContext()
        val opts = arrayOf("1 天", "3 天", "7 天", "15 天", "30 天", "90 天")
        val vals = intArrayOf(1, 3, 7, 15, 30, 90)
        val cur = Trash.days(ctx)
        val idx = vals.indexOfFirst { it == cur }.let { if (it >= 0) it else 2 }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("回收站保留天数")
            .setSingleChoiceItems(opts, idx) { d, w ->
                Trash.setDays(ctx, vals[w])
                d.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 清理残留：找出装过又卸载留下的孤儿配置，挪进回收站 */
    private fun cleanResidue() {
        val ctx = requireContext()
        exec.execute {
            val list = Trash.findResidue(ctx)
            handler.post {
                if (list.isEmpty()) {
                    Toast.makeText(ctx, "没有发现残留文件", Toast.LENGTH_SHORT).show()
                    return@post
                }
                val names = list.take(20).mapNotNull { it.name }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("发现 ${list.size} 个残留文件")
                    .setMessage(
                        "这些配置对应的模组已经不在 mods 里了：\n\n" +
                            names.joinToString("\n") +
                            if (list.size > 20) "\n…等共 ${list.size} 个" else ""
                    )
                    .setPositiveButton("移到回收站") { _, _ ->
                        exec.execute {
                            var n = 0
                            for (f in list) if (Trash.moveToTrash(ctx, f)) n++
                            handler.post {
                                Toast.makeText(ctx, "已清理 $n 个（可在回收站还原）", Toast.LENGTH_LONG).show()
                                refresh()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun reopenAnnouncement() {
        val ctx = requireContext()
        exec.execute {
            val n = runCatching { Announcement.fetch(ctx, force = true) }.getOrNull()
            handler.post {
                if (n == null) {
                    Toast.makeText(ctx, "暂时没有公告，或所有镜像都取不到", Toast.LENGTH_LONG).show()
                    return@post
                }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(n.title)
                    .setMessage(n.body)
                    .setPositiveButton("知道了") { _, _ -> Announcement.dismiss(ctx, n.id) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun clearAnnouncement() {
        val ctx = requireContext()
        Announcement.clearAll(ctx)
        Toast.makeText(ctx, "已清除全部公告", Toast.LENGTH_SHORT).show()
    }

    private fun viewCrash() {
        val ctx = requireContext()
        val log = CrashShare.read(ctx)
        if (log.isBlank()) {
            Toast.makeText(ctx, "没有崩溃记录", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("崩溃日志（${log.length} 字符）")
            .setMessage(log.takeLast(3000))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun shareCrash() {
        val ctx = requireContext()
        val log = CrashShare.read(ctx)
        if (log.isBlank()) {
            Toast.makeText(ctx, "没有崩溃记录", Toast.LENGTH_SHORT).show()
            return
        }
        CrashShare.share(ctx, log)
    }

    private fun saveCrash() {
        val ctx = requireContext()
        val log = CrashShare.read(ctx)
        if (log.isBlank()) {
            Toast.makeText(ctx, "没有崩溃记录", Toast.LENGTH_SHORT).show()
            return
        }
        CrashShare.save(ctx, log)
    }

    private fun showTraffic() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("流量使用")
            .setMessage(Traffic.describe(ctx))
            .setPositiveButton("清零") { _, _ ->
                Traffic.reset(ctx)
                Toast.makeText(ctx, "已清零", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 实验性 ----------------

    /**
     * 实验性自动升级：下载最新 APK 并调起安装。
     * 失败不影响现有安装——只是下载了个文件，用户手动装也行。
     */
    private fun experimentalUpgrade() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("实验性：自动升级")
            .setMessage("会去下载最新版 APK 并打开安装界面。这是试验功能，可能下载中断或装不上。")
            .setPositiveButton("开始") { _, _ ->
                Toast.makeText(ctx, "正在查找最新版…", Toast.LENGTH_SHORT).show()
                exec.execute {
                    val rel = runCatching { UpdateChecker.latest(ctx) }.getOrNull()
                    if (rel == null) {
                        handler.post {
                            Toast.makeText(
                                ctx,
                                "没查到版本：${UpdateChecker.lastError.ifBlank { "网络不通" }}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@execute
                    }
                    val o = UpdateChecker.owner(ctx).ifBlank { UpdateChecker.defaultOwner() }
                    val r = UpdateChecker.repo(ctx).ifBlank { UpdateChecker.defaultRepo() }
                    val file = rel.apkUrl.substringAfterLast('/').ifBlank { "ModMigrator-release.apk" }
                    val url = UpdateChecker.pickMirror(o, r, rel.tag, file)
                    handler.post {
                        Toast.makeText(ctx, "开始下载 ${rel.tag}", Toast.LENGTH_SHORT).show()
                        DownloadService.start(ctx, url, file, "downloads")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 实验性：更新手机启动器版本。
     *
     * 启动器是另一个应用，我们无法直接改它的内部版本数据；
     * 这里做的是「跳到它的官方发布页，走镜像加速」，
     * 由用户自己下载安装——不假装能一键替换。
     */
    private fun launcherUpdate() {
        val ctx = requireContext()
        val pkg = Prefs.get(ctx).getString(K.LAUNCHER, "") ?: ""
        val url = LauncherHelper.updateUrl(pkg)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("实验性：更新启动器")
            .setMessage(
                if (pkg.isBlank()) "还没选择启动器。先到迁移页点「选启动器」。"
                else "启动器是独立应用，需要你自己下载安装。\n\n已为你找好地址（走镜像）：\n$url"
            )
            .setPositiveButton("打开地址") { _, _ ->
                if (url.isNotBlank()) WebActivity.open(ctx, url, "启动器更新")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
