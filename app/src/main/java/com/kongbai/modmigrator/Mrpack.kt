package com.kongbai.modmigrator

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Modrinth 整合包格式（.mrpack）—— 全项目只在这里定义。
 *
 * 之所以不自己发明一种打包方式：Modrinth 是这个格式的发起者，
 * Prism / Modrinth App / GDLauncher 都按同一份规范实现。
 * 照规范写出来的包能直接被这些启动器导入；自己造的格式只能自己开。
 *
 * 规范要点（官方）：
 *  - 本质是一个 zip，MIME 为 application/x-modrinth-modpack+zip，扩展名 .mrpack
 *  - 根目录放 modrinth.index.json（UTF-8），这是唯一的必需文件
 *  - overrides/ 里的文件由启动器直接复制到实例根目录
 *    client-overrides/、server-overrides/ 分别在客户端/服务端再覆盖一层
 *  - files[] 描述**需要下载**的文件：path + hashes(sha1/sha512) + downloads + fileSize
 *    放进 files[] 的文件**不打包进 zip**，靠 downloads 远程拉取
 *
 * ⚠️ 最容易搞错的一条：files[] 与 overrides/ 是互斥的两种携带方式。
 *    同一个文件既塞进 overrides 又在 files[] 里登记，
 *    启动器读到 files[] 会去下载却没有地址 —— 直接导入失败。
 */
object Mrpack {

    const val FORMAT_VERSION = 1
    const val GAME = "minecraft"
    const val INDEX_NAME = "modrinth.index.json"

    const val OVERRIDES = "overrides"
    const val CLIENT_OVERRIDES = "client-overrides"
    const val SERVER_OVERRIDES = "server-overrides"

    /**
     * 导出包名的用途后缀。
     *
     * 之前导出一律叫「实例名.mrpack」，导几个就分不清哪个是模组包、
     * 哪个是地图包。现在名字里直接写用途：xxx_mods / xxx_map。
     */
    enum class Kind(val suffix: String, val label: String) {
        FULL("full", "全量（模组 + 配置）"),
        MODS("mods", "仅模组"),
        CONFIG("config", "仅配置"),
        MAP("map", "地图 / 存档")
    }

    /**
     * 官方规定的下载域名白名单。
     *
     * 规范里写明：上传到 Modrinth 的包会校验 downloads 域名，
     * 只允许 cdn.modrinth.com / github.com / raw.githubusercontent.com / gitlab.com。
     * 我们自己生成的包虽然不上传，但启动器会按同样规则校验 ——
     * 把第三方直链写进 downloads，导入时会被拒。
     * 所以域名不在白名单里的文件一律改走 overrides/（随包带走）。
     */
    private val ALLOWED_HOSTS = setOf(
        "cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com",
        // 规范允许各实现自定义白名单。国内直连 Modrinth CDN 经常不通，
        // 所以把项目自带的镜像域名也列入 —— 用镜像导出的包在启动器里
        // 装不上没有意义，宁可放宽域名也要保证能下载。
        "mod.mcimirror.top"
    )

    data class Env(val client: String, val server: String)

    data class FileEntry(
        val path: String,
        val sha512: String,
        val sha1: String,
        val downloads: List<String>,
        val fileSize: Long,
        val env: Env? = null
    )

    data class Index(
        val name: String,
        val versionId: String,
        val summary: String = "",
        val files: List<FileEntry> = emptyList(),
        val dependencies: Map<String, String> = emptyMap()
    ) {
        val mcVersion: String get() = dependencies["minecraft"].orEmpty()
        val loader: String
            get() = listOf("fabric-loader", "quilt-loader", "neoforge", "forge")
                .firstOrNull { dependencies.containsKey(it) }.orEmpty()
    }

    // ------------------------------------------------------------------
    // 序列化 / 反序列化
    // ------------------------------------------------------------------

    fun toJson(index: Index): String {
        val files = JSONArray()
        for (f in index.files) {
            val o = JSONObject()
                .put("path", f.path)
                .put("hashes", JSONObject()
                    .put("sha512", f.sha512)
                    .put("sha1", f.sha1))
                .put("fileSize", f.fileSize)
            val d = JSONArray()
            for (u in f.downloads) d.put(u)
            o.put("downloads", d)
            if (f.env != null) {
                o.put("env", JSONObject()
                    .put("client", f.env.client)
                    .put("server", f.env.server))
            }
            files.put(o)
        }
        val deps = JSONObject()
        for ((k, v) in index.dependencies) if (v.isNotBlank()) deps.put(k, v)
        return JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("game", GAME)
            .put("versionId", index.versionId)
            .put("name", index.name)
            .put("summary", index.summary)
            .put("files", files)
            .put("dependencies", deps)
            .toString(2)
    }

