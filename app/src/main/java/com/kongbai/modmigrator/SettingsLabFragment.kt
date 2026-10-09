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

    private val exec = Bg.io
    private val handler = Bg.ui
    private lateinit var root: LinearLayout
    /** 「清理卸载残留」按钮：数量要后台读，先占位后更新文字 */
    private lateinit var residueBtn: Button

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
        com.google.android.material.button.MaterialButton(requireContext()).apply {
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
        // ⚠️ `Trash.count(ctx)` 是**读磁盘 JSON 索引**，而 build() 在主线程跑，
        // 每次 refresh() 都要重读一遍。改成先占位，后台取到再更新文字。
        residueBtn = button("清理卸载残留（… 项在回收站）") { cleanResidue() }
        root.addView(residueBtn)
        exec.execute {
            val n = runCatching { Trash.count(ctx) }.getOrDefault(0)
            handler.post {
                if (!isAdded) return@post
                (residueBtn as? android.widget.TextView)?.text = "清理卸载残留（$n 项在回收站）"
            }
        }

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
                Tips.short(ctx, "已切换，正在应用…")
                // ⚠️ 之前是 postDelayed(300) 里直接 activity?.recreate()。
                // 对话框 dismiss 会引发重排、同步屏障正活跃，
                // 延时 300ms 只是碰运气，照样可能撞在某一帧的遍历上 ——
                // 真机上崩过：removeSyncBarrier: token has already been removed。
                // 改由 recreateSafely() 交给系统屏障时序来保证。
                activity?.recreateSafely()
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

    /**
     * ⚠️ 之前这里读的是 `K.DOWNLOAD_SPEED`（0/1/2 三档），
     * 而**真正下载时读的是 `K.DOWNLOAD_PARALLEL`（1~6 的并发数）**
     * —— Downloader、BatchModOps、MigrationFragment、ModpackFragment 全都读后者。
     * 于是在实验室里改"下载并发"**完全不生效**：
     * 选完按钮文字变了，实际下载的并发数还是设置→迁移里那个值。
     * 现在两边统一到同一个键，改一处即生效。
     */
    private fun parallelLabel(ctx: android.content.Context): String {
        val n = Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3)
        return when {
            n <= 1 -> "1 个（最稳，一个一个下）"
            n >= 6 -> "6 个（需要好网络）"
            else -> "$n 个"
        }
    }

    private fun pickParallel() {
        val ctx = requireContext()
        // 与「设置 → 迁移」里的并发下拉用同一套档位（arrays.xml 的
        // parallel_labels / parallel_values），避免两处数字对不上。
        val opts = ctx.resources.getStringArray(R.array.parallel_labels)
        val values = ctx.resources.getStringArray(R.array.parallel_values)
        val curN = Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3)
        val cur = values.indexOfFirst { it.toIntOrNull() == curN }.let { if (it < 0) 2 else it }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("下载并发")
            .setSingleChoiceItems(opts, cur) { d, w ->
                // 存的是**并发数**（1/2/3/4/6），不是档位序号，
                // 这样 Downloader 读到的就是用户真正想要的值
                val n = values.getOrNull(w)?.toIntOrNull() ?: 3
                Prefs.get(ctx).edit().putInt(K.DOWNLOAD_PARALLEL, n).apply()
                Tips.short(ctx, "下载并发已设为 $n 个")
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
                    Tips.short(ctx, "没有发现残留文件")
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
                                Tips.long(ctx, "已清理 $n 个（可在回收站还原）")
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
                    Tips.long(ctx, "暂时没有公告，或所有镜像都取不到")
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
        Tips.short(ctx, "已清除全部公告")
    }

    /**
     * 读崩溃日志。
     *
     * ⚠️ `CrashShare.read(ctx)` 是**读磁盘上多个 .log 文件**，
     * 而 viewCrash / shareCrash / saveCrash 三个入口都直接在**主线程**调，
     * 日志多时点一下就卡住。统一改成后台读，读完再按用途分发。
     */
    private fun withCrashLog(act: (android.content.Context, String) -> Unit) {
        val ctx = requireContext()
        Tips.short(ctx, "正在读取…")
        exec.execute {
            val log = CrashShare.read(ctx)
            handler.post {
                if (!isAdded) return@post
                if (log.isBlank()) {
                    Tips.short(ctx, "没有崩溃记录")
                    return@post
                }
                act(ctx, log)
            }
        }
    }

    private fun viewCrash() {
        withCrashLog { ctx, log ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle("崩溃日志（${log.length} 字符）")
                .setMessage(log.takeLast(3000))
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun shareCrash() {
        withCrashLog { ctx, log -> CrashShare.share(ctx, log) }
    }

    private fun saveCrash() {
        withCrashLog { ctx, log -> CrashShare.save(ctx, log) }
    }

    private fun showTraffic() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("流量使用")
            .setMessage(Traffic.describe(ctx))
            .setPositiveButton("清零") { _, _ ->
                Traffic.reset(ctx)
                Tips.short(ctx, "已清零")
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
                Tips.short(ctx, "正在查找最新版…")
                exec.execute {
                    val rel = runCatching { UpdateChecker.latest(ctx) }.getOrNull()
                    if (rel == null) {
                        handler.post {
                            Tips.long(ctx, "没查到版本：${UpdateChecker.lastError.ifBlank { "网络不通" }}")
                        }
                        return@execute
                    }
                    val o = UpdateChecker.owner(ctx).ifBlank { UpdateChecker.defaultOwner() }
                    val r = UpdateChecker.repo(ctx).ifBlank { UpdateChecker.defaultRepo() }
                    val file = rel.apkUrl.substringAfterLast('/').ifBlank { "ModMigrator-release.apk" }
                    val url = UpdateChecker.pickMirror(o, r, rel.tag, file)
                    handler.post {
                        Tips.short(ctx, "开始下载 ${rel.tag}")
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
                else "启动器是独立应用，需要你自己下载安装。\n\n已为你找好官方发布页：\n$url"
            )
            .setPositiveButton("打开地址") { _, _ ->
                if (url.isNotBlank()) WebActivity.open(ctx, url, "启动器更新")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
