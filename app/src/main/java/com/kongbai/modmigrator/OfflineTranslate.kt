package com.kongbai.modmigrator

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 离线翻译：本地词典 + 缓存。
 *
 * 优先级：命中过的缓存 > 内置常用词典 > 网络翻译（在线时才走）。
 * 缓存落在文件里，杀进程后仍然有效。
 */
object OfflineTranslate {

    /** 内置常用词典：模组/配置场景高频词，够覆盖绝大多数界面与简介关键词 */
    private val DICT: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        fun put(en: String, zh: String) {
            m[en.lowercase(Locale.ROOT)] = zh
        }
        // 模组
        put("mod", "模组"); put("mods", "模组"); put("modpack", "整合包")
        put("resource pack", "资源包"); put("shader", "光影"); put("shaderpack", "光影包")
        put("addon", "附属包"); put("plugin", "插件"); put("datapack", "数据包")
        put("library", "依赖库"); put("api", "接口库"); put("dependency", "依赖")
        put("dependencies", "依赖"); put("optional", "可选"); put("required", "必需")
        put("version", "版本"); put("versions", "版本"); put("release", "正式版")
        put("beta", "测试版"); put("alpha", "预览版"); put("snapshot", "快照版")
        put("update", "更新"); put("updated", "已更新"); put("download", "下载")
        put("downloads", "下载量"); put("install", "安装"); put("installed", "已安装")
        put("fabric", "Fabric"); put("forge", "Forge"); put("quilt", "Quilt")
        put("neoforge", "NeoForge"); put("loader", "加载器"); put("minecraft", "我的世界")
        put("client", "客户端"); put("server", "服务端"); put("singleplayer", "单人")
        put("multiplayer", "多人"); put("config", "配置"); put("configuration", "配置")
        put("settings", "设置"); put("options", "选项"); put("gui", "界面")
        put("hud", "信息显示"); put("inventory", "物品栏"); put("item", "物品")
        put("block", "方块"); put("entity", "实体"); put("mob", "生物")
        put("world", "世界"); put("biome", "生物群系"); put("dimension", "维度")
        put("recipe", "配方"); put("crafting", "合成"); put("enchant", "附魔")
        put("storage", "存储"); put("energy", "能量"); put("fluid", "流体")
        put("performance", "性能优化"); put("optimization", "优化"); put("fps", "帧率")
        put("bug", "问题"); put("fix", "修复"); put("crash", "崩溃")
        put("compatibility", "兼容性"); put("support", "支持"); put("supported", "支持的")
        put("description", "简介"); put("summary", "简介"); put("overview", "概述")
        put("feature", "功能"); put("features", "功能"); put("adds", "新增")
        put("add", "添加"); put("removes", "移除"); put("changes", "改动")
        put("warning", "注意"); put("note", "说明"); put("requires", "需要")
        put("author", "作者"); put("authors", "作者"); put("license", "许可证")
        put("source", "来源"); put("changelog", "更新日志"); put("wiki", "百科")
        put("migration", "迁移"); put("migrate", "迁移"); put("backup", "备份")
        put("restore", "恢复"); put("export", "导出"); put("import", "导入")
        put("search", "搜索"); put("recommend", "推荐"); put("popular", "热门")
        put("offline", "离线"); put("online", "在线"); put("cache", "缓存")
        put("translate", "翻译"); put("translation", "翻译"); put("language", "语言")
        put("enabled", "已启用"); put("disabled", "已禁用"); put("default", "默认")
        put("custom", "自定义"); put("advanced", "高级"); put("simple", "简易")
        put("memory", "内存"); put("ram", "内存"); put("performance mode", "性能模式")
        m
    }

    private fun cacheFile(ctx: Context): File {
        val d = File(ctx.filesDir, "trans")
        if (!d.exists()) d.mkdirs()
        return File(d, "cache.tsv")
    }

    /**
     * 内存缓存。
     *
     * 之前 `lookup()` **每次调用都把整个 cache.tsv 读一遍**，
     * 而翻译是逐段调用的（网页翻译一轮就上百段），等于
     * 每翻一段就做一次全文件 IO —— 页面越大越卡，最后像死掉一样。
     * 现在只在第一次加载，之后走内存；写缓存时同步更新内存。
     */
    @Volatile
    private var memCache: HashMap<String, String>? = null

    /** 缓存条数上限，超过就重写一次文件（丢掉最早的一半） */
    private const val MAX_CACHE = 2000

    private fun loadCache(ctx: Context): HashMap<String, String> {
        memCache?.let { return it }
        val m = HashMap<String, String>()
        try {
            val f = cacheFile(ctx)
            if (f.exists()) {
                f.readLines().forEach { line ->
                    val i = line.indexOf('\t')
                    if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
                }
            }
        } catch (t: Throwable) {
            Err.ignore(t, "读取翻译缓存")
        }
        memCache = m
        return m
    }

    private fun saveCache(ctx: Context, key: String, value: String) {
        val m = loadCache(ctx)
        // 已存在就不重复追加：之前每次都 append，
        // 同一个词翻多少次文件里就有多少行，无限增长。
        if (m[key] == value) return
        m[key] = value
        try {
            if (m.size > MAX_CACHE) {
                // 超限：保留较新的一半，整体重写一次
                val keep = m.entries.toList().takeLast(MAX_CACHE / 2)
                m.clear()
                for (e in keep) m[e.key] = e.value
                val sb = StringBuilder()
                for (e in m) sb.append(e.key).append('\t').append(e.value).append('\n')
                cacheFile(ctx).writeText(sb.toString())
            } else {
                cacheFile(ctx).appendText("$key\t$value\n")
            }
        } catch (t: Throwable) {
            Err.ignore(t, "写入翻译缓存")
        }
    }

    /** 离线可用：先查缓存，再查内置词典，最后整句里逐词替换 */
    fun lookup(ctx: Context, text: String): String? {
        val t = text.trim()
        if (t.isBlank()) return null
        val key = t.lowercase(Locale.ROOT)

        val cached = loadCache(ctx)[key]
        if (!cached.isNullOrBlank()) return cached

        val exact = DICT[key]
        if (!exact.isNullOrBlank()) return exact

        // 整句：把认识的词逐个替换，覆盖率够高就返回
        val words = t.split(Regex("([\\s,.;:!?()\\[\\]{}/\\\\|]+)"))
        if (words.size <= 1) return null
        var hit = 0
        var total = 0
        val out = words.map { w ->
            if (w.isBlank()) return@map w
            if (w.length < 2 || !w.first().isLetter()) return@map w
            total++
            val zh = DICT[w.lowercase(Locale.ROOT)]
            if (zh != null) {
                hit++
                zh
            } else w
        }
        if (total > 0 && hit.toDouble() / total >= 0.5) {
            return out.joinToString(" ")
        }
        return null
    }

    /** 网络翻译成功后写入缓存，供下次离线使用 */
    fun remember(ctx: Context, original: String, translated: String) {
        saveCache(ctx, original.trim().lowercase(Locale.ROOT), translated)
    }

    /**
     * 翻译：本地缓存/词典 → ML Kit 离线模型 → 在线兜底。
     *
     * 现在是异步回调（ML Kit 本身就是异步的）。
     * 本地命中的话会同步立刻回调，调用方不用区分。
     */
    fun translate(ctx: Context, text: String, cb: (String?) -> Unit) {
        val cached = lookup(ctx, text)
        if (cached != null) {
            cb(cached)
            return
        }
        // 离线模式：只用本地词典，不联网
        if (Prefs.get(ctx).getBoolean(K.OFFLINE, false)) {
            cb(null)
            return
        }
        Translator.toZh(ctx, text) { zh ->
            if (zh != null) remember(ctx, text, zh)
            cb(zh)
        }
    }

    /** 网页离线翻译：注入 JS 做词级替换，页面不出网也能看个大概 */
    fun webScript(ctx: Context): String {
        // 必须转义：词典里一旦出现引号或反斜杠（比如以后加中文词条、
        // 或值为 "Iris + Sodium" 这类含特殊字符的内容），
        // 直接拼进 JS 字符串字面量会**破坏整段脚本**
        // —— 脚本解析失败，翻译一个字都不会生效，而且没有任何报错。
        fun esc(s: String) = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        val pairs = DICT.entries.joinToString(",") {
            "\"${esc(it.key)}\":\"${esc(it.value)}\""
        }
        return """
        (function(){
          var D = {$pairs};
          function walk(n){
            if(n.nodeType===3){
              var s=n.nodeValue;
              var hit=0;
              var out=s.replace(/[A-Za-z][A-Za-z-]{1,}/g,function(w){
                var z=D[w.toLowerCase()];
                if(z){hit++;return z+'('+w+')';}
                return w;
              });
              if(hit>0) n.nodeValue=out;
              return;
            }
            if(n.nodeType===1){
              var t=n.tagName;
              if(t==='SCRIPT'||t==='STYLE'||t==='CODE'||t==='PRE') return;
              for(var i=0;i<n.childNodes.length;i++) walk(n.childNodes[i]);
            }
          }
          walk(document.body);
        })();
        """.trimIndent()
    }
}
