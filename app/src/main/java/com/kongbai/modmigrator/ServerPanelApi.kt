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

    /**
     * 校验 key 形态，提前给出人话提示。
     *
     * 官方明确区分两类 key：
     *   - **Client API key**：`ptlc_`（Pterodactyl）/ `plcn_`（Pelican），
     *     用户在 `/account/api` 自己创建，客户端 API **只认这种**
     *   - **Application API key**：`ptla_` / `peli_`，管理员用，
     *     拿它访问 `/api/client/...` 会直接 403
     * 用户常常填错，而 403 的返回体对用户等于天书，这里提前拦下来。
     */
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

            // ── 关键修正：identifier 在 **attributes 里**，不在对象顶层 ──
            // 之前写的是 Json.s(d, "identifier")（读对象顶层），
            // 而 Pterodactyl 的返回是
            // { object:"server", attributes:{ identifier:"abcd1234", uuid:"...", name:"...", node:"..." } }
            // → 顶层没有 identifier，取到空串；uuid 若也没有，
            //   最终 id = "" → 拼出 /api/client/servers//files/list
            //   → 404，表现为"服务器列表出来了，但点进去什么都加载不了"。
            val identifier = Json.s(attrs, "identifier")
            val uuid = Json.s(attrs, "uuid")
            // URL 里用短 id 或 uuid 都可以，优先短 id
            val id = identifier.ifBlank { uuid }

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

    /**
     * 关于「账号 + 密码登录」。
     *
     * ⚠️ 之前这里依次 POST `/api/auth/login`、`/api/client/login`、`/auth/login`，
     * 然后从返回体里找 `token` —— **这条路走不通**：
     *
     * 1. Pterodactyl 客户端 API **只认 `ptlc_` 开头的 Client API Key**，
     *    官方文档明确：Client API Key 由用户在 **`/account/api`** 页面手动创建，
     *    **没有任何 API 能用账号密码换出一把 ptlc_ key**。
     * 2. 面板的 `/auth/login` 是 **Web 登录路由**（Laravel/Sanctum 会话）：
     *    要先 GET `/sanctum/csrf-cookie` 拿 XSRF-TOKEN，再带
     *    `X-XSRF-TOKEN` / `Referer` / `X-Pterodactyl-Route: 1` 提交
     *    `{"user":..., "password":...}`，返回的是 **会话 Cookie**，
     *    不是 token —— 而 OkHttp 的 Cookie 又和面板的 API 鉴权不是一回事，
     *    照样访问不了 `/api/client/...`。
     *
     * 所以这个功能以前只是"点了没反应然后报一句登录失败"。
     * 现在如实说明，把用户引到真正能用的做法上，不再假装能登。
     */
    fun login(base: String, user: String, pass: String): String {
        throw RuntimeException(
            "无法用账号密码登录。\n\n" +
                "Pterodactyl 的客户端接口只接受 **Client API Key**：\n" +
                "登录面板 → 右上角账号 → API 凭证（/account/api）→ 创建，\n" +
                "复制那把以 ptlc_ 开头的 Key，填回这里即可。\n\n" +
                "（注意：以 ptla_ 开头的是应用 API Key，客户端接口不接受。）"
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

    /**
     * 自动探测：逐个试候选目录，返回有 jar 的那些。
     *
     * 探测是**串行**的且要试十来个目录，之前用默认超时（读取 120 秒），
     * 面板地址填错或网络不通时要等上好几分钟才出结果，
     * 看起来就是"点连接没反应"。改成短超时快速失败。
     */
    fun probeDirs(base: String, key: String, uuid: String): List<String> {
        val found = LinkedHashMap<String, Int>()
        for (d in DIR_CANDIDATES) {
            val fs = listFiles(base, key, uuid, d, Http.SHORT)
            if (fs.isNotEmpty()) found[d] = fs.size
        }
        return found.keys.toList()
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
            // ── 官方字段是 is_file，不是 is_directory ──
            // Pterodactyl 文件对象 attributes 实际为：
            //   name / mode / mode_bits / size / **is_file** / is_symlink / mimetype
            // 没有 is_directory 这个字段。之前靠它判定，
            // 恒为 false → 文件夹被当成文件，目录树点不进去。
            // 现在以 is_file 为主，mimetype 兜底。
            val isDir = !Json.b(attrs, "is_file", true) ||
                Json.s(attrs, "mimetype") == "inode/directory"
            out.add(Pair(name, isDir))
        }
        return out
    }

    private fun esc(x: String): String = x.replace("\\", "\\\\").replace("\"", "\\\"")

}
