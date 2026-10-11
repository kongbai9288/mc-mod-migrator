package com.kongbai.modmigrator

import org.json.JSONArray
import org.json.JSONObject

/**
 * mcdoc 类型 → 表单 → JSON。
 *
 * 这是数据包生成器的核心：把一份 schema 类型定义递归展开成可编辑的表单，
 * 再把表单回填成 JSON。所有生成器共用这一套逻辑，新增一个类型不需要写代码。
 *
 * ## 防爆炸
 *
 * 有些类型是自引用的（density_function 里可以再嵌 density_function），
 * 无条件递归会直接把栈打穿。三条限制：
 *
 *  1. [MAX_DEPTH] 硬上限，超了给一个自由文本（Raw）让用户手写
 *  2. 同一条引用链上出现过的类型不再展开（seen 集合）
 *  3. optional 字段默认**不展开**，要点开才加 —— 大部分自引用都藏在 optional 里
 */
object McdocForm {

    private const val MAX_DEPTH = 7

    // ---------------------------------------------------------------- 节点

    /** 表单节点。UI 和 JSON 输出都按类型在这里分支，没有第二种表示 */
    sealed class N {
        /** 是否启用。optional 字段默认 false，用户勾上才写进 JSON */
        abstract var on: Boolean

        class Str(
            var v: String = "",
            /** 资源 ID 的 registry（item / block / ...），用于提示该填什么 */
            val reg: String? = null,
            var hint: String = "",
            override var on: Boolean = true
        ) : N()

        class Num(var v: String = "", val int: Boolean = true, override var on: Boolean = true) : N()

        class Bool(var v: Boolean = false, override var on: Boolean = true) : N()

        /** 单选项。opts 是候选值，allowAny 时也可以手填 */
        class Pick(
            val opts: List<String>,
            var v: String = "",
            val allowAny: Boolean = false,
            override var on: Boolean = true
        ) : N()

        class Obj(val fields: MutableList<F>, override var on: Boolean = true) : N()

        /** 多选一。选中后 child 是那个变体的表单 */
        class Union(
            val opts: List<String>,
            var v: String? = null,
            var child: N? = null,
            /** 变体的类型定义，选中的时候才展开 */
            val make: (String) -> N? = { null },
            override var on: Boolean = true
        ) : N()

        class Arr(val make: () -> N, val items: MutableList<N> = ArrayList(), override var on: Boolean = true) : N()

        /** 兜底：类型太深或认不出来，直接给一段自由 JSON */
        class Raw(var text: String = "{}", override var on: Boolean = true) : N()
    }

    /** 对象里的一个字段 */
    class F(val key: String, val node: N, val opt: Boolean, val desc: String = "")

    // ---------------------------------------------------------------- 构建

    /** 从类型路径建一棵表单。path 找不到时返回 null */
    fun build(ctx: android.content.Context, path: String, ver: String): N? {
        val t = McdocSchema.type(path) ?: return null
        return buildType(t, ver, 0, HashSet(), ctx)
    }

