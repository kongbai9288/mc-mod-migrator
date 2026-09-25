package com.kongbai.modmigrator

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 模组元数据解析。
 *
 * 这一版**不再用正则去 grep JSON/TOML**——那是之前各种解析错误的根源：
 *   - 数组、嵌套表、多行字符串一律解析不出
 *   - forge 的 mods.toml 真实路径是 META-INF/mods.toml，正则很容易找错
 *   - fabric.mod.json 的 icon 字段既可能是字符串也可能是对象，正则只能覆盖一种
 *
 * 现在用标准解析器：
 *   - JSON → Gson
 *   - TOML → tomlj（Apache-2.0，完整支持 TOML 1.0）
 *
 * 判定顺序参考 Fabric Loader 与 modcrawl 的做法：**按 sentinel 文件探测**，
 * 而不是看文件名猜测。
 */
object ModMeta {

    private val gson = Gson()

    /** 加载器类型常量 */
    const val FABRIC = "fabric"
    const val QUILT = "quilt"
    const val FORGE = "forge"
    const val NEOFORGE = "neoforge"
    const val UNKNOWN = "unknown"

    data class Info(
        val id: String = "",
        val name: String = "",
        val version: String = "",
        val loader: String = UNKNOWN,
        val description: String = "",
        val authors: List<String> = emptyList(),
        /** 必需依赖：modId → 版本范围 */
        val depends: Map<String, String> = emptyMap(),
        /** 明确声明会崩的冲突项 */
        val breaks: Map<String, String> = emptyMap(),
        /** 提供（provides）的别名 id，冲突检测要用 */
        val provides: List<String> = emptyList(),
        /** 要求的 MC 版本范围 */
        val mcRange: String = "",
        val license: String = "",
        /** jar 内图标路径，供 ModIcons 提取 */
        val iconPath: String = "",
        val entrypoints: List<String> = emptyList(),
        /** 解析过程中的问题（不抛异常，收集起来给界面显示） */
        val warnings: List<String> = emptyList()
    ) {
        val isKnown: Boolean get() = loader != UNKNOWN
    }

    /**
     * 元数据文件 → 加载器。顺序即优先级：越靠前越优先。
     * 注意 Forge 的真实路径是 META-INF/mods.toml，不是根目录。
     */
    private val SENTINELS = listOf(
        "fabric.mod.json" to FABRIC,
        "quilt.mod.json" to QUILT,
        "META-INF/neoforge.mods.toml" to NEOFORGE,
        "neoforge.mods.toml" to NEOFORGE,
        "META-INF/mods.toml" to FORGE,
        "mods.toml" to FORGE,
        "mcmod.info" to FORGE          // 1.7.10 及更早的老格式
    )

