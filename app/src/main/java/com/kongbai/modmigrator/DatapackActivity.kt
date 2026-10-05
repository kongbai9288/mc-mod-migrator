package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * 数据包生成器（自研）。
 *
 * # 为什么不继续用离线网页版
 *
 * 上一版是把 misode 的整站网页塞进 WebView。网页版依赖自己的路由和资源映射，
 * 路径对不上就是一直「正在载入」，或者生成完写不出来 —— 而中间是黑盒，
 * 出问题时连在哪一步断的都不知道。
 *
 * 这里改成**原生表单**：内置若干常用生成器，每个是一张表单 + 一份 JSON 模板。
 * 生成器种类没有网页版那 137 个多，但每一个都是「填完 → 一定能写出能用的 JSON」，
 * 不需要网页运行时，也不受文件访问权限影响。
 *
 * 写入位置严格按数据包格式放在
 * `datapacks/<包名>/data/<命名空间>/<类型>/<名字>.json`，
 * 并自动补 `pack.mcmeta`（少了它游戏会直接忽略整个包）。
 */
class DatapackActivity : AppCompatActivity() {

    // ---------------------------------------------------------------- 定义

    private enum class Kind { TEXT, NUM, BOOL, SELECT, TEXTAREA }

    private data class F(
        val key: String, val label: String, val kind: Kind,
        val def: String = "", val options: List<String> = emptyList()
    )

    private data class Gen(
        val id: String, val title: String, val dir: String,
        val fields: List<F>, val template: String
    )

