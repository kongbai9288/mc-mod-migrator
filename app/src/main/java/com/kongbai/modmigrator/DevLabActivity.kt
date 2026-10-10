package com.kongbai.modmigrator

import android.content.Intent

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * 开发者模式：把全程序的对外接口集中在这里，逐个手动触发。
 *
 * 入口：设置 → 关于 → **长按**「第三方开源许可」。
 *
 * 存在的理由：很多接口在正常使用中根本碰不到——
 * 后端连不上就永远走不到登录，没有存档就永远走不到区域解析，
 * 于是这些代码出问题时没有任何办法复现。这里给每个接口一个手动入口，
 * 点了就把返回值、耗时、异常原样显示出来。
 *
 * 所有调用都在后台线程执行，结果回到主线程打印。
 * 有副作用的（登出、清缓存、写文件）单独放在最后一节，默认不自动跑。
 */
class DevLabActivity : AppCompatActivity() {

    private val exec = Bg.io
    private val handler = Bg.ui
    private lateinit var out: TextView
    private lateinit var btnAll: Button
    private var busy = false

    /**
     * 给每一项配一个图标。
     *
     * 之前这一整页全是纯文字按钮，几十个密密麻麻排下来，
     * 想找某一个只能逐行读 —— 图标是最快的索引。
     * 图标统一走 `?attr/colorControlNormal` 染色，跟着主题变。
     */
    private fun iconFor(c: Case): Int {
        val n = c.name
        return when {
            c.group == "副作用" -> when {
                n.contains("登出") -> R.drawable.ic_shield
                n.contains("造") -> R.drawable.ic_bug_report
                n.contains("清") -> R.drawable.ic_delete
                else -> R.drawable.ic_warning
            }
            c.group == "本机" -> when {
                n.contains("区域") -> R.drawable.ic_grid_view
                n.contains("断点") -> R.drawable.ic_history
                n.contains("HTTP") -> R.drawable.ic_terminal
                n.contains("网络") -> R.drawable.ic_dns
                n.contains("目录") -> R.drawable.ic_folder
                n.contains("工作目录") -> R.drawable.ic_storage
                n.contains("启动器") -> R.drawable.ic_rocket_launch
                else -> R.drawable.ic_devices
            }
            c.group == "后端" -> when {
                n.contains("登录") || n.contains("授权") -> R.drawable.ic_login
                n.contains("诊断") -> R.drawable.ic_health_and_safety
                n.contains("地址") -> R.drawable.ic_link
                else -> R.drawable.ic_dns
            }
            c.group == "GitHub" -> when {
                n.contains("镜像") -> R.drawable.ic_open_in_new
                else -> R.drawable.ic_download
            }
            c.group == "Modrinth" -> when {
                n.contains("哈希") -> R.drawable.ic_code
                n.contains("刷新") -> R.drawable.ic_refresh
                else -> R.drawable.ic_inventory
            }
            c.group == "CurseForge" -> R.drawable.ic_search
            c.group == "公告" -> R.drawable.ic_info
            c.group == "补丁" -> R.drawable.ic_sync
            else -> R.drawable.ic_terminal
        }
    }

    /** 分组标题的图标 */
    private fun groupIcon(g: String): Int = when (g) {
        "Modrinth" -> R.drawable.ic_storefront
        "CurseForge" -> R.drawable.ic_extension
        "后端" -> R.drawable.ic_dns
        "GitHub" -> R.drawable.ic_login
        "公告" -> R.drawable.ic_info
        "补丁" -> R.drawable.ic_sync
        "本机" -> R.drawable.ic_devices
        "副作用" -> R.drawable.ic_warning
        else -> R.drawable.ic_science
    }

    /**
     * 给一个 MaterialButton 挂图标（图标在文字左侧）。
     *
     * 不在这里设 iconTint —— 图标本身用 `?attr/colorControlNormal` 染色，
     * 换主题会自动跟着变；写死一个颜色反而会在深色主题下看不见。
     */
    private fun withIcon(b: MaterialButton, res: Int) {
        runCatching {
            b.setIconResource(res)
            b.iconGravity = MaterialButton.ICON_GRAVITY_START
        }
    }

