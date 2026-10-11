package com.kongbai.modmigrator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据包生成器（原生）。
 *
 * # 为什么不再套网页
 *
 * 之前是把 misode 的整站塞进 WebView。网页运行时要自己拉一份 schema
 * （symbols.json，2.6 MB）才能出表单，拉不到就是"页面在、但一个表单都没有"；
 * 而且中间是黑盒，断在哪一步完全看不到。
 *
 * 现在直接读同一份 schema，用 [McdocForm] 把类型定义展开成表单。
 * 所有生成器共用这一套逻辑 —— 新增类型不用写 UI 代码。
 *
 * # 覆盖范围
 *
 * [DatapackCatalog] 里 97 个：70 个按 schema 动态生成，
 * 24 个标签走固定模板，pack.mcmeta / 文本组件各一个。
 * 少数几个 schema 里暂时找不到根类型的标成"暂不支持"，不假装能用。
 */
class DatapackActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private lateinit var formBox: LinearLayout
    private lateinit var tvOut: TextView
    private lateinit var etPack: EditText
    private lateinit var etNs: EditText
    private lateinit var etName: EditText
    private lateinit var etMc: EditText

    /** 当前生成器对应的表单树。UI 只是它的投影，重建 View 不丢数据 */
    private var node: McdocForm.N? = null
    private var gen: DatapackCatalog.G? = null
    private var treeUri: Uri? = null
    private var lastJson: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemePrefs.styleRes(this))
        super.onCreate(savedInstanceState)
        title = "数据包生成器"

        scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        scroll.addView(root)
        setContentView(scroll)

        buildHead()
        formBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(formBox)
        tvOut = TextView(this).apply {
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(tvOut)
        buildActions()

        val saved = Prefs.get(this).getString(K.DST_URI, null)
        if (!saved.isNullOrBlank()) treeUri = Uri.parse(saved)
    }

    override fun onDestroy() {
        super.onDestroy()
        // schema 有几万个对象，页面一走就该还回去
        McdocSchema.release()
    }

    // ---------------------------------------------------------------- 顶部

    private fun buildHead() {
        val card = card()
        card.addView(label("生成器"))

        val items = DatapackCatalog.ALL
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            items.map { "${it.title}（${it.id}）" }
        )
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) = pickGen(items[i])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        card.addView(sp)
        root.addView(card)

        val c2 = card()
        c2.addView(label("MC 版本（决定显示哪些字段）"))
        etMc = EditText(this).apply {
            setText(McVer.defaultMc(this@DatapackActivity))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        c2.addView(etMc)
        c2.addView(label("数据包名 / 命名空间 / 文件名"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etPack = EditText(this).apply { setText("mypack"); hint = "包名" }
        etNs = EditText(this).apply { setText("mypack"); hint = "命名空间" }
        etName = EditText(this).apply { setText("generated"); hint = "文件名" }
        for (e in listOf(etPack, etNs, etName)) {
            row.addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        c2.addView(row)
        root.addView(c2)
    }

    private fun pickGen(g: DatapackCatalog.G) {
        gen = g
        node = null
        formBox.removeAllViews()
        when (g.kind) {
            DatapackCatalog.Kind.NONE -> {
                formBox.addView(label("「${g.title}」在 schema 里暂时找不到根类型，先不支持。\n" +
                    "换一个生成器，或者用网页版。"))
                return
            }
            DatapackCatalog.Kind.TAG -> {
                node = tagForm(g)
            }
            DatapackCatalog.Kind.FIXED -> {
                node = fixedForm(g)
            }
            DatapackCatalog.Kind.TEXT -> {
                node = McdocForm.N.Raw(text = "{\"text\":\"\"}")
            }
            DatapackCatalog.Kind.SCHEMA -> {
                val err = McdocSchema.loadError
                if (McdocSchema.get(this) == null) {
                    formBox.addView(label("读不到 schema：${err ?: "文件不在包里"}\n" +
                        "这类生成器需要构建时下载的 symbols.json。"))
                    return
                }
                val h = LoadingOverlay.show(this, "正在展开 ${g.title}…")
                Bg.io.execute {
                    val n = McdocForm.build(this, g.path!!, mcVer())
                    runOnUiThread {
                        LoadingOverlay.hide(this, h)
                        node = n
                        if (n == null) formBox.addView(label("展开失败：${g.path}"))
                        else renderForm()
                    }
                }
                return
            }
        }
        renderForm()
    }

    private fun mcVer(): String = etMc.text.toString().trim().ifBlank { McVer.defaultMc(this@DatapackActivity) }

    /** 标签：结构固定，就 values 列表 + replace 开关 */
    private fun tagForm(g: DatapackCatalog.G): McdocForm.N {
        val reg = DatapackCatalog.tagRegistry(g)
        val values = McdocForm.N.Arr(make = {
            McdocForm.N.Str(reg = reg, hint = "$reg id，如 minecraft:stone")
        })
        values.items.add(McdocForm.N.Str(reg = reg, hint = "$reg id，如 minecraft:stone"))
        return McdocForm.N.Obj(
            mutableListOf(
                McdocForm.F("replace", McdocForm.N.Bool(false), true),
                McdocForm.F("values", values, false)
            )
        )
    }

    /** pack.mcmeta：不在 data 下，字段就这几个 */
    private fun fixedForm(g: DatapackCatalog.G): McdocForm.N = McdocForm.N.Obj(
        mutableListOf(
            McdocForm.F("pack_format", McdocForm.N.Num("26", true), false),
            McdocForm.F("description", McdocForm.N.Str("My datapack"), false)
        )
    )

    // ---------------------------------------------------------------- 表单

    private fun renderForm() {
        formBox.removeAllViews()
        val n = node ?: return
        if (n is McdocForm.N.Obj) {
            for (f in n.fields) addField(f, formBox, 0)
        } else {
            addNode(n, formBox, "根", 0)
        }
        formBox.addView(label("— 可选字段默认关着，勾上才会写进 JSON —").apply {
            textSize = 11f
        })
    }

    private fun addField(f: McdocForm.F, box: LinearLayout, depth: Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (f.opt) {
            val cb = CheckBox(this).apply {
                text = "${f.key}${if (f.desc.isNotBlank()) " — ${f.desc.take(60)}" else ""}"
                isChecked = f.node.on
                textSize = 12f
            }
            val sub = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sub.visibility = if (f.node.on) View.VISIBLE else View.GONE
            addNode(f.node, sub, "", depth)
            cb.setOnCheckedChangeListener { _, on ->
                f.node.on = on
                sub.visibility = if (on) View.VISIBLE else View.GONE
            }
            row.addView(cb)
            row.addView(sub)
        } else {
            addNode(f.node, row, f.key, depth)
        }
        box.addView(row)
    }

    private fun addNode(n: McdocForm.N, box: LinearLayout, key: String, depth: Int) {
        // McdocForm 建树时已经限制过深度，这里只是兜底，正常走不到
        if (depth > 12) {
            box.addView(label("$key（层级太深，已省略）"))
            return
        }
        when (n) {
            is McdocForm.N.Str -> {
                if (key.isNotBlank()) box.addView(label(key + if (n.reg != null) "（${n.reg} id）" else ""))
                val e = EditText(this).apply {
                    setText(n.v)
                    hint = n.hint
                    inputType = InputType.TYPE_CLASS_TEXT
                    addTextChangedListener(simple { n.v = it })
                }
                box.addView(e)
            }
            is McdocForm.N.Num -> {
                if (key.isNotBlank()) box.addView(label(key))
                box.addView(EditText(this).apply {
                    setText(n.v)
                    inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                    addTextChangedListener(simple { n.v = it })
                })
            }
            is McdocForm.N.Bool -> {
                box.addView(CheckBox(this).apply {
                    text = if (key.isBlank()) "启用" else key
                    isChecked = n.v
                    textSize = 12f
                    setOnCheckedChangeListener { _, b -> n.v = b }
                })
            }
            is McdocForm.N.Pick -> {
                if (key.isNotBlank()) box.addView(label(key))
                val opts = n.opts
                box.addView(Spinner(this).apply {
                    adapter = ArrayAdapter(
                        this@DatapackActivity,
                        android.R.layout.simple_spinner_dropdown_item, opts
                    )
                    val idx = opts.indexOf(n.v).let { if (it < 0) 0 else it }
                    setSelection(idx)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                            n.v = opts.getOrNull(i) ?: ""
                        }
                        override fun onNothingSelected(p: AdapterView<*>?) {}
                    }
                })
            }
            is McdocForm.N.Raw -> {
                if (key.isNotBlank()) box.addView(label("$key（自由 JSON）"))
                box.addView(EditText(this).apply {
                    setText(n.text)
                    minLines = 3
                    setSingleLine(false)
                    textSize = 11f
                    addTextChangedListener(simple { n.text = it })
                })
            }
            is McdocForm.N.Obj -> {
                val sub = card()
                if (key.isNotBlank()) sub.addView(label(key).apply { textSize = 13f })
                for (f in n.fields) addField(f, sub, depth + 1)
                box.addView(sub)
            }
            is McdocForm.N.Union -> {
                if (key.isNotBlank()) box.addView(label(key))
                val opts = n.opts
                val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun show(sel: String) {
                    n.v = sel
                    n.child = n.make(sel)
                    holder.removeAllViews()
                    n.child?.let { addNode(it, holder, "", depth + 1) }
                }
                box.addView(Spinner(this).apply {
                    adapter = ArrayAdapter(
                        this@DatapackActivity,
                        android.R.layout.simple_spinner_dropdown_item, opts
                    )
                    val start = n.v?.let { opts.indexOf(it) } ?: 0
                    setSelection(if (start < 0) 0 else start)
                    var last = -1
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                            if (i == last) return          // setSelection 会回调，别重复展开
                            last = i
                            show(opts.getOrNull(i) ?: return)
                        }
                        override fun onNothingSelected(p: AdapterView<*>?) {}
                    }
                    post { if (n.child == null) show(opts.getOrNull(start) ?: return@post) }
                })
                box.addView(holder)
            }
            is McdocForm.N.Arr -> {
                val sub = card()
                if (key.isNotBlank()) sub.addView(label("$key（列表）").apply { textSize = 13f })
                val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun redraw() {
                    items.removeAllViews()
                    n.items.forEachIndexed { idx, it ->
                        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        row.addView(label("第 ${idx + 1} 项"))
                        addNode(it, row, "", depth + 1)
                        row.addView(MaterialButton(this).apply {
                            text = "删除第 ${idx + 1} 项"
                            setOnClickListener {
                                n.items.removeAt(idx)
                                redraw()
                            }
                        })
                        items.addView(row)
                    }
                }
                redraw()
                sub.addView(items)
                sub.addView(MaterialButton(this).apply {
                    text = "添加一项"
                    setOnClickListener {
                        if (n.items.size >= 64) { toast("最多 64 项"); return@setOnClickListener }
                        n.items.add(n.make())
                        redraw()
                    }
                })
                box.addView(sub)
            }
        }
    }

    // ---------------------------------------------------------------- 输出

    private fun buildActions() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(MaterialButton(this).apply {
            text = "预览 JSON"
            setOnClickListener { preview() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialButton(this).apply {
            text = "写入"
            setOnClickListener { write() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        root.addView(MaterialButton(this).apply {
            text = "选择存档目录"
            setOnClickListener { pickDir.launch(null) }
        })
    }

    private fun preview() {
        val n = node ?: run { toast("先选一个生成器"); return }
        lastJson = packJson(n)
        tvOut.text = if (lastJson.length > 6000) lastJson.take(6000) + "\n…（已截断）" else lastJson
    }

    /** pack.mcmeta 这类要套一层 pack；其余直接输出 */
    private fun packJson(n: McdocForm.N): String {
        val g = gen
        val body = McdocForm.pretty(n)
        if (g != null && g.kind == DatapackCatalog.Kind.FIXED) {
            return runCatching {
                val o = JSONObject(body)
                JSONObject().put("pack", o).toString(2)
            }.getOrDefault(body)
        }
        return body
    }

    private val pickDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        treeUri = uri
        Prefs.get(this).edit().putString(K.DST_URI, uri.toString()).apply()
        toast("已选目录")
    }

    private fun write() {
        val g = gen ?: run { toast("先选一个生成器"); return }
        val n = node ?: run { toast("表单还没展开"); return }
        lastJson = packJson(n)
        if (lastJson.isBlank() || lastJson == "{}") {
            toast("内容是空的，先填点东西"); return
        }
        val uri = treeUri ?: run { toast("先选存档目录"); return }
        val pack = etPack.text.toString().trim().ifBlank { "mypack" }
        val ns = etNs.text.toString().trim().ifBlank { "mypack" }
            .lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
        val name = etName.text.toString().trim().ifBlank { "generated" }
        val tree = DocumentFile.fromTreeUri(this, uri)
            ?: run { toast("打开不了这个目录"); return }
        try {
            val rootDir: DocumentFile
            val fileName: String
            val mime: String
            val relPath: String

            if (g.kind == DatapackCatalog.Kind.FIXED) {
                // pack.mcmeta 放在数据包根，不在 data 下
                rootDir = ensureDirs(tree, listOf("datapacks", pack))
                    ?: run { toast("建不了数据包目录"); return }
                fileName = "pack.mcmeta"
                mime = "application/json"
                relPath = "datapacks/$pack/pack.mcmeta"
            } else {
                val dirs = listOf("datapacks", pack, "data", ns) + (g.dir ?: "").split("/")
                rootDir = ensureDirs(tree, dirs)
                    ?: run { toast("建不了目录"); return }
                val isFunc = g.dir == "functions"
                fileName = if (isFunc) "$name.mcfunction" else "$name.json"
                mime = if (isFunc) "text/plain" else "application/json"
                relPath = "datapacks/$pack/data/$ns/${g.dir ?: ""}/$fileName"
            }

            // 少了 pack.mcmeta 游戏会直接忽略整个包
            if (g.kind != DatapackCatalog.Kind.FIXED) {
                ensureMeta(tree, pack)
            }

            val existing = rootDir.findFile(fileName)
            val file = existing ?: rootDir.createFile(mime, fileName)
                ?: run { toast("建不了文件"); return }
            contentResolver.openOutputStream(file.uri, "wt")
                ?.use { it.write(lastJson.toByteArray()) }
                ?: run { toast("写不进去"); return }

            MaterialAlertDialogBuilder(this)
                .setTitle("已写入")
                .setMessage("$relPath\n\n在游戏里用 /datapack list 能看到它；\n" +
                    "老存档要用 /datapack enable \"file/$pack\" 启用。")
                .setPositiveButton("知道了", null)
                .show()
        } catch (t: Throwable) {
            Err.fail(t, "写数据包")
            toast("写入失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun ensureDirs(tree: DocumentFile, parts: List<String>): DocumentFile? {
        var cur: DocumentFile = tree
        for (p in parts) {
            if (p.isBlank()) continue
            cur = cur.findFile(p) ?: cur.createDirectory(p) ?: return null
        }
        return cur
    }

    private fun ensureMeta(tree: DocumentFile, pack: String) {
        runCatching {
            val d = ensureDirs(tree, listOf("datapacks", pack)) ?: return
            if (d.findFile("pack.mcmeta") != null) return
            val f = d.createFile("application/json", "pack.mcmeta") ?: return
            contentResolver.openOutputStream(f.uri, "wt")?.use {
                it.write(mcmeta(pack).toByteArray())
            }
        }
    }

    private fun mcmeta(name: String): String {
        val fmt = packFormat(etMc.text.toString().trim())
        return """{
  "pack": {
    "pack_format": $fmt,
    "description": "${name.replace("\"", "")}"
  }
}"""
    }

    /**
     * pack_format 写错的两个后果：写高了游戏提示版本不符，写低了直接不认。
     * 按版本号推一个接近的值，认不出来就用 26（1.20.5 起通用）。
     */
    private fun packFormat(v: String): Int {
        val m = Regex("""(\d+)\.(\d+)""").find(v) ?: return 26
        val major = m.groupValues[1].toIntOrNull() ?: return 26
        val minor = m.groupValues[2].toIntOrNull() ?: 0
        return when {
            major >= 2 -> 26
            major == 1 && minor >= 21 -> 34
            major == 1 && minor == 20 -> if (minor >= 5) 26 else 15
            major == 1 && minor == 19 -> 10
            major == 1 && minor == 18 -> 9
            else -> 26
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, dp(8))
        layoutParams = lp
        setBackgroundResource(R.drawable.bg_card)
        val p = dp(10)
        setPadding(p, p, p, p)
    }

    private fun simple(fn: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { fn(s?.toString() ?: "") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
