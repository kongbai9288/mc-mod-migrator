package com.kongbai.modmigrator

import com.google.gson.JsonElement
import java.util.Locale

data class PanelServer(
    var id: String = "",
    var uuid: String = "",
    var name: String = "",
    var node: String = "",
    var egg: String = ""
)

data class PanelFile(
    var name: String = "",
    var path: String = "",
    var size: Long = 0,
    var kind: String = "mod",
    var sha1: String = "",
    var projectId: String = "",
    var currentVersion: String = "",
    var latestVersion: String = "",
    var latestUrl: String = "",
    var latestName: String = "",
    var status: String = "待检测"
)

object ServerPanelApi {

    fun auth(key: String) = mapOf(
        "Authorization" to "Bearer $key",
        "Accept" to "application/json"
    )

    fun servers(base: String, key: String): List<PanelServer> {
        val url = "${trim(base)}/api/client"
        val root = Json.obj(Http.get(url, auth(key))) ?: return emptyList()
        val data = Json.a(root, "data") ?: return emptyList()
        val out = mutableListOf<PanelServer>()
        for (d in data) {
            val attrs = d.asJsonObject.get("attributes")
            val uuid = Json.s(attrs, "uuid").ifBlank { Json.s(d, "identifier") }
            val eggName = Json.s(attrs, "name")
            var eggKind = ""
            val rel = d.asJsonObject.get("relationships")
            if (rel != null && rel.isJsonObject) {
                val egg = rel.asJsonObject.get("egg")
                eggKind = Json.s(if (egg != null && egg.isJsonObject) egg.asJsonObject.get("attributes") else null, "name")
            }
            out.add(
                PanelServer(
                    id = Json.s(d, "identifier").ifBlank { uuid },
                    uuid = uuid,
                    name = eggName,
                    node = Json.s(attrs, "node"),
                    egg = eggKind
                )
            )
        }
        return out
    }

    fun listFiles(base: String, key: String, uuid: String, dir: String): List<PanelFile> {
        val b = trim(base)
        val url = "$b/api/client/servers/${Http.enc(uuid)}/files/list?directory=${Http.enc(dir)}"
        return try {
            val root = Json.obj(Http.get(url, auth(key))) ?: return emptyList()
            val data = Json.a(root, "data") ?: return emptyList()
            val out = mutableListOf<PanelFile>()
            for (d in data) {
                val attrs = d.asJsonObject.get("attributes")
                val name = Json.s(attrs, "name")
                if (!name.endsWith(".jar", true)) continue
                out.add(
                    PanelFile(
                        name = name,
                        path = dir.trimEnd('/') + "/" + name,
                        size = Json.l(attrs, "size")
                    )
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun downloadLink(base: String, key: String, uuid: String, path: String): String {
        val b = trim(base)
        val url = "$b/api/client/servers/${Http.enc(uuid)}/files/download?file=${Http.enc(path)}"
        val o = Json.obj(Http.get(url, auth(key))) ?: return ""
        val attrs = o.asJsonObject.get("attributes")
        return Json.s(attrs, "url")
    }

    fun guessKind(path: String): String {
        val p = path.lowercase(Locale.ROOT)
        return when {
            p.contains("/plugins/") -> "plugin"
            p.contains("/mods/") -> "mod"
            else -> "mod"
        }
    }

    private fun trim(base: String): String {
        var b = base.trim()
        while (b.endsWith("/")) b = b.dropLast(1)
        if (!b.startsWith("http")) b = "https://$b"
        return b
    }

    /** 认证方式 */
    enum class Mode { KEY, LOGIN }

    data class Cred(
        var base: String = "",
        var mode: Mode = Mode.KEY,
        var key: String = "",
        var user: String = "",
        var pass: String = "",
        var token: String = ""
    )

    /**
     * 账号 + 密码登录 → 换取 Client API token。
     * Pterodactyl 的登录端点在各版本略有差异，这里依次尝试常见路径，
     * 并从返回体/响应头里尽力取出 token。
     */
    fun login(base: String, user: String, pass: String): String {
        val b = trim(base)
        val body = """{"user":"${esc(user)}","username":"${esc(user)}","password":"${esc(pass)}"}"""
        val candidates = listOf(
            "$b/api/auth/login",
            "$b/api/client/login",
            "$b/auth/login"
        )
        var lastErr = ""
        for (u in candidates) {
            try {
                val res = Http.postJson(u, body, mapOf("Accept" to "application/json"))
                val o = Json.obj(res)
                if (o == null) continue
                // 常见返回：data.token / token / attributes.token
                var t = Json.s(o, "token")
                if (t.isBlank()) {
                    val d = o.asJsonObject.get("data")
                    if (d != null) {
                        t = Json.s(d, "token")
                        if (t.isBlank()) t = Json.s(if (d.isJsonObject) d.asJsonObject.get("attributes") else null, "token")
                    }
                }
                if (t.isBlank()) {
                    val at = o.asJsonObject.get("attributes")
                    if (at != null) t = Json.s(at, "token")
                }
                if (t.isNotBlank()) return t
            } catch (e: Throwable) {
                lastErr = e.message ?: ""
            }
        }
        throw RuntimeException(
            if (lastErr.isNotBlank()) "登录失败：$lastErr" else "登录失败：面板未返回 token（可能版本不支持账号密码直登，请改用 API Key）"
        )
    }

    /** 拿最终可用的 token：账号密码模式会先登录 */
    fun tokenOf(c: Cred): String {
        if (c.mode == Mode.KEY) return c.key.trim()
        if (c.token.isNotBlank()) return c.token
        val t = login(c.base, c.user, c.pass)
        c.token = t
        return t
    }

    /** 常见模组/插件目录候选（服务器面板下各服务器根目录结构不统一，逐个试） */
    val DIR_CANDIDATES = listOf(
        "/mods", "/plugins", "/mod", "/plugin",
        "/data/mods", "/data/plugins",
        "/server/mods", "/server/plugins",
        "/home/container/mods", "/home/container/plugins",
        "/"
    )

    /** 自动探测：逐个试候选目录，返回第一个有 jar 的 */
    fun probeDirs(base: String, key: String, uuid: String): List<String> {
        val found = LinkedHashMap<String, Int>()
        for (d in DIR_CANDIDATES) {
            val fs = listFiles(base, key, uuid, d)
            if (fs.isNotEmpty()) found[d] = fs.size
        }
        return found.keys.toList()
    }

    /** 列出目录内容（不筛 jar），用于浏览面板目录树 */
    fun listRaw(base: String, key: String, uuid: String, dir: String): List<Pair<String, Boolean>> {
        val b = trim(base)
        val url = "$b/api/client/servers/${Http.enc(uuid)}/files/list?directory=${Http.enc(dir)}"
        val root = Json.obj(Http.get(url, auth(key))) ?: return emptyList()
        val data = Json.a(root, "data") ?: return emptyList()
        val out = mutableListOf<Pair<String, Boolean>>()
        for (d in data) {
            val attrs = d.asJsonObject.get("attributes")
            val name = Json.s(attrs, "name")
            val isDir = Json.b(attrs, "is_directory") ||
                Json.s(attrs, "mimetype") == "inode/directory"
            out.add(Pair(name, isDir))
        }
        return out
    }

    private fun esc(s: String): String = s.replace("\", "\\").replace(""", "\"")

}
