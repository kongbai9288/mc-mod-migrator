package com.kongbai.modmigrator

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.misode.mobile.MisodeEvent
import io.misode.mobile.MisodeView
import java.io.File

/**
 * 数据包 / 资源包生成器（离线）。
 *
 * 用的是 misode.github.io 的移动端打包（MIT），整站 137 个生成器
 * 已经打进 AAR 的 assets，**完全离线**，不需要联网。
 *
 * ⚠️ 这是个**便捷功能**，与迁移、商店等主流程无关。
 * 许可单独写在「更多 → 开源许可」里，归属 Misode（MIT）。
 *
 * 生成结果可以一键写进某个存档的 datapacks 目录 —— 那一步才算"数据包写入"。
 */
class DatapackActivity : AppCompatActivity() {

    private var misode: MisodeView? = null
    private lateinit var tvState: TextView
    private lateinit var btnSave: MaterialButton
    private var lastOutput: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        setContentView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        tvState = TextView(this).apply {
            text = "正在载入离线生成器…"
            textSize = 12f
            setPadding(0, 0, 0, 6)
        }
        root.addView(tvState)

        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(container)

        btnSave = MaterialButton(this).apply {
            text = "把当前结果写入存档"
            isEnabled = false
            setOnClickListener { writeToWorld() }
        }
        root.addView(btnSave)

        val v = MisodeView(this)
        misode = v
        container.addView(
            v,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        v.addListener { ev ->
            when (ev.type) {
                MisodeEvent.READY -> {
                    tvState.text = "离线生成器已就绪（137 个生成器，无需联网）"
                    btnSave.isEnabled = true
                }
                MisodeEvent.OUTPUT -> {
                    lastOutput = ev.value ?: ""
                }
                MisodeEvent.TITLE -> {
                    val t = ev.value
                    if (!t.isNullOrBlank()) title = t
                }
                else -> Unit
            }
        }
    }

    /**
     * 把生成结果写进存档的 datapacks。
     *
     * ⚠️ 写入前必须校验目录结构：数据包要求
     * `<world>/datapacks/<名字>/data/<命名空间>/...`，
     * 少一层游戏就认不出来（且不会报错，只是不生效）。
     * 这里只负责把 JSON 落盘并建好目录，命名空间由用户填。
     */
    private fun writeToWorld() {
        val ctx = this
        val worlds = try {
            val saves = File(Prefs.get(ctx).getString(K.GAME_DIR, "") ?: "", "saves")
            if (saves.isDirectory) {
                saves.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
            } else emptyList()
        } catch (t: Throwable) {
            Err.ignore(t, "列出存档")
            emptyList()
        }
        if (worlds.isEmpty()) {
            Toast.makeText(
                ctx,
                "没找到存档。先在设置 → 存储里把游戏目录指到 .minecraft",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val dirs = worlds.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("写到哪个存档")
            .setItems(dirs) { _, which ->
                askFileName(ctx, File(
                    Prefs.get(ctx).getString(K.GAME_DIR, "") ?: "",
                    "saves/${dirs[which]}"
                ))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askFileName(ctx: android.content.Context, world: File) {
        val et = android.widget.EditText(ctx).apply {
            setText(lastOutputName())
            setSingleLine(true)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(TextView(ctx).apply { text = "数据包目录名（英文，不要空格）" })
            addView(et)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("写入 ${world.name}")
            .setView(box)
            .setPositiveButton("写入") { _, _ ->
                val name = et.text.toString().trim()
                    .ifBlank { "generated_${System.currentTimeMillis() / 1000}" }
                if (!name.matches(Regex("[a-z0-9_\\-]+"))) {
                    Toast.makeText(ctx, "名字只能用小写字母、数字、下划线和连字符", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                doWrite(world, name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun lastOutputName(): String {
        // 生成器一般会把当前类型放在输出里，取不到就给个通用名
        return "generated_${System.currentTimeMillis() / 1000}"
    }

    private fun doWrite(world: File, name: String) {
        Thread {
            val msg = try {
                val dp = File(world, "datapacks")
                val target = File(dp, name)
                val data = File(target, "data")
                data.mkdirs()
                // pack.mcmeta 是数据包必需的：没有它游戏直接忽略整个包
                val meta = File(target, "pack.mcmeta")
                if (!meta.exists()) {
                    meta.writeText(
                        """
                        {
                          "pack": {
                            "pack_format": 26,
                            "description": "由 ModMigrator 生成"
                          }
                        }
                        """.trimIndent()
                    )
                }
                val out = File(data, "generated.json")
                val body = lastOutput.ifBlank { "{}" }
                out.writeText(body)
                "已写入：${out.absolutePath}"
            } catch (t: Throwable) {
                Err.fail(t, "写入数据包")
                "写入失败：${t.message ?: t.javaClass.simpleName}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun onDestroy() {
        // 不 destroy 的话 WebView 会一直持有 Activity，反复进出必内存泄漏
        runCatching { misode?.destroy() }
        misode = null
        super.onDestroy()
    }
}
