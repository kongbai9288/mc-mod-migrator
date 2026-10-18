package com.kongbai.modmigrator

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 插件宿主：插件不再是独立 APK，而是**目录里的一个清单 + 一个脚本**。
 *
 * ## 为什么放弃 APK 方案
 *
 * 之前插件是一个已安装的 APK，宿主用广播调用它（见 [PluginApi]）。
 * 这条路有三个绕不过去的问题：
 *   1. Android 8.0 起隐式广播不送达静态接收器，必须显式指定包名，
 *      而宿主事先并不知道装了哪些插件；
 *   2. 用户得先去装一个来源不明的 APK，装完还得猜它到底提供了什么；
 *   3. 高版本系统对跨包访问持续收紧，这条路只会越来越窄。
 *
 * 所以改成纯文件：把目录放进去就能用，删掉就没了，
 * 不需要安装、不需要授权、不进系统包管理器。
 *
 * ## 目录结构
 *
 * <工作目录>/ModMigrator/plugins/<插件 id>/
 *     manifest.json   必填
 *     main.js         默认入口，可在清单里改
 *
 * ## 脚本引擎
 *
 * JavaScript 用 Rhino。这里有个 Android 上必须做的事：
 * optimizationLevel 必须设为 -1，否则 Rhino 会尝试生成 JVM 字节码，
 * 而 Android 跑的是 dex，走那条路会直接失败。
 */
object PluginHost {

    /** 插件目录名 */
    private const val DIR = "plugins"

    /** 清单文件名 */
    const val MANIFEST = "manifest.json"

    /** 默认入口脚本名 */
    private const val DEFAULT_ENTRY = "main.js"

    /** 单条脚本最多执行多少条指令。超过就中断，防死循环把界面卡死 */
    private const val INSTRUCTION_LIMIT = 2_000_000

    /** 网络返回最多读多少字节 */
    private const val HTTP_MAX = 2 * 1024 * 1024

    /** 单文件读写上限 */
    private const val FILE_MAX = 2 * 1024 * 1024

    /** 装插件时整个压缩包解压后的总字节上限 */
    private const val ZIP_TOTAL_MAX = 32 * 1024 * 1024

    /**
     * 权限：清单里声明了才注入对应能力。
     *
     * 不声明却调用时报错而不是静默放行 —— 静默放行等于这个声明形同虚设，
     * 用户看清单以为插件碰不到网络，实际照样能发请求。
     */
    val PERMISSIONS = listOf("net", "files", "prefs")

    /** 被禁用的插件 id。存 Set 而不是逐条布尔，新增插件不用改存储结构 */
    private const val K_DISABLED = "plugins_disabled"

    data class Plugin(
        val id: String,
        val name: String,
        val version: String = "",
        val author: String = "",
        val desc: String = "",
        val engine: String = "js",
        val entry: File? = null,
        val permissions: List<String> = emptyList(),
        var enabled: Boolean = true,
        /** 插件所在目录。卸载要按它删，不能靠 id 拼——目录名可能被改过 */
        val root: File? = null
    ) {
        /** 列表上显示的名字：没写 name 就退回 id */
        fun title(): String = name.ifBlank { id }

        fun summary(): String = buildString {
            append(if (author.isBlank()) id else "$id · $author")
            if (version.isNotBlank()) append(" · v").append(version)
        }
    }

    /** 插件根目录 */
    fun dir(ctx: Context): File = WorkRoot.sub(ctx, DIR)

    private fun disabled(ctx: Context): Set<String> =
        Prefs.get(ctx).getStringSet(K_DISABLED, emptySet()) ?: emptySet()

    /** 扫描插件目录。目录不存在或为空都返回空列表，不抛异常 */
    fun scan(ctx: Context): List<Plugin> {
        val root = dir(ctx)
        val off = disabled(ctx)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val out = ArrayList<Plugin>()
        for (d in dirs.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            val mf = File(d, MANIFEST)
            if (!mf.isFile) continue
            val p = readManifest(mf) ?: continue
            out.add(p.copy(enabled = p.id !in off))
        }
        return out
    }

