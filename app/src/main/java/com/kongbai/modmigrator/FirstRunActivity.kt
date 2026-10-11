package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

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
 */
class FirstRunActivity : AppCompatActivity() {

    private lateinit var tvWork: TextView
    private lateinit var tvGame: TextView
    private lateinit var spMc: Spinner
    private lateinit var spLoader: Spinner
    private lateinit var cbMirror: CheckBox

    /** 选工作目录（SAF 树授权，结果会持久保留跨重启） */
    private val pickWork = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        grant(uri)
        Prefs.get(this).edit().putString(K.WORKDIR_URI, uri.toString()).apply()
        refresh()
    }

    private val pickGame = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        grant(uri)
        Prefs.get(this).edit()
            .putString(K.GAME_DIR, uri.toString())
            .putString(K.GAME_DIR_NAME, readable(uri.toString()))
            .apply()
        refresh()
    }

    private fun grant(uri: android.net.Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
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
        setContentView(scroll)

        root.addView(title("初次设置"))
        root.addView(note("下面几项都可以跳过，之后在设置里随时改。"))

        // ---- 工作目录 ----
        root.addView(section("工作目录"))
        tvWork = body("")
        root.addView(tvWork)
        root.addView(
            row(
                btn("用应用目录") {
                    Prefs.get(this).edit().remove(K.WORKDIR_URI).apply()
                    refresh()
                },
                btn("选我的目录") { pickWork.launch(null) }
            )
        )

        // ---- 游戏目录 ----
        root.addView(section("游戏目录（.minecraft）"))
        tvGame = body("")
        root.addView(tvGame)
        root.addView(note("填了才能把模组直接装进启动器认的位置。手机启动器一般在内部存储里。"))
        root.addView(
            row(
                btn("选目录") { pickGame.launch(null) },
                btn("清除") {
                    Prefs.get(this).edit().remove(K.GAME_DIR).remove(K.GAME_DIR_NAME).apply()
                    refresh()
                }
            )
        )

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
        val done = Button(this).apply {
            text = "开始使用"
            setOnClickListener { finishSetup() }
        }
        root.addView(done)
        root.addView(
            btn("跳过，直接进主界面") {
                markDone()
                goMain()
            }
        )

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
        goMain()
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
            "已选：${readable(work)}"
        }
        val game = p.getString(K.GAME_DIR, null)
        tvGame.text = if (game.isNullOrBlank()) "未设置" else "已选：${readable(game)}"
    }

    /** SAF Uri 长得没法读，只取最后一段给用户看 */
    private fun readable(s: String): String {
        val i = s.lastIndexOf('%')
        return if (i >= 0) s.substring(i + 1) else s.substringAfterLast('/')
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

    private fun btn(t: String, fn: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { fn() }
    }

    private fun row(vararg views: View): LinearLayout {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { v ->
            l.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return l
    }

}
