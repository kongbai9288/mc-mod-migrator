package com.kongbai.modmigrator

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Build
import android.os.Process
import androidx.documentfile.provider.DocumentFile

/**
 * 迁移前的守卫：同一时间只跑一个迁移，且尽量确认游戏没在跑。
 *
 * ## 为什么要单独一个文件
 *
 * 这两件事以前都没有：
 *
 * - **同时点两下「开始迁移」会叠着跑。** `busy` 是页面的 Compose 状态，
 *   离开页面再回来就是新的实例，另一个入口（比如通知栏、工具箱）
 *   也能再起一个。两个任务同时往同一个 mods 目录写，出来的包是坏的，
 *   而且用户只看到进度条乱跳，不知道发生了什么。
 * - **游戏还在跑就开迁。** 正在玩的存档里 `level.dat`、`.mca` 会被
 *   游戏反复改写，复制到一半的内容是撕裂的；Minecraft 自己用
 *   `session.lock` 防止两个实例同时写一个世界，我们跟着这个信号走。
 *
 * ## 检测"游戏在不在跑"是分级的，不要假装很准
 *
 * Android 上没有官方 API 能问"某个第三方应用现在在前台吗"：
 *
 * - `ActivityManager.getRunningAppProcesses()` 从 Android 5.0 起
 *   只保证返回**自己**的进程和少量系统进程，用它判断第三方不可靠。
 * - `UsageStatsManager` 能看到前后台事件，但需要用户在系统设置里
 *   授予"使用情况访问"权限，且事件不是实时落库的。
 *
 * 所以这里按可信度分级，判不出就返回 [Verdict.UNKNOWN]，由界面去问用户，
 * **不把"没查到"当成"确定没在跑"**。
 *
 * 依据：
 * - `session.lock` 的行为与格式（Java 版）
 *   https://zh.minecraft.wiki/w/Session.lock
 * - 使用情况访问需要用户授权
 *   https://developer.android.com/reference/android/app/usage/UsageStatsManager
 */
object MigGuard {

    /** 单飞用的任务名。改这个值不影响历史记录，只是标识。 */
    const val TAG_MIGRATE = "migrate"

    /** 用同步器读私有目录并打包：同样是重活，和迁移互斥 */
    const val TAG_PRIV = "priv"

    // ══════════════════════════════════════════════════════════
    //  一、单飞：同一时间只允许一个迁移
    // ══════════════════════════════════════════════════════════

    private data class Hold(val tag: String, val at: Long)

    private val lock = Any()

    @Volatile
    private var hold: Hold? = null

    /**
     * 试着占住。已被占则返回 false。
     *
     * 拿到 true 的一方必须在任务结束时调 [release]，
     * 建议写在 `finally` 里——迁移里有多个提前 return 的分支。
     */
    fun acquire(tag: String): Boolean {
        synchronized(lock) {
            if (hold != null) return false
            hold = Hold(tag, System.currentTimeMillis())
            return true
        }
    }

    /** 释放。传 tag 时只释放同名的，避免误放别人的任务。 */
    fun release(tag: String? = null) {
        synchronized(lock) {
            if (tag == null || hold?.tag == tag) hold = null
        }
    }

    /**
     * 当前占着的那个任务名，没有则 null。
     * 界面用它决定要不要禁用按钮；返回 String 而不是 [Hold]，
     * 因为 [Hold] 是私有的，不能出现在公开签名里。
     */
    fun busyTag(): String? = hold?.tag

    /** 卡死时的兜底：无条件放掉。只在用户明确点"强制结束"时用。 */
    fun forceRelease() {
        synchronized(lock) { hold = null }
    }

    // ══════════════════════════════════════════════════════════
    //  二、游戏是不是还在跑
    // ══════════════════════════════════════════════════════════

    enum class Verdict {
        /** 有明确迹象表明正在跑 */
        RUNNING,

        /** 有明确迹象表明没在跑 */
        IDLE,

        /** 判断不了。界面必须去问用户，不能当 IDLE 处理。 */
        UNKNOWN,
    }

    /** 结论是怎么来的，界面据此决定措辞强度 */
    enum class How {
        /** 存档目录里还留着 session.lock */
        SESSION_LOCK,

        /** 系统"使用情况访问"里看到它还在前台 */
        USAGE_STATS,

        /** 没有任何可用信号 */
        NONE,
    }

    data class Report(
        val verdict: Verdict,
        val how: How,
        val detail: String,
        /** [How.USAGE_STATS] 时命中的包名 */
        val pkg: String = "",
        /**
         * 是不是因为没拿到「使用情况访问」才判不出来。
         * 界面据此给一个去授权的入口 —— 不然用户永远不知道
         * 为什么每次都要手动确认一遍。
         */
        val needUsage: Boolean = false,
    )

    /** `session.lock` 的档案名。Mojang 固定写法，见文件头依据。 */
    private const val SESSION_LOCK = "session.lock"

    /**
     * 放进目标目录时的副名后缀。
     *
     * 不能叫 `.tmp` 之类会被启动器当模组扫进去的名字，
     * 也不带 `.jar` 后缀，免得被识别成半个模组。
     */
    const val PART_SUFFIX = ".mmpart"

