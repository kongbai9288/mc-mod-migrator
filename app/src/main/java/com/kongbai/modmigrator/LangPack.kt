package com.kongbai.modmigrator

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonParser
import java.util.Locale

/**
 * 语言拓展包。
 *
 * 放在工作目录 lang/ 下的任意 .json 文件，内容是：
 *   {"字符串名": "译文", ...}
 * 例如 {"tab_migration": "转移", "btn_search": "找模组"}。
 *
 * 覆盖优先级：语言包 > 系统语言（values-xx）> 默认中文（values）。
 * 语言包缺的键会退回默认文案，不会显示空白。
 */
object LangPack {

    /** 已加载的译文：字符串名 → 译文 */
    private val map = HashMap<String, String>()

    @Volatile
    var loadedName: String = ""
        private set

    /** 扫描工作目录 lang/ 下的所有 .json 并合并 */
    fun load(ctx: Context): Int {
        map.clear()
        loadedName = ""
        val dir = WorkDir.sub(ctx, "lang") ?: return 0
        val names = ArrayList<String>()
        for (f in dir.listFiles()) {
            val n = f.name ?: continue
            if (!n.endsWith(".json", true)) continue
            if (!f.isFile) continue
            try {
                val txt = ctx.contentResolver.openInputStream(f.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: continue
                val o = JsonParser.parseString(txt).asJsonObject
                for (k in o.keySet()) {
                    val v = o.get(k)
                    if (v == null || !v.isJsonPrimitive) continue
                    map[k] = v.asString
                }
                names.add(n)
            } catch (t: Throwable) {
                // 单个文件坏了不影响其他包
                     Err.ignore(t, "单个文件坏了不影响其他包")
                 }
        }
        if (names.isNotEmpty()) loadedName = names.joinToString("、")
        return names.size
    }

    fun has(key: String): Boolean = map.containsKey(key)

    /**
     * 用语言包包一层 Context。
     *
     * ContextWrapper.getString 是 final 不能覆盖，所以这里覆盖 getResources，
     * 返回一个改写过 getString 的 Resources。语言包没覆盖的键会退回系统资源。
     */
    fun wrap(base: Context): Context {
        if (map.isEmpty()) return base
        val res = LangResources(base.resources)
        return object : ContextWrapper(base) {
            override fun getResources(): android.content.res.Resources = res
        }
    }

    /** 只改写 getString，其余全部走系统实现 */
    private class LangResources(private val base: android.content.res.Resources) :
        android.content.res.Resources(
            base.assets, base.displayMetrics, base.configuration
        ) {

        override fun getString(id: Int): String {
            val k = keyOf(id)
            val v = if (k != null) map[k] else null
            return if (!v.isNullOrBlank()) v else try {
                base.getString(id)
            } catch (t: Throwable) {
                ""
            }
        }

        override fun getString(id: Int, vararg args: Any?): String {
            val k = keyOf(id)
            val v = if (k != null) map[k] else null
            return if (!v.isNullOrBlank()) {
                try {
                    String.format(Locale.getDefault(), v, *args)
                } catch (t: Throwable) {
                    v
                }
            } else try {
                base.getString(id, *args)
            } catch (t: Throwable) {
                ""
            }
        }

        private fun keyOf(id: Int): String? {
            return try {
                val n = base.getResourceName(id)
                n.substring(n.indexOf('/') + 1)
            } catch (t: Throwable) {
                null
            }
        }
    }

    /** 导出一份当前语言的模板，方便用户照着做自己的语言包 */
    fun template(ctx: Context): String {
        val fields = R.string::class.java.fields
        val o = com.google.gson.JsonObject()
        for (f in fields) {
            val id = try {
                f.getInt(null)
            } catch (t: Throwable) {
                continue
            }
            val txt = try {
                ctx.getString(id)
            } catch (t: Throwable) {
                continue
            }
            o.addProperty(f.name, txt)
        }
        return o.toString()
    }

    fun writeTemplate(ctx: Context) {
        try {
            val dir = WorkDir.sub(ctx, "lang") ?: return
            val f = dir.createFile("application/json", "template.json") ?: return
            ctx.contentResolver.openOutputStream(f.uri)?.use {
                it.write(template(ctx).toByteArray(Charsets.UTF_8))
            }
        } catch (t: Throwable) { Err.ignore(t, "it.write(template(ctx).toByteArray(Charsets.UTF_8)") }
    }
}
