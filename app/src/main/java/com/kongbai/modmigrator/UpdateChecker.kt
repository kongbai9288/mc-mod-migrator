package com.kongbai.modmigrator

import android.content.Context

/**
 * 应用更新提醒。
 *
 * 先取仓库 release 信息，再逐个尝试镜像源拿到可下载地址。
 * 仓库地址不写死，设置里可改；镜像源只是「下载加速」，
 * 取不到就直接用官方直链，不会阻断更新。
 */
object UpdateChecker {

    data class Release(
        var tag: String = "",
        var name: String = "",
        var notes: String = "",
        var apkUrl: String = "",
        var size: Long = 0,
        var published: String = ""
    )

    /** 镜像模板：{owner}/{repo}/{tag}/{file} 会被替换 */
    private val MIRRORS = listOf(
        "https://ghfast.top/https://github.com/{owner}/{repo}/releases/download/{tag}/{file}",
        "https://gh-proxy.com/https://github.com/{owner}/{repo}/releases/download/{tag}/{file}",
        "https://gh.llkk.cc/https://github.com/{owner}/{repo}/releases/download/{tag}/{file}",
        "https://hub.fastgit.org/{owner}/{repo}/releases/download/{tag}/{file}",
        "https://raw.gitmirror.com/{owner}/{repo}/gh-pages/{tag}/{file}"
    )

    fun owner(ctx: Context): String {
        val s = Prefs.get(ctx).getString(K.OWNER, "") ?: ""
        return s.trim()
    }

    fun repo(ctx: Context): String {
        val s = Prefs.get(ctx).getString(K.REPO, "") ?: ""
        return s.trim()
    }

    /** 没填仓库就用自己的仓库作为默认来源（不写死到代码逻辑，只是兜底展示） */
    fun defaultOwner() = "kongbai9288"
    fun defaultRepo() = "mc-mod-migrator"

    /**
     * 取最新版本。
     *
     * 之前只打 api.github.com 一个地址：国内经常连不上，
     * 一失败就 catch 成 null → 永远"没有更新"。
     * 现在多个来源依次尝试，谁通了用谁；全部失败才返回 null，
     * 并把失败原因记下来，界面能显示"为什么没查到"。
     */
    fun latest(ctx: Context): Release? {
        val o = owner(ctx).ifBlank { defaultOwner() }
        val r = repo(ctx).ifBlank { defaultRepo() }
        val token = Prefs.get(ctx).getString(K.TOKEN, "") ?: ""

        val errs = ArrayList<String>()
        for ((idx, url) in releaseApiUrls(o, r).withIndex()) {
            try {
                val json = Http.get(url, headersFor(idx, token))
                val rel = parseRelease(json, o, r)
                if (rel != null) {
                    lastError = ""
                    return rel
                }
                errs.add("解析失败")
            } catch (t: Throwable) {
                errs.add("${hostOf(url)}: ${t.message?.take(40)}")
            }
        }
        lastError = errs.take(2).joinToString("；")
        return null
    }

    /** 最后一次失败原因，界面展示用 */
    @Volatile
    var lastError: String = ""

    /**
     * release 信息的多个来源。
     * 前几个是 GitHub API 的镜像（能拿到完整 assets），
     * 最后是 jsdelivr 的数据接口（只能拿版本号，但国内稳定）。
     */
    private fun releaseApiUrls(owner: String, repo: String): List<String> = listOf(
        "https://api.github.com/repos/$owner/$repo/releases/latest",
        "https://ghfast.top/https://api.github.com/repos/$owner/$repo/releases/latest",
        "https://gh-proxy.com/https://api.github.com/repos/$owner/$repo/releases/latest",
        "https://gh.llkk.cc/https://api.github.com/repos/$owner/$repo/releases/latest",
        "https://cdn.jsdelivr.net/gh/$owner/$repo@latest/",
        "https://data.jsdelivr.com/v1/package/gh/$owner/$repo"
    )

