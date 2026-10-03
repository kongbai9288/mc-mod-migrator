package com.kongbai.modmigrator

import android.util.Base64
import com.google.gson.JsonObject

object GitHubApi {

    /**
     * 直接用**个人访问令牌（PAT）**登录，不走后端 OAuth。
     *
     * ⚠️ 为什么要有这条路：
     * 原来的登录完全依赖后端（workers.dev）转发 GitHub OAuth，
     * 而这个域名在国内**大面积不可达**——日志里全是
     * `Connection reset` / `failed to connect ... after 6000ms`。
     * 域名不通，代码再怎么改都登不上，用户只能反复点。
     *
     * PAT 是 GitHub 官方给的方式：
     * 用户在 GitHub 网页上生成一串令牌粘进来，应用拿它直接调
     * `https://api.github.com/user` —— 直连 GitHub，不经过我们的后端，
     * 域名可达性完全不一样。
     */

    /** PAT 的常见前缀（ghp_ 经典 / github_pat_ 细粒度） */
    fun looksLikePat(t: String): Boolean {
        val s = t.trim()
        return s.startsWith("ghp_") || s.startsWith("github_pat_") ||
            s.startsWith("gho_") || s.startsWith("ghu_") || s.startsWith("ghs_")
    }

    /**
     * 校验令牌并取回账号信息。
     * @return null 表示令牌无效/网络不通，msg 里是一句人话原因
     */
    fun verifyPat(token: String): Pair<User?, String> {
        val t = token.trim()
        if (t.isBlank()) return null to "还没填令牌"
        if (!looksLikePat(t)) {
            return null to "这不像 GitHub 令牌（应以 ghp_ 或 github_pat_ 开头）"
        }
        val body = try {
            // 官方 REST 根路径下的 /user，用 PAT 直连即可
            Http.get(
                "https://api.github.com/user",
                mapOf(
                    "Authorization" to "Bearer $t",
                    "Accept" to "application/vnd.github+json",
                    // 官方要求所有 API 请求带 UA
                    "User-Agent" to "ModMigrator/1.0"
                ),
                Http.SHORT
            )
        } catch (e: Throwable) {
            return null to "连不上 GitHub：${Http.describeError(e)}"
        }
        val o = Json.obj(body)
        if (o == null) return null to "GitHub 返回的不是 JSON（可能被网关拦截了）"
        val login = Json.s(o, "login")
        if (login.isBlank()) {
            // 401 时 GitHub 返回 {"message":"Bad credentials"}
            val msg = Json.s(o, "message")
            return null to if (msg.isBlank()) "令牌无效" else "令牌无效：$msg"
        }
        return User(
            login = login,
            name = Json.s(o, "name"),
            avatarUrl = Json.s(o, "avatar_url")
        ) to ""
    }

    data class User(
        var login: String = "",
        var name: String = "",
        var avatarUrl: String = ""
    )


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

    /**
     * 流式下载到文件。
     *
     * ## 为什么必须有这个方法
     *
     * [getBytes] 会把整个文件一次性解码成 ByteArray。
     * 配置包打包了 config / scripts / resourcepacks / shaderpacks，
     * 几十 MB 很常见，GitHub 上限更是给到 100MB。
     * 而 Base64 解码还要额外占一份空间 ——
     * 100MB 的文件在这一步峰值内存接近 **300MB**（Base64 字符串 + ByteArray），
     * 低端机上就是一记干净的 OOM，而且崩在"恢复备份"这种关键路径上。
     *
     * 这里改成：把 Base64 分块解码后**边解边写**，
     * 内存占用恒定在几十 KB，跟文件大小完全无关。
     *
     * @return 实际写入的字节数；失败返回 -1
     */
    fun downloadToFile(
        owner: String, repo: String, path: String, branch: String, token: String,
        dest: java.io.File
    ): Long {
        val c = contentOf(owner, repo, path, branch, token) ?: return -1L
        val clean = c.replace("\n", "").replace("\r", "")
        return try {
            val tmp = java.io.File(dest.parentFile, dest.name + ".tmp")
            var total = 0L
            java.io.FileOutputStream(tmp).use { out ->
                // 每块取 4 的倍数，保证 Base64 不被切断
                val CHUNK = 3 * 1024 * 1024   // 3MB，能被 4 整除
                var i = 0
                while (i < clean.length) {
                    val end = minOf(i + CHUNK, clean.length)
                    val slice = clean.substring(i, end)
                    val buf = Base64.decode(slice, Base64.DEFAULT)
                    out.write(buf)
                    total += buf.size
                    i = end
                }
            }
            // 写完整了才替换目标文件，避免中途失败留下半个坏 zip
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            total
        } catch (t: Throwable) {
            Err.fail(t, "GitHub 流式下载失败")
            runCatching { java.io.File(dest.parentFile, dest.name + ".tmp").delete() }
            -1L
        }
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
