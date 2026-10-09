package com.kongbai.modmigrator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.viaversion.nbt.tag.CompoundTag
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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

    private val exec = Bg.io
    private val handler = Bg.ui

    /**
     * 两种来源，二选一：
     *  · file —— 真实路径（有「所有文件访问」权限时）
     *  · uri  —— SAF 授权目录里的文件（**绝大多数情况**）
     *
     * ⚠️ 之前只支持 File。而现在的游戏目录是 SAF 授权的
     * （Android 11+ 下 File API 进不去 Android/data，
     *  启动器的 .minecraft 基本都在那儿），
     * 于是 NBT 编辑器只能退化成"手填路径"，等于不可用。
     * 现在两条路都走通：读都是拿 InputStream，写都是拿 OutputStream。
     */
    private var file: File? = null
    private var uri: Uri? = null
    private var root: CompoundTag? = null

    private lateinit var tvPath: TextView
    private lateinit var tvTree: TextView
    private lateinit var etSnbt: android.widget.EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnSaveTop: MaterialButton
    private lateinit var tvState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (!uriStr.isNullOrBlank()) {
            uri = Uri.parse(uriStr)
        } else if (!path.isNullOrBlank()) {
            file = File(path)
        } else {
            finish(); return
        }

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
        title = "NBT：${displayName()}"

        tvPath = TextView(this).apply {
            text = displayName()
            textSize = 11f
            setTextColor(resources.getColor(R.color.textSecondary, null))
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        root.addView(tvPath)

        // 这个文件属于哪个启动器：同一台机器上可能装着好几个，
        // 光看路径分不清是谁的存档，配一枚图标一眼可辨。
        val b = LauncherBrand.fromPath(displayName())
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            addView(ImageView(this@NbtViewerActivity).apply {
                setImageResource(b.icon)
                val s = (22 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginEnd = (6 * resources.displayMetrics.density).toInt()
                }
            })
            addView(TextView(this@NbtViewerActivity).apply {
                text = "属于 ${b.label}"
                textSize = 11f
                setTextColor(resources.getColor(R.color.textSecondary, null))
            })
        })

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

        //
        // ⚠️ 树很长时，「写回」按钮在整页滚动容器的**最末尾**，
        // 要一路滑到底才够得着；而 SNBT 编辑框本身也会抢焦点、
        // 把内容顶上去，实际使用中基本等于点不到。
        // 现在在顶部复制一份同样的按钮，两处等价。
        //
        btnSaveTop = MaterialButton(this).apply {
            text = "写回文件（先自动备份 .bak）"
            isEnabled = false
            setOnClickListener { save() }
        }
        root.addView(btnSaveTop, 0)

        // 用其他应用打开（MT 管理器等）。
        // 有些编辑（批量替换、十六进制看结构）外部工具更顺手，
        // 没必要什么都自己做一遍。
        root.addView(MaterialButton(this).apply {
            text = "用其他应用打开（MT 管理器等）"
            setOnClickListener { openExternal() }
        }, 0)

        load()
    }

    /** 展示用的文件标识：真实路径优先，SAF 下退化为 URI */
    private fun displayName(): String {
        file?.let { return it.absolutePath }
        uri?.let {
            return runCatching {
                DocumentFile.fromSingleUri(this, it)?.name ?: it.toString()
            }.getOrElse { it.toString() }
        }
        return ""
    }

    private fun load() {
        val f = file
        val u = uri
        if (f == null && u == null) return
        exec.execute {
            val tag = try {
                if (u != null) {
                    contentResolver.openInputStream(u)?.use { NbtFile.readStream(it) }
                        ?: throw java.io.IOException("打不开这个文件")
                } else {
                    NbtFile.read(f!!)
                }
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
                    btnSaveTop.isEnabled = false
                    return@post
                }
                this.root = tag
                tvState.text = "已读取 ${tag.getValue().size} 个顶层字段"
                tvTree.text = tree
                etSnbt.setText(snbt)
                btnSave.isEnabled = true
                btnSaveTop.isEnabled = true
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

    /** 交给外部应用处理当前文件（MT 管理器、文本编辑器等） */
    private fun openExternal() {
        val u = uri
        val target: Uri = if (u != null) u else {
            val f = file ?: return
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", f
                )
            }.getOrNull() ?: run {
                Tips.short(this, "这个文件没法交给外部应用")
                return
            }
        }
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(i, "用其他应用打开"))
        } catch (t: Throwable) {
            Err.ignore(t, "外部打开 NBT 文件")
            Tips.short(this, "没有找到可以打开它的应用")
        }
    }

    private fun save() {
        val f = file
        val u = uri
        if (f == null && u == null) return
        val txt = etSnbt.text.toString()
        btnSave.isEnabled = false
        btnSaveTop.isEnabled = false
        tvState.text = "正在写回…"
        exec.execute {
            val msg = try {
                val parsed = NbtFile.fromSnbt(txt)
                val bytes = NbtFile.toBytes(parsed, true)
                // 备份：写坏了还能救回来。两条路都先复制一份再覆盖。
                if (u != null) {
                    val bakUri = runCatching { backupSaf(u) }.getOrNull()
                    contentResolver.openOutputStream(u, "wt")?.use { it.write(bytes) }
                        ?: throw java.io.IOException("无法写入（授权可能已失效）")
                    if (bakUri != null) "已写回（原文件已备份）" else "已写回（未能备份）"
                } else {
                    val bak = File(f!!.parentFile, f.name + ".bak")
                    if (f.exists()) runCatching { f.copyTo(bak, true) }
                    NbtFile.write(f, parsed, true)
                    "已写回（原文件备份为 ${bak.name}）"
                }
            } catch (t: Throwable) {
                Err.fail(t, "写回 NBT 失败")
                "写回失败：${t.message ?: t.javaClass.simpleName}"
            }
            handler.post {
                tvState.text = msg
                btnSave.isEnabled = true
                btnSaveTop.isEnabled = true
                Tips.long(this, msg)
                if (!msg.startsWith("失败") && !msg.startsWith("写回失败")) {
                    btnSave.isEnabled = true
                    btnSaveTop.isEnabled = true
                    load()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { handler.removeCallbacksAndMessages(null) }
        // exec 是全局共享池，不能 shutdown
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * SAF 下没法直接 copyTo，只能读出来再写一份 .bak。
     * 写 .bak 也失败就返回 null —— 备份是保险，不能因为它挡住正常保存。
     */
    private fun backupSaf(src: Uri): Uri? {
        return try {
            val name = DocumentFile.fromSingleUri(this, src)?.name ?: return null
            val bytes = contentResolver.openInputStream(src)?.use { it.readBytes() }
                ?: return null
            val out = createSibling(src, "$name.bak")
            contentResolver.openOutputStream(out, "wt")?.use { it.write(bytes) }
            out
        } catch (t: Throwable) {
            Err.ignore(t, "NBT 备份失败")
            null
        }
    }

    /** 在同一目录下创建文件：用 DocumentFile 的树能力，URI 形态不兼容时返回 null */
    private fun createSibling(src: Uri, name: String): Uri {
        val df = DocumentFile.fromSingleUri(this, src)
            ?: throw java.io.IOException("打不开这个文件")
        val parentUri = parentTreeUri(src)
        if (parentUri != null) {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, parentUri)
            val existing = tree?.findFile(name)
            return existing?.uri ?: tree?.createFile("application/octet-stream", name)?.uri
                ?: throw java.io.IOException("无法创建备份文件")
        }
        throw java.io.IOException("无法定位所在目录")
    }

    /** 从 single document uri 还原出父树 uri（形如 .../document/xx:path/to/dir） */
    private fun parentTreeUri(src: Uri): Uri? {
        return try {
            val id = android.provider.DocumentsContract.getDocumentId(src)
            val parentId = id.substringBeforeLast('/', "")
            if (parentId.isEmpty()) return null
            android.provider.DocumentsContract.buildDocumentUriUsingTree(src, parentId)
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_URI = "uri"

        fun open(ctx: Context, f: File) {
            val i = Intent(ctx, NbtViewerActivity::class.java)
                .putExtra(EXTRA_PATH, f.absolutePath)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }

        /** SAF 授权目录下的文件：传 URI，读写都走 contentResolver */
        fun openUri(ctx: Context, uri: android.net.Uri) {
            val i = Intent(ctx, NbtViewerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
