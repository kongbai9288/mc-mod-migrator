package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent

object LauncherHelper {

    val KNOWN = listOf(
        "net.kdt.pojavlaunch",
        "net.kdt.pojavlaunch.zh",
        "com.movtery.zalithlauncher",
        "com.aof.mcinabox",
        "com.mojang.minecraftpe"
    )

    fun installed(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val out = mutableListOf<Pair<String, String>>()
        for (p in KNOWN) {
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

    fun launch(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
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
