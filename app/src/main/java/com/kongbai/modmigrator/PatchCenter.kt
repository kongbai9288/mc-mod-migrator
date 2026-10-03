package com.kongbai.modmigrator

import java.io.File

/**
 * 补丁更新：让**旧版本的 App** 也能拿到新数据，不必重装。
 *
 * 为什么要这个：启动器改名、改目录、MC 出新版本的速度
 * 远快于发新版 App。如果这些全靠硬编码在 App 里，
 * 用户就得为了"多支持一个启动器"重装一次。
 *
 * 做法：App 启动时拉一份 JSON 补丁，把可变的数据替换掉。
 * 目前下发两类：
 *   - `launcher_dirs`：启动器的 .minecraft 目录清单（改名/换路径时更新）
 *   - `announcement`：公告（已另有实现，这里只做统一入口的记录）
 *
 * ⚠️ 补丁只改**数据**，不改代码 —— 更新失败一律保留内置值，
 * 绝不能因为补丁坏了就让功能不可用。
 */
object PatchCenter {

    /**
     * 补丁来源。和公告一样走多个镜像依次尝试，
     * 单个镜像挂掉不影响。
     */
    private val BASES = listOf(
        "https://raw.githubusercontent.com/kongbai9288/mc-mod-migrator/main",
        "https://cdn.jsdelivr.net/gh/kongbai9288/mc-mod-migrator@main",
        "https://fastly.jsdelivr.net/gh/kongbai9288/mc-mod-migrator@main"
    )

    /** 拉取间隔：6 小时。补丁不是实时数据，没必要每次启动都打网络。 */
    private const val INTERVAL = 6 * 60 * 60 * 1000L

    private const val K_LAST = "patch_last_at"

    /**
     * 更新启动器目录清单。
     * @param force 用户手动触发时无视节流
     * @return 一句人话结果，用于界面提示
     */
    fun updateLauncherDirs(ctx: android.content.Context, force: Boolean = false): String {
        if (!force) {
            val last = Prefs.get(ctx).getLong(K_LAST, 0L)
            if (System.currentTimeMillis() - last < INTERVAL) return ""
        }
        for (b in BASES) {
            val json = try {
                Http.get("$b/patch/launcher_dirs.json")
            } catch (t: Throwable) {
                continue          // 这个镜像不通，换下一个
            }
            if (json.isBlank()) continue
            val ok = LauncherDirs.updateFrom(json)
            if (ok) {
                Prefs.get(ctx).edit().putLong(K_LAST, System.currentTimeMillis()).apply()
                return "启动器目录已更新（${LauncherDirs.all().size} 项）"
            }
        }
        // 全部失败：什么都不说，内置表继续工作。
        // 补丁是"锦上添花"，失败不该打断用户。
        return ""
    }

    /** 后台拉一次，不阻塞启动 */
    fun updateInBackground(ctx: android.content.Context) {
        Thread {
            runCatching { updateLauncherDirs(ctx) }
        }.start()
    }
}
