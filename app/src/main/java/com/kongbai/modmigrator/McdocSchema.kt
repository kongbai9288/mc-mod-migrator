package com.kongbai.modmigrator

import org.json.JSONObject

/**
 * mcdoc schema 的读取层。
 *
 * 数据包生成器原本是套一个 misode 网页，网页运行时自己拉 symbols.json，
 * 拉不到就一个表单都没有（页面还在，但什么都做不了），中间是黑盒。
 * 原生化之后由这里直接读同一份 schema。
 *
 * 数据来自 SpyglassMC/vanilla-mcdoc 的 generated/symbols.json，
 * 构建时已下载进 assets（见 .github/workflows/build-apk.yml），
 * 所以运行时不需要联网。
 *
 * 文件 2.6 MB，解析后是几万个 JSONObject —— 不便宜。
 * 所以[get]是懒加载，且只在使用期间持有，用完由调用方调 [release] 释放。
 */
object McdocSchema {

    /** assets 里的路径。由 CI 构建时下载，和网页版用的是同一份 */
    private const val ASSET = "misode-data/raw.githubusercontent.com/SpyglassMC/vanilla-mcdoc/generated/symbols.json"

    @Volatile
    private var root: JSONObject? = null

    @Volatile
    private var types: JSONObject? = null

    @Volatile
    private var dispatchers: JSONObject? = null

    @Volatile
    var loadError: String? = null
        private set

    /**
     * 读 schema。已加载就直接返回。
     * 失败时记下原因并返回 null，让调用方退回手写表单，不要抛出去把页面搞崩。
     */
    fun get(ctx: android.content.Context): JSONObject? {
        root?.let { return it }
        return synchronized(this) {
            root?.let { return@synchronized it }
            val r = runCatching {
                ctx.assets.open(ASSET).use { s ->
                    JSONObject(String(s.readBytes(), Charsets.UTF_8))
                }
            }.getOrElse { e ->
                loadError = e.message ?: e.toString()
                null
            } ?: return@synchronized null
            types = r.optJSONObject("mcdoc")
            dispatchers = r.optJSONObject("mcdoc/dispatcher")
            root = r
            r
        }
    }

    /** 取类型定义。path 形如 `::java::data::loot::LootTable` */
    fun type(path: String?): JSONObject? {
        if (path.isNullOrBlank()) return null
        return types?.optJSONObject(path)
    }

    /** 取分发表。registry 形如 `minecraft:loot_condition` */
    fun dispatcher(registry: String?): JSONObject? {
        if (registry.isNullOrBlank()) return null
        return dispatchers?.optJSONObject(registry)
    }

    fun typeCount(): Int = types?.length() ?: 0

    /** 释放。schema 只在生成数据包时用，用完就该还回去 */
    fun release() {
        synchronized(this) {
            root = null
            types = null
            dispatchers = null
        }
    }

    // ------------------------------------------------------------- 版本过滤

    /**
     * 字段上的 since / until 属性表示该字段只在某个 MC 版本区间存在。
     *
     * 这里只做**粗筛**：能解析成数字就比，解析不了就放行 ——
     * 宁可多显示一个字段，也不要把该有的字段藏掉（藏掉用户就填不了）。
     */
    fun versionOk(attrs: org.json.JSONArray?, ver: String): Boolean {
        if (attrs == null) return true
        val cur = verNum(ver)
        for (i in 0 until attrs.length()) {
            val a = attrs.optJSONObject(i) ?: continue
            when (a.optString("name")) {
                "since" -> {
                    val v = litString(a.opt("value"))
                    val n = verNum(v)
                    if (n != null && cur != null && cur < n) return false
                }
                "until" -> {
                    val v = litString(a.opt("value"))
                    val n = verNum(v)
                    if (n != null && cur != null && cur >= n) return false
                }
            }
        }
        return true
    }

    /**
     * 把版本串转成可比较的数值，认不出来返回 null。
     *
     * ⚠️ 之前这里是自己算的 `major * 10000 + minor * 100 + patch`：
     * 每一段固定占两位，于是 1.20.1 → 12001、26.3 → 260300。
     * 这套编码在两个方向上都会错：
     *   · 某一段超过两位就串位（比如 minor 变成 100+）；
     *   · 它假设版本一定是"1.x.y"的形状，而 26.3 这种两段写法
     *     和当年 1.x 的三段写法在数值上完全不可比。
     * 而这里的比较结果直接决定**字段显不显示** —— 算错就是静默少显示字段，
     * 用户只会觉得"这个生成器怎么缺东西"，查不到原因。
     * 现在统一交给 [McVer.key]，格式变了只改那一处。
     */
    private fun verNum(s: String?): Long? {
        val k = McVer.key(s)
        return if (k < 0) null else k
    }

    /** 从 `{"kind":"literal","value":{"kind":"string","value":"1.20"}}` 里取字符串 */
    fun litString(v: Any?): String? {
        if (v !is JSONObject) return v as? String
        if (v.optString("kind") == "literal") return litString(v.opt("value"))
        if (v.optString("kind") == "string") return v.optString("value")
        return v.optString("value", null)
    }
}