    private fun readManifest(f: File): Plugin? {
        return try {
            val o = org.json.JSONObject(f.readText())
            val id = o.optString("id").trim()
            if (id.isBlank()) return null
            val perms = ArrayList<String>()
            val arr = o.optJSONArray("permissions")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotBlank()) perms.add(s)
                }
            }
            val entryName = o.optString("entry").trim().ifBlank { DEFAULT_ENTRY }
            val entry = File(f.parentFile, entryName)
            Plugin(
                id = id,
                name = o.optString("name").trim(),
                version = o.optString("version").trim(),
                author = o.optString("author").trim(),
                desc = o.optString("desc").trim(),
                engine = o.optString("engine").trim().lowercase(Locale.ROOT).ifBlank { "js" },
                entry = if (entry.isFile) entry else null,
                permissions = perms,
                root = f.parentFile
            )
        } catch (t: Throwable) {
            Err.ignore(t, "读取插件清单")
            null
        }
    }

    /**
     * 从一个 zip 装插件。
     *
     * 两种打包都认：根目录直接放 manifest.json，或整包套一层目录。
     * 后者更常见，不剥掉的话装完会多一层，宿主按 <id>/manifest.json 找不到，
     * 表现就是"装了却扫不出来"。
     *
     * 装到已存在的 id 上时先清空再写，否则新旧文件混在一起，
     * 旧脚本残留也看不出来。
     */
    fun installFromZip(ctx: Context, uri: android.net.Uri): Pair<Boolean, String> {
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                java.util.zip.ZipInputStream(ins).use { zs ->
                    var total = 0L
                    while (true) {
                        val e = zs.nextEntry ?: break
                        val name = e.name
                        if (!e.isDirectory && entries.size < 400 && !name.contains("..")) {
                            // ⚠️ 边读边判，而不是 readBytes() 读完再算。
                            // 原来只压了"条目数 < 400"，那只挡得住文件特别多，
                            // 挡不住单个文件特别大 —— 一条 200MB 的条目照样吃光堆。
                            // 这里同时压住单条大小和解压后总量。
                            val b = readCappedBytes(zs, FILE_MAX)
                            if (b == null) {
                                return Pair(false, "压缩包里有超过 ${FILE_MAX / 1024 / 1024}MB 的文件，已中止")
                            }
                            total += b.size
                            if (total > ZIP_TOTAL_MAX) {
                                return Pair(false, "压缩包内容超过 ${ZIP_TOTAL_MAX / 1024 / 1024}MB，已中止")
                            }
                            entries[name] = b
                        }
                        zs.closeEntry()
                    }
                }
            } ?: return Pair(false, "打不开这个文件")
        } catch (t: Throwable) {
            Err.ignore(t, "读插件压缩包")
            return Pair(false, "读不了这个文件：${t.message ?: "格式不对"}")
        }
        if (entries.isEmpty()) return Pair(false, "压缩包是空的，或者不是 zip")

        val mfKey = entries.keys
            .filter { it.endsWith(MANIFEST, ignoreCase = true) }
            .sortedBy { it.count { c -> c == '/' } }
            .firstOrNull()
            ?: return Pair(false, "压缩包里找不到 $MANIFEST")
        val id = runCatching {
            org.json.JSONObject(String(entries[mfKey]!!, Charsets.UTF_8))
                .optString("id").trim()
        }.getOrDefault("")
        if (id.isBlank()) return Pair(false, "清单里没写 id")

        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val prefix = mfKey.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val target = File(dir(ctx), safeId)
        return try {
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()
            val base = target.canonicalPath
            var n = 0
            for ((k, v) in entries) {
                if (prefix.isNotEmpty() && !k.startsWith(prefix)) continue
                val rel = k.removePrefix(prefix)
                if (rel.isBlank()) continue
                val out = File(target, rel)
                // 越界保护：名字里带 .. 或绝对路径时会写到插件目录外面
                if (!out.canonicalPath.startsWith(base)) continue
                out.parentFile?.mkdirs()
                out.writeBytes(v)
                n++
            }
            if (!File(target, MANIFEST).isFile) return Pair(false, "装完找不到清单")
            // 新装的默认启用，否则装完扫出来是禁用的，像是没装成功
            val off = disabled(ctx).toMutableSet()
            off.remove(safeId)
            Prefs.get(ctx).edit().putStringSet(K_DISABLED, off).apply()
            Pair(true, "已安装 $safeId，共 $n 个文件")
        } catch (t: Throwable) {
            Err.ignore(t, "写插件目录")
            Pair(false, "写不进插件目录：${t.message ?: "存储不可用"}")
        }
    }

    /** 卸载：删掉整个插件目录，并从禁用记录里去掉 */
    fun uninstall(ctx: Context, p: Plugin): Boolean {
        val d = p.root ?: File(dir(ctx), p.id)
        val ok = runCatching { d.deleteRecursively() }.getOrDefault(false)
        val off = disabled(ctx).toMutableSet()
        off.remove(p.id)
        Prefs.get(ctx).edit().putStringSet(K_DISABLED, off).apply()
        return ok
    }

    /** 启用 / 禁用。只改记录，不删文件 */
    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        val set = disabled(ctx).toMutableSet()
        if (on) set.remove(id) else set.add(id)
        Prefs.get(ctx).edit().putStringSet(K_DISABLED, set).apply()
    }

    /**
     * 执行插件脚本。
     *
     * ⚠️ 必须在**后台线程**调用：网络与文件读写都在里面，
     * 而且 Rhino 的执行时间不可预期。
     *
     * @return 脚本产生的日志；执行不了时返回错误信息
     */
    fun run(ctx: Context, p: Plugin): String {
        val logs = StringBuilder()
        val script = p.entry
        if (script == null) return "找不到入口脚本（清单里的 entry 指向的文件不存在）"
        if (p.engine != "js") return "暂不支持的引擎：${p.engine}（目前只支持 js）"
        val src = try {
            script.readText()
        } catch (t: Throwable) {
            return "读不了脚本：${t.message}"
        }
        val app = ctx.applicationContext
        val ui = Handler(Looper.getMainLooper())
        val rc = RhinoContext.enter()
        try {
            // Android 上必须关掉优化：否则 Rhino 走 JVM 字节码那条路会失败
            rc.optimizationLevel = -1
            rc.instructionObserverThreshold = INSTRUCTION_LIMIT
            val scope = rc.initStandardObjects()

            val has = { k: String -> k in p.permissions }
            fun need(k: String): String? =
                if (has(k)) null else "缺少权限 $k，请在 ${MANIFEST} 的 permissions 里声明"

            ScriptableObject.putProperty(scope, "log", fn { a ->
                logs.appendLine(a.firstOrNull()?.toString() ?: "")
                Undefined.instance
            })
            ScriptableObject.putProperty(scope, "toast", fn { a ->
                val m = a.firstOrNull()?.toString() ?: ""
                ui.post { Tips.short(app, m) }
                logs.appendLine("提示：$m")
                Undefined.instance
            })

            val http = rc.newObject(scope)
            ScriptableObject.putProperty(http, "get", fn { a ->
                val e = need("net")
                if (e != null) return@fn e
                val u = a.firstOrNull()?.toString() ?: ""
                httpGet(u, logs)
            })
            ScriptableObject.putProperty(scope, "http", http)

            val file = rc.newObject(scope)
            ScriptableObject.putProperty(file, "read", fn { a ->
                val e = need("files")
                if (e != null) return@fn e
                val rel = a.firstOrNull()?.toString() ?: ""
                val f = resolve(app, rel)
                    ?: return@fn "路径超出了应用目录：$rel"
                if (!f.isFile) return@fn "文件不存在：$rel"
                if (f.length() > FILE_MAX) return@fn "文件超过 2MB，不读：$rel"
                try {
                    f.readText()
                } catch (t: Throwable) {
                    "读取失败：${t.message}"
                }
            })
            ScriptableObject.putProperty(file, "write", fn { a ->
                val e = need("files")
                if (e != null) return@fn e
                val rel = a.getOrNull(0)?.toString() ?: ""
                val text = a.getOrNull(1)?.toString() ?: ""
                if (text.toByteArray().size > FILE_MAX) return@fn "内容超过 2MB，不写"
                val f = resolve(app, rel) ?: return@fn "路径超出了应用目录：$rel"
                return@fn try {
                    f.parentFile?.mkdirs()
                    f.writeText(text)
                    "已写入 ${f.absolutePath}"
                } catch (t: Throwable) {
                    "写入失败：${t.message}"
                }
            })
            ScriptableObject.putProperty(file, "list", fn { a ->
                val e = need("files")
                if (e != null) return@fn e
                val rel = a.firstOrNull()?.toString() ?: ""
                val f = resolve(app, rel) ?: return@fn "路径超出了应用目录：$rel"
                if (!f.isDirectory) return@fn "不是目录：$rel"
                (f.list()?.joinToString("\n") ?: "")
            })
            ScriptableObject.putProperty(file, "delete", fn { a ->
                val e = need("files")
                if (e != null) return@fn e
                val rel = a.firstOrNull()?.toString() ?: ""
                val f = resolve(app, rel) ?: return@fn "路径超出了应用目录：$rel"
                if (f.delete()) "已删除" else "删除失败"
            })
            ScriptableObject.putProperty(scope, "file", file)

            val prefs = rc.newObject(scope)
            ScriptableObject.putProperty(prefs, "get", fn { a ->
                val e = need("prefs")
                if (e != null) return@fn e
                val k = a.firstOrNull()?.toString() ?: ""
                Prefs.get(app).getString(k, "") ?: ""
            })
            ScriptableObject.putProperty(prefs, "set", fn { a ->
                val e = need("prefs")
                if (e != null) return@fn e
                val k = a.getOrNull(0)?.toString() ?: ""
                val v = a.getOrNull(1)?.toString() ?: ""
                Prefs.get(app).edit().putString(k, v).apply()
                "已保存"
            })
            ScriptableObject.putProperty(scope, "prefs", prefs)

            val r = rc.evaluateString(scope, src, p.id, 1, null)
            val rv = RhinoContext.toString(r)
            if (rv.isNotBlank() && rv != "undefined") logs.appendLine("返回值：$rv")
        } catch (t: Throwable) {
            // 指令数超限抛的是 Error 不是 Exception，这里必须连 Error 一起接住
            logs.appendLine("执行中断：${t.message}")
        } finally {
            RhinoContext.exit()
        }
        return logs.toString().ifBlank { "脚本执行完毕，没有产生输出" }
    }

    /** 把 js 函数包成 Rhino 能认的 callable */
    private fun fn(body: (List<Any?>) -> Any?): BaseFunction =
        object : BaseFunction() {
            override fun call(
                cx: RhinoContext?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<Any?>?
            ): Any? = body(args?.toList() ?: emptyList())
        }

    /**
     * 相对路径 → 应用目录内的文件。
     *
     * 返回 null 表示越界：脚本里写 ../../ 想跳出目录时拒绝，
     * 否则一个插件就能读写整个外部存储。
     */
    private fun resolve(ctx: Context, rel: String): File? {
        if (rel.isBlank()) return null
        val base = WorkRoot.base(ctx)
        val f = File(base, rel)
        return try {
            val bp = base.canonicalPath
            val fp = f.canonicalPath
            if (fp == bp || fp.startsWith(bp + File.separator)) f else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun httpGet(url: String, logs: StringBuilder): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "只支持 http/https 地址"
        }
        var c: HttpURLConnection? = null
        return try {
            logs.appendLine("请求 $url")
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ModMigrator-Plugin")
            }
            val code = c.responseCode
            if (code !in 200..299) return "HTTP $code"
            // ⚠️ 之前是 readBytes() 整段读进来再和 HTTP_MAX 比 ——
            // 上限等于没设：对方真返回 500MB，也是先吃掉 500MB 堆再扔掉，
            // 中间那一下就是 OutOfMemoryError。改成边读边判，超了就停。
            val body = c.inputStream.use { readCappedText(it, HTTP_MAX) }
                ?: return "响应超过 ${HTTP_MAX / 1024 / 1024}MB，已放弃"
            body
        } catch (t: Throwable) {
            "请求失败：${t.message}"
        } finally {
            c?.disconnect()
        }
    }

    /**
     * 示例插件：名字 → 清单与脚本。
     *
     * 写在代码里而不是打包进 assets，是为了让示例随代码走、
     * 改脚本能力时顺手就能改示例，不至于两边脱节。
     */
    fun samples(): List<Pair<String, Pair<String, String>>> = listOf(
        "hello" to (
            """
            {
              "id": "hello",
              "name": "示例：打招呼",
              "version": "1.0",
              "author": "内置",
              "desc": "只往日志里写两行，用来确认插件能跑起来",
              "engine": "js",
              "entry": "main.js",
              "permissions": []
            }
            """.trimIndent() to """
            log("插件跑起来了");
            toast("示例插件已执行");
            "完成";
            """.trimIndent()
            ),
        "list-mods" to (
            """
            {
              "id": "list-mods",
              "name": "示例：列出工作目录",
              "version": "1.0",
              "author": "内置",
              "desc": "列出工作目录下的内容，用来确认 files 权限可用",
              "engine": "js",
              "entry": "main.js",
              "permissions": ["files"]
            }
            """.trimIndent() to """
            var names = file.list(".");
            log("目录内容：\n" + names);
            names.split("\n").length;
            """.trimIndent()
            ),
        "fetch" to (
            """
            {
              "id": "fetch",
              "name": "示例：取一个网页",
              "version": "1.0",
              "author": "内置",
              "desc": "演示 http.get，需要 net 权限",
              "engine": "js",
              "entry": "main.js",
              "permissions": ["net"]
            }
            """.trimIndent() to """
            var s = http.get("https://example.com");
            log("取回 " + s.length + " 字节");
            s.length;
            """.trimIndent()
            )
    )

    /** 写入全部示例。已存在同名目录时跳过，不覆盖用户改过的东西 */
    fun writeSamples(ctx: Context): Int {
        var n = 0
        for ((id, pair) in samples()) {
            val d = File(dir(ctx), id)
            if (d.exists()) continue
            try {
                d.mkdirs()
                File(d, MANIFEST).writeText(pair.first)
                File(d, DEFAULT_ENTRY).writeText(pair.second)
                n++
            } catch (t: Throwable) {
                Err.ignore(t, "写入示例插件")
            }
        }
        return n
    }

    /** 给用户看的说明：插件目录在哪、清单长什么样 */
    fun help(ctx: Context): String = buildString {
        append("插件目录：\n").append(dir(ctx).absolutePath).append("\n\n")
        append("每个插件是一个子目录，里面放 ").append(MANIFEST)
        append(" 和脚本（默认 main.js）。\n\n")
        append("清单字段：id（必填）、name、version、author、desc、")
        append("engine（目前只支持 js）、entry、permissions。\n\n")
        append("可用权限：").append(PERMISSIONS.joinToString("、")).append("。\n")
        append("没声明的权限调用时会直接返回错误，不会静默放行。\n\n")
        append("脚本能用的：log、toast、http.get、file.read/write/list/delete、prefs.get/set。")
    }
}
