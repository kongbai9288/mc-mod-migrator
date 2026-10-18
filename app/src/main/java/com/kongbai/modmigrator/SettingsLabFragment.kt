package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 实验室 / 高级设置。
 *
 * 放那些新加的、或不适合进主设置页的项：外观、速率、存储清理、
 * 初次设置、公告、诊断、传输，以及彩蛋激活后才出现的实验性功能。
 *
 * 每一项收成「标签 + 当前值」一行并排看全 —— 原先是竖排一排按钮，
 * 一屏看不到底，看着像只有前几项。
 */
class SettingsLabFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { LabScreen(ctx) } }
        }
    }
}

/** 点一行弹出来的单选：标题 + 若干项 + 当前选中 */
private data class Picker(
    val title: String,
    val options: List<String>,
    val selected: Int,
    val onPick: (Int) -> Unit
)

/** 长文本结果弹窗：正文可滚动，最多再挂一个操作 */
private data class Dlg(
    val title: String,
    val body: String,
    val action: Pair<String, () -> Unit>? = null,
    val dismissText: String = "关闭"
)

@Composable
private fun LabScreen(ctx: android.content.Context) {
    val scope = rememberCoroutineScope()

    var night by remember { mutableIntStateOf(ThemePrefs.nightMode(ctx)) }
    var speed by remember { mutableIntStateOf(AnimPrefs.speed(ctx)) }
    var parallel by remember {
        mutableIntStateOf(Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3))
    }
    var threshold by remember { mutableIntStateOf(Traffic.thresholdMb(ctx)) }
    var days by remember { mutableIntStateOf(Trash.days(ctx)) }
    var residue by remember { mutableIntStateOf(-1) }
    val experimental = Prefs.get(ctx).getBoolean(K.EXPERIMENTAL_UPGRADE, false)

    var picker by remember { mutableStateOf<Picker?>(null) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    /**
     * 残留项数量要读磁盘上的回收站 JSON 索引，不能在主线程取。
     * 没取到之前显示「…」，不去猜一个数字。
     */
    LaunchedEffect(Unit) {
        val n = withContext(Dispatchers.IO) {
            runCatching { Trash.count(ctx) }.getOrDefault(0)
        }
        residue = n
    }

    /** 崩溃日志是读磁盘上多个 .log，同样走后台，读不到就明说 */
    fun withCrashLog(then: (String) -> Unit) {
        Tips.short(ctx, "正在读取…")
        scope.launch {
            val log = withContext(Dispatchers.IO) { CrashShare.read(ctx) }
            if (log.isBlank()) {
                Tips.short(ctx, "没有崩溃记录")
                return@launch
            }
            then(log)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {

        // ---------- 外观 ----------
        McSectionTitle("外观")
        McPickRow("深色模式", ThemePrefs.nightLabel(ctx)) {
            picker = Picker("深色模式", listOf("跟随系统", "浅色", "深色"), night) { w ->
                ThemePrefs.setNightMode(ctx, w)
                night = w
                picker = null
                Tips.short(ctx, "已切换，正在应用…")
                // ⚠️ 不能 postDelayed(300) 再 recreate：对话框 dismiss 会引发重排，
                // 同步屏障正活跃时重建会崩（removeSyncBarrier: token has already
                // been removed），真机崩过。交给 recreateSafely() 处理时序。
                (ctx as? android.app.Activity)?.recreateSafely()
            }
        }
        McPickRow("主题配色", ThemePrefs.themes()[ThemePrefs.index(ctx)].name) {
            Egg.showPalette(ctx)
        }
        McPickRow("动画速率", AnimPrefs.speedLabel(ctx)) {
            picker = Picker("动画速率", listOf("慢（看得清）", "正常", "快（干脆利落）"), speed) { w ->
                AnimPrefs.setSpeed(ctx, w)
                speed = w
                picker = null
            }
        }

        // ---------- 速率 ----------
        McSectionTitle("速率")
        McPickRow("下载并发", parallelLabel(ctx, parallel)) {
            // 与「设置 → 迁移」里的并发下拉共用 arrays.xml 的同一套档位，
            // 否则两处数字对不上，改这边等于没改。
            picker = Picker(
                "下载并发",
                ctx.resources.getStringArray(R.array.parallel_labels).toList(),
                curParallelIndex(ctx, parallel)
            ) { w ->
                val n = ctx.resources.getStringArray(R.array.parallel_values)
                    .getOrNull(w)?.toIntOrNull() ?: 3
                // ⚠️ 存的是并发数（1/2/3/4/6）不是档位序号：
                // Downloader、BatchModOps、迁移页读的都是 K.DOWNLOAD_PARALLEL，
                // 早先这里写的是另一个键，选完按钮文字变了、实际并发没变。
                Prefs.get(ctx).edit().putInt(K.DOWNLOAD_PARALLEL, n).apply()
                parallel = n
                picker = null
                Tips.short(ctx, "下载并发已设为 $n 个")
            }
        }
        McPickRow("流量提醒阈值", "${Traffic.thresholdMb(ctx)} MB") {
            picker = Picker(
                "移动网络流量提醒阈值",
                listOf("50 MB", "100 MB", "200 MB", "500 MB", "1 GB"),
                trafficIndex(threshold)
            ) { w ->
                val v = intArrayOf(50, 100, 200, 500, 1024)[w]
                Traffic.setThresholdMb(ctx, v)
                threshold = v
                picker = null
            }
        }

        // ---------- 存储与清理 ----------
        McSectionTitle("存储与清理")
        McPickRow("回收站保留天数", "${Trash.days(ctx)} 天") {
            picker = Picker("回收站保留天数", trashDayLabels(), trashIndex(days)) { w ->
                val v = TRASH_DAYS[w]
                Trash.setDays(ctx, v)
                days = v
                picker = null
            }
        }
        McPickRow(
            "清理卸载残留",
            if (residue < 0) "… 项在回收站" else "$residue 项在回收站"
        ) {
            scope.launch {
                val list = withContext(Dispatchers.IO) { Trash.findResidue(ctx) }
                if (list.isEmpty()) {
                    Tips.short(ctx, "没有发现残留文件")
                    return@launch
                }
                val names = list.take(20).mapNotNull { it.name }
                dlg = Dlg(
                    "发现 ${list.size} 个残留文件",
                    "这些配置对应的模组已经不在 mods 里了：\n\n" +
                        names.joinToString("\n") +
                        if (list.size > 20) "\n…等共 ${list.size} 个" else "",
                    "移到回收站" to {
                        dlg = null
                        scope.launch {
                            val n = withContext(Dispatchers.IO) {
                                var c = 0
                                for (f in list) if (Trash.moveToTrash(ctx, f)) c++
                                c
                            }
                            Tips.long(ctx, "已清理 $n 个（可在回收站还原）")
                            residue = withContext(Dispatchers.IO) {
                                runCatching { Trash.count(ctx) }.getOrDefault(0)
                            }
                        }
                    }
                )
            }
        }

        // ---------- 初次设置 ----------
        McSectionTitle("初次设置")
        McPickRow("重跑初次设置页", "把「跑过了」的标记清掉") {
            // 目录或版本设置改坏了要快速重来时用，不用清应用数据
            Prefs.get(ctx).edit().putBoolean(K.FIRST_RUN_DONE, false).apply()
            ctx.startActivity(Intent(ctx, FirstRunActivity::class.java))
        }

        // ---------- 公告 ----------
        McSectionTitle("公告")
        McPickRow("再弹一次公告", "重新取一次") {
            scope.launch {
                val n = withContext(Dispatchers.IO) {
                    runCatching { Announcement.fetch(ctx, force = true) }.getOrNull()
                }
                if (n == null) {
                    Tips.long(ctx, "暂时没有公告，或所有镜像都取不到")
                    return@launch
                }
                dlg = Dlg(n.title, n.body, "知道了" to {
                    Announcement.dismiss(ctx, n.id)
                    dlg = null
                })
            }
        }
        McPickRow("清除全部公告", "清掉已读记录") {
            Announcement.clearAll(ctx)
            Tips.short(ctx, "已清除全部公告")
        }

        // ---------- 诊断 ----------
        McSectionTitle("诊断")
        McPickRow("旧版网页生成器", "备用入口") {
            ctx.startActivity(Intent(ctx, MisodeActivity::class.java))
        }
        McPickRow("查看崩溃日志", "") {
            withCrashLog { log ->
                //
                // ⚠️ 这里原来是 takeLast(3000)：日志是「最新在前、旧的在后」，
                // 取末尾拿到的其实是最旧那次崩溃；而且从中间一刀切开，
                // 崩块开头那几行（设备/型号/系统版本）正好被切掉 ——
                // 反馈里说的「崩溃日志看不到型号」就是它。
                // 改取开头，看到的是最新一次崩溃的完整头部。
                // 超长部分仍走下面的「分享 / 保存」拿全文。
                //
                dlg = Dlg("崩溃日志（最新，共 ${log.length} 字符）", log.take(3000))
            }
        }
        McPickRow("分享崩溃日志", "走系统分享") {
            withCrashLog { log -> CrashShare.share(ctx, log) }
        }
        McPickRow("保存崩溃日志", "存到工作目录") {
            withCrashLog { log -> CrashShare.save(ctx, log) }
        }
        McPickRow("流量使用情况", "") {
            dlg = Dlg("流量使用", Traffic.describe(ctx), "清零" to {
                Traffic.reset(ctx)
                dlg = null
                Tips.short(ctx, "已清零")
            })
        }

        // ---------- 传输 ----------
        McSectionTitle("快速传输")
        McNote(QuickTransfer.describe(ctx))

        // ---------- 实验性（彩蛋激活后才显示） ----------
        if (experimental) {
            McSectionTitle("实验性（可能不稳定）")
            McPickRow("⚠ 自动升级到最新版", "") {
                confirm = Triple(
                    "实验性：自动升级",
                    "会去下载最新版 APK 并打开安装界面。这是试验功能，可能下载中断或装不上。"
                ) {
                    confirm = null
                    Tips.short(ctx, "正在查找最新版…")
                    scope.launch {
                        val o = UpdateChecker.owner(ctx).ifBlank { UpdateChecker.defaultOwner() }
                        val r = UpdateChecker.repo(ctx).ifBlank { UpdateChecker.defaultRepo() }
                        val rel = withContext(Dispatchers.IO) {
                            runCatching { UpdateChecker.latest(ctx) }.getOrNull()
                        }
                        if (rel == null) {
                            Tips.long(
                                ctx,
                                "没查到版本：${UpdateChecker.lastError.ifBlank { "网络不通" }}"
                            )
                            return@launch
                        }
                        val file = rel.apkUrl.substringAfterLast('/')
                            .ifBlank { "ModMigrator-release.apk" }
                        // pickMirror 会逐个 HEAD 试探镜像，是网络调用
                        val url = withContext(Dispatchers.IO) {
                            UpdateChecker.pickMirror(o, r, rel.tag, file)
                        }
                        Tips.short(ctx, "开始下载 ${rel.tag}")
                        DownloadService.start(ctx, url, file, "downloads")
                    }
                }
            }
            McPickRow("⚠ 更新手机启动器版本", "") {
                val pkg = Prefs.get(ctx).getString(K.LAUNCHER, "") ?: ""
                val url = LauncherHelper.updateUrl(pkg)
                // 启动器是另一个应用，我们改不了它的内部版本数据，
                // 只能跳到官方发布页由用户自己装 —— 不假装能一键替换。
                dlg = Dlg(
                    "实验性：更新启动器",
                    if (pkg.isBlank()) "还没选择启动器。先到迁移页选一个启动器。"
                    else "启动器是独立应用，需要你自己下载安装。\n\n已为你找好官方发布页：\n$url",
                    "打开地址" to {
                        dlg = null
                        if (url.isNotBlank()) WebActivity.open(ctx, url, "启动器更新")
                    }
                )
            }
            McNote("这两项仍在试验阶段，失败不会损坏已有文件，但可能下载中断或装不上。")
        }

        Spacer(Modifier.height(24.dp))
    }

    picker?.let { p ->
        McPickOne(p.title, p.options, p.selected, onPick = p.onPick, onDismiss = { picker = null })
    }

    dlg?.let { d ->
        McLongTextDialog(d.title, d.body, onDismiss = { dlg = null }, action = d.action)
    }

    confirm?.let { (t, b, act) ->
        McConfirm(t, b, "开始", onConfirm = act, onDismiss = { confirm = null })
    }
}

// ---------------- 档位换算 ----------------

private val TRASH_DAYS = intArrayOf(1, 3, 7, 15, 30, 90)

private fun trashDayLabels(): List<String> = TRASH_DAYS.map { "$it 天" }

private fun trashIndex(days: Int): Int =
    TRASH_DAYS.indexOfFirst { it == days }.let { if (it >= 0) it else 2 }

private fun trafficIndex(mb: Int): Int =
    intArrayOf(50, 100, 200, 500, 1024).indexOfFirst { it == mb }.let { if (it >= 0) it else 2 }

private fun parallelLabel(ctx: android.content.Context, n: Int): String = when {
    n <= 1 -> "1 个（最稳，一个一个下）"
    n >= 6 -> "6 个（需要好网络）"
    else -> "$n 个"
}

private fun curParallelIndex(ctx: android.content.Context, n: Int): Int =
    ctx.resources.getStringArray(R.array.parallel_values)
        .indexOfFirst { it.toIntOrNull() == n }.let { if (it >= 0) it else 2 }