    /** 从 uri 读取（SAF DocumentFile 场景） */
    fun read(ctx: Context, uri: Uri): Info {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { parse(ZipInputStream(it)) }
                ?: Info(warnings = listOf("打不开文件"))
        } catch (t: Throwable) {
            Info(warnings = listOf("读取失败：${t.message?.take(60)}"))
        }
    }

    /** 从本地 File 读取（有文件路径时更快，ZipFile 可随机访问） */
    fun readFile(f: java.io.File): Info {
        if (!f.exists() || !f.canRead()) return Info(warnings = listOf("文件不可读"))
        return try {
            java.util.zip.ZipFile(f).use { zf ->
                parseFromZipFile(zf)
            }
        } catch (t: Throwable) {
            // 不是 zip（比如下载了一半的坏文件）也要安静返回，不能崩
            Info(warnings = listOf("不是有效的 jar：${t.message?.take(60)}"))
        }
    }

    /**
     * 从 ZipFile 解析。优先用这个——ZipFile 有中央目录，
     * 可以直接定位条目，不用像 ZipInputStream 那样顺序扫。
     */
    private fun parseFromZipFile(zf: java.util.zip.ZipFile): Info {
        val found = findSentinel(zf) ?: return Info()
        val (entryName, loader) = found
        val text = readEntry(zf, entryName) ?: return Info(loader = loader)

        return when (loader) {
            FABRIC, QUILT -> parseFabric(text, loader)
            else -> {
                if (entryName.equals("mcmod.info", true)) parseMcmodInfo(text)
                else parseModsToml(text, loader)
            }
        }
    }

    /** 按优先级找第一个存在的 sentinel 文件 */
    private fun findSentinel(zf: java.util.zip.ZipFile): Pair<String, String>? {
        for ((name, loader) in SENTINELS) {
            if (zf.getEntry(name) != null) return name to loader
        }
        return null
    }

    private fun readEntry(zf: java.util.zip.ZipFile, name: String): String? =
        try {
            zf.getInputStream(zf.getEntry(name)).use { it.readBytes() }
                .toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }

    /** 从流解析（拿不到 File 时的兜底） */
    private fun parse(zip: ZipInputStream): Info {
        var hit: Pair<String, String>? = null
        var payload = ""
        var e: ZipEntry? = zip.nextEntry
        var scanned = 0
        // 只扫前 400 条：元数据文件一定在靠前的位置
        while (e != null && scanned < 400) {
            scanned++
            val n = e.name ?: ""
            val match = SENTINELS.firstOrNull { it.first.equals(n, true) }
            val rank = SENTINELS.indexOf(match)
            val cur = hit?.let { SENTINELS.indexOf(it) } ?: Int.MAX_VALUE
            if (rank >= 0 && rank < cur) {
                val text = runCatching { zip.readBytes().toString(Charsets.UTF_8) }.getOrNull()
                if (!text.isNullOrBlank()) {
                    hit = match
                    payload = text
                }
            }
            e = zip.nextEntry
        }
        runCatching { zip.closeEntry() }
        if (hit == null) return Info()
        val (name, loader) = hit
        return when (loader) {
            FABRIC, QUILT -> parseFabric(payload, loader)
            else -> if (name.equals("mcmod.info", true)) parseMcmodInfo(payload)
            else parseModsToml(payload, loader)
        }
    }

    // ---------------- fabric.mod.json / quilt.mod.json ----------------

    private fun parseFabric(text: String, loader: String): Info {
        val o = runCatching {
            gson.fromJson(text, JsonObject::class.java)
        }.getOrNull() ?: return Info(loader = loader, warnings = listOf("fabric.mod.json 不是合法 JSON"))

        val warnings = ArrayList<String>()

        // icon 既可能是 "icon.png"，也可能是 {"size":128,"file":"assets/icon.png"}
        // 还可能是 {"assets/icon.png": "..."} 这种 map 形式
        val icon = runCatching { extractIcon(o.get("icon")) }.getOrNull() ?: ""

        val depends = runCatching { strMap(o.get("depends")) }.getOrNull() ?: emptyMap()
        val breaks = runCatching { strMap(o.get("breaks")) }.getOrNull() ?: emptyMap()
        val provides = runCatching { strList(o.get("provides")) }.getOrNull() ?: emptyList()
        val mc = depends.entries.firstOrNull {
            it.key.equals("minecraft", true) || it.key.equals("minecraft_version", true)
        }?.value ?: ""

        return Info(
            id = str(o, "id"),
            name = str(o, "name").ifBlank { str(o, "id") },
            version = str(o, "version"),
            loader = loader,
            description = str(o, "description"),
            authors = authorsOf(o),
            depends = depends,
            breaks = breaks,
            provides = provides,
            mcRange = mc,
            license = licenseOf(o),
            iconPath = icon,
            entrypoints = entrypointsOf(o),
            warnings = warnings
        )
    }

    /** icon 字段的三种形态都要能取出来 */
    private fun extractIcon(el: JsonElement?): String {
        when {
            el == null || el.isJsonNull -> return ""
            el.isJsonPrimitive -> return el.asString
            el.isJsonObject -> {
                val o = el.asJsonObject
                // {"file": "..."} 形式
                str(o, "file").let { if (it.isNotBlank()) return it }
                // {"128": "path", "256": "path"} 形式：取分辨率最大的
                var bestKey = ""
                var bestSize = -1
                for ((k, _) in o.entrySet()) {
                    val sz = k.toIntOrNull() ?: continue
                    if (sz > bestSize) {
                        bestSize = sz
                        bestKey = k
                    }
                }
                if (bestKey.isNotBlank()) return str(o, bestKey)
                return ""
            }
            else -> return ""
        }
    }

    private fun strMap(el: JsonElement?): Map<String, String> {
        if (el == null || !el.isJsonObject) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in el.asJsonObject.entrySet()) {
            out[k] = when {
                v.isJsonPrimitive -> v.asString
                v.isJsonArray -> v.asJsonArray.joinToString(" || ") { it.asString }
                else -> ""
            }
        }
        return out
    }

    private fun strList(el: JsonElement?): List<String> {
        if (el == null || !el.isJsonArray) return emptyList()
        return el.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }
    }

    private fun authorsOf(o: JsonObject): List<String> {
        val a = o.get("authors")
        if (a != null && a.isJsonArray) {
            return a.asJsonArray.mapNotNull {
                // 作者既可能是 "name" 也可能是 {"name": "xxx"}
                if (it.isJsonPrimitive) it.asString
                else if (it.isJsonObject) str(it.asJsonObject, "name").ifBlank { null }
                else null
            }
        }
        str(o, "author").let { if (it.isNotBlank()) return listOf(it) }
        return emptyList()
    }

    private fun licenseOf(o: JsonObject): String {
        val l = o.get("license")
        return when {
            l == null -> ""
            l.isJsonPrimitive -> l.asString
            l.isJsonArray -> l.asJsonArray.joinToString(", ") {
                if (it.isJsonPrimitive) it.asString else str(it.asJsonObject, "id")
            }
            else -> ""
        }
    }

    private fun entrypointsOf(o: JsonObject): List<String> {
        val e = o.get("entrypoints")
        if (e == null || !e.isJsonObject) return emptyList()
        val out = ArrayList<String>()
        for ((_, v) in e.asJsonObject.entrySet()) {
            if (v.isJsonArray) {
                v.asJsonArray.forEach {
                    when {
                        it.isJsonPrimitive -> out.add(it.asString)
                        it.isJsonObject -> str(it.asJsonObject, "value").let { s ->
                            if (s.isNotBlank()) out.add(s)
                        }
                    }
                }
            }
        }
        return out
    }

    // ---------------- mods.toml（Forge / NeoForge） ----------------

    private fun parseModsToml(text: String, loader: String): Info {
        val r: TomlParseResult = try {
            Toml.parse(text)
        } catch (t: Throwable) {
            return Info(loader = loader, warnings = listOf("mods.toml 解析失败"))
        }
        val warnings = ArrayList<String>()
        if (r.hasErrors()) {
            // tomlj 不抛异常，错误都收在这里
            r.errors().take(3).forEach { warnings.add("TOML 错误：${it}") }
        }

        // [[mods]] 是数组表，可能有多个；取第一个作为主模组
        val modsArr = r.getArray("mods")
        val mod: TomlTable? = when {
            modsArr != null && modsArr.size() > 0 ->
                runCatching { modsArr.getTable(0) }.getOrNull()
            else -> runCatching { r.getTable("mods") }.getOrNull()
        }

        val id = mod?.let { tomlStr(it, "modId") } ?: ""
        val name = mod?.let { tomlStr(it, "displayName") }
            ?.ifBlank { id } ?: id
        val version = mod?.let { tomlStr(it, "version") } ?: ""
        val desc = mod?.let { tomlStr(it, "description") } ?: ""
        val authors = mod?.let { t ->
            runCatching { t.getArray("authors") }?.getOrNull()?.let { arr ->
                (0 until arr.size()).mapNotNull { i ->
                    runCatching { arr.getString(i) }.getOrNull()
                }
            }
        } ?: emptyList()
        val license = mod?.let { tomlStr(it, "license") } ?: ""
        val icon = mod?.let { tomlStr(it, "logoFile") } ?: ""

        // [[dependencies.<modId>]] 数组表：mandatory=true 的才算必需依赖
        val depends = LinkedHashMap<String, String>()
        val depTable = runCatching { r.getTable("dependencies") }.getOrNull()
        if (depTable != null) {
            for (key in depTable.keySet()) {
                val arr = runCatching { depTable.getArray(key) }.getOrNull() ?: continue
                for (i in 0 until arr.size()) {
                    val d = runCatching { arr.getTable(i) }.getOrNull() ?: continue
                    val depId = tomlStr(d, "modId")
                    if (depId.isBlank()) continue
                    val mandatory = runCatching { d.getBoolean("mandatory") }.getOrNull() ?: false
                    val range = tomlStr(d, "versionRange")
                    when {
                        // minecraft / forge 是环境依赖，不算外部模组
                        depId.equals("minecraft", true) -> depends["minecraft"] = range
                        depId.equals("forge", true) -> Unit
                        mandatory -> depends[depId] = range
                    }
                }
            }
        }

        val mc = depends["minecraft"] ?: ""

        return Info(
            id = id,
            name = name,
            version = version,
            loader = loader,
            description = desc,
            authors = authors,
            depends = depends.filterKeys { !it.equals("minecraft", true) },
            mcRange = mc,
            license = license,
            iconPath = icon,
            warnings = warnings
        )
    }

    // ---------------- mcmod.info（1.7.10 及更早） ----------------

    private fun parseMcmodInfo(text: String): Info {
        // mcmod.info 是一个数组，里面每项是一个模组描述
        val arr = runCatching {
            gson.fromJson(text, com.google.gson.JsonArray::class.java)
        }.getOrNull()
        val o = when {
            arr == null || arr.size() == 0 -> return Info(loader = FORGE)
            arr.get(0).isJsonObject -> arr.get(0).asJsonObject
            else -> return Info(loader = FORGE)
        }
        val deps = runCatching { strMap(o.get("dependencies")) }.getOrNull() ?: emptyMap()
        return Info(
            id = str(o, "modid").ifBlank { str(o, "modId") },
            name = str(o, "name"),
            version = str(o, "version"),
            loader = FORGE,
            description = str(o, "description"),
            authors = authorsOf(o),
            depends = deps,
            mcRange = str(o, "mcversion"),
            license = str(o, "license")
        )
    }

    // ---------------- 小工具 ----------------

    private fun str(o: JsonObject, key: String): String {
        val e = o.get(key) ?: return ""
        return if (e.isJsonPrimitive) runCatching { e.asString }.getOrDefault("") else ""
    }

    private fun tomlStr(t: TomlTable, key: String): String =
        runCatching { t.getString(key) }.getOrNull() ?: ""

    /** 加载器显示名 */
    fun loaderLabel(loader: String): String = when (loader.lowercase(Locale.ROOT)) {
        FABRIC -> "Fabric"
        QUILT -> "Quilt"
        FORGE -> "Forge"
        NEOFORGE -> "NeoForge"
        else -> "未知"
    }
}
