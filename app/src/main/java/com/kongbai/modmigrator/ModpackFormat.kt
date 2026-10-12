package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream

/**
 * 除了 Modrinth 之外还能导出的整合包格式。
 *
 * ## 为什么要支持多种
 *
 * 只出 .mrpack 的话，用 CurseForge 启动器、Prism / MultiMC 的人就装不了。
 * 而国内相当一部分人手里拿的是 CurseForge 或 HMCL 系启动器导的包。
 *
 * ## 结构怎么定的
 *
 * 参考 HMCL 的做法：它把每种格式拆成一个 provider
 * （curse / multimc / mcbbs / modrinth / server），
 * 共用一份"清单 + overrides"的模型，只有清单的写法不同。
 * 这里同样处理：收集文件只有一份代码，格式只决定
 *  · 清单叫什么、怎么写
 *  · overrides 在 zip 里放到哪个前缀下
 *
 * ## 一个容易踩的坑
 *
 * Modrinth 的 files[] 和 overrides/ 是互斥的两种携带方式：
 * 同一个文件既塞进 overrides 又在 files[] 登记，
 * 启动器读到 files[] 会去下载却没有地址 —— 导入直接失败。
 * 所以只有能拿到远程下载地址时才写进 files[]。
 */
enum class ModpackFormat(
    val ext: String,
    val label: String,
    val note: String,
    /** 清单文件名。null = 不写清单 */
    val manifestName: String?,
    /** overrides 在 zip 里的前缀。MultiMC 用的是 .minecraft */
    val overridesPrefix: String
) {
    MODRINTH(
        "mrpack", "Modrinth（.mrpack）",
        "清单里写下载地址与哈希，包很小，装的时候由启动器下载。Modrinth 没收录的会自动随包带走。",
        Mrpack.INDEX_NAME, Mrpack.OVERRIDES
    ),
    CURSEFORGE(
        "zip", "CurseForge（.zip）",
        "清单里写 CurseForge 的项目号与文件号，需要填 CurseForge Key 才能反查；查不到的随包带走。",
        "manifest.json", "overrides"
    ),
    MULTIMC(
        "zip", "MultiMC / Prism（.zip）",
        "清单只写版本与加载器，所有文件随包带走（这个格式没有远程下载）。",
        "mmc-pack.json", ".minecraft"
    ),
    PLAIN(
        "zip", "通用 zip（无清单）",
        "不带任何清单，解压后直接放进实例目录。给不支持导入整合包的启动器用。",
        null, ""
    );

    /** 文件名带上用途后缀，导几个包之后还能分清哪个是哪个 */
    fun fileName(name: String, parts: Collection<McParts.Part>): String {
        val s = when {
            parts.isEmpty() -> "pack"
            parts.size == 1 && parts.first().id == McDirs.SAVES -> "map"
            parts.any { it.id == McDirs.MODS } -> "mods"
            parts.any { it.id == McDirs.CONFIG } -> "config"
            else -> "pack"
        }
        val safe = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\r\n\t]"), "_")
            .trim()
            .ifBlank { "modpack" }
        return "${safe}_$s.$ext"
    }

    companion object {
        fun of(saved: String): ModpackFormat =
            values().firstOrNull { it.name == saved } ?: MODRINTH
    }
}

/**
 * 各种格式的清单文本。全部集中在这里，导出器只负责收集文件。
 */
object ModpackManifest {