    /** 一项可测的接口 */
    private data class Case(
        val group: String,
        val name: String,
        val desc: String = "",
        /** 需要输入参数时给个提示语，null 表示不用参数 */
        val param: String? = null,
        val paramDefault: String = "",
        /** true = 有副作用，不进"全部跑一遍" */
        val risky: Boolean = false,
        val run: (ctx: android.content.Context, arg: String) -> String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "开发者模式"

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        scroll.addView(root)
        setContentView(
            scroll,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(TextView(this).apply {
            text = "逐个触发接口，结果直接显示在下方。\n" +
                "带「!」的是有副作用的操作，点「全部跑一遍」时不会执行。"
            textSize = 12f
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
        })

        btnAll = MaterialButton(this).apply {
            text = "全部跑一遍（安全项）"
            setOnClickListener { runAll() }
        }
        root.addView(btnAll)

        out = TextView(this).apply {
            textSize = 11f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(out)

        // ── 启动器开场动画 ──────────────────────────────────
        // 这段动画只有**真的从某个启动器进来**才会播，
        // 自己打开应用永远看不到，也就没法调。
        // 所以这里给每个品牌一个手动入口，想看哪个点哪个。
        root.addView(TextView(this).apply {
            text = "  启动器开场动画"
            textSize = 14f
            setPadding(0, (18 * resources.displayMetrics.density).toInt(), 0, 4)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_movie, 0, 0, 0)
            compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
        })
        for (b in LauncherBrand.all()) {
            root.addView(MaterialButton(this).apply {
                text = "播放：${b.label}"
                gravity = Gravity.START
                withIcon(this, R.drawable.ic_play_arrow)
                setOnClickListener {
                    LauncherSplash.play(
                        this@DevLabActivity,
                        LauncherBrand.Handoff(b, b.label, "1.0.0")
                    )
                }
            })
        }
        root.addView(MaterialButton(this).apply {
            text = "依次播放全部"
            withIcon(this, R.drawable.ic_movie)
            setOnClickListener { playAllSplash() }
        })
        root.addView(MaterialButton(this).apply {
            text = "模拟从启动器进来（重启主界面）"
            withIcon(this, R.drawable.ic_science)
            setOnClickListener { askLauncherName() }
        })

        var lastGroup = ""
        for (c in cases()) {
            if (c.group != lastGroup) {
                lastGroup = c.group
                root.addView(TextView(this).apply {
                    text = "  " + c.group
                    textSize = 14f
                    setPadding(0, (18 * resources.displayMetrics.density).toInt(), 0, 4)
                    setCompoundDrawablesWithIntrinsicBounds(groupIcon(c.group), 0, 0, 0)
                    compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
                })
            }
            root.addView(MaterialButton(this).apply {
                text = (if (c.risky) "! " else "") + c.name +
                    (if (c.desc.isNotBlank()) "\n" + c.desc else "")
                gravity = Gravity.START
                withIcon(this, iconFor(c))
                setOnClickListener { askAndRun(c) }
            })
        }
    }

    /** 需要参数的先弹个输入框 */
    private fun askAndRun(c: Case) {
        val ctx = this
        if (c.param == null) {
            runOne(c, c.paramDefault)
            return
        }
        val et = android.widget.EditText(ctx).apply {
            setText(c.paramDefault)
            setSingleLine(true)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(c.param)
            .setView(et)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("执行") { _, _ -> runOne(c, et.text.toString().trim()) }
            .show()
    }

    private fun runOne(c: Case, arg: String) {
        if (busy) {
            append("正在跑上一个，稍等\n")
            return
        }
        busy = true
        btnAll.isEnabled = false
        val ctx: android.content.Context = this
        append("── ${c.group} / ${c.name}${if (arg.isNotBlank()) "  [$arg]" else ""}\n")
        exec.execute {
            val t0 = System.currentTimeMillis()
            val r = try {
                c.run(ctx, arg)
            } catch (t: Throwable) {
                "异常 ${t.javaClass.simpleName}: ${t.message}\n" +
                    (t.stackTrace.take(3).joinToString("\n") { "    at $it" })
            }
            val ms = System.currentTimeMillis() - t0
            handler.post {
                append("$r\n耗时 ${ms}ms\n\n")
                busy = false
                btnAll.isEnabled = true
            }
        }
    }

    private fun runAll() {
        if (busy) return
        val safe = cases().filter { !it.risky }
        append("=== 全部跑一遍：${safe.size} 项 ===\n")
        busy = true
        btnAll.isEnabled = false
        val ctx: android.content.Context = this
        exec.execute {
            for (c in safe) {
                val t0 = System.currentTimeMillis()
                val r = try {
                    c.run(ctx, c.paramDefault)
                } catch (t: Throwable) {
                    "异常 ${t.javaClass.simpleName}: ${t.message}"
                }
                val ms = System.currentTimeMillis() - t0
                handler.post { append("· ${c.name}：${r.take(200)}  (${ms}ms)\n") }
            }
            handler.post {
                append("=== 结束 ===\n\n")
                busy = false
                btnAll.isEnabled = true
            }
        }
    }

    /** 依次播放全部品牌，每个之间留出动画本身的时长 */
    private fun playAllSplash() {
        if (splashBusy) {
            append("正在依次播放，稍等\n")
            return
        }
        val all = LauncherBrand.all()
        splashBusy = true
        append("=== 依次播放 ${all.size} 个启动器动画 ===\n")
        var i = 0
        fun step() {
            if (i >= all.size) {
                splashBusy = false
                append("=== 播放结束 ===\n\n")
                return
            }
            val b = all[i]
            append("▶ ${b.label}\n")
            LauncherSplash.play(this, LauncherBrand.Handoff(b, b.label, "1.0.0"))
            i++
            // 动画固定 3 秒，多留一点确保上一轮 running 已复位
            handler.postDelayed({ step() }, 3600)
        }
        step()
    }

    private var splashBusy = false

    /** 输入一个启动器名字，假装是它把我们拉起来的 */
    private fun askLauncherName() {
        val et = android.widget.EditText(this).apply {
            setText("Zalith Launcher 2")
            setSingleLine(true)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("启动器名字（随便填，用于测试识别）")
            .setView(et)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("用它启动主界面") { _, _ ->
                val name = et.text.toString().trim()
                val i = Intent(this, MainActivity::class.java)
                i.putExtra(LauncherBrand.EXTRA_LAUNCHER_NAME, name)
                i.putExtra(LauncherBrand.EXTRA_LAUNCHER_VERSION, "9.9.9")
                // 强制新建，确保 MainActivity 走 onCreate 读到新的 extra
                i.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
                startActivity(i)
            }
            .show()
    }

    private fun append(s: String) {
        handler.post {
            val cur = out.text.toString()
            // 结果区不无限增长，超长砍掉最早的
            out.text = if (cur.length > 24000) (cur.takeLast(16000) + s) else (cur + s)
        }
    }

    override fun onDestroy() {
        // 注意：exec 现在是全局共享池（Bg.io），**不能**在这里 shutdown，
        // 否则会把别的界面正在用的线程一起关掉。
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── 接口清单 ─────────────────────────────────────────────
    private fun cases(): List<Case> {

        /** 结果太长时截断，避免刷屏 */
        fun cut(s: String, n: Int = 600) =
            if (s.length > n) s.take(n) + "…（共 ${s.length} 字符）" else s

        return listOf(
            // ── Modrinth ──
            Case("Modrinth", "项目版本列表", "取某个项目在指定版本/加载器下可用的文件",
                "项目 ID 或 slug", "sodium") { ctx, a ->
                val v = ModrinthApi.versions(a, "", "")
                if (v.isEmpty()) "空（可能项目名不对或网络不通）"
                else v.take(5).joinToString("\n") { "${it.name} ${it.version} ${it.fileName}" }
            },
            Case("Modrinth", "哈希查项目", "给一个 sha1，看能不能认出是哪个项目",
                "sha1", "") { _, a ->
                val m = ModrinthApi.lookupHashes(listOf(a))
                if (m.isEmpty()) "空" else m.entries.joinToString("\n") { "${it.key.take(12)} → ${it.value}" }
            },
            Case("Modrinth", "哈希查最新版", "批量接口，这里只喂一个 sha1",
                "sha1", "") { _, a ->
                val m = ModrinthApi.latestForHashes(listOf(a), "", "")
                if (m.isEmpty()) "空" else m.entries.joinToString("\n") { "${it.key.take(12)} → ${it.value.name} ${it.value.version}" }
            },
            Case("Modrinth", "刷新项目缓存", "单个项目") { _, a ->
                ModrinthApi.refresh(a.ifBlank { "sodium" }); "已刷新"
            },

            // ── CurseForge ──
            Case("CurseForge", "搜索", "需要 API Key，没填会直接失败",
                "关键词", "jei") { ctx, a ->
                val key = Prefs.get(ctx).getString("cf_key", "") ?: ""
                if (key.isBlank()) "未填 CurseForge API Key"
                else {
                    val r = CurseForgeApi.search(a, "", "", key, 5, 0)
                    if (r.isEmpty()) "空" else r.joinToString("\n") { "${it.name} ← ${it.source}" }
                }
            },

            // ── 后端 ──
            Case("后端", "已知地址", "列出所有候选入口") { ctx, _ ->
                BackendApi.candidates(ctx).joinToString("\n")
            },
            Case("后端", "取配置 /config") { ctx, _ ->
                val c = BackendApi.config(ctx)
                if (c == null) "null（连不上或没这个接口）" else cut(c.toString())
            },
            Case("后端", "登录态 /auth/me") { ctx, _ ->
                val u = BackendApi.me(ctx)
                if (u == null) "未登录或连不上" else cut(u.toString())
            },
            Case("后端", "取授权地址", "GitHub 登录第一步") { ctx, _ ->
                val s = BackendApi.loginUrl(ctx)
                "url=${s.url}\nstateCookieOk=${s.stateCookieOk}\nerror=${s.error}"
            },
            Case("后端", "回调地址清单") { ctx, _ ->
                BackendApi.allCallbackUrls().joinToString("\n")
            },
            Case("后端", "登录诊断", "把整条登录链路跑一遍") { ctx, _ ->
                val r = LoginDiag.run(ctx)
                cut(LoginDiag.format(r))
            },

            // ── GitHub ──
            Case("GitHub", "最新发行版") { ctx, _ ->
                val r = UpdateChecker.latest(ctx)
                if (r == null) "null" else "${r.tag} ${r.name} ${r.apkUrl}"
            },
            Case("GitHub", "镜像下载链接", "给定 tag 与文件名拼出镜像地址",
                "tag", "v2.3.2") { ctx, a ->
                UpdateChecker.mirrorUrls(
                    UpdateChecker.owner(ctx).ifBlank { UpdateChecker.defaultOwner() },
                    UpdateChecker.repo(ctx).ifBlank { UpdateChecker.defaultRepo() },
                    a, "ModMigrator-release.apk"
                ).joinToString("\n")
            },

            // ── 公告 / 补丁 ──
            Case("公告", "拉取公告", "强制刷新，跳过节流") { ctx, _ ->
                val n = Announcement.fetch(ctx, true)
                if (n == null) "null（仓库里可能没有 announcement.json）" else cut(n.toString())
            },
            Case("补丁", "全部更新", "强制拉一次远端清单") { ctx, _ ->
                val kinds = PatchCenter.all()
                kinds.joinToString("\n") { k ->
                    "${k.key}: ${if (PatchCenter.update(ctx, k, true)) "成功" else "失败"}"
                }
            },
            Case("补丁", "上次更新时间") { ctx, _ ->
                PatchCenter.all().joinToString("\n") { k ->
                    "${k.key}: ${PatchCenter.timeText(PatchCenter.lastAt(ctx, k))}"
                }
            },

            // ── 本机能力 ──
            Case("本机", "探测启动器目录") { ctx, _ ->
                val r = LauncherDirs.detect(ctx)
                if (r.isEmpty()) "一个都没探测到"
                else r.joinToString("\n") { "${it.first.name} → ${it.second.absolutePath}" }
            },
            Case("本机", "启动器清单", "含未安装的") { _, _ ->
                LauncherDirs.all().joinToString("\n") { "${it.name}  ${it.rel}" }
            },
            Case("本机", "网络开关状态") { ctx, _ ->
                NetGate.Area.values().joinToString("\n") {
                    "${it.name}: ${if (NetGate.allow(ctx, it)) "开" else "关"}"
                }
            },
            Case("本机", "来源启动器", "这次是不是从某个启动器进来的") { _, _ ->
                LauncherBrand.describe(runCatching { LauncherBrand.handoff(this) }.getOrNull())
            },
            Case("本机", "工作目录") { ctx, _ ->
                runCatching { "${WorkDir.root(ctx)?.uri}" }.getOrElse { "不可用：${it.message}" }
            },
            Case("本机", "断点续做记录") { ctx, _ ->
                val j = Resume.peek(ctx)
                if (j == null) "没有未完成的任务"
                else "${j.title}  ${j.finished}/${j.total}"
            },
            Case("本机", "HTTP 直连测试", "给个 URL，看通不通",
                "URL", "https://api.modrinth.com/v2/project/sodium") { _, a ->
                cut(Http.get(a), 300)
            },
            Case("本机", "区域文件解析", "给一个 .mca 的完整路径",
                "文件路径", "") { _, a ->
                if (a.isBlank()) "请先填 .mca 路径"
                else {
                    val f = java.io.File(a)
                    if (!f.exists()) "文件不存在"
                    else {
                        val r = McaEdit.Region(f.readBytes())
                        "非空区块 ${r.present().size} 个：${r.present().take(12).joinToString(",")}"
                    }
                }
            },

            // ── 有副作用 ──
            Case("副作用", "登出后端", "清掉本地登录态", risky = true) { ctx, _ ->
                BackendApi.logout(ctx); "已登出"
            },
            Case("副作用", "清公告记录", "清掉已读标记", risky = true) { ctx, _ ->
                Announcement.clearAll(ctx); "已清"
            },
            Case("副作用", "造一条断点记录", "用来测续做弹窗", risky = true) { ctx, _ ->
                Resume.begin(
                    ctx, Resume.KIND_DOWNLOAD, "测试：手动造的续做记录",
                    listOf("https://example.com/a.jar" to "a.jar",
                        "https://example.com/b.jar" to "b.jar")
                )
                "已写入，回到主界面即可看到提示"
            },
            Case("副作用", "清断点记录", risky = true) { ctx, _ ->
                Resume.clear(ctx); "已清"
            },
        )
    }
}
