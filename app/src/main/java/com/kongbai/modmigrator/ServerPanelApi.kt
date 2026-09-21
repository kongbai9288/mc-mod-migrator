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
}