    /**
     * 跑一遍检查。
     *
     * @param roots 要写入的目录树（迁移前/后各一个即可），用于找 session.lock
     * @param pkgs  本机装着的启动器包名，用于查使用情况
     */
    fun check(ctx: Context, roots: List<DocumentFile?>, pkgs: List<String>): Report {
        // 1) 先看存档锁。这个信号最贴近"世界正被占用"，
        //    而且只需要能读目录，不需要任何权限。
        val locked = roots.mapNotNull { it }.flatMap { lockedWorlds(it) }.distinct()
        if (locked.isNotEmpty()) {
            return Report(
                Verdict.RUNNING,
                How.SESSION_LOCK,
                "这些存档里还留着 $SESSION_LOCK：" +
                    locked.take(5).joinToString("、") +
                    if (locked.size > 5) " 等 ${locked.size} 个" else ""
            )
        }

        // 2) 再看使用情况。需要用户授权，没授权就只能说不知道。
        val want = pkgs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (want.isEmpty()) {
            return Report(
                Verdict.UNKNOWN, How.NONE,
                "没能确定你用的是哪个启动器，无法自动判断它是否在运行"
            )
        }
        if (!usageGranted(ctx)) {
            return Report(
                Verdict.UNKNOWN, How.NONE,
                "系统没有授予「使用情况访问」，无法自动判断游戏是否在运行。" +
                    "开启后这里就能自动判断，不用每次都手动确认。",
                needUsage = true
            )
        }
        val fg = foreground(ctx, want)
        if (fg.isNotEmpty()) {
            return Report(
                Verdict.RUNNING, How.USAGE_STATS,
                "检测到 ${fg.first()} 还在前台", fg.first()
            )
        }
        return Report(
            Verdict.IDLE, How.USAGE_STATS,
            "没检测到启动器在前台"
        )
    }

    /** 本机装着的启动器包名（去重，历史包名也算） */
    fun launcherPkgs(ctx: Context): List<String> =
        LauncherDirs.all()
            .filter { LauncherDirs.isInstalled(ctx, it) }
            .flatMap { it.pkgs }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * 找出带 `session.lock` 的存档名。
     *
     * 只做**阳性**判断：有锁 = 正在玩或上次崩了没退干净；
     * 没有锁 ≠ 一定没在跑（游戏刚启动还没建锁的瞬间）。
     * 所以这一步只能定 RUNNING，不能定 IDLE。
     *
     * 实测：一份玩过又正常退出的 26.2 存档里没有这个文件，
     * 说明正常退出会清掉它，留着的多半是异常退出。
     */
    private fun lockedWorlds(root: DocumentFile): List<String> {
        val out = ArrayList<String>()
        runCatching {
            // 根目录自己就是存档（用户直接选了世界目录）
            if (root.findFile(SESSION_LOCK) != null) out.add(root.name ?: "（当前目录）")
            val saves = root.findFile(McDirs.SAVES)
            if (saves != null && saves.isDirectory) {
                for (c in Fs.children(saves)) {
                    if (!c.isDirectory) continue
                    if (c.findFile(SESSION_LOCK) != null) out.add(c.name ?: "?")
                }
            }
        }
        return out
    }

    /**
     * 跳到系统的「使用情况访问」页面。
     *
     * 这个页面没有直接的 API 开关，只能把用户送过去自己开。
     * 注意：`PACKAGE_USAGE_STATS` 必须在清单里声明，否则本应用
     * 根本不会出现在那个列表里 —— 用户想给也给不了。
     */
    fun usageIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** 用户授予「使用情况访问」了吗 */
    fun usageGranted(ctx: Context): Boolean {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                ao.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    ctx.packageName
                )
            } else {
                ao.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    ctx.packageName
                )
            }
        }.getOrNull()
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 这些包里哪些还在前台。
     *
     * 用 `MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND` 配对：只看最近一次事件，
     * 是前台就算仍在跑。窗口取 10 分钟——玩一局早就超过一分钟，
     * 窗口太短会把"正在玩"误判成"没在跑"。
     *
     * 注意事件不是实时落库的，刚切换过去可能读不到，
     * 所以这个方法只能定 RUNNING，定不了 IDLE。
     */
    private fun foreground(ctx: Context, pkgs: List<String>): List<String> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val now = System.currentTimeMillis()
        val window = 10 * 60 * 1000L
        val events = runCatching { usm.queryEvents(now - window, now) }.getOrNull()
            ?: return emptyList()
        val last = HashMap<String, Boolean>()
        val ev = UsageEvents.Event()
        while (runCatching { events.hasNextEvent() }.getOrNull() == true) {
            if (runCatching { events.getNextEvent(ev) }.isFailure) break
            val p = ev.packageName ?: continue
            if (p !in pkgs) continue
            when (ev.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> last[p] = true
                UsageEvents.Event.MOVE_TO_BACKGROUND -> last[p] = false
            }
        }
        return last.filter { it.value }.keys.toList()
    }
}
