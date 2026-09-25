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

    /**
     * Content API 单文件的实际内容上限。
     *
     * GitHub 的 **Contents API**（`GET/PUT /repos/{o}/{r}/contents/{path}`）
     * 对文件有约 **1MB** 的实际限制：
     *   - 读取时：超过后响应里的 `content` 是**空字符串**、`encoding` 变成 `none`
     *   - 写入时：超过后直接 **422** 失败
     * 超过 1MB 的文件要走 **Git Data / Blobs API**（`GET /repos/{o}/{r}/git/blobs/{sha}`，
     * 上限 100MB）。这是 GitHub 官方文档与社区实践里的通行做法。
     *
     * 我们的备份 config.zip 打包了 config / scripts / resourcepacks / shaderpacks，
     * **很容易超过 1MB**。之前没处理这个限制，于是：
     *   - 上传：PUT 422 → 只显示"配置包上传失败"，用户不知道为什么
     *   - 恢复：GET 返回空 content → `getBytes` 返回 null → 恢复不出来也没提示
     * 两边都表现为"备份功能不好用"，但完全没有错误信息。
     */
    const val CONTENT_MAX_BYTES = 1024 * 1024

    /** 上一次失败的原因（给界面提示用） */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * 取文件的 base64 内容。
     * 超过 1MB 时 Contents API 不给内容，改用 Blobs API。
     */
    private fun contentOf(
        owner: String, repo: String, path: String, branch: String, token: String
    ): String? {
        val m = meta(owner, repo, path, branch, token) ?: run {
            lastError = "取不到文件信息"
            return null
        }
        var c = Json.s(m, "content")
        if (c.isNotBlank()) return c

        // ── 超过 1MB：content 为空，改用 Blobs API ──────────────
        val sha = Json.s(m, "sha")
        if (sha.isBlank()) {
            lastError = "服务端没有返回文件内容"
            return null
        }
        c = blobContent(owner, repo, sha, token)
        if (c.isBlank()) {
            lastError = "文件超过 1MB，走 Blobs API 也没取到（sha=$sha）"
            return null
        }
        return c
    }

    /** Git Data / Blobs API 读取（支持到 100MB） */
    private fun blobContent(owner: String, repo: String, sha: String, token: String): String {
        val u = "https://api.github.com/repos/$owner/$repo/git/blobs/$sha"
        return try {
            val o = Json.obj(Http.get(u, auth(token))) ?: return ""
            // 大 blob 也可能只给 sha，此时确实拿不到
            Json.s(o, "content")
        } catch (t: Throwable) {
            Err.ignore(t, "Blobs API 读取")
            ""
        }
    }

    fun getText(owner: String, repo: String, path: String, branch: String, token: String): String? {
        val c = contentOf(owner, repo, path, branch, token) ?: return null
        val bytes = Base64.decode(c.replace("\n", ""), Base64.DEFAULT)
        return String(bytes, Charsets.UTF_8)
    }

    fun getBytes(owner: String, repo: String, path: String, branch: String, token: String): ByteArray? {
        val c = contentOf(owner, repo, path, branch, token) ?: return null
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

        // ── 超过 1MB 直接说明原因，不要让 422 变成一句没头没脑的"失败" ──
        // 原始体积 ≈ base64 长度 × 3/4
        val raw = base64.length * 3 / 4
        if (raw > CONTENT_MAX_BYTES) {
            lastError = buildString {
                append("文件 ${String.format("%.1f", raw / 1048576.0)}MB，")
                append("超过 GitHub Contents API 约 1MB 的上限（会返回 422）。")
                append("请精简内容（比如去掉光影包/资源包），或改用网盘备份。")
            }
            LogCenter.w("GitHubApi", lastError)
            return false
        }

        return try {
            Http.put(url(owner, repo, path), o.toString(), auth(token))
            lastError = ""
            true
        } catch (t: Throwable) {
            lastError = buildString {
                append("上传失败：${t.message?.take(120)}")
                if (t.message?.contains("422") == true) {
                    append("（422 通常是文件过大，或更新时没带 sha）")
                }
            }
            Err.ignore(t, "GitHub 上传")
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
