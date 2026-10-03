package com.kongbai.modmigrator

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 各启动器的 `.minecraft` 目录在哪。
 *
 * ⚠️ 之前默认落到 **App 私有目录**（getExternalFilesDir，
 * 即 /sdcard/Android/data/<本包名>/files/ModMigrator）。
 * 那个目录有三个致命问题：
 *   1. 启动器**根本读不到** —— 模组下进去等于白下，游戏里一个都不加载
 *   2. 用户在文件管理器里看不见（Android 11+ 其他 App 也进不来）
 *   3. 卸载本 App 时系统一起清掉
 * 所以默认目录必须换成启动器真正的 `.minecraft`。
 *
 * ⚠️ 另一条硬限制（官方 Android 11 存储更新已确认）：
 *   **SAF 无法授权 Android/data 及其子目录**，无论 targetSDK 多少。
 * 因此这里**只收录公共目录下的路径**（/sdcard/games/…、/sdcard/FCL/… 等）。
 * 落在 Android/data 的私有路径仅供显示说明，不能拿来授权。
 *
 * 启动器将来改路径是不可避免的（Pojav 就换过一次），
 * 所以这份表是**数据**而非硬编码逻辑，支持从远端补丁更新（见 [updateFrom]）。
 */
object LauncherDirs {

    /** 一条启动器目录记录 */
    data class Entry(
        /** 显示名，如 "Zalith Launcher 2" */
        val name: String,
        /** 公共目录下的相对路径，如 "games/ZalithLauncher2/.minecraft" */
        val rel: String,
        /** 该启动器的包名，用于判断是否已安装 */
        val pkg: String = "",
        /** 备注：版本隔离、私有路径等说明 */
        val note: String = ""
    )

    /**
     * 内置预设。
     *
     * 路径均已联网核对：
     *   - PojavLauncher      /sdcard/games/PojavLauncher/.minecraft
     *   - Zalith Launcher    /sdcard/games/ZalithLauncher/.minecraft
     *   - Zalith Launcher 2  /sdcard/games/ZalithLauncher2/.minecraft
     *   - FCL                /sdcard/FCL/.minecraft
     *   - HMCL PE            /sdcard/HMCLPE/.minecraft
     *   - 澪 MioLauncher     /sdcard/MioLauncher/.minecraft
     *   - MCinaBox           /sdcard/MCinaBox/.minecraft
     */
    private val BUILTIN = listOf(
        Entry("PojavLauncher", "games/PojavLauncher/.minecraft",
            "net.kdt.pojavlaunch", "已停止维护，建议换 Zalith"),
        Entry("Zalith Launcher", "games/ZalithLauncher/.minecraft",
            "com.movtery.zalithlauncher", ""),
        Entry("Zalith Launcher 2", "games/ZalithLauncher2/.minecraft",
            "com.movtery.zalithlauncher2", "推荐"),
        Entry("Amethyst", "games/Amethyst/.minecraft",
            "com.aurora.amethyst", ""),
        Entry("FCL", "FCL/.minecraft",
            "com.fcl.launcher", "开版本隔离则在 versions/<版本>/ 下"),
        Entry("HMCL PE", "HMCLPE/.minecraft",
            "com.tungsten.hmclpe", "开版本隔离则在 versions/<版本>/ 下"),
        Entry("澪 MioLauncher", "MioLauncher/.minecraft", "", ""),
        Entry("MCinaBox", "MCinaBox/.minecraft",
            "com.aof.mcinabox", "")
    )

    /** 外部存储根。多数设备是 /storage/emulated/0 */
    private fun sdcard(): File =
        Environment.getExternalStorageDirectory()

    /** 当前生效的目录清单：远端更新过的用远端的，否则用内置 */
    private var entries: List<Entry> = BUILTIN

    /** 全部（含未安装的），供设置页列出 */
    fun all(): List<Entry> = entries

    /**
     * 探测：返回**确实存在**的目录。
     * 按「启动器已安装」优先排序，装了的排前面。
     */
    fun detect(ctx: Context): List<Pair<Entry, File>> {
        val root = sdcard()
        val pm = ctx.packageManager
        val out = ArrayList<Pair<Entry, File>>()
        for (e in entries) {
            val f = File(root, e.rel)
            if (!f.isDirectory) continue
            val installed = e.pkg.isNotBlank() && try {
                pm.getPackageInfo(e.pkg, 0) != null
            } catch (t: Throwable) { false }
            out.add(e to f)
            if (installed) {
                // 已安装的排到最前
                val i = out.indexOfFirst { it.first === e }
                if (i > 0) {
                    val item = out.removeAt(i)
                    out.add(0, item)
                }
            }
        }
        return out
    }

    /** 该目录是不是一个真正的 .minecraft（有 mods / saves / versions 之一） */
    fun looksLikeMinecraft(f: File): Boolean {
        if (!f.isDirectory) return false
        val names = f.list()?.toSet() ?: return false
        return names.any {
            it.equals("mods", true) || it.equals("saves", true) ||
                it.equals("versions", true) || it.equals("resourcepacks", true) ||
                it.equals("config", true)
        }
    }

    /**
     * 从远端补丁更新目录表。
     *
     * 启动器改名/换路径时不用发新版 App，下一份 JSON 即可。
     * 格式：[{"name":"…","rel":"…","pkg":"…","note":"…"}, …]
     *
     * 解析失败一律保留内置表 —— 补丁坏了不能让用户没得选。
     */
    fun updateFrom(json: String): Boolean {
        return try {
            val arr = JSONArray(json)
            val list = ArrayList<Entry>(BUILTIN)
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                val rel = o.optString("rel").trim().trimStart('/')
                if (name.isBlank() || rel.isBlank()) continue
                // 同名覆盖内置的，其余追加
                list.removeAll { it.name == name }
                list.add(
                    Entry(
                        name, rel,
                        o.optString("pkg").trim(),
                        o.optString("note").trim()
                    )
                )
            }
            if (list.isEmpty()) return false
            entries = list
            true
        } catch (t: Throwable) {
            Err.ignore(t, "更新启动器目录表")
            false
        }
    }

    /** 恢复内置表 */
    fun reset() { entries = BUILTIN }
}