    private fun buildType(
        t: JSONObject?,
        ver: String,
        depth: Int,
        seen: MutableSet<String>,
        ctx: android.content.Context
    ): N {
        if (t == null) return N.Raw()
        if (depth > MAX_DEPTH) return N.Raw()

        return when (t.optString("kind")) {

            // struct：一组字段
            "struct" -> {
                val fields = ArrayList<F>()
                collectFields(t.optJSONArray("fields"), fields, ver, depth, seen, ctx)
                N.Obj(fields)
            }

            // union：多选一
            "union" -> {
                val members = t.optJSONArray("members") ?: JSONArray()
                val opts = ArrayList<String>()
                for (i in 0 until members.length()) {
                    val m = members.optJSONObject(i) ?: continue
                    val p = m.optString("path", null) ?: continue
                    opts.add(nameOf(p))
                }
                makeUnion(opts, ver, depth, seen, ctx) { key ->
                    val hit = (0 until members.length())
                        .map { members.optJSONObject(it) }
                        .firstOrNull { nameOf(it?.optString("path") ?: "") == key }
                    val p = hit?.optString("path")
                    val def = McdocSchema.type(p)
                    if (def == null) null
                    else childWithType(buildType(def, ver, depth + 1, child(seen, p), ctx), key)
                }
            }

            // dispatcher：按 registry 查分发表，本质也是多选一
            "dispatcher" -> {
                val reg = t.optString("registry")
                val d = McdocSchema.dispatcher(reg)
                if (d == null) N.Raw() else {
                    val names = d.keys().asSequence().toList()
                        .filter { !it.startsWith("%") }
                        .filter { McdocSchema.versionOk(
                            d.optJSONObject(it)?.optJSONArray("attributes"), ver) }
                        .sorted()
                    makeUnion(names, ver, depth, seen, ctx) { key ->
                        val e = d.optJSONObject(key) ?: return@makeUnion null
                        val p = e.optString("path", null)
                        val def = McdocSchema.type(p)
                        if (def == null) null
                        else childWithType(buildType(def, ver, depth + 1, child(seen, p), ctx), key)
                    }
                }
            }

            // reference：跳到另一个类型
            "reference" -> {
                val p = t.optString("path", null)
                if (p.isNullOrBlank()) return N.Raw()
                // 自引用/循环：不再展开，给一个手写框
                if (p in seen) return N.Raw(text = "{}", on = false)
                val def = McdocSchema.type(p)
                buildType(def, ver, depth + 1, child(seen, p), ctx)
            }

            // concrete / template：真正的内容在 child
            "concrete", "template" ->
                buildType(t.optJSONObject("child"), ver, depth + 1, seen, ctx)

            "list" -> {
                val item = t.optJSONObject("item")
                N.Arr(make = { buildType(item, ver, depth + 1, HashSet(seen), ctx) })
            }

            "string" -> {
                val reg = idRegistry(t.optJSONArray("attributes"))
                N.Str(reg = reg, hint = if (reg != null) "$reg id" else "")
            }

            "boolean" -> N.Bool()
            "byte", "short", "int", "long" -> N.Num(int = true)
            "float", "double" -> N.Num(int = false)

            "literal" -> {
                val v = McdocSchema.litString(t.opt("value")) ?: ""
                N.Pick(listOf(v), v)
            }

            "enum" -> {
                // enum 通常出现在 reference 指向的类型上，这里一并处理
                val vals = t.optJSONArray("values") ?: JSONArray()
                val opts = ArrayList<String>()
                for (i in 0 until vals.length()) {
                    val e = vals.optJSONObject(i) ?: continue
                    McdocSchema.litString(e.opt("value"))?.let { opts.add(it) }
                }
                if (opts.isEmpty()) N.Str() else N.Pick(opts, opts.first())
            }

            "any", "unsafe", "index" -> N.Raw()

            else -> N.Raw()
        }
    }

    private fun child(seen: Set<String>, p: String?): MutableSet<String> {
        val s = HashSet(seen)
        if (p != null) s.add(p)
        return s
    }

    /**
     * 变体选中后，往它的 JSON 里补 `type` 字段。
     * dispatcher 的 key 不带命名空间（all_of），游戏里要写成 minecraft:all_of。
     */
    private fun childWithType(node: N, key: String): N {
        if (node !is N.Obj) return node
        val typeField = node.fields.firstOrNull { it.key == "type" }
        val value = if (key.contains(':')) key else "minecraft:$key"
        if (typeField != null) {
            if (typeField.node is N.Pick) typeField.node.v = value
            else if (typeField.node is N.Str) typeField.node.v = value
            typeField.node.on = true
        } else {
            // 结构里没声明 type 字段，插入一个隐藏的常量
            node.fields.add(0, F("type", N.Pick(listOf(value), value), false))
        }
        return node
    }

