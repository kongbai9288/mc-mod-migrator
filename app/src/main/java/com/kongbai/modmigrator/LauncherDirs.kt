package com.kongbai.modmigrator

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 各启动器的 `.minecraft` 目录在哪。
 *
 * 本文件**只有一份数据**（[TABLE]），改目录改包名改版本都只动那张表，
 * 逻辑不用碰。启动器换路径是常态（Pojav 换过、FCL 换过包名），
 * 所以目录同时记「旧」和「新」两套，两套都会去找。
 *
 * ⚠️ 官方 Android 11 存储更新已确认：**SAF 无法授权 Android/data 及其子目录**。
 * 因此 [Entry.priv] 里的私有路径只能拿来看、以及交给同步器读取（见 [PrivSync]），
 * 绝不能拿去发起目录授权。
 *
 * [Entry.mcFrom] / [Entry.mcTo] 是社区资料整理的适配版本区间，
 * 会随启动器更新变化，仅用于提示，不用来拦截操作。
 */
object LauncherDirs {

    /** 一条启动器记录。改目录只改这里。 */
    data class Entry(
        /** 显示名 */
        val name: String,
        /** 品牌 key，对应 [LauncherBrand]，用于显示图标 */
        val key: String = "",
        /** 包名（可能有多个：历史包名 + 现用包名） */
        val pkgs: List<String> = emptyList(),
        /** 旧公开目录，相对 /sdcard */
        val pubOld: List<String> = emptyList(),
        /** 新公开目录，相对 /sdcard */
        val pubNew: List<String> = emptyList(),
        /** 旧私有目录（Android/data 下），仅供显示与同步器读取 */
        val privOld: List<String> = emptyList(),
        /** 新私有目录（Android/data 下），仅供显示与同步器读取 */
        val privNew: List<String> = emptyList(),
        /** 适配的 MC 版本下限 */
        val mcFrom: String = "",
        /** 适配的 MC 版本上限，"" = 跟随最新 */
        val mcTo: String = "",
        /** 备注 */
        val note: String = ""
    )