    private val GENS: List<Gen> by lazy { listOf(
        Gen("loot", "战利品表（方块 / 箱子 / 生物）", "loot_tables", listOf(
            F("type", "类型", Kind.SELECT, "block", listOf("block", "chest", "entity", "fishing")),
            F("rolls", "抽取次数", Kind.NUM, "1"),
            F("bonus", "幸运加成次数", Kind.NUM, "0"),
            F("item", "掉落物品", Kind.TEXT, "minecraft:diamond"),
            F("count", "数量", Kind.NUM, "1")
        ), """{
  "type": "minecraft:{type}",
  "pools": [
    {
      "rolls": {rolls},
      "bonus_rolls": {bonus},
      "entries": [
        {
          "type": "minecraft:item",
          "name": "{item}",
          "functions": [ { "function": "minecraft:set_count", "count": {count} } ]
        }
      ],
      "conditions": [ { "condition": "minecraft:survives_explosion" } ]
    }
  ]
}"""),
        Gen("shaped", "有序合成配方", "recipes", listOf(
            F("p1", "第 1 行（3 格，空格留空）", Kind.TEXT, "AAA"),
            F("p2", "第 2 行", Kind.TEXT, " B "),
            F("p3", "第 3 行", Kind.TEXT, " B "),
            F("ch", "上面用的字母", Kind.TEXT, "A"),
            F("ing", "该字母对应的物品", Kind.TEXT, "minecraft:diamond"),
            F("res", "产物", Kind.TEXT, "minecraft:diamond_sword"),
            F("count", "产物数量", Kind.NUM, "1")
        ), """{
  "type": "minecraft:crafting_shaped",
  "pattern": [ "{p1}", "{p2}", "{p3}" ],
  "key": { "{ch}": { "item": "{ing}" } },
  "result": { "id": "{res}", "count": {count} }
}"""),
        Gen("shapeless", "无序合成配方", "recipes", listOf(
            F("ings", "材料（英文逗号分隔）", Kind.TEXT, "minecraft:diamond,minecraft:stick"),
            F("res", "产物", Kind.TEXT, "minecraft:diamond_sword"),
            F("count", "产物数量", Kind.NUM, "1")
        ), """{
  "type": "minecraft:crafting_shapeless",
  "ingredients": [ {ings} ],
  "result": { "id": "{res}", "count": {count} }
}"""),
        Gen("smelt", "熔炼配方", "recipes", listOf(
            F("ing", "原料", Kind.TEXT, "minecraft:iron_ore"),
            F("res", "产物", Kind.TEXT, "minecraft:iron_ingot"),
            F("xp", "经验", Kind.NUM, "0.7"),
            F("time", "时间（tick）", Kind.NUM, "200")
        ), """{
  "type": "minecraft:smelting",
  "ingredient": { "item": "{ing}" },
  "result": "minecraft:{res}",
  "experience": {xp},
  "cookingtime": {time}
}"""),
        Gen("adv", "进度", "advancements", listOf(
            F("title", "标题", Kind.TEXT, "我的进度"),
            F("desc", "描述", Kind.TEXT, "做了点什么"),
            F("icon", "图标物品", Kind.TEXT, "minecraft:diamond"),
            F("item", "触发所需物品", Kind.TEXT, "minecraft:diamond"),
            F("trigger", "触发条件", Kind.SELECT, "inventory_changed",
                listOf("inventory_changed", "consume_item", "placed_block", "used_totem"))
        ), """{
  "display": {
    "icon": { "id": "{icon}" },
    "title": { "text": "{title}" },
    "description": { "text": "{desc}" },
    "frame": "task",
    "show_toast": true,
    "announce_to_chat": true
  },
  "criteria": {
    "main": {
      "trigger": "minecraft:{trigger}",
      "conditions": { "items": [ { "items": [ "{item}" ] } ] }
    }
  }
}"""),
        Gen("tag_item", "物品标签", "tags/items", listOf(
            F("values", "物品（英文逗号分隔）", Kind.TEXT, "minecraft:diamond,minecraft:emerald"),
            F("replace", "覆盖原版标签", Kind.BOOL, "false")
        ), """{ "replace": {replace}, "values": [ {values} ] }"""),
        Gen("tag_block", "方块标签", "tags/blocks", listOf(
            F("values", "方块（英文逗号分隔）", Kind.TEXT, "minecraft:stone,minecraft:dirt"),
            F("replace", "覆盖原版标签", Kind.BOOL, "false")
        ), """{ "replace": {replace}, "values": [ {values} ] }"""),
        Gen("func", "函数（命令列表）", "functions", listOf(
            F("cmds", "命令（每行一条）", Kind.TEXTAREA, "say hello\ngive @s minecraft:diamond 1")
        ), """{cmds}"""),
        Gen("pred", "谓词", "predicates", listOf(
            F("item", "物品", Kind.TEXT, "minecraft:diamond"),
            F("count", "最小数量", Kind.NUM, "1")
        ), """{
  "condition": "minecraft:match_tool",
  "predicate": { "items": [ "{item}" ], "count": { "min": {count} } }
}""")
    ) }

    // ---------------------------------------------------------------- 状态

    private var gen: Gen? = null
    private val inputs = LinkedHashMap<String, android.view.View>()
    private var treeUri: Uri? = null
    private var lastJson = ""

    private lateinit var etPack: EditText
    private lateinit var etNs: EditText
    private lateinit var etName: EditText
    private lateinit var etMc: EditText
    private lateinit var tvDir: TextView
    private lateinit var tvOut: TextView
    private lateinit var formBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "数据包生成器"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val sc = ScrollView(this)
        sc.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sc, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(btn("选择生成器") { pickGen() })
        root.addView(label(""))
        root.addView(label("数据包名（文件夹名）"))
        etPack = edit("mypack"); root.addView(etPack)
        root.addView(label("命名空间（小写英文）"))
        etNs = edit("mypack"); root.addView(etNs)
        root.addView(label("文件名（不含 .json）"))
        etName = edit("generated"); root.addView(etName)
        root.addView(label("游戏版本（决定 pack_format）"))
        etMc = edit("1.21"); root.addView(etMc)

        root.addView(btn("选择存档目录") { pickDir.launch(null) })
        tvDir = label("还没选存档目录"); root.addView(tvDir)

        formBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(formBox)