    /**
     * 解析 modrinth.index.json。
     *
     * @return 解析失败返回 null。失败原因写进日志，方便排查"导入后什么都没有"。
     */
    fun parse(raw: String?): Index? {
        if (raw.isNullOrBlank()) {
            LogCenter.w("Mrpack", "清单为空")
            return null
        }
        val o = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            LogCenter.w("Mrpack", "清单不是合法 JSON：${t.message}")
            return null
        }
        val fv = o.optInt("formatVersion", -1)
        if (fv != FORMAT_VERSION) {
            // 规范说得很清楚：以后可能加新的 formatVersion。
            // 遇到不认识的版本就该明确拒绝，而不是硬着头皮解析，
            // 否则会静默丢字段，用户只会觉得"装完少东西"。
            LogCenter.w("Mrpack", "不支持的 formatVersion：$fv（当前只支持 $FORMAT_VERSION）")
            return null
        }
        val game = o.optString("game")
        if (game.isNotBlank() && game != GAME) {
            LogCenter.w("Mrpack", "不是 Minecraft 整合包：game=$game")
            return null
        }

        val files = ArrayList<FileEntry>()
        val arr = o.optJSONArray("files")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val path = e.optString("path")
                if (path.isBlank()) continue
                if (!safePath(path)) {
                    // 路径越界是安全问题不是小毛病：规范里专门警告过
                    // `..` 或绝对路径会让启动器把文件写到实例目录之外。
                    LogCenter.w("Mrpack", "跳过不安全的路径：$path")
                    continue
                }
                val h = e.optJSONObject("hashes")
                val sha1 = h?.optString("sha1").orEmpty()
                val sha512 = h?.optString("sha512").orEmpty()
                val dl = ArrayList<String>()
                val darr = e.optJSONArray("downloads")
                if (darr != null) {
                    for (j in 0 until darr.length()) {
                        val u = darr.optString(j)
                        if (u.isNotBlank() && allowedUrl(u)) dl.add(u)
                    }
                }
                val envObj = e.optJSONObject("env")
                val env = if (envObj != null) {
                    Env(
                        envObj.optString("client", "required"),
                        envObj.optString("server", "required")
                    )
                } else null
                files.add(
                    FileEntry(
                        path = path,
                        sha512 = sha512,
                        sha1 = sha1,
                        downloads = dl,
                        fileSize = e.optLong("fileSize", 0L),
                        env = env
                    )
                )
            }
        }

        val deps = LinkedHashMap<String, String>()
        val dobj = o.optJSONObject("dependencies")
        if (dobj != null) {
            val it = dobj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = dobj.optString(k)
                if (v.isNotBlank()) deps[k] = v
            }
        }

        return Index(
            name = o.optString("name").ifBlank { "未命名整合包" },
            versionId = o.optString("versionId").ifBlank { "1.0.0" },
            summary = o.optString("summary"),
            files = files,
            dependencies = deps
        )
    }

    /**
     * 路径安全性检查。
     *
     * 规范原文：path 不得包含 `..`，不得以盘名（[A-Z]:/ 或 [A-Z]:\）、
     * `/`、`\` 开头。不校验的话，一个恶意包可以把文件写到实例目录之外。
     */
    fun safePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains("..")) return false
        // 盘符：C:/ C:\
        if (path.length >= 2 && path[1] == ':' &&
            path[0] in 'A'..'Z'
        ) return false
        if (path.any { it == '\u0000' }) return false
        return true
    }

    /** downloads 里的地址必须是 https 且域名在白名单内 */
    fun allowedUrl(url: String): Boolean {
        if (!url.startsWith("https://", true)) return false
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().lowercase()
        if (host.isBlank()) return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** 生成规范的文件名：名称_用途.mrpack */
    fun fileName(name: String, kind: Kind): String {
        val safe = name.trim().replace(Regex("[\\\\/:*?\"<>|\r\n\t]"), "_")
            .trim().ifBlank { "modpack" }
        return "${safe}_${kind.suffix}.mrpack"
    }

    // ------------------------------------------------------------------
    // 哈希
    // ------------------------------------------------------------------

    /**
     * 一次遍历同时算出 SHA-512 与 SHA-1。
     *
     * 官方要求 hashes 里**必须同时有**这两个。
     * 分两次算等于把 mods 目录整个读两遍，而这里的文件多走 SAF，
     * 每次 read 都是跨进程调用 —— 重复读的代价特别明显。
     */
    fun hashes(ctx: Context, f: DocumentFile): Pair<String, String> {
        return try {
            val md512 = MessageDigest.getInstance("SHA-512")
            val md1 = MessageDigest.getInstance("SHA-1")
            ctx.contentResolver.openInputStream(f.uri)?.use { raw ->
                // SAF 给的流本身不带缓冲，每次 read 都是一次 Binder IPC，
                // 必须自己包一层 BufferedInputStream。
                java.io.BufferedInputStream(raw, 1 shl 18).use { input ->
                    val buf = ByteArray(1 shl 16)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        md512.update(buf, 0, n)
                        md1.update(buf, 0, n)
                    }
                }
            }
            Pair(hex(md512.digest()), hex(md1.digest()))
        } catch (t: Throwable) {
            Pair("", "")
        }
    }

    fun sha1Of(bytes: ByteArray): String = try {
        hex(MessageDigest.getInstance("SHA-1").digest(bytes))
    } catch (t: Throwable) {
        ""
    }

    fun sha512Of(bytes: ByteArray): String = try {
        hex(MessageDigest.getInstance("SHA-512").digest(bytes))
    } catch (t: Throwable) {
        ""
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