    // ══════════════════════════════════════════════════════════
    //  数据区：改目录 / 改包名 / 改版本，只改这一段
    //  路径一律相对 /sdcard（多数设备是 /storage/emulated/0）
    // ══════════════════════════════════════════════════════════
    private val TABLE = listOf(
        Entry(
            name = "FCL", key = "fcl",
            pkgs = listOf("com.tungsten.fcl", "com.fcl.launcher"),
            pubNew = listOf("FCL/.minecraft"),
            pubOld = listOf("games/FCL/.minecraft"),
            privNew = listOf("Android/data/com.fcl.launcher/files/.minecraft"),
            privOld = listOf("Android/data/com.tungsten.fcl/files/.minecraft"),
            mcFrom = "1.0", mcTo = "",
            note = "开版本隔离则在 versions/<版本>/ 下"
        ),
        Entry(
            name = "Zalith Launcher 2", key = "zalith2",
            pkgs = listOf("com.movtery.zalithlauncher.v2", "com.movtery.zalithlauncher2"),
            pubNew = listOf("games/ZalithLauncher2/.minecraft"),
            pubOld = listOf("games/ZalithLauncher/.minecraft"),
            privNew = listOf("Android/data/com.movtery.zalithlauncher.v2/files/.minecraft"),
            privOld = listOf("Android/data/com.movtery.zalithlauncher/files/.minecraft"),
            mcFrom = "1.7.10", mcTo = "",
            note = "可在启动器里改到外部存储，改了就走公开目录"
        ),
        Entry(
            name = "Zalith Launcher", key = "zalith",
            pkgs = listOf("com.movtery.zalithlauncher"),
            pubNew = listOf("games/ZalithLauncher/.minecraft"),
            privNew = listOf("Android/data/com.movtery.zalithlauncher/files/.minecraft"),
            mcFrom = "1.7.10", mcTo = "",
            note = "支持自定义游戏目录"
        ),
        Entry(
            name = "PojavLauncher", key = "pojav",
            pkgs = listOf("net.kdt.pojavlaunch", "net.kdt.pojavlaunch.debug"),
            pubNew = listOf("games/PojavLauncher/.minecraft"),
            privNew = listOf("Android/data/net.kdt.pojavlaunch/files/.minecraft"),
            privOld = listOf("Android/data/net.kdt.pojavlaunch.debug/files/.minecraft"),
            mcFrom = "1.0", mcTo = "1.21",
            note = "已停止维护，建议换 Zalith"
        ),
        Entry(
            name = "Amethyst", key = "amethyst",
            pkgs = listOf("org.angelauramc.amethyst", "org.angelauramc.amethyst.debug"),
            privNew = listOf("Android/data/org.angelauramc.amethyst/files/.minecraft"),
            privOld = listOf("Android/data/org.angelauramc.amethyst.debug/files/.minecraft"),
            mcFrom = "1.14", mcTo = "",
            note = "仅私有目录"
        ),
        Entry(
            name = "HMCL PE", key = "hmcl",
            pkgs = listOf("com.tungsten.hmclpe"),
            pubNew = listOf("HMCLPE/.minecraft"),
            privNew = listOf("Android/data/com.tungsten.hmclpe/files/.minecraft"),
            mcFrom = "1.0", mcTo = "1.20",
            note = "开版本隔离则在 versions/<版本>/ 下"
        ),
        Entry(
            name = "澪 MioLauncher", key = "mio",
            pkgs = listOf("com.mio.launcher", "com.tungsten.mio"),
            pubNew = listOf("MioLauncher/.minecraft"),
            mcFrom = "1.7.10", mcTo = "",
            note = "模组放在 versions/<版本>/mods"
        ),
        Entry(
            name = "Boat", key = "other",
            pubNew = listOf("boat/gamedir"),
            pubOld = listOf("games/boat/.minecraft"),
            mcFrom = "1.7.10", mcTo = "",
            note = " Boat 系底座，目录名各分支不同"
        ),
        Entry(
            name = "MojoLauncher / MJLauncher", key = "other",
            pkgs = listOf("com.mojolauncher.android"),
            privNew = listOf("Android/data/com.mojolauncher.android/files/mc_data"),
            mcFrom = "1.0", mcTo = "",
            note = "注意是 mc_data 不是 .minecraft"
        ),
        Entry(
            name = "MCinaBox", key = "mcinabox",
            pkgs = listOf("com.aof.mcinabox"),
            pubNew = listOf("MCinaBox/.minecraft"),
            mcFrom = "1.7.10", mcTo = "1.17",
            note = ""
        )
    )
    // ══════════════════════════════════════════════════════════

    /** 当前生效的表：远端更新过的用远端的，否则用内置 */
    private var entries: List<Entry> = TABLE

    /** 全部（含未安装的） */
    fun all(): List<Entry> = entries

    /** 按品牌 key 找 */
    fun byKey(key: String): Entry? = entries.firstOrNull { it.key == key }

    private fun sdcard(): File = Environment.getExternalStorageDirectory()

    private fun installed(ctx: Context, e: Entry): Boolean {
        if (e.pkgs.isEmpty()) return false
        val pm = ctx.packageManager
        return e.pkgs.any { p ->
            try { pm.getPackageInfo(p, 0) != null } catch (t: Throwable) { false }
        }
    }

    /** 该启动器已装吗 */
    fun isInstalled(ctx: Context, e: Entry): Boolean = installed(ctx, e)

    /** 适配版本的一句话，如「1.7.10 起」 */
    fun mcRange(e: Entry): String = when {
        e.mcFrom.isBlank() && e.mcTo.isBlank() -> ""
        e.mcTo.isBlank() -> "${e.mcFrom} 起"
        e.mcFrom.isBlank() -> "到 ${e.mcTo}"
        else -> "${e.mcFrom} – ${e.mcTo}"
    }

