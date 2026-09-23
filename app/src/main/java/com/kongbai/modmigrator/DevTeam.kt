package com.kongbai.modmigrator

import android.content.Context

/**
 * 开发者名单。
 *
 * 优先读仓库里的 devs.json（可随时更新而不用发版），
 * 取不到就用内置名单兜底。
 */
object DevTeam {

    data class Dev(
        var name: String = "",
        var role: String = "",
        var url: String = ""
    )

    /** 内置兜底名单 */
    private val BUILTIN = listOf(
        Dev("kongbai9288", "发起人 / 主程", "https://github.com/kongbai9288"),
        Dev("Modrinth", "模组数据源", "https://modrinth.com"),
        Dev("CurseForge", "模组数据源", "https://curseforge.com"),
        Dev("MCIM", "国内镜像源", "https://www.mcimirror.top"),
        Dev("Pterodactyl", "服务器面板协议", "https://pterodactyl.io")
    )

    /** 内置名单，外部可直接取用（远端拿不到时的兜底） */
    fun builtin(): List<Dev> = BUILTIN

    /**
     * 读取名单。无论发生什么都不抛异常：
     * 远端拿不到、JSON 解析失败、网络超时一律退回内置名单。
     * 之前这里会把异常抛给调用方，调用方的 catch 又是空的，
     * 于是列表一片空白——现在从源头杜绝。
     */
    fun load(ctx: Context): List<Dev> {
        return try {
            loadInner(ctx)
        } catch (t: Throwable) {
            BUILTIN
        }
    }

    private fun loadInner(ctx: Context): List<Dev> {
        val o = owner(ctx)
        val r = repo(ctx)
        if (o.isNotBlank() && r.isNotBlank()) {
            val token = Prefs.get(ctx).getString(K.TOKEN, "") ?: ""
            val text = try {
                GitHubApi.getText(o, r, "devs.json", "main", token)
            } catch (t: Throwable) {
                null
            }
            if (!text.isNullOrBlank()) {
                val arr = Json.obj(text)?.let { Json.a(it, "devs") } ?: Json.arr(text)
                if (arr != null) {
                    val out = ArrayList<Dev>()
                    for (d in arr) {
                        out.add(
                            Dev(
                                name = Json.s(d, "name"),
                                role = Json.s(d, "role"),
                                url = Json.s(d, "url")
                            )
                        )
                    }
                    if (out.isNotEmpty()) return out
                }
            }
        }
        return BUILTIN
    }

    private fun owner(ctx: Context) = Prefs.get(ctx).getString(K.OWNER, "") ?: ""
    private fun repo(ctx: Context) = Prefs.get(ctx).getString(K.REPO, "") ?: ""
}
