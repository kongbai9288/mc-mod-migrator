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
 * ## 每条记录都要标明证据等级（[Evidence]）
 *
 * 这份清单过去混进了凭印象写的路径和包名。应用要对外发布，
 * 显示给用户的东西不能有编造成分，所以现在分三档：
 *
 * - [Evidence.SOURCE] —— 有上游源码或官方文件佐证，[Entry.source] 写明出处
 * - [Evidence.COMMUNITY] —— 社区资料整理，**未经核实**，只能当候选去试
 * - [Evidence.RUNTIME] —— 在这台设备上探测到了，属于本机事实
 *
 * **候选路径一律"存在才算"**（见 [detect]），猜错不会误导用户，
 * 但 [Evidence.COMMUNITY] 的条目在界面上应当标明未核实，
 * 免得用户以为这是官方确认的路径。
 *
 * ⚠️ 官方 Android 11 存储更新：**SAF 无法授权 `Android/data` 及其子目录**，
 * 且从 11 起就是如此，不是 Android 14 才收紧（见 [StoreRules]）。
 * 因此 [Entry.privNew] / [Entry.privOld] 里的私有路径只能拿来看、
 * 以及交给同步器读取（见 [PrivSync]），绝不能拿去发起目录授权。
 *
 * [Entry.mcFrom] / [Entry.mcTo] 是社区整理的适配区间，
 * 会随启动器更新变化，**仅用于提示，不用来拦截操作**。
 */
object LauncherDirs {

    /**
     * 一条记录的依据有多硬。
     *
     * 对外发布后，界面上会据此决定要不要标注"未核实"。
     */
    enum class Evidence {
        /** 有上游源码 / 官方文件佐证，见 [Entry.source] */
        SOURCE,

        /** 社区资料整理，未核实 */
        COMMUNITY,

        /** 本机探测到确实存在 */
        RUNTIME,
    }

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
        val note: String = "",
        /** 这条记录的依据有多硬 */
        val evidence: Evidence = Evidence.COMMUNITY,
        /** [Evidence.SOURCE] 时写明出处，例如上游文件路径 */
        val source: String = ""
    )

    // ══════════════════════════════════════════════════════════
    //  数据区：改目录 / 改包名 / 改版本，只改这一段
    //  路径一律相对 /sdcard（多数设备是 /storage/emulated/0）
    // ══════════════════════════════════════════════════════════
    private val TABLE = listOf(
        Entry(
            name = "FCL", key = "fcl",
            pkgs = listOf("com.tungsten.fcl", "com.tungsten.fcl.debug"),
            pubNew = listOf("FCL/.minecraft"),
            pubOld = listOf("games/FCL/.minecraft"),
            privNew = listOf("Android/data/com.tungsten.fcl/files/.minecraft"),
            privOld = listOf("Android/data/com.tungsten.fcl.debug/files/.minecraft"),
            mcFrom = "1.0", mcTo = "",
            note = "开版本隔离则在 versions/<版本>/ 下；" +
                "games/FCL 那条是社区说法，未在上游源码中确认",
            evidence = Evidence.SOURCE,
            source = "FCLPath.java 的 SHARED_COMMON_DIR 与 PRIVATE_COMMON_DIR；" +
                "FCL/build.gradle.kts 的 applicationId"
        ),
        Entry(
            name = "Zalith Launcher 2", key = "zalith2",
            pkgs = listOf("com.movtery.zalithlauncher.v2", "com.movtery.zalithlauncher.debug"),
            privNew = listOf("Android/data/com.movtery.zalithlauncher.v2/files/.minecraft"),
            privOld = listOf("Android/data/com.movtery.zalithlauncher.debug/files/.minecraft"),
            mcFrom = "1.7.10", mcTo = "",
            note = "默认就放在私有目录；但它支持用户自定义多个游戏目录，" +
                "改过之后位置由用户决定，猜不到——请让用户在文件管理器里选",
            evidence = Evidence.SOURCE,
            source = "GamePathManager.kt 的 defaultGamePath = File(DIR_FILES_EXTERNAL, \".minecraft\")；" +
                "ZalithLauncher/build.gradle.kts 的 applicationId + \".v2\""
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
