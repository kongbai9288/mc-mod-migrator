package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent

object LauncherHelper {

    /** 只列 Java 版启动器。基岩版（com.mojang.minecraftpe）不是迁移目标，不能出现在这里 */
    val KNOWN = listOf(
        "net.kdt.pojavlaunch",
        "net.kdt.pojavlaunch.zh",
        "com.movtery.zalithlauncher",
        "com.aof.mcinabox",
        "com.tungsten.fcl",
        "com.tungsten.fcl.zh"
    )

    /** 明确排除：基岩版 / 非迁移目标 */
    private val BLOCKED = setOf(
        "com.mojang.minecraftpe",
        "com.mojang.minecraftedu",
        "com.mojang.minecraftpe"
    )

    fun installed(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val out = mutableListOf<Pair<String, String>>()
        for (p in KNOWN) {
            if (p in BLOCKED) continue
            try {
                val info = pm.getApplicationInfo(p, 0)
                val label = pm.getApplicationLabel(info).toString()
                out.add(Pair(p, label))
            } catch (t: Throwable) {
                // not installed
            }
        }
        return out
    }

    fun isBlocked(pkg: String): Boolean = pkg in BLOCKED

    /** 目录名 -> 是否属于已知 Java 版启动器数据目录（用于扫描时优先命中） */
    fun isKnownDir(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("pojav") || n.contains("zalith") || n.contains("fcl") ||
            n.contains("hmcl") || n.contains("multimc") || n.contains("prism") ||
            n.contains("mcinabox") || n.contains("instances")
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
}