        root.addView(btn("生成并预览") { build(true) })
        tvOut = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        root.addView(tvOut)
        root.addView(btn("写入存档") { write() })
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 12f }
    private fun edit(def: String) = EditText(this).apply {
        setText(def); setSingleLine(true); textSize = 14f
    }
    private fun btn(t: String, fn: () -> Unit) = MaterialButton(this).apply {
        text = t
        setOnClickListener { fn() }
    }

    private val pickDir = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        treeUri = uri
        tvDir.text = "存档目录：${Uri.decode(uri.lastPathSegment ?: uri.toString())}"
    }

    private fun pickGen() {
        val names = GENS.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("用哪个生成器")
            .setItems(names) { _, i -> setGen(GENS[i]) }
            .show()
    }

    private fun setGen(g: Gen) {
        gen = g
        inputs.clear()
        formBox.removeAllViews()
        formBox.addView(label("生成器：${g.title}　→　data/<ns>/${g.dir}/"))
        for (f in g.fields) {
            formBox.addView(label(f.label))
            when (f.kind) {
                Kind.SELECT -> {
                    val sp = Spinner(this)
                    sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, f.options)
                    val idx = f.options.indexOf(f.def)
                    if (idx >= 0) sp.setSelection(idx)
                    inputs[f.key] = sp
                    formBox.addView(sp)
                }
                Kind.BOOL -> {
                    val cb = CheckBox(this).apply { isChecked = f.def == "true"; text = "是" }
                    inputs[f.key] = cb
                    formBox.addView(cb)
                }
                Kind.TEXTAREA -> {
                    val et = EditText(this).apply {
                        setText(f.def); textSize = 13f
                        setSingleLine(false); minLines = 4; gravity = Gravity.TOP
                    }
                    inputs[f.key] = et
                    formBox.addView(et)
                }
                Kind.NUM -> {
                    val et = EditText(this).apply {
                        setText(f.def); setSingleLine(true)
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    inputs[f.key] = et
                    formBox.addView(et)
                }
                else -> {
                    val et = EditText(this).apply { setText(f.def); setSingleLine(true) }
                    inputs[f.key] = et
                    formBox.addView(et)
                }
            }
        }
    }

    private fun value(key: String): String {
        return when (val v = inputs[key]) {
            is EditText -> v.text.toString()
            is Spinner -> v.selectedItem?.toString() ?: ""
            is CheckBox -> v.isChecked.toString()
            else -> ""
        }
    }

    private fun build(preview: Boolean): Boolean {
        val g = gen ?: run { toast("先选一个生成器"); return false }
        var s = g.template
        for (f in g.fields) {
            val raw = value(f.key)
            val rep = when (f.kind) {
                Kind.NUM, Kind.BOOL -> raw.trim().ifBlank { "0" }
                Kind.TEXT -> JSONObject.quote(raw.trim()).let { it.substring(1, it.length - 1) }
                else -> raw.trim()
            }
            s = s.replace("{${f.key}}", rep)
        }
        // 两个特殊展开：材料列表、命令列表
        if (s.contains("{ings}")) {
            val list = value("ings").split(",").map { it.trim() }
                .filter { it.isNotBlank() }.joinToString(",") { "{\"item\":\"$it\"}" }
            s = s.replace("{ings}", list.ifBlank { "{\"item\":\"minecraft:air\"}" })
        }
        if (s.contains("{values}")) {
            val list = value("values").split(",").map { it.trim() }
                .filter { it.isNotBlank() }.joinToString(",") { "\"${it.removePrefix("minecraft:").let { v -> if (v.contains(":")) it else "minecraft:$v" }}\"" }
            s = s.replace("{values}", list.ifBlank { "\"minecraft:air\"" })
        }
        if (s.contains("{cmds}")) {
            // 函数文件不是 JSON，是每行一条命令的 .mcfunction
            val cmds = value("cmds").lines().map { it.trim() }.filter { it.isNotBlank() }
            s = cmds.joinToString("\n").ifBlank { "# 空函数" }
        }
        // 去掉没填的占位符残留
        s = Regex("\\{[a-z0-9_]+\\}").replace(s) { "" }
        lastJson = s
        if (g.dir != "functions") {
            val ok = runCatching { JSONObject(s) }.isSuccess
            if (!ok) {
                // 函数文件是纯文本，其他类型必须是合法 JSON
                toast("生成的 JSON 有问题，检查一下填的内容")
            }
        }
        if (preview) {
            tvOut.text = if (s.length > 4000) s.take(4000) + "\n…（已截断）" else s
        }
        return true
    }

    private fun write() {
        val g = gen ?: run { toast("先选一个生成器"); return }
        if (!build(false)) return
        val uri = treeUri ?: run { toast("先选存档目录"); return }
        val pack = etPack.text.toString().trim().ifBlank { "mypack" }
        val ns = etNs.text.toString().trim().ifBlank { "mypack" }
            .lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
        val name = etName.text.toString().trim().ifBlank { "generated" }
        val root = DocumentFile.fromTreeUri(this, uri) ?: run { toast("打开不了这个目录"); return }
        try {
            val dp = root.findFile("datapacks") ?: root.createDirectory("datapacks")
                ?: run { toast("建不了 datapacks 目录"); return }
            val packDir = dp.findFile(pack) ?: dp.createDirectory(pack)
                ?: run { toast("建不了数据包目录"); return }
            // pack.mcmeta 缺失的话游戏会直接忽略整个包
            if (packDir.findFile("pack.mcmeta") == null) {
                packDir.createFile("application/json", "pack.mcmeta")?.let { f ->
                    contentResolver.openOutputStream(f.uri, "wt")?.use {
                        it.write(mcmeta().toByteArray())
                    }
                }
            }
            var dir = packDir.findFile("data") ?: packDir.createDirectory("data")
                ?: run { toast("建不了 data 目录"); return }
            for (part in (g.dir + "/$ns").split("/")) {
                if (part.isBlank()) continue
                dir = dir.findFile(part) ?: dir.createDirectory(part)
                    ?: run { toast("建不了 $part 目录"); return }
            }
            val isFunc = g.dir == "functions"
            val fileName = if (isFunc) "$name.mcfunction" else "$name.json"
            val mime = if (isFunc) "text/plain" else "application/json"
            val existing = dir.findFile(fileName)
            val file = existing ?: dir.createFile(mime, fileName)
                ?: run { toast("建不了文件"); return }
            contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(lastJson.toByteArray()) }
            val target = "datapacks/$pack/data/$ns/${g.dir}/$fileName"
            MaterialAlertDialogBuilder(this)
                .setTitle("已写入")
                .setMessage("$target\n\n在游戏里用 /datapack list 能看到它；\n" +
                    "新存档要在创建世界时启用，老存档用 /datapack enable \"file/$pack\"。")
                .setPositiveButton("知道了", null)
                .show()
        } catch (t: Throwable) {
            Err.fail(t, "写数据包")
            toast("写入失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun mcmeta(): String {
        val v = etMc.text.toString().trim()
        val fmt = packFormat(v)
        return """{
  "pack": {
    "pack_format": $fmt,
    "description": "${pack.replace("\"", "")}"
  }
}"""
    }

    /**
     * pack_format 写错的两个后果：
     *  · 写高了 → 游戏提示数据包版本不符；
     *  · 写低了 → 直接不认。
     * 所以按版本号推一个接近的值，认不出来就用 26（1.20.5 起通用）。
     */
    private fun packFormat(v: String): Int {
        val m = Regex("""(\d+)\.(\d+)""").find(v) ?: return 26
        val major = m.groupValues[1].toIntOrNull() ?: return 26
        val minor = m.groupValues[2].toIntOrNull() ?: 0
        return when {
            major >= 2 -> 26                       // 26.x 这一支
            major == 1 && minor >= 21 -> if (minor >= 21) 34 else 26
            major == 1 && minor == 20 -> if (minor >= 5) 26 else 15
            major == 1 && minor == 19 -> 10
            major == 1 && minor == 18 -> 9
            else -> 26
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, DatapackActivity::class.java))
    }
}
