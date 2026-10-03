package com.kongbai.modmigrator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.viaversion.nbt.tag.CompoundTag
import java.io.File
import java.util.concurrent.Executors

/**
 * NBT 查看 / 编辑器。
 *
 * 编辑方式是**改 SNBT 文本再整体写回**，而不是做树形逐字段编辑：
 *   - SNBT 是 Minecraft 官方的文本格式（/data get 输出的那种），玩家熟悉
 *   - 树形编辑器要处理 12 种标签类型 × 嵌套 × 数组，在此无法真机验证，
 *     做一半反而会写出损坏的存档
 * 所以：树形**展示**（只读）+ SNBT **编辑**（可选），读写都走 NbtFile。
 *
 * ⚠️ 写回前强制备份 .bak —— 存档损坏不可逆。
 */
class NbtViewerActivity : AppCompatActivity() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var file: File? = null
    private var root: CompoundTag? = null

    private lateinit var tvPath: TextView
    private lateinit var tvTree: TextView
    private lateinit var etSnbt: android.widget.EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var tvState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) { finish(); return }
        val f = File(path)
        file = f

        val scroll = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        scroll.addView(root)
        setContentView(scroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        title = "NBT：${f.name}"

        tvPath = TextView(this).apply {
            text = f.absolutePath
            textSize = 11f
            setTextColor(resources.getColor(R.color.textSecondary, null))
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        root.addView(tvPath)

        tvState = TextView(this).apply {
            text = "正在读取…"
            textSize = 12f
        }
        root.addView(tvState)

        val treeScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (280 * resources.displayMetrics.density).toInt()
            )
        }
        tvTree = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setHorizontallyScrolling(true)
            setTextIsSelectable(true)
        }
        treeScroll.addView(tvTree)
        root.addView(treeScroll)

        root.addView(TextView(this).apply {
            text = "SNBT（可直接改，点「写回」保存）"
            textSize = 12f
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 4)
        })

        etSnbt = android.widget.EditText(this).apply {
            setSingleLine(false)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            minLines = 6
        }
        root.addView(etSnbt)

        btnSave = MaterialButton(this).apply {
            text = "写回文件（先自动备份 .bak）"
            isEnabled = false
            setOnClickListener { save() }
        }
        root.addView(btnSave)

        load()
    }

    private fun load() {
        val f = file ?: return
        exec.execute {
            val tag = try {
                NbtFile.read(f)
            } catch (t: Throwable) {
                Err.fail(t, "读取 NBT 失败")
                null
            }
            val snbt = tag?.let { NbtFile.toSnbt(it) } ?: ""
            val tree = tag?.let { renderTree(it) } ?: ""
            handler.post {
                if (tag == null) {
                    tvState.text = "读取失败：这个文件不是有效的 NBT，或已损坏"
                    btnSave.isEnabled = false
                    return@post
                }
                this.root = tag
                tvState.text = "已读取 ${tag.getValue().size} 个顶层字段"
                tvTree.text = tree
                etSnbt.setText(snbt)
                btnSave.isEnabled = true
            }
        }
    }

    /** 只读的树形展示。深了就折叠，避免几万行把界面卡死。 */
    private fun renderTree(tag: CompoundTag): String {
        val sb = StringBuilder()
        fun walk(t: com.viaversion.nbt.tag.Tag, name: String, depth: Int) {
            if (sb.length > 60000) return          // 硬上限，防止超大存档撑爆
            if (depth > 6) { sb.append("  ".repeat(depth)).append("$name …\n"); return }
            sb.append("  ".repeat(depth)).append(name).append(" = ")
            when (t) {
                is CompoundTag -> {
                    sb.append("{\n")
                    val m = t.getValue()
                    var i = 0
                    for ((k, v) in m) {
                        walk(v, k, depth + 1)
                        if (++i > 200) { sb.append("  ".repeat(depth + 1)).append("…（已省略）\n"); break }
                    }
                    sb.append("  ".repeat(depth)).append("}\n")
                }
                is com.viaversion.nbt.tag.ListTag<*> -> {
                    val list = t.getValue()
                    sb.append("[${list.size} 项]\n")
                    var i = 0
                    for (v in list) {
                        walk(v, "[$i]", depth + 1)
                        if (++i > 60) { sb.append("  ".repeat(depth + 1)).append("…（已省略）\n"); break }
                    }
                }
                is com.viaversion.nbt.tag.NumberArrayTag -> {
                    sb.append(t.javaClass.simpleName).append("(${t.getValue().javaClass.simpleName})\n")
                }
                else -> sb.append(t.getValue().toString().take(160)).append("\n")
            }
        }
        walk(tag, "（根）", 0)
        return sb.toString()
    }

    private fun save() {
        val f = file ?: return
        val txt = etSnbt.text.toString()
        btnSave.isEnabled = false
        tvState.text = "正在写回…"
        exec.execute {
            val msg = try {
                val parsed = NbtFile.fromSnbt(txt)
                // 备份：写坏了还能救回来
                val bak = File(f.parentFile, f.name + ".bak")
                if (f.exists()) runCatching { f.copyTo(bak, true) }
                NbtFile.write(f, parsed, true)
                "已写回（原文件备份为 ${bak.name}）"
            } catch (t: Throwable) {
                Err.fail(t, "写回 NBT 失败")
                "写回失败：${t.message ?: t.javaClass.simpleName}"
            }
            handler.post {
                tvState.text = msg
                btnSave.isEnabled = true
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!msg.startsWith("失败") && !msg.startsWith("写回失败")) {
                    btnSave.isEnabled = true
                    load()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { handler.removeCallbacksAndMessages(null) }
        exec.shutdownNow()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        fun open(ctx: Context, f: File) {
            val i = Intent(ctx, NbtViewerActivity::class.java)
                .putExtra(EXTRA_PATH, f.absolutePath)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
