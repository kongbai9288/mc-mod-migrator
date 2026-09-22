package com.kongbai.modmigrator

import android.util.Base64
import com.google.gson.JsonObject

object GitHubApi {

    private fun url(owner: String, repo: String, path: String) =
        "https://api.github.com/repos/$owner/$repo/contents/$path"

    private fun auth(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json"
    )

    fun latestRelease(owner: String, repo: String, token: String): String {
        val url = "https://api.github.com/repos/${Http.enc(owner)}/${Http.enc(repo)}/releases/latest"
        val h = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
        return Http.get(url, h)
    }

    fun listDir(owner: String, repo: String, path: String, branch: String, token: String): List<String> {
        val u = "${url(owner, repo, path)}?ref=${Http.enc(branch)}"
        val arr = Json.arr(Http.get(u, auth(token))) ?: return emptyList()
        val out = mutableListOf<String>()
        for (e in arr) out.add(Json.s(e, "name"))
        return out
    }

    fun meta(owner: String, repo: String, path: String, branch: String, token: String): JsonObject? {
        val u = "${url(owner, repo, path)}?ref=${Http.enc(branch)}"
        return try {
            Json.obj(Http.get(u, auth(token)))
        } catch (t: Throwable) {
            null
        }
    }

    fun sha(owner: String, repo: String, path: String, branch: String, token: String): String? {
        val m = meta(owner, repo, path, branch, token)
        return if (m == null) null else Json.s(m, "sha")
    }

    fun getText(owner: String, repo: String, path: String, branch: String, token: String): String? {
        val m = meta(owner, repo, path, branch, token) ?: return null
        val c = Json.s(m, "content")
        if (c.isBlank()) return null
        val bytes = Base64.decode(c.replace("\n", ""), Base64.DEFAULT)
        return String(bytes, Charsets.UTF_8)
    }

    fun getBytes(owner: String, repo: String, path: String, branch: String, token: String): ByteArray? {
        val m = meta(owner, repo, path, branch, token) ?: return null
        val c = Json.s(m, "content")
        if (c.isBlank()) return null
        return Base64.decode(c.replace("\n", ""), Base64.DEFAULT)
    }

    fun putBase64(
        owner: String,
        repo: String,
        path: String,
        branch: String,
        token: String,
        base64: String,
        message: String,
        sha: String?
    ): Boolean {
        val o = JsonObject()
        o.addProperty("message", message)
        o.addProperty("content", base64)
        o.addProperty("branch", branch)
        if (!sha.isNullOrBlank()) o.addProperty("sha", sha)
        return try {
            Http.put(url(owner, repo, path), o.toString(), auth(token))
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun putText(
        owner: String,
        repo: String,
        path: String,
        branch: String,
        token: String,
        text: String,
        message: String
    ): Boolean {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return putBase64(owner, repo, path, branch, token, b64, message, sha(owner, repo, path, branch, token))
    }
}
