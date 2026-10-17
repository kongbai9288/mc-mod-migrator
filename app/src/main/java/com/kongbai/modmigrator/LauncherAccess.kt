package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 拿不到游戏目录时怎么办。
 *
 * 已知启动器：清单里有它的目录，探测到了就直接用。
 * 未知启动器（清单里没有、或者它的目录在 Android/data 里读不到）：
 * 给用户两条路，任选一条 ——
 *
 *   ① [Way.PICK_PUBLIC]  自己选一个公开目录
 *      用户在系统文件管理器里选到能直接看到 mods 的那一层。
 *      启动器支持自定义目录时（Zalith 等）这条最省事。
 *
 *   ② [Way.SYNC_PRIVATE] 用同步器读私有目录，读出来打成整合包
 *      同步器（Shizuku）以 adb/root 身份代读 Android/data，
 *      复制到公共暂存区后按普通目录打成 .mrpack。
 *
 * 打出来的包再由 [Way.LAUNCHER_IMPORT] 交给启动器自己导入 ——
 * 启动器写自己的私有目录不受系统限制，这条在 Android 14/16 上最稳。
 */
object LauncherAccess {

    /** 目录在哪 */
    enum class Kind {
        /** 公共目录，SAF 能授权 */
        PUBLIC,
        /** 私有目录（Android/data），只能靠同步器 */
        PRIVATE
    }

    data class Found(
        val entry: LauncherDirs.Entry,
        val path: String,
        val kind: Kind,
        val installed: Boolean
    )

    /** 拿目录的两条路 */
    enum class Way {
        PICK_PUBLIC,
        SYNC_PRIVATE,
        LAUNCHER_IMPORT
    }

    /** 清单里没有的启动器 */
    fun unknown(): LauncherDirs.Entry = LauncherDirs.Entry(
        name = "未知启动器",
        key = "other",
        note = "清单里没有它，需要你指定目录"
    )

    /**
     * 这个启动器能用哪几条路。
     * 公开目录只有①；私有目录给②③（②③其实是同一条流水线的后半段）。
     */
    fun ways(found: Found?): List<Way> = when (found?.kind) {
        Kind.PUBLIC -> listOf(Way.PICK_PUBLIC)
        Kind.PRIVATE -> listOf(Way.SYNC_PRIVATE, Way.LAUNCHER_IMPORT)
        null -> listOf(Way.PICK_PUBLIC, Way.SYNC_PRIVATE, Way.LAUNCHER_IMPORT)
    }

    /**
     * 未知启动器：按包名推出它的私有目录候选。
     *
     * 只能推，不能保证对 —— 所以这里列的是**各家最常见的几种落法**，
     * 逐个试到能读的为止。以后发现新的落法，往 [SUBS] 里加一行即可。
     */
    private val SUBS = listOf(
        "files/.minecraft",
        "files/mc_data",
        "files/gamedir",
        "files/minecraft"
    )

    /** 由包名造一条临时记录 */
    fun fromPackage(pkg: String, label: String): LauncherDirs.Entry = LauncherDirs.Entry(
        name = label,
        key = LauncherBrand.fromPath(pkg).key,
        pkgs = listOf(pkg),
        privNew = SUBS.map { "Android/data/$pkg/$it" }
    )

    /**
     * 已安装、但清单里没有的疑似启动器。
     * 按包名与应用名里有没有品牌关键词 / minecraft / launch 判断。
     */
    fun unknownCandidates(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val known = LauncherDirs.all().flatMap { it.pkgs }.toHashSet()
        val keys = LauncherBrand.keys()
        val out = ArrayList<Pair<String, String>>()
        val mine = ctx.packageName
        for (pi in pm.getInstalledPackages(0)) {
            val pkg = pi.packageName ?: continue
            if (pkg == mine || pkg in known) continue
            val label = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
            val blob = "$pkg ${label.lowercase()}"
            val hit = keys.any { blob.contains(it) } ||
                blob.contains("minecraft") || blob.contains("launch")
            if (hit) out.add(label to pkg)
        }
        return out.sortedBy { it.first }
    }

    fun title(ctx: Context, w: Way): String = ctx.getString(
        when (w) {
            Way.PICK_PUBLIC -> R.string.way_pick_public
            Way.SYNC_PRIVATE -> R.string.way_sync_private
            Way.LAUNCHER_IMPORT -> R.string.way_launcher_import
        }
    )

    fun desc(ctx: Context, w: Way): String = ctx.getString(
        when (w) {
            Way.PICK_PUBLIC -> R.string.way_pick_public_desc
            Way.SYNC_PRIVATE -> R.string.way_sync_private_desc
            Way.LAUNCHER_IMPORT -> R.string.way_launcher_import_desc
        }
    )

    /**
     * 方式②：同步器读私有目录 → 复制到公共暂存区 → 打成整合包。
     *
     * 返回导出的 .mrpack；读不到或打包失败返回 null，
     * 原因会通过 [log] 逐条写出来，界面直接显示这些行。
     */
    fun packFromPrivate(
        ctx: Context,
        found: Found,
        mcVersion: String,
        loader: String,
        log: (String) -> Unit
    ): File? {
        val cands = LauncherDirs.privateCandidates(found.entry)
        if (cands.isEmpty()) {
            log("清单里没记 ${found.entry.name} 的私有目录，只能自己选一个公开目录")
            return null
        }
        val src = cands.firstOrNull { PrivSync.exists(it) }
        if (src == null) {
            log("这些私有目录都读不到：\n" + cands.joinToString("\n"))
            val st = Prefs.appCtx()?.let { PrivSync.status(it) }
            if (st != null) log(st.text(ctx))
            return null
        }
        val stage = File(ctx.filesDir, "priv-stage/" + (found.entry.key.ifBlank { "unknown" }))
        stage.deleteRecursively()
        if (!PrivSync.copyOut(src, stage.absolutePath, log)) {
            log("读出来了但复制失败，暂存区：${stage.absolutePath}")
            return null
        }
        val out = File(ctx.cacheDir, "export/${found.entry.name.replace("/", "_")}_mods.mrpack")
        out.parentFile?.mkdirs()
        val r = MrpackExport.export(
            ctx,
            MrpackExport.Options(
                name = found.entry.name,
                mcVersion = mcVersion,
                loader = loader,
                format = ModpackFormat.MODRINTH,
                onProgress = log
            ),
            DocumentFile.fromFile(stage),
            out
        )
        if (r.file == null) {
            log("打包失败")
            return null
        }
        log("清单下载 ${r.indexed} 个，随包带走 ${r.embedded} 个")
        if (r.note.isNotBlank()) log(r.note)
        return r.file
    }
}
