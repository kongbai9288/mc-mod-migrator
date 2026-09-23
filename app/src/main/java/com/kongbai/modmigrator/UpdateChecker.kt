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

    fun latest(ctx: Context): Release? {
        val o = owner(ctx).ifBlank { defaultOwner() }
        val r = repo(ctx).ifBlank { defaultRepo() }
        val token = Prefs.get(ctx).getString(K.TOKEN, "") ?: ""
        return try {
            val json = GitHubApi.latestRelease(o, r, token)
            val root = Json.obj(json) ?: return null
            val tag = Json.s(root, "tag_name")
            if (tag.isBlank()) return null
            val assets = Json.a(root, "assets")
            var url = ""
            var size = 0L
            if (assets != null && assets.size() > 0) {
                for (a in assets) {
                    val n = Json.s(a, "name")
                    if (n.endsWith(".apk", true)) {
                        url = Json.s(a, "browser_download_url")
                        size = Json.l(a, "size")
                        break
                    }
                }
            }
            Release(
                tag = tag,
                name = Json.s(root, "name").ifBlank { tag },
                notes = Json.s(root, "body").take(400),
                apkUrl = url,
                size = size,
                published = Json.s(root, "published_at")
            )
        } catch (t: Throwable) {
            null
        }
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
}
