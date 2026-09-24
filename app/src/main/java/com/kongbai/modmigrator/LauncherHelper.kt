package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * 启动器识别。
 *
 * 不再预置「哪个包名才是启动器」——各家启动器包名会变，猜是猜不准的。
 * 现在的做法：把系统里所有可启动的应用列出来，让用户自己认领哪个是
 * Minecraft 启动器；选好之后再根据这个包名去拉它的数据目录。
 */
object LauncherHelper {

    /** 明确不是 Java 版启动器：基岩版 / 教育版 */
    private val BLOCKED = setOf(
        "com.mojang.minecraftpe",
        "com.mojang.minecraftedu"
    )

    /** 名字里带这些关键词的会被排在前面，方便用户找 */
    private val HINTS = listOf(
        "pojav", "zalith", "fcl", "hmcl", "multimc", "prism",
        "mcinabox", "minecraft", "mc ", "launcher", "启动器"
    )

    data class AppInfo(
        val pkg: String,
        val label: String,
        val icon: Drawable? = null,
        val hint: Boolean = false
    )

    /**
     * 已知启动器的发布页（走 GitHub 镜像加速）。
     *
     * 说明：启动器是独立应用，本应用**不能**直接改它的内部版本数据，
     * 只能把用户带到官方发布页，由用户自己下载安装。
     * 不认识的启动器返回空串，界面会提示用户自己去找。
     */
    private val UPDATE_PAGES = mapOf(
        "net.kdt.pojavlaunch" to "https://github.com/PojavLauncherTeam/PojavLauncher/releases/latest",
        "net.kdt.pojavlaunch.zhcn" to "https://github.com/PojavLauncherTeam/PojavLauncher/releases/latest",
        "com.movtery.zalithlauncher" to "https://github.com/ZalithLauncher/ZalithLauncher/releases/latest",
        "com.tungsten.fcl" to "https://github.com/FCL-Team/FoldCraftLauncher/releases/latest",
        "com.tungsten.fcl.zhcn" to "https://github.com/FCL-Team/FoldCraftLauncher/releases/latest",
        "com.mio.miomc" to "https://github.com/mio-team/mio-mc/releases/latest"
    )

    /** 该启动器的更新页地址（已套镜像），不认识就返回空 */
    fun updateUrl(pkg: String): String {
        if (pkg.isBlank()) return ""
        val page = UPDATE_PAGES[pkg] ?: return ""
        // 套一层镜像加速，国内更容易打开
        return "https://ghfast.top/$page"
    }

    /**
     * 列出系统里所有可启动的应用（用户能在桌面看到的那张应用列表）。
     * 排除自己，并按「名字像不像启动器」排序，像的排前面。
     */
    fun listApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = try {
            pm.queryIntentActivities(main, 0)
        } catch (t: Throwable) {
            emptyList<android.content.pm.ResolveInfo>()
        }
        val out = ArrayList<AppInfo>()
        val seen = HashSet<String>()
        for (r in infos) {
            val pkg = r.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName || seen.contains(pkg)) continue
            seen.add(pkg)
            val label = try {
                r.loadLabel(pm).toString()
            } catch (t: Throwable) {
                pkg
            }
            val icon = try {
                r.loadIcon(pm)
            } catch (t: Throwable) {
                null
            }
            val l = label.lowercase()
            val hint = HINTS.any { l.contains(it) } || pkg.lowercase().let { p ->
                HINTS.any { p.contains(it.trim()) }
            }
            out.add(AppInfo(pkg, label, icon, hint))
        }
        return out.sortedWith(compareByDescending<AppInfo> { it.hint }.thenBy { it.label.lowercase() })
    }

    /** 只看「像启动器」的，用于快速入口 */
    fun listCandidates(ctx: Context): List<AppInfo> =
        listApps(ctx).filter { it.hint && it.pkg !in BLOCKED }

    fun isBlocked(pkg: String): Boolean = pkg in BLOCKED

    /**
     * 根据选中的包名，给出它的数据目录候选。
     * 启动器一般把实例放在 Android/data/<pkg>/files 下，
     * 具体子目录各家不同，所以这里列出所有可能路径交给扫描器逐个试。
     */
    fun dataDirs(ctx: Context, pkg: String): List<String> {
        val out = LinkedHashMap<String, String>()
        val ext = java.io.File("/storage/emulated/0")
        val roots = listOf(ext, ctx.getExternalFilesDir(null)).filterNotNull()
        fun add(f: java.io.File) {
            if (f.exists() && f.isDirectory) out[f.absolutePath] = f.absolutePath
        }
        for (r in roots) {
            add(java.io.File(r, "Android/data/$pkg/files"))
            add(java.io.File(r, "Android/data/$pkg/files/instances"))
            add(java.io.File(r, "Android/data/$pkg/files/games"))
        }
        add(java.io.File(ext, "Android/data/$pkg/files"))
        // 有些启动器会写到公共目录，用包名或常见名字建子目录
        val short = pkg.substringAfterLast('.')
        add(java.io.File(ext, "games/$short"))
        add(java.io.File(ext, short))
        return out.values.toList()
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (isBlocked(pkg)) return false
        return try {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 目录名是否像启动器数据目录 */
    fun isKnownDir(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("pojav") || n.contains("zalith") || n.contains("fcl") ||
            n.contains("hmcl") || n.contains("multimc") || n.contains("prism") ||
            n.contains("mcinabox") || n.contains("instances")
    }
}