    /**
     * CurseForge 的 manifest.json。
     *
     * files[] 里的 projectID / fileID 只能靠 murmur2 指纹向接口反查，
     * 反查不到就一个都不能写 —— 写了错的号，装出来的是另一个模组。
     * 所以这里只接收已经确认过的条目。
     */
    fun curseforge(
        name: String,
        version: String,
        mcVersion: String,
        loader: String,
        loaderVersion: String,
        files: List<Pair<Long, Long>>
    ): String {
        val arr = JSONArray()
        for ((projectId, fileId) in files) {
            arr.put(
                JSONObject()
                    .put("projectID", projectId)
                    .put("fileID", fileId)
                    .put("required", true)
            )
        }
        val loaders = JSONArray()
        val id = curseLoaderId(loader, loaderVersion)
        if (id.isNotBlank()) {
            loaders.put(JSONObject().put("id", id).put("primary", true))
        }
        return JSONObject()
            .put(
                "minecraft", JSONObject()
                    .put("version", mcVersion)
                    .put("modLoaders", loaders)
            )
            .put("manifestType", "minecraftModpack")
            .put("manifestVersion", 1)
            .put("name", name)
            .put("version", version)
            .put("author", "ModMigrator")
            .put("files", arr)
            .put("overrides", "overrides")
            .toString(2)
    }

    /** CurseForge 的 modLoaders.id 形如 forge-47.1.0 */
    fun curseLoaderId(loader: String, loaderVersion: String): String {
        if (loader.isBlank()) return ""
        val v = loaderVersion.ifBlank { return "" }
        return "${loader.lowercase()}-$v"
    }

    /**
     * MultiMC / Prism 的 mmc-pack.json。
     *
     * 组件 uid 是 Prism 元数据服务发布的那一套，不是加载器自己的名字：
     * net.minecraft / net.minecraftforge / net.neoforged /
     * net.fabricmc.fabric-loader / org.quiltmc.quilt-loader。
     *
     * Fabric 与 Quilt 还必须额外钉一个 net.fabricmc.intermediary，
     * 版本跟 Minecraft 走（不是跟加载器版本走）——
     * 少了这一条，导出的实例在 Prism 里解析不出来。
     */
    fun multimc(mcVersion: String, loader: String, loaderVersion: String): String {
        val comps = JSONArray()
        if (mcVersion.isNotBlank()) {
            comps.put(
                JSONObject()
                    .put("uid", "net.minecraft")
                    .put("version", mcVersion)
                    .put("cachedName", "Minecraft")
                    .put("important", true)
            )
        }
        val uid = when (loader.lowercase()) {
            "forge" -> "net.minecraftforge"
            "neoforge" -> "net.neoforged"
            "fabric" -> "net.fabricmc.fabric-loader"
            "quilt" -> "org.quiltmc.quilt-loader"
            else -> ""
        }
        if (uid.isNotBlank() && loaderVersion.isNotBlank()) {
            if (uid.startsWith("net.fabricmc") || uid.startsWith("org.quiltmc")) {
                comps.put(
                    JSONObject()
                        .put("uid", "net.fabricmc.intermediary")
                        .put("version", mcVersion)
                        .put("cachedName", "Intermediary Mappings")
                        .put("cachedVolatile", true)
                        .put("dependencyOnly", true)
                )
            }
            comps.put(
                JSONObject()
                    .put("uid", uid)
                    .put("version", loaderVersion)
            )
        }
        return JSONObject()
            .put("formatVersion", 1)
            .put("components", comps)
            .toString(2)
    }
}

/**
 * CurseForge 的 murmur2 指纹。
 *
 * CurseForge 不用 SHA-1 认文件，用的是 murmur2（种子 1），
 * 而且算之前要把空白字节（0x09 / 0x0a / 0x0d / 0x20）全部剔除 ——
 * 这样重新打包、换行符变了也能认出是同一个文件。
 */
object CfFingerprint {

    fun of(ctx: Context, f: DocumentFile): Long {
        return try {
            val buf = ByteArrayOutputStream()
            ctx.contentResolver.openInputStream(f.uri)?.use { raw ->
                BufferedInputStream(raw, 1 shl 18).use { input ->
                    val tmp = ByteArray(1 shl 16)
                    var n: Int
                    while (input.read(tmp).also { n = it } > 0) buf.write(tmp, 0, n)
                }
            }
            murmur2(buf.toByteArray())
        } catch (t: Throwable) {
            0L
        }
    }

