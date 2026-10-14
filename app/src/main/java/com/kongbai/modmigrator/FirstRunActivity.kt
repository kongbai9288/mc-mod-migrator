package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * 初次启动设置页。
 *
 * 第一次打开应用时先走这里，把「东西放哪、迁到哪个版本、用哪个加载器」
 * 这三件事一次问清楚，省得用户进了主界面还要自己一个个翻设置。
 *
 * 每一项都可以跳过，不填就用默认值 —— 目录默认走 App 私有目录，
 * 那个目录不需要任何存储授权，开箱可用。
 *
 * 开发者模式里留了重跑入口（见 SettingsLabFragment），
 * 改坏了可以随时回来重设，不用清数据。
 *
 * ## 关于按钮
 *
 * 之前这里用的是原生 Button，而主题的 `materialButtonStyle` 只对
 * **布局 XML 里的** <Button> 生效，代码里 new 出来的不受它影响 ——
 * 于是整页按钮是系统默认的灰底，跟界面其它部分完全不是一个风格。
 * 现在统一用 MaterialButton：实心走 AppButton，次要操作走描边，
 * 两者都跟随主题主色。
 */
class FirstRunActivity : AppCompatActivity() {

    private lateinit var tvWork: TextView
    private lateinit var boxGame: LinearLayout
    private lateinit var spMc: Spinner
    private lateinit var spLoader: Spinner
    private lateinit var cbMirror: CheckBox