    /** 只有第一个（官方 API）才带 token，镜像站带了反而可能被拒 */
    private fun headersFor(idx: Int, token: String): Map<String, String> {
        if (idx != 0 || token.isBlank()) return emptyMap()
        return mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json"
        )
    }

    private fun hostOf(u: String): String =
        u.removePrefix("https://").substringBefore('/')

    /** 兼容两种返回：标准 release 对象 / jsdelivr 的 versions 数组 */
    private fun parseRelease(json: String, owner: String, repo: String): Release? {
        // 标准 release
        val root = runCatching { Json.obj(json) }.getOrNull()
        if (root != null) {
            val tag = Json.s(root, "tag_name")
            if (tag.isNotBlank()) {
                val assets = Json.a(root, "assets")
                var url = ""
                var size = 0L
                if (assets != null) {
                    for (a in assets) {
                        if (Json.s(a, "name").endsWith(".apk", true)) {
                            url = Json.s(a, "browser_download_url")
                            size = Json.l(a, "size")
                            break
                        }
                    }
                }
                // 没解析出 apk 就按约定拼一个，下载时再走镜像探测
                if (url.isBlank()) {
                    url = "https://github.com/$owner/$repo/releases/download/$tag/ModMigrator-release.apk"
                }
                return Release(
                    tag = tag,
                    name = Json.s(root, "name").ifBlank { tag },
                    // 之前的 release body 是 CI 写死的模板，每次都一样，
                    // 显示出来毫无意义（用户看到的是"更新内容"却永远是同一句话）。
                    // 这里识别并过滤掉模板文案，留空则由界面不显示该栏。
                    notes = realNotes(Json.s(root, "body")),
                    apkUrl = url,
                    size = size,
                    published = Json.s(root, "published_at")
                )
            }

            // jsdelivr data API：{ "tags": {...}, "versions": [{"version":"1.1.0"}] }
            val versions = Json.a(root, "versions")
            if (versions != null && versions.size() > 0) {
                val v = if (versions.size() > 0) Json.s(versions.get(0), "version") else ""
                if (v.isNotBlank()) {
                    return Release(
                        tag = v,
                        name = v,
                        notes = "（来自镜像源，无更新说明）",
                        apkUrl = "https://github.com/$owner/$repo/releases/download/$v/ModMigrator-release.apk"
                    )
                }
            }
            val tags = root.asJsonObject.get("tags")
            if (tags != null && tags.isJsonObject) {
                val latest = tags.asJsonObject.keySet().firstOrNull()
                if (!latest.isNullOrBlank()) {
                    return Release(
                        tag = latest,
                        name = latest,
                        notes = "（来自镜像源，无更新说明）",
                        apkUrl = "https://github.com/$owner/$repo/releases/download/$latest/ModMigrator-release.apk"
                    )
                }
            }
        }
        return null
    }

    /** 依次探测镜像，返回第一个可用的下载地址；全挂就回退官方直链 */
    fun mirrorUrls(owner: String, repo: String, tag: String, file: String): List<String> {
        val out = ArrayList<String>()
        for (m in MIRRORS) {
            out.add(
                m.replace("{owner}", owner)
                    .replace("{repo}", repo)
                    .replace("{tag}", tag)
                    .replace("{file}", file)
            )
        }
        out.add("https://github.com/$owner/$repo/releases/download/$tag/$file")
        return out
    }

    /** 挑一个真正能下载的：发 HEAD 请求看响应码 */
    fun pickMirror(owner: String, repo: String, tag: String, file: String): String {
        val urls = mirrorUrls(owner, repo, tag, file)
        for (u in urls) {
            try {
                val r = Http.call(u)
                r.use {
                    if (it.isSuccessful || it.code in 300..399) return u
                }
            } catch (t: Throwable) {
                // 换下一个镜像
                     Err.ignore(t, "换下一个镜像")
                 }
        }
        return urls.last()
    }

    /**
     * 版本号比较：支持 v1.2.3 / 1.2.3。
     *
     * 之前直接用 toIntOrNull() ?: 0，遇到 "nightly" 这种非数字 tag
     * 会被解析成 [0]，跟本地 1.0.0 一比就是 0 < 1 → 永远判定「无更新」。
     * 这正是「从没见过更新提示」的根因。
     * 现在：远端不是语义化版本时，认为没有可用的版本更新（但依然可以提示有新构建）。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote) ?: return false
        val b = parts(local) ?: return false
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrNull(i) ?: 0
            val y = b.getOrNull(i) ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 解析语义化版本号。返回 null 表示这不是一个可比较的版本号
     * （比如 nightly、latest 这类固定标签）。
     */
    private fun parts(s: String): List<Int>? {
        val t = s.trim().removePrefix("v").removePrefix("V")
        if (t.isBlank()) return null
        val seg = t.split(".")
        // 至少第一段必须是数字，否则视为非版本号标签
        val first = seg.firstOrNull()?.toIntOrNull() ?: return null
        return listOf(first) + seg.drop(1).map { it.toIntOrNull() ?: 0 }
    }

    /** 远端 tag 是不是一个可比较的语义化版本号 */
    fun isVersionTag(tag: String): Boolean = parts(tag) != null

    /** 本地版本号 */
    fun local(ctx: Context): String =
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
        } catch (t: Throwable) {
            "0.0.0"
        }

    /**
     * 识别并过滤"没有信息量"的更新说明。
     *
     * CI 曾用一段写死的模板当作 release body，结果每次更新
     * 显示的"更新内容"都是同一句话——对用户毫无意义，
     * 反而让人误以为自己没更新成功。
     *
     * 这里做两件事：
     *  1. 命中模板特征 → 返回空，界面就不显示这一栏（比显示废话好）
     *  2. 正常内容 → 原样返回，并去掉 HTML 标签
     */
    fun realNotes(body: String): String {
        val raw = body.trim()
        if (raw.isBlank()) return ""

        // 模板特征句：这些话在任何版本里都一样，没有信息量
        val boilerplate = listOf(
            "更新内容以本条 release 的说明为准",
            "CI 自动构建的 release APK",
            "安装后需在设置里填写"
        )
        val hit = boilerplate.count { raw.contains(it) }
        // 模板里这些句子会同时出现；真 changelog 不会
        if (hit >= 2) return ""

        // 去掉常见 Markdown/HTML 噪音，保留可读文本
        return raw
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
            .trim()
            .take(600)
    }
}