    private fun makeUnion(
        opts: List<String>,
        ver: String,
        depth: Int,
        seen: MutableSet<String>,
        ctx: android.content.Context,
        make: (String) -> N?
    ): N {
        if (opts.isEmpty()) return N.Raw()
        return N.Union(opts, null, null, make)
    }

    /** 收集 struct 的字段；spread 会把另一个结构的字段摊进来 */
    private fun collectFields(
        arr: JSONArray?,
        out: MutableList<F>,
        ver: String,
        depth: Int,
        seen: MutableSet<String>,
        ctx: android.content.Context
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val kind = f.optString("kind")
            val attrs = f.optJSONArray("attributes")
            if (!McdocSchema.versionOk(attrs, ver)) continue

            if (kind == "spread") {
                val t = f.opt("type")
                val p = (t as? JSONObject)?.optString("path", null)
                val def = McdocSchema.type(p)
                if (def != null && def.optString("kind") == "struct") {
                    collectFields(def.optJSONArray("fields"), out, ver, depth, seen, ctx)
                }
                continue
            }

            val key = f.optString("key", null) ?: continue
            val opt = f.optBoolean("optional", false)
            val desc = f.optString("desc", "")
            val node = buildType(f.optJSONObject("type"), ver, depth + 1, seen, ctx)
            // optional 字段默认关掉，用户点开才加 —— 否则自引用类型会一路展开下去
            node.on = !opt
            out.add(F(key, node, opt, desc))
        }
    }

    /** attributes 里的 id 表示这是一个资源 ID，可能带 registry（item / block ...） */
    private fun idRegistry(attrs: JSONArray?): String? {
        if (attrs == null) return null
        for (i in 0 until attrs.length()) {
            val a = attrs.optJSONObject(i) ?: continue
            if (a.optString("name") != "id") continue
            val v = a.opt("value")
            if (v is JSONObject) {
                val tree = v.optJSONObject("values")?.optJSONObject("registry")
                val r = McdocSchema.litString(tree)
                if (r != null) return r
            }
            return "id"
        }
        return null
    }

    /** `::java::data::loot::condition::AllOf` → `AllOf` */
    private fun nameOf(path: String): String = path.substringAfterLast("::")

    // ---------------------------------------------------------------- 输出

    /** 表单 → 可直接写文件的 JSON 对象 */
    fun toJson(n: N): Any? = when (n) {
        is N.Obj -> {
            val o = JSONObject()
            for (f in n.fields) {
                if (!f.node.on) continue
                val v = toJson(f.node)
                if (v == null || v === SKIP) continue
                o.put(f.key, v)
            }
            o
        }
        is N.Union -> n.child?.let { toJson(it) }
        is N.Arr -> {
            val a = JSONArray()
            for (it in n.items) {
                if (!it.on) continue
                val v = toJson(it)
                if (v == null || v === SKIP) continue
                a.put(v)
            }
            a
        }
        is N.Str -> n.v
        is N.Num -> {
            val s = n.v.trim()
            if (s.isEmpty()) SKIP
            else if (n.int) (s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() ?: s)
            else (s.toDoubleOrNull() ?: s)
        }
        is N.Bool -> n.v
        is N.Pick -> if (n.v.isEmpty()) SKIP else n.v
        is N.Raw -> {
            val s = n.text.trim()
            if (s.isEmpty()) SKIP
            else runCatching { org.json.JSONTokener(s).nextValue() }.getOrElse { s }
        }
    }

    /** 空值标记：用来区分「没填」和「填了空字符串」 */
    private val SKIP = Any()

    /** 生成带缩进的 JSON 文本，给预览和写文件用 */
    fun pretty(n: N): String = runCatching {
        val v = toJson(n)
        when (v) {
            is JSONObject -> v.toString(2)
            is JSONArray -> v.toString(2)
            null -> "{}"
            else -> v.toString()
        }
    }.getOrDefault("{}")
}
