package com.kongbai.modmigrator

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
     * 方式②的结果。
     *
     * [PackResult.file] 是缓存里那份，用来分享给启动器；
     * [PackResult.uri] / [PackResult.savedTo] 是公共下载目录里那份，
     * 用户自己能在文件管理器里找到 —— 之前产物只落在 cacheDir，
     * 用户既看不见也拿不到，等于白打。
     */
    data class PackResult(
        val file: File? = null,
        val uri: Uri? = null,
        /** 人能读的存放位置；空 = 没能存进公共目录 */
        val savedTo: String = "",
        val mcVersion: String = "",
        val loader: String = "",
        val indexed: Int = 0,
        val embedded: Int = 0,
        val note: String = ""
    ) {
        val ok: Boolean get() = file != null
    }

    /** 版本号形如 1.20.1 / 26.2 */
    private val VER_RE = Regex("^\\d+(\\.\\d+)*$")

    private fun verParts(s: String): List<Int> =
        s.split('.').mapNotNull { it.toIntOrNull() }

    /**
     * 从读出来的目录里认游戏版本：只看 `versions/` 下的目录名。
     *
     * 认不出就返回空，**不猜** —— 导入时启动器会自己列出版本让你选，
     * 填一个猜的数字反而会让启动器按错的版本去准备依赖。
     */
    fun detectMcVersion(root: File): String {
        for (base in listOf(File(root, "versions"), File(root, ".minecraft/versions"))) {
            if (!base.isDirectory) continue
            val names = base.listFiles()
                ?.filter { it.isDirectory && VER_RE.matches(it.name) }
                ?.map { it.name }
            if (!names.isNullOrEmpty()) {
                return names.sortedWith(compareByDescending<String> { verParts(it) }).first()
            }
        }
        return ""
    }

    /** 加载器在 libraries 下的落点，命中哪个算哪个 */
    private val LOADER_HINTS = listOf(
        "net/neoforged" to "neoforge",
        "net/minecraftforge" to "forge",
        "net/fabricmc" to "fabric",
        "org/quiltmc" to "quilt"
    )

    fun detectLoader(root: File): String {
        for (libs in listOf(File(root, "libraries"), File(root, ".minecraft/libraries"))) {
            if (!libs.isDirectory) continue
            for ((sub, name) in LOADER_HINTS) {
                if (File(libs, sub).isDirectory) return name
            }
        }
        return ""
    }

    /**
     * 把整合包存进公共下载目录，让用户自己也能拿到。
     *
     * Android 10 起走 MediaStore（不用权限）；更低版本退回公共 Download 目录，
     * 那边需要写权限，没授就失败 —— 失败不影响分享那条路。
     */
    fun saveToDownloads(ctx: Context, src: File, name: String): Pair<Uri?, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ModMigrator")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, v
                )
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { o ->
                        src.inputStream().use { i -> i.copyTo(o) }
                    }
                    v.clear()
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, v, null, null)
                    return uri to "下载/ModMigrator/$name"
                }
            } catch (t: Throwable) {
                Err.ignore(t, "存到下载目录")
            }
        }
        return fallback(ctx, src, name)
    }

    @Suppress("DEPRECATION")
    private fun fallback(ctx: Context, src: File, name: String): Pair<Uri?, String> {
        return try {
            val d = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ModMigrator"
            ).apply { mkdirs() }
            val out = File(d, name)
            src.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            Uri.fromFile(out) to out.absolutePath
        } catch (t: Throwable) {
            Err.ignore(t, "导出整合包")
            null to ""
        }
    }

    /** 把打好的包发给别的 App —— 启动器的「导入整合包」通常能接 */
    fun sharePack(ctx: Context, r: PackResult) {
        val f = r.file ?: return
        QuickTransfer.shareOne(ctx, f, "整合包")
    }

    /**
     * 方式②：同步器读私有目录 → 复制到公共暂存区 → 打成整合包
     * → 存一份到公共下载目录。
     *
     * 游戏版本和加载器**从读出来的目录里认**，不再由调用方传空字符串 ——
     * 之前界面上传的是 `""`，打出来的包 index 里版本是空的，
     * 启动器导入时会按错的（或默认的）版本去准备依赖。
     *
     * 全过程的原因会通过 [log] 逐条写出来，界面直接显示这些行。
     */
    fun packFromPrivate(
        ctx: Context,
        found: Found,
        log: (String) -> Unit
    ): PackResult {
        val cands = LauncherDirs.privateCandidates(found.entry)
        if (cands.isEmpty()) {
            log("清单里没记 ${found.entry.name} 的私有目录，只能自己选一个公开目录")
            return PackResult()
        }
        val src = cands.firstOrNull { PrivSync.exists(it) }
        if (src == null) {
            log("这些私有目录都读不到：\n" + cands.joinToString("\n"))
            val st = Prefs.appCtx()?.let { PrivSync.status(it) }
            if (st != null) log(st.text(ctx))
            return PackResult()
        }
        val stage = File(ctx.filesDir, "priv-stage/" + (found.entry.key.ifBlank { "unknown" }))
        stage.deleteRecursively()
        if (!PrivSync.copyOut(src, stage.absolutePath, log)) {
            log("读出来了但复制失败，暂存区：${stage.absolutePath}")
            return PackResult()
        }

        val mc = detectMcVersion(stage)
        val ld = detectLoader(stage)
        log(
            if (mc.isBlank()) "没能认出游戏版本，导入时启动器会让你自己选"
            else "认出游戏版本 $mc" + if (ld.isBlank()) "" else "，加载器 $ld"
        )

        val safe = found.entry.name.replace(Regex("[^0-9A-Za-z._\\-一-龥]"), "_")
        val out = File(ctx.cacheDir, "export/${safe}_mods.mrpack")
        out.parentFile?.mkdirs()
        val r = MrpackExport.export(
            ctx,
            MrpackExport.Options(
                name = found.entry.name,
                mcVersion = mc,
                loader = ld,
                format = ModpackFormat.MODRINTH,
                onProgress = log
            ),
            DocumentFile.fromFile(stage),
            out
        )
        if (r.file == null) {
            log("打包失败")
            return PackResult(note = "打包失败")
        }
        log("清单下载 ${r.indexed} 个，随包带走 ${r.embedded} 个")
        if (r.note.isNotBlank()) log(r.note)

        val name = "${safe}-${mc.ifBlank { "mc" }}.mrpack"
        val (uri, where) = saveToDownloads(ctx, r.file, name)
        if (where.isNotBlank()) log("已放到：$where")
        else log("没能存进公共下载目录，可以直接用下面的「发送给启动器导入」")
        return PackResult(r.file, uri, where, mc, ld, r.indexed, r.embedded, r.note)
    }
}
