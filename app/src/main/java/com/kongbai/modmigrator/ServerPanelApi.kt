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
    var latestSource: String = "",
    var status: String = "待检测"
)

object ServerPanelApi {

    /**
     * Pterodactyl 客户端 API 的鉴权头。
     *
     * Accept 用官方推荐的 `Application/vnd.pterodactyl.v1+json`
     * （官方示例与各集成文档都是这个值），比 `application/json` 稳妥。
     */
    fun auth(key: String) = mapOf(
        "Authorization" to "Bearer ${key.trim()}",
        "Accept" to "Application/vnd.pterodactyl.v1+json",
        "Content-Type" to "application/json"
    )

    fun keyHint(key: String): String? {
        val k = key.trim()
        if (k.isBlank()) return "还没填 API Key"
        if (k.startsWith("ptla_") || k.startsWith("peli_")) {
            return "这是**应用 API Key**（${k.take(5)}…），客户端接口不接受它。" +
                "请到面板 /account/api 创建**客户端** API Key（以 ptlc_ 开头）"
        }
        if (!k.startsWith("ptlc_") && !k.startsWith("plcn_")) {
            return "这个 Key 不以 ptlc_ / plcn_ 开头，可能不是客户端 API Key"
        }
        return null
    }

    fun servers(base: String, key: String): List<PanelServer> {
        val url = "${trim(base)}/api/client"
        val root = Json.obj(Http.get(url, auth(key))) ?: return emptyList()
        val data = Json.a(root, "data") ?: return emptyList()
        val out = mutableListOf<PanelServer>()
        for (d in data) {
            val attrNode = d.asJsonObject.get("attributes")
            val attrs = if (attrNode != null && attrNode.isJsonObject) attrNode.asJsonObject else null

            val identifier = Json.s(attrs, "identifier")
            val uuid = Json.s(attrs, "uuid")
            // URL 里用短 id 或 uuid 都可以，优先短 id
            val id = identifier.ifBlank { uuid }

            if (id.isBlank()) continue

            val eggName = Json.s(attrs, "name")
            var eggKind = ""
            val rel = d.asJsonObject.get("relationships")
            if (rel != null && rel.isJsonObject) {
                val egg = rel.asJsonObject.get("egg")
                eggKind = Json.s(if (egg != null && egg.isJsonObject) egg.asJsonObject.get("attributes") else null, "name")
            }
            out.add(
                PanelServer(
                    id = id,
                    uuid = uuid.ifBlank { identifier },
                    name = eggName,
                    node = Json.s(attrs, "node"),
                    egg = eggKind
                )
            )
        }
        return out
    }

    fun listFiles(
        base: String, key: String, uuid: String, dir: String, timeout: Int = Http.NORMAL
    ): List<PanelFile> {
        val b = trim(base)
        val url = "$b/api/client/servers/${Http.enc(uuid)}/files/list?directory=${Http.enc(dir)}"
        return try {
            val root = Json.obj(Http.get(url, auth(key), timeout)) ?: return emptyList()
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

    val LOGIN_UNSUPPORTED_MSG = "无法用账号密码登录。\n\n" +
        "Pterodactyl 的客户端接口只接受 **Client API Key**：\n" +
        "登录面板 → 右上角账号 → API 凭证（/account/api）→ 创建，\n" +
        "复制那把以 ptlc_ 开头的 Key，填回这里即可。\n\n" +
        "（注意：以 ptla_ 开头的是应用 API Key，客户端接口不接受。）"

    fun login(base: String, user: String, pass: String): String? = null

    /**
     * 拿最终可用的 token。
     * 账号密码模式拿不到，返回 null（调用方必须判空，不能拿去拼 URL）。
     */
    fun tokenOf(c: Cred): String? {
        if (c.mode == Mode.KEY) return c.key.trim().ifBlank { null }
        if (c.token.isNotBlank()) return c.token
        val t = login(c.base, c.user, c.pass)
        c.token = t ?: ""
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

    fun probeDirs(base: String, key: String, uuid: String): List<String> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            (DIR_CANDIDATES.size).coerceAtMost(6)
        )
        return try {
            // 用有序容器 + 按下标定长数组，保证**结果顺序与候选顺序一致**，
            // 并发下不能用 LinkedHashMap 边跑边 put（顺序会乱）
            val slots = arrayOfNulls<String>(DIR_CANDIDATES.size)
            val tasks = DIR_CANDIDATES.mapIndexed { i, d ->
                pool.submit {
                    try {
                        // 只关心"这个目录有没有内容"，不关心内容是什么
                        val fs = listFiles(base, key, uuid, d, Http.SHORT)
                        if (fs.isNotEmpty()) slots[i] = d
                    } catch (_: Throwable) {
                        // 单个目录失败 = 它不存在，忽略即可
                    }
                }
            }
            for (t in tasks) {
                try { t.get() } catch (_: Throwable) {}
            }
            slots.filterNotNull()
        } finally {
            pool.shutdownNow()
        }
    }

    /** 列出目录内容（不筛 jar），用于浏览面板目录树 */
    fun listRaw(base: String, key: String, uuid: String, dir: String): List<Pair<String, Boolean>> {
        val b = trim(base)
        val url = "$b/api/client/servers/${Http.enc(uuid)}/files/list?directory=${Http.enc(dir)}"
        val root = Json.obj(Http.get(url, auth(key), Http.SHORT)) ?: return emptyList()
        val data = Json.a(root, "data") ?: return emptyList()
        val out = mutableListOf<Pair<String, Boolean>>()
        for (d in data) {
            val attrs = d.asJsonObject.get("attributes")
            val name = Json.s(attrs, "name")
            val isDir = !Json.b(attrs, "is_file", true) ||
                Json.s(attrs, "mimetype") == "inode/directory"
            out.add(Pair(name, isDir))
        }
        return out
    }

    private fun esc(x: String): String = x.replace("\\", "\\\\").replace("\"", "\\\"")

}