    /** 公开目录候选，新的排前面 */
    fun publicCandidates(e: Entry): List<String> = e.pubNew + e.pubOld

    /** 私有目录候选，新的排前面 */
    fun privateCandidates(e: Entry): List<String> = e.privNew + e.privOld

    /**
     * 探测：返回**确实存在**的公开目录。
     * 按「启动器已安装」优先排序。
     *
     * 签名保持不变，设置页与工具箱依赖它。
     */
    fun detect(ctx: Context): List<Pair<Entry, File>> {
        val root = sdcard()
        val out = ArrayList<Pair<Entry, File>>()
        for (e in entries) {
            val f = publicCandidates(e).map { File(root, it) }.firstOrNull { it.isDirectory }
                ?: continue
            out.add(e to f)
            if (installed(ctx, e)) {
                val i = out.indexOfFirst { it.first === e }
                if (i > 0) {
                    val item = out.removeAt(i)
                    out.add(0, item)
                }
            }
        }
        return out
    }

    /**
     * 完整探测：公开目录存在就给出来，不存在但启动器已装的
     * 也列出来（这时只有私有目录，要走同步器）。
     */
    fun detectAll(ctx: Context): List<LauncherAccess.Found> {
        val root = sdcard()
        val out = ArrayList<LauncherAccess.Found>()
        for (e in entries) {
            val ins = installed(ctx, e)
            val pub = publicCandidates(e).map { File(root, it) }.firstOrNull { it.isDirectory }
            if (pub != null) {
                out.add(LauncherAccess.Found(e, pub.absolutePath, LauncherAccess.Kind.PUBLIC, ins))
                continue
            }
            if (ins && privateCandidates(e).isNotEmpty()) {
                out.add(
                    LauncherAccess.Found(
                        e, privateCandidates(e).first(),
                        LauncherAccess.Kind.PRIVATE, true
                    )
                )
            }
        }
        return out.sortedWith(compareBy({ it.installed.not().toString() }, { it.entry.name }))
    }

    /** 该目录是不是一个真正的 .minecraft（有 mods / saves / versions 之一） */
    fun looksLikeMinecraft(f: File): Boolean {
        if (!f.isDirectory) return false
        val names = f.list()?.toSet() ?: return false
        return names.any { McDirs.ROOT_MARKS.any { m -> it.equals(m, true) } }
    }

    /**
     * 从远端补丁更新目录表。
     * 格式：[{"name":"…","pubNew":["…"],"privNew":["…"],"pkgs":["…"],"mcFrom":"…","mcTo":"…","note":"…"}, …]
     * 解析失败一律保留内置表 —— 补丁坏了不能让用户没得选。
     */
    fun updateFrom(json: String): Boolean {
        return try {
            val arr = JSONArray(json)
            val list = ArrayList<Entry>(TABLE)
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                if (name.isBlank()) continue
                fun strs(k: String): List<String> {
                    val a = o.optJSONArray(k) ?: return emptyList()
                    return (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
                }
                val e = Entry(
                    name = name,
                    key = o.optString("key").trim(),
                    pkgs = strs("pkgs"),
                    pubOld = strs("pubOld"),
                    pubNew = strs("pubNew"),
                    privOld = strs("privOld"),
                    privNew = strs("privNew"),
                    mcFrom = o.optString("mcFrom").trim(),
                    mcTo = o.optString("mcTo").trim(),
                    note = o.optString("note").trim()
                )
                if (e.pubNew.isEmpty() && e.pubOld.isEmpty() &&
                    e.privNew.isEmpty() && e.privOld.isEmpty()
                ) continue
                list.removeAll { it.name == name }
                list.add(e)
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
    fun reset() { entries = TABLE }
}
