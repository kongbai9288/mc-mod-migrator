package com.kongbai.modmigrator

import android.content.Context
import org.json.JSONObject

/**
 * 公告。
 *
 * 从 GitHub 仓库拉 announcement.json，走**多个镜像源**依次尝试，
 * 某个镜像挂了自动换下一个（参考 ghproxy / jsdelivr 这类常见做法）。
 *
 * 行为：
 *   - 弹窗展示，可以关闭（关了就记下这条的 id，不再弹）
 *   - 可以「清除」——彻底不显示当前这条
 *   - 可以「再弹一次」——设置里手动触发
 *   - 每条公告有 id，换新的公告会重新弹
 *
 * 文件格式（仓库根目录 announcement.json）：
 * {
 *   "id": "2026-09-24",
 *   "title": "标题",
 *   "body": "正文，支持换行",
 *   "level": "info" | "warn" | "important",
 *   "minVersion": "1.0.0"      // 可选，低于此版本才显示
 * }
 */
object Announcement {

    data class Notice(
        val id: String = "",
        val title: String = "",
        val body: String = "",
        val level: String = "info",
        val url: String = ""
    )

    /** 镜像列表，依次尝试 */
    private val MIRRORS = listOf(
        "https://raw.githubusercontent.com/{owner}/{repo}/{branch}/announcement.json",
        "https://cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}/announcement.json",
        "https://fastly.jsdelivr.net/gh/{owner}/{repo}@{branch}/announcement.json",
        "https://gcore.jsdelivr.net/gh/{owner}/{repo}@{branch}/announcement.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/{owner}/{repo}/{branch}/announcement.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/{owner}/{repo}/{branch}/announcement.json",
        "https://ghps.cc/https://raw.githubusercontent.com/{owner}/{repo}/{branch}/announcement.json"
    )

    /** 已关闭的公告 id */
    private fun closedIds(ctx: Context): Set<String> {
        val raw = Prefs.get(ctx).getString(K.ANNO_CLOSED, "") ?: ""
        return raw.split("|").filter { it.isNotBlank() }.toSet()
    }

    private fun markClosed(ctx: Context, id: String) {
        val set = closedIds(ctx).toMutableSet()
        set.add(id)
        Prefs.get(ctx).edit().putString(K.ANNO_CLOSED, set.joinToString("|")).apply()
    }

    /** 清除（这条永不再显示，且立刻清空缓存） */
    fun clear(ctx: Context, id: String) {
        markClosed(ctx, id)
        Prefs.get(ctx).edit()
            .remove(K.ANNO_CLOSED + "_cache")
            .putString("anno_last_body", "")
            .apply()
    }

    /** 重新允许弹出（设置里「再弹一次」） */
    fun reopen(ctx: Context, id: String) {
        val set = closedIds(ctx).toMutableSet()
        set.remove(id)
        Prefs.get(ctx).edit().putString(K.ANNO_CLOSED, set.joinToString("|")).apply()
    }

    /** 全部清除 */
    fun clearAll(ctx: Context) {
        Prefs.get(ctx).edit()
            .putString(K.ANNO_CLOSED, "")
            .putString("anno_last_body", "")
            .apply()
    }

    /**
     * 拉取公告。后台线程调用。
     * @param force 忽略「已关闭」，强制返回（用于「再弹一次」）
     */
    fun fetch(ctx: Context, force: Boolean = false): Notice? {
        val owner = Prefs.get(ctx).getString(K.OWNER, "").orEmpty()
            .ifBlank { UpdateChecker.defaultOwner() }
        val repo = Prefs.get(ctx).getString(K.REPO, "").orEmpty()
            .ifBlank { UpdateChecker.defaultRepo() }
        val branch = Prefs.get(ctx).getString(K.BRANCH, "").orEmpty().ifBlank { "main" }

        // 节流：6 小时内查过就不再打外网。
        // 「再弹一次」是用户主动触发的，必须无视节流立刻去查。
        if (!force && !shouldCheck()) return null

        val notice = fetchFromMirrors(owner, repo, branch) ?: return null
        if (notice.id.isBlank()) return null
        if (!force && closedIds(ctx).contains(notice.id)) return null
        return notice
    }