    /**
     * 批量反查指纹 →（项目号, 文件号）。
     *
     * 需要用户自己填的 CurseForge Key。没 Key 就返回空，
     * 调用方会把文件改成随包带走，而不是写一个错误的号。
     */
    fun match(fps: List<Long>, key: String): Map<Long, Pair<Long, Long>> {
        if (key.isBlank() || fps.isEmpty()) return emptyMap()
        return try {
            val arr = JSONArray()
            for (f in fps) arr.put(f)
            val body = JSONObject().put("fingerprints", arr).toString()
            val raw = Http.postJson(
                "https://api.curseforge.com/v1/fingerprints/432", body,
                mapOf("x-api-key" to key, "Content-Type" to "application/json")
            )
            val data = JSONObject(raw).optJSONObject("data") ?: return emptyMap()
            val hits = data.optJSONArray("exactMatches") ?: return emptyMap()
            val out = LinkedHashMap<Long, Pair<Long, Long>>()
            for (i in 0 until hits.length()) {
                val e = hits.optJSONObject(i) ?: continue
                val fileId = e.optLong("id", 0L)
                val fileObj = e.optJSONObject("file") ?: continue
                val projectId = fileObj.optLong("modId", 0L)
                if (fileId == 0L || projectId == 0L) continue
                // 靠文件对象里带回来的指纹对应，不靠下标 ——
                // 响应顺序不保证和请求一致，按位置配对会把 A 模组的号装到 B 模组上
                var fp = fileObj.optLong("fileFingerprint", 0L)
                if (fp == 0L && i < fps.size) fp = fps[i]
                out[norm(fp)] = Pair(projectId, fileId)
            }
            out
        } catch (t: Throwable) {
            Err.ignore(t, "CurseForge 指纹反查")
            emptyMap()
        }
    }

    /**
     * 归一化成无符号 32 位再比对。
     *
     * 各家实现有的按 Java int（有符号）发，有的按无符号发，
     * 回包里的 fileFingerprint 也可能不是同一种写法。
     * 直接比数值会对不上，统一按 2^32 取模才稳。
     */
    private fun norm(v: Long): Long = v and 0xffffffffL

    /** 返回按 Java int 解释的有符号值，和 CF 官方 Java 实现保持一致 */
    private fun murmur2(data: ByteArray): Long {
        val bytes = ArrayList<Byte>(data.size)
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v == 0x09 || v == 0x0a || v == 0x0d || v == 0x20) continue
            bytes.add(b)
        }
        val len = bytes.size
        val m = 0xc6a4a793L
        val r = 24
        var h = (1L xor len.toLong()) and 0xffffffffL
        var i = 0
        while (i + 4 <= len) {
            var k = (bytes[i].toLong() and 0xff) or
                ((bytes[i + 1].toLong() and 0xff) shl 8) or
                ((bytes[i + 2].toLong() and 0xff) shl 16) or
                ((bytes[i + 3].toLong() and 0xff) shl 24)
            k = (k * m) and 0xffffffffL
            k = k xor (k ushr r)
            k = (k * m) and 0xffffffffL
            h = (h * m) and 0xffffffffL
            h = h xor k
            i += 4
        }
        val rem = len - i
        if (rem == 3) h = h xor ((bytes[i + 2].toLong() and 0xff) shl 16)
        if (rem >= 2) h = h xor ((bytes[i + 1].toLong() and 0xff) shl 8)
        if (rem >= 1) {
            h = h xor (bytes[i].toLong() and 0xff)
            h = (h * m) and 0xffffffffL
        }
        h = h xor (h ushr 13)
        h = (h * m) and 0xffffffffL
        h = h xor (h ushr 15)
        h = h and 0xffffffffL
        return if (h >= 0x80000000L) h - 0x100000000L else h
    }
}
