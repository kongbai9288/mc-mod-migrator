package com.kongbai.modmigrator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 目录库：引导页里多选出来的「迁移前 / 迁移后」目录列表。
 *
 * ## 为什么要单独一个文件
 *
 * 引导页（`FirstRunActivity`）让用户**每个列表都能加多个目录**，
 * 也确实把两份列表存进了 Prefs。但这两份列表**没有任何地方读**：
 *
 * - `finishSetup()` 里只把每类的第一个填进 `K.SRC_URI` / `K.DST_URI`，
 *   注释自己写着「迁移页目前还是单选」。
 * - 全项目搜 `fir_src_dirs` / `fir_dst_dirs`，除引导页外零引用。
 *
 * 结果就是：用户按引导多选了三个旧版本目录，进主页后只剩第一个，
 * 另外两个凭空消失 —— 这正是需求文档里「多选、分批」要的效果没落地。
 *
 * 这个文件把两份列表的读写收在一处，引导页负责写、迁移页负责读，
 * 两边不再各算一套。以后真要支持「一次迁多对目录」，列表的读取
 * 也从这里出，不用再改解析逻辑。
 *
 * ## 存成 JSON 数组而不是逗号拼接
 *
 * 目录名里可能有逗号，SAF 的 URI 里有 `%2F` 这类转义，
 * 逗号拼接会在下一次读回来的时候错位。
 */
object DirLib {

    /** 迁移前目录列表 */
    const val P_SRC = "fir_src_dirs"

    /** 迁移后目录列表 */
    const val P_DST = "fir_dst_dirs"

    /** 列表里的一项。名字是给界面看的，uri 才是唯一标识。 */
    data class Item(val name: String, val uri: String)

    /**
     * 读一份列表。
     *
     * 存的内容可能是旧版本写的、被手改过的、或半截的，
     * 所以每一层都用 opt* + try 兜住，读不出来就当空列表，
     * **不让一条坏数据把整个页面搞崩**。
     */
    fun read(ctx: Context, key: String): List<Item> {
        val raw = Prefs.get(ctx).getString(key, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            val out = ArrayList<Item>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val uri = o.optString("uri", "")
                if (uri.isBlank()) continue
                val name = o.optString("name", "").ifBlank { DirGuide.human(uri) }
                out.add(Item(name, uri))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 整份覆盖写。引导页最后一步用：界面上是什么样就存什么样。 */
    fun write(ctx: Context, key: String, list: List<Item>) {
        val a = JSONArray()
        for (d in list) {
            a.put(JSONObject().apply {
                put("name", d.name)
                put("uri", d.uri)
            })
        }
        Prefs.get(ctx).edit().putString(key, a.toString()).apply()
    }

    /**
     * 加一项。按 uri 去重 —— 同一个目录加两次没有意义，
     * 只会让列表看着变长，用户还以为加成功了两个。
     *
     * @return 真的加进去了返回 true；已存在返回 false。
     */
    fun add(ctx: Context, key: String, item: Item): Boolean {
        val cur = read(ctx, key)
        if (cur.any { it.uri == item.uri }) return false
        write(ctx, key, cur + item)
        return true
    }

    /** 移除一项。按 uri 匹配，名字可能重名但 uri 不会。 */
    fun remove(ctx: Context, key: String, uri: String) {
        write(ctx, key, read(ctx, key).filter { it.uri != uri })
    }

    /**
     * 这项在不在列表里。界面用它决定显示「加入列表」还是「已在列表」。
     */
    fun has(ctx: Context, key: String, uri: String): Boolean =
        uri.isNotBlank() && read(ctx, key).any { it.uri == uri }

    /**
     * 界面上显示的一行：名字 + 可读路径。
     *
     * 只显示名字会分不清两个同名目录（比如两个启动器都有「1.20.1」），
     * 只显示路径又太长，所以两个都给。
     */
    fun label(item: Item): String {
        val p = DirGuide.human(item.uri)
        return if (item.name.isBlank()) p
        else if (p.isBlank()) item.name
        else "${item.name}\n$p"
    }

    /** 这份列表有多少项 */
    fun size(ctx: Context, key: String): Int = read(ctx, key).size
}