    /**
     * 拉公告。
     *
     * ⚠️ 这是被反复修的一个点，把踩过的坑记下来：
     *
     * **第一次**：仓库里从来没建过 `announcement.json`，而这里要依次试 7 个镜像
     * 去拉它 —— 每次冷启动 7 个全部 404，刷出 14 条日志
     * （每个镜像一条"错误"+一条"警告"），把有用的日志全淹没。
     *
     * **第二次**：只把 404 静默了，网络类失败（Connection reset）照样
     * 每个镜像两条日志，每条还带 3KB 堆栈 —— 一次启动几十 KB 日志。
     *
     * 根子上的问题是**设计**：公告是个低频、非关键的功能，
     * 却用了"每次启动都串行打 7 个外网请求、每个失败都记日志"的写法。
     * 现在改成：
     *  1. **记住上次成功的镜像**，下次优先试它（绝大多数情况一次就成）
     *  2. **节流**：距上次尝试不足 6 小时就跳过（公告没必要每次启动都查）
     *  3. **失败不逐条记**，全部落空才记一句总结
     */
    private fun fetchFromMirrors(owner: String, repo: String, branch: String): Notice? {
        val urls = MIRRORS.map {
            it.replace("{owner}", owner)
                .replace("{repo}", repo)
                .replace("{branch}", branch)
        }
        // 上次成功的那个排到最前
        val last = lastMirror()
        val ordered = if (last.isBlank()) urls
        else listOf(last) + urls.filter { it != last }

        var notFound = 0
        var netFail = 0
        for (u in ordered) {
            try {
                val text = Http.get(u, timeout = Http.SHORT)
                if (text.isBlank()) continue
                val n = parse(text)
                if (n != null) {
                    saveMirror(u)
                    markAttempt()
                    return n
                }
            } catch (t: Throwable) {
                if (isNotFound(t)) notFound++ else netFail++
                // ⚠️ 这里**故意不记日志**。
                // 7 个镜像逐个试，每个都记一条就是 7 倍噪音，
                // 而且失败原因对用户没有意义（他改不了网络、改不了镜像站）。
                // 全部落空时才在下面记一句总结。
            }
        }
        markAttempt()
        when {
            notFound > 0 && netFail == 0 ->
                LogCenter.i("Announcement", "仓库中没有公告文件（$notFound 个镜像均 404），跳过")
            netFail > 0 ->
                LogCenter.w(
                    "Announcement",
                    "公告拉取失败（$netFail 个镜像不可达" +
                        "${if (notFound > 0) "，$notFound 个无此文件" else ""}），跳过"
                )
        }
        return null
    }

    /** 判断是不是 404（仓库里没有该文件的正常情况） */
    private fun isNotFound(t: Throwable): Boolean {
        val msg = t.message ?: return false
        return msg.contains("HTTP 404") || msg.contains("404 Not Found")
    }

    // ---- 镜像记忆与节流（都放普通 Prefs，失败也不影响主流程） ----

    private const val KEY_MIRROR = "anno_last_mirror"
    private const val KEY_AT = "anno_last_attempt"
    private const val INTERVAL_MS = 6 * 60 * 60 * 1000L

    private fun prefs() = Prefs.appCtx()?.let { Prefs.get(it) }

    private fun lastMirror(): String = try {
        prefs()?.getString(KEY_MIRROR, "") ?: ""
    } catch (_: Throwable) { "" }

    private fun saveMirror(u: String) {
        try {
            prefs()?.edit()?.putString(KEY_MIRROR, u)?.apply()
        } catch (_: Throwable) {}
    }

    /** 距上次尝试是否已超过节流间隔 */
    fun shouldCheck(): Boolean {
        val p = prefs() ?: return true
        val at = try { p.getLong(KEY_AT, 0L) } catch (_: Throwable) { 0L }
        return System.currentTimeMillis() - at > INTERVAL_MS
    }

    private fun markAttempt() {
        try {
            prefs()?.edit()?.putLong(KEY_AT, System.currentTimeMillis())?.apply()
        } catch (_: Throwable) {}
    }

    private fun parse(text: String): Notice? {
        return try {
            val o = JSONObject(text)
            val id = o.optString("id")
            if (id.isBlank()) return null
            Notice(
                id = id,
                title = o.optString("title").ifBlank { "公告" },
                body = o.optString("body"),
                level = o.optString("level").ifBlank { "info" },
                url = o.optString("url")
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** 标记这条已读（用户关掉弹窗后调用） */
    fun dismiss(ctx: Context, id: String) = markClosed(ctx, id)
}
