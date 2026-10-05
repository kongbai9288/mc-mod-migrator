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
     * ⚠️ 目录结构错了游戏**不会报错**，只是整个包不生效 —— 所以这里必须严格按
     * `<world>/datapacks/<包名>/data/<命名空间>/<类型>/<名字>.json` 来放。
     *
     * 之前只写 `data/generated.json`：既没有命名空间也没有类型目录，
     * 游戏里压根扫不到，表现就是"写不出来数据包"。
     *
     * 另一处：存档目录可能是 SAF 授权的 content://，
     * 用 File() 直接拼路径会一步都走不动，这里两条路都铺了。
     */
    private fun writeToWorld() {
        val ctx = this
        val game = Prefs.get(ctx).getString(K.GAME_DIR, "") ?: ""
        val worlds = LinkedHashMap<String, Any>()
        if (game.isNotBlank() && !game.startsWith("content://")) {
            val saves = File(game, "saves")
            if (saves.isDirectory) {
                saves.listFiles()?.filter { it.isDirectory }?.forEach { worlds[it.name] = it }
            }
        }
        // SAF：拿不到 File，只能拿 DocumentFile
        val safUri = game.takeIf { it.startsWith("content://") }
        if (safUri != null) {
            val tree = Fs.tree(ctx, safUri)
            val saves = tree?.findFile("saves") ?: tree
            saves?.listFiles()?.forEach { if (it.isDirectory) worlds[it.name ?: "?"] = it }
        }
        if (worlds.isEmpty()) {
            Toast.makeText(
                ctx,
                "没找到存档。先在设置 → 存储里把游戏目录指到 .minecraft",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val names = worlds.keys.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("写到哪个存档")
            .setItems(names) { _, which ->
                askNamespace(ctx, names[which], worlds[names[which]]!!)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 命名空间 + 类型 + 文件名。这三样决定了游戏能不能扫到。 */
    private fun askNamespace(ctx: android.content.Context, worldName: String, world: Any) {
        val etNs = android.widget.EditText(ctx).apply {
            setText("mymod"); setSingleLine(true)
        }
        val etId = android.widget.EditText(ctx).apply {
            setText(suggestId()); setSingleLine(true)
        }
        val types = arrayOf(
            "loot_tables", "advancements", "recipes", "predicates",
            "item_modifiers", "damage_types", "tags/blocks", "tags/items",
            "functions", "structures"
        )
        var typeIdx = types.indexOf(guessType()).let { if (it < 0) 0 else it }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(TextView(ctx).apply { text = "命名空间（小写，不要空格）" })
            addView(etNs)
            addView(TextView(ctx).apply { text = "文件类型（决定放在哪个目录下）" })
        }
        val tvType = TextView(ctx).apply { text = types[typeIdx] }
        box.addView(tvType)
        box.addView(MaterialButton(ctx).apply {
            text = "换一个类型"
            setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("文件类型")
                    .setItems(types) { _, i -> typeIdx = i; tvType.text = types[i] }
                    .show()
            }
        })
        box.addView(TextView(ctx).apply { text = "文件名（不含扩展名）" })
        box.addView(etId)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("写入 $worldName")
            .setView(box)
            .setPositiveButton("写入") { _, _ ->
                val ns = etNs.text.toString().trim().ifBlank { "mymod" }
                val id = etId.text.toString().trim().ifBlank { "generated" }
                if (!ns.matches(Regex("[a-z0-9_.-]+")) || !id.matches(Regex("[a-z0-9_./-]+"))) {
                    Toast.makeText(
                        ctx, "命名空间和文件名只能用小写字母、数字、下划线、点、连字符",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                doWrite(world, ns, types[typeIdx], id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 从生成器标题猜类型，猜不中就让用户选 */
    private fun guessType(): String {
        val t = title?.toString() ?: ""
        return when {
            t.contains("Loot Table", true) -> "loot_tables"
            t.contains("Advancement", true) -> "advancements"
            t.contains("Recipe", true) -> "recipes"
            t.contains("Predicate", true) -> "predicates"
            t.contains("Item Modifier", true) -> "item_modifiers"
            t.contains("Damage", true) -> "damage_types"
            t.contains("Block Tag", true) -> "tags/blocks"
            t.contains("Item Tag", true) -> "tags/items"
            else -> "loot_tables"
        }
    }

    private fun suggestId(): String {
        val t = title?.toString() ?: ""
        val slug = t.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return slug.ifBlank { "generated" }
    }

    private fun doWrite(world: Any, ns: String, type: String, id: String) {
        Thread {
            val msg = try {
                val body = lastOutput.ifBlank { "{}" }
                val ext = if (type == "functions") ".mcfunction" else ".json"
                val rel = "datapacks/${safePackName()}/data/$ns/$type/$id$ext"
                val metaRel = "datapacks/${safePackName()}/pack.mcmeta"
                val meta = """
                {
                  "pack": {
                    "pack_format": ${'$'}{packFormat()},
                    "description": "由 ModMigrator 生成"
                  }
                }
                """.trimIndent()
                when (world) {
                    is File -> {
                        val f = File(world, rel)
                        f.parentFile?.mkdirs()
                        f.writeText(body)
                        val mf = File(world, metaRel)
                        if (!mf.exists()) {
                            mf.parentFile?.mkdirs()
                            mf.writeText(meta)
                        }
                        "已写入：${f.absolutePath}"
                    }
                    is androidx.documentfile.provider.DocumentFile -> {
                        writeSaf(world, rel, body)
                        writeSaf(world, metaRel, meta)
                        "已写入：$rel"
                    }
                    else -> "不支持的存档类型"
                }
            } catch (t: Throwable) {
                Err.fail(t, "写入数据包")
                "写入失败：${t.message ?: t.javaClass.simpleName}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    /**
     * pack_format 写死 26 是不对的：这个值随版本变，
     * 填高了游戏会提示"用更高版本创建"，填低了直接不认。
     * 这里按当前存档/游戏版本推一个，推不出来再给个常见值。
     */
    private fun packFormat(): Int {
        val v = Prefs.get(this).getString(K.MC_VERSION, "") ?: ""
        val m = Regex("(\\d+)\\.(\\d+)").find(v)
        val minor = m?.groupValues?.get(2)?.toIntOrNull()
        return when {
            minor == null -> 26
            minor >= 21 -> 26
            minor == 20 -> 26
            minor == 19 -> 12
            minor == 18 -> 10
            minor == 17 -> 8
            minor == 16 -> 6
            else -> 5
        }
    }

    private fun safePackName(): String {
        val t = title?.toString()?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')
        return ("mm_" + (t?.take(24) ?: "pack")).ifBlank { "mm_pack" }
    }

    private fun writeSaf(root: androidx.documentfile.provider.DocumentFile, rel: String, body: String) {
        var cur = root
        val parts = rel.split("/")
        for (i in 0 until parts.lastIndex) {
            val next = cur.findFile(parts[i]) ?: cur.createDirectory(parts[i])
            cur = next ?: throw IllegalStateException("建不了目录 ${parts[i]}")
        }
        val f = cur.findFile(parts.last()) ?: cur.createFile("application/json", parts.last())
            ?: throw IllegalStateException("建不了文件 ${parts.last()}")
        contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(body.toByteArray()) }
            ?: throw IllegalStateException("打不开 ${parts.last()} 写入")
    }

    override fun onDestroy() {
        // 不 destroy 的话 WebView 会一直持有 Activity，反复进出必内存泄漏
        runCatching { misode?.destroy() }
        misode = null
        super.onDestroy()
    }
}
