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

        val notice = fetchFromMirrors(owner, repo, branch) ?: return null
        if (notice.id.isBlank()) return null
        if (!force && closedIds(ctx).contains(notice.id)) return null
        return notice
    }

    /** 依次试镜像，谁通了用谁 */
    private fun fetchFromMirrors(owner: String, repo: String, branch: String): Notice? {
        for (m in MIRRORS) {
            val url = m
                .replace("{owner}", owner)
                .replace("{repo}", repo)
                .replace("{branch}", branch)
            try {
                val text = Http.get(url)
                if (text.isBlank()) continue
                val n = parse(text)
                if (n != null) return n
            } catch (t: Throwable) {
                // 换下一个镜像
            }
        }
        return null
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

    /** 本地兜底公告：仓库里没文件时用（不至于永远弹不出来） */
    fun fallback(): Notice? = null
}
