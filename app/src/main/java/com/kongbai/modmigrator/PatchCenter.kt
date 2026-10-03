package com.kongbai.modmigrator

import android.content.Context

/**
 * 补丁中心：让旧版本 App 也能拿到新数据，不必重装。
 *
 * 背景：启动器改名、换目录、MC 出新版本的速度远快于发新版 App。
 * 这些内容如果全硬编码在 App 里，用户就得为了"多支持一个启动器"
 * 重装一次。改成远端下发后，改一份 JSON 即可全量生效。
 *
 * 设计约束：
 * 1. 补丁只替换**数据**，不执行代码。注入代码等于给远程开后门。
 * 2. 任一分类更新失败，保留该分类的内置值，其余分类照常。
 * 3. 用户可以随时手动刷新，不受自动更新的节流限制。
 */
object PatchCenter {

    /** 一个补丁分类 */
    data class Kind(
        val key: String,
        val title: String,
        /** 这一份补丁解决什么问题，界面上给用户看 */
        val desc: String,
        /** 内置的兜底条数，用于展示"内置 X 项" */
        val builtinCount: Int,
        val apply: (String) -> Boolean
    ) {
        var remoteCount: Int = 0
        var updatedAt: Long = 0L
        var lastError: String = ""
    }

    /**
     * 已登记的分类。
     * 以后新增可远程更新的数据时，在这里加一项即可，
     * 设置页会自动列出它并支持单独刷新。
     */
    private val kinds = listOf(
        Kind(
            key = "launcher_dirs",
            title = "启动器目录",
            desc = "各启动器存放 .minecraft 的路径。启动器改名或调整目录结构时更新。",
            builtinCount = LauncherDirs.all().size,
            apply = { LauncherDirs.updateFrom(it) }
        )
    )

    fun all(): List<Kind> = kinds

    fun kindOf(key: String): Kind? = kinds.firstOrNull { it.key == key }

    /** 补丁来源，依次尝试。单个镜像不可用不影响其余。 */
    private val BASES = listOf(
        "https://raw.githubusercontent.com/kongbai9288/mc-mod-migrator/main",
        "https://cdn.jsdelivr.net/gh/kongbai9288/mc-mod-migrator@main",
        "https://fastly.jsdelivr.net/gh/kongbai9288/mc-mod-migrator@main"
    )

    /** 自动更新的间隔。手动刷新不受此限制。 */
    private const val AUTO_INTERVAL = 6 * 60 * 60 * 1000L

    private const val K_AT = "patch_at_"

    /**
     * 更新单个分类。
     *
     * @param force true 时忽略节流（用户手动点刷新）
     * @return 是否更新成功
     */
    fun update(ctx: Context, kind: Kind, force: Boolean = false): Boolean {
        if (!force && System.currentTimeMillis() - lastAt(ctx, kind) < AUTO_INTERVAL) {
            return false
        }
        kind.lastError = ""
        for (b in BASES) {
            val json = try {
                Http.get("$b/patch/${kind.key}.json")
            } catch (t: Throwable) {
                // 镜像不通就换下一个。多个镜像都失败是网络环境的常态，
                // 逐条记日志会把真正有用的信息淹掉（之前踩过这个坑）。
                continue
            }
            if (json.isBlank()) continue
            val ok = try {
                kind.apply(json)
            } catch (t: Throwable) {
                Err.ignore(t, "应用补丁 ${kind.key}")
                false
            }
            if (ok) {
                kind.remoteCount = countOf(json)
                kind.updatedAt = System.currentTimeMillis()
                Prefs.get(ctx).edit()
                    .putLong(K_AT + kind.key, System.currentTimeMillis())
                    .apply()
                LogCenter.i("Patch", "已更新「${kind.title}」，共 ${kind.remoteCount} 项")
                return true
            }
            kind.lastError = "补丁内容无法解析"
        }
        if (kind.lastError.isBlank()) kind.lastError = "所有镜像均不可达"
        return false
    }

    /** 全部更新一次（启动时后台调用，跳过节流） */
    fun updateAllInBackground(ctx: Context, force: Boolean = false) {
        Thread {
            for (k in kinds) {
                runCatching { update(ctx, k, force) }
            }
            // 目录清单变了要重扫，否则界面上还是旧的
            runCatching { Fs.clearCache() }
        }.start()
    }

    fun lastAt(ctx: Context, kind: Kind): Long =
        Prefs.get(ctx).getLong(K_AT + kind.key, 0L)

    /** 从 JSON 里粗数条目数，只用于界面展示，不参与解析校验 */
    private fun countOf(json: String): Int = try {
        org.json.JSONArray(json).length()
    } catch (t: Throwable) {
        try {
            org.json.JSONObject(json).length()
        } catch (t2: Throwable) {
            0
        }
    }

    /** 人类可读的更新时间 */
    fun timeText(ms: Long): String {
        if (ms <= 0L) return "从未更新"
        val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return f.format(java.util.Date(ms))
    }
}