    /** 完成后那层加载遮罩：进度条 + 当前在做的事 */
    private lateinit var overlay: android.widget.FrameLayout
    private lateinit var bar: android.widget.ProgressBar
    private lateinit var tvStep: TextView

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val setupExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 选工作目录（SAF 树授权，结果会持久保留跨重启） */
    private val pickWork = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        GameDirs.grant(this, uri)
        Prefs.get(this).edit().putString(K.WORKDIR_URI, uri.toString()).apply()
        refresh()
    }

    /**
     * 选游戏目录。
     *
     * ⚠️ 这里是**追加**不是覆盖：手机上装多个启动器时，
     * 每个启动器的 .minecraft 各自独立，覆盖掉就等于把前一个的权限扔了，
     * 那个启动器下的模组再也扫不到。见 [GameDirs]。
     */
    private val pickGame = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val added = GameDirs.add(this, uri)
        Toast.makeText(
            this,
            if (added) "已添加，可继续添加其它启动器的目录" else "这个目录已经加过了，已切到它",
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemePrefs.styleRes(this))
        super.onCreate(savedInstanceState)

        val scroll = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        scroll.addView(root)
        val container = android.widget.FrameLayout(this)
        container.addView(scroll)
        overlay = buildOverlay()
        overlay.visibility = View.GONE
        container.addView(overlay)
        setContentView(container)

        // ---- 欢迎 ----
        root.addView(title("欢迎使用模组迁移助手"))
        root.addView(note(
            "帮你把旧版本的模组、配置和存档搬到新版本。\n" +
                "下面几步是让它知道东西该放哪、要迁到哪个版本 —— 都可以跳过，之后在设置里随时改。"
        ))

        // ---- 工作目录 ----
        root.addView(section("工作目录"))
        tvWork = body("")
        root.addView(tvWork)
        root.addView(
            row(
                outline("用应用目录") {
                    Prefs.get(this).edit().remove(K.WORKDIR_URI).apply()
                    refresh()
                },
                outline("选我的目录") { pickWork.launch(null) }
            )
        )

        // ---- 游戏目录（可多个） ----
        root.addView(section("游戏目录（.minecraft）"))
        root.addView(note("填了才能把模组直接装进启动器认的位置。装了多个启动器就把每个的目录都加上。"))
        boxGame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(boxGame)
        root.addView(outline("添加游戏目录") { pickGame.launch(null) })

        // ---- 目标版本与加载器 ----
        root.addView(section("迁移目标"))
        spMc = spinner(McTargets.MC_VERSIONS)
        root.addView(labeled("MC 版本", spMc))
        spLoader = spinner(McTargets.LOADERS)
        root.addView(labeled("加载器", spLoader))

        // ---- 网络 ----
        root.addView(section("网络"))
        cbMirror = CheckBox(this).apply {
            text = "优先用镜像（国内连不上官方源时打开）"
            isChecked = Prefs.get(this@FirstRunActivity).getBoolean(K.USE_MIRROR, true)
        }
        root.addView(cbMirror)

        // ---- 完成 ----
        root.addView(primary("开始使用") { finishSetup() })
        root.addView(outline("跳过，直接进主界面") {
            markDone()
            runSetupSteps()
        })

        refresh()
    }

    private fun finishSetup() {
        val ed = Prefs.get(this).edit()
        ed.putString(K.TARGET_MC, McTargets.MC_VERSIONS[spMc.selectedItemPosition])
        ed.putString(K.TARGET_LOADER, McTargets.LOADERS[spLoader.selectedItemPosition])
        ed.putBoolean(K.USE_MIRROR, cbMirror.isChecked)
        ed.apply()
        markDone()
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        runSetupSteps()
    }

    /** 建好工作目录下的各个子目录，省得第一次用到时才临时建 */
    private fun primeDirs() {
        DefaultDir.mods(this)
        DefaultDir.configs(this)
        DefaultDir.packs(this)
        DefaultDir.trash(this)
        DefaultDir.downloads(this)
        DefaultDir.cache(this)
        DefaultDir.logs(this)
    }

    /** 完成设置后的加载层。每一步都会真实做一点事，不只是装样子 */
    private fun runSetupSteps() {
        overlay.visibility = View.VISIBLE
        val steps: List<Pair<String, () -> Unit>> = listOf(
            "正在准备工作目录…" to { runCatching { primeDirs() } },
            "正在读取版本清单…" to { runCatching { McVer.known() } },
            "正在同步最新版本…" to { runCatching { McVer.refresh(this) } },
            "正在核对游戏目录…" to { runCatching { GameDirs.list(this) } },
            "正在载入主界面…" to {}
        )
        var idx = 0
        val advance = object : Runnable {
            override fun run() {
                if (isFinishing) return
                if (idx >= steps.size) {
                    handler.postDelayed({ goMain() }, 260)
                    return
                }
                val s = steps[idx]
                tvStep.text = s.first
                bar.progress = ((idx + 1) * 100) / steps.size
                setupExec.execute {
                    s.second()
                    idx++
                    handler.post(this)
                }
            }
        }
        handler.post(advance)
    }

    /** 加载遮罩：半透明底 + 居中卡片（进度条 + 当前步骤） */
    private fun buildOverlay(): android.widget.FrameLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(resources.getColor(R.color.cardBg, null))
        }
        val tvTitle = TextView(this).apply {
            text = "正在完成设置"
            textSize = 15f
            setTextColor(resources.getColor(R.color.textPrimary, null))
        }
        box.addView(tvTitle)
        bar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        box.addView(bar)
        tvStep = body("")
        box.addView(tvStep)
        return android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(
                box,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            )
        }
    }

    override fun onDestroy() {
        setupExec.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun markDone() {
        Prefs.get(this).edit().putBoolean(K.FIRST_RUN_DONE, true).apply()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun refresh() {
        val p = Prefs.get(this)
        val work = p.getString(K.WORKDIR_URI, null)
        tvWork.text = if (work.isNullOrBlank()) {
            "应用目录（默认，无需授权）\n${DefaultDir.path(this)}"
        } else {
            "已选：${GameDirs.readable(work)}"
        }

        // 游戏目录：列出全部，当前那个标出来，每个都能单独移除
        boxGame.removeAllViews()
        val items = GameDirs.list(this)
        if (items.isEmpty()) {
            boxGame.addView(body("未设置"))
            return
        }
        val cur = GameDirs.current(this)
        for (e in items) {
            val isCur = cur != null && cur.uri == e.uri
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 6)
            }
            val tv = TextView(this).apply {
                text = "${if (isCur) "● " else "○ "}${e.label()}"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                safeClick {
                    GameDirs.setCurrent(this@FirstRunActivity, e.uri)
                    refresh()
                }
            }
            val rm = MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "移除"
                safeClickUnit {
                    GameDirs.remove(this@FirstRunActivity, e.uri)
                    refresh()
                }
            }
            row.addView(tv)
            row.addView(rm)
            boxGame.addView(row)
        }
    }

    // ------------------------------------------------------------ 控件

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 20f
        setPadding(0, 0, 0, 8)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setPadding(0, 20, 0, 6)
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        alpha = 0.7f
        setPadding(0, 0, 0, 6)
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, 0, 0, 8)
    }

    private fun labeled(label: String, v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, 10)
        addView(TextView(this@FirstRunActivity).apply { text = label; textSize = 12f })
        addView(v)
    }

    private fun spinner(items: Array<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@FirstRunActivity,
            android.R.layout.simple_spinner_dropdown_item,
            items
        )
    }

    /** 主要按钮：实心，跟随主题主色 */
    private fun primary(t: String, fn: () -> Unit) = MaterialButton(this).apply {
        text = t
        safeClickUnit { fn() }
    }

    /** 次要按钮：描边，同样跟随主题主色 */
    private fun outline(t: String, fn: () -> Unit) = MaterialButton(
        this, null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = t
        safeClickUnit { fn() }
    }

    private fun row(vararg views: View): LinearLayout {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { v ->
            v.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            l.addView(v)
        }
        return l
    }
}
