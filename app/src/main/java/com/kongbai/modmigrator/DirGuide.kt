package com.kongbai.modmigrator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * 目录授权引导。
 *
 * ⚠️ 真实问题：点「选择目录」后，系统弹出的是**整台手机的目录树**，
 * 用户根本不知道该选到哪一层。
 * 选错的表现千奇百怪：选到启动器根目录 → 扫出 0 个实例；
 * 选到 Android/data 上一层 → 提示"目录不可访问"；
 * 选到某个实例的上级 → 扫出来一堆不相干的东西。
 * 而界面上只会说"请选择目录"，没有任何指引，
 * 用户只能靠猜，猜错也不知道错在哪。
 *
 * 这里做三件事：
 *   1. 授权**前**先说清楚"该选到哪一层"，并列出常见启动器的实际路径
 *   2. 尽可能让系统文件管理器**直接定位**到那一层（少点十几下）
 *   3. 授权**后**校验，选错了当场说清楚，而不是扫出空列表让人莫名其妙
 */
object DirGuide {

    /** 授权用途。不同用途要选到的层级不一样，提示文案也不同。 */
    enum class Purpose {
        /** 迁移前的版本（源） */
        SOURCE,
        /** 迁移后的版本（目标） */
        TARGET,
        /** 实例扫描根：可以选启动器根，里面有很多版本 */
        SCAN_ROOT,
        /** 工作目录 / 下载输出目录：随便哪层都行 */
        FREE
    }

    /** 常见启动器的实例所在目录，用于给用户看实际路径 */
    private val SAMPLES = listOf(
        "FCL" to "/storage/emulated/0/Android/data/com.tungsten.fcl/files/.minecraft",
        "Zalith" to "/storage/emulated/0/Android/data/com.movtery.zalithlauncher/files/.minecraft",
        "Pojav" to "/storage/emulated/0/Android/data/net.kdt.pojavlaunch/files/.minecraft",
        "Amethyst" to "/storage/emulated/0/Android/data/org.angelauramc.amethyst.debug/files/.minecraft",
        "HMCL / PCL" to "/storage/emulated/0/games/HMCL/.minecraft"
    )

    /** 每种用途的一句话说明 */
    private fun what(p: Purpose): String = when (p) {
        Purpose.SOURCE ->
            "选到「迁移前」的那个游戏版本目录 —— 也就是直接能看到 mods、saves、config 这几个文件夹的那一层。"
        Purpose.TARGET ->
            "选到「迁移后」的那个游戏版本目录 —— 模组要搬过去的那一层，同样要能直接看到 mods 文件夹。"
        Purpose.SCAN_ROOT ->
            "选到启动器的 .minecraft 目录（或放实例的那一层）。它下面通常有 versions、instances、mods。"
        Purpose.FREE ->
            "选一个用来存放导出包和下载的目录，哪一层都可以。"
    }

    /**
     * 发起目录授权。
     *
     * @param act     调用方 Activity（结果会回到它的 onActivityResult）
     * @param code    requestCode
     * @param purpose 用途，决定提示文案和校验规则
     */
    fun pick(act: Activity, code: Int, purpose: Purpose) {
        val ctx = act
        val body = buildString {
            append(what(purpose)).append("\n\n")
            if (purpose != Purpose.FREE) {
                append("常见位置（照着点进去即可）：\n")
                for ((name, path) in SAMPLES) append("• $name：$path\n")
                append("\n不用一层层翻：多数机型会直接停在你上次打开的位置，")
                append("顺着上面的路径点进去就行。")
            }
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle(if (purpose == Purpose.FREE) "选择目录" else "该选到哪一层")
            .setMessage(body)
            .setPositiveButton("去选择") { _, _ -> launch(act, code, purpose) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launch(act: Activity, code: Int, purpose: Purpose) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        //
        // 尽量让系统文件管理器直接定位到目标目录。
        // Android 11+ 支持 EXTRA_INITIAL_URI，能省掉用户十几下点击；
        // 不支持的机型会忽略这个参数，行为跟以前一样。
        //
        initialUri(act, purpose)?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        try {
            act.startActivityForResult(i, code)
        } catch (t: Throwable) {
            // 极少数机型没有能处理这个 Intent 的文件管理器
            Err.fail(t, "打不开系统文件选择器")
            android.widget.Toast.makeText(act, "找不到文件管理器，无法选择目录", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 构造初始定位 URI。
     *
     * 只对**能确定**的路径生效（上次成功扫到实例的目录、已保存的源/目标目录）。
     * 拿不到就返回 null，让系统自己决定起始位置。
     */
    private fun initialUri(ctx: Context, purpose: Purpose): Uri? {
        val p = Prefs.get(ctx)
        val saved = when (purpose) {
            Purpose.SOURCE -> p.getString(K.SRC_URI, "") ?: ""
            Purpose.TARGET -> p.getString(K.DST_URI, "") ?: ""
            Purpose.SCAN_ROOT -> p.getString(K.SCAN_ROOT, "") ?: ""
            Purpose.FREE -> ""
        }
        if (saved.isNotBlank()) return runCatching { Uri.parse(saved) }.getOrNull()
        return null
    }

    /**
     * 把 SAF 的 tree URI 转成人能看懂的路径。
     *
     * ⚠️ 界面上原来直接显示 URI 原文：
     * `content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2F...`
     * 用户看了一脸茫然，根本不知道自己选到了哪 ——
     * 这正是"不知道该授权到哪一页"的另一半原因：选完也确认不了。
     */
    fun human(uri: String): String {
        if (uri.isBlank()) return ""
        return try {
            val u = Uri.parse(uri)
            val raw = u.path ?: return uri
            // 形如 /tree/primary:Android/data/xxx
            val after = raw.substringAfter("/tree/", raw)
            val decoded = android.net.Uri.decode(after)
            when {
                decoded.startsWith("primary:") ->
                    "/storage/emulated/0/" + decoded.removePrefix("primary:")
                else -> "/$decoded"
            }
        } catch (t: Throwable) {
            uri
        }
    }

    /**
     * 授权回来后校验：这个目录选对了吗。
     *
     * 返回 null 表示没问题；返回非空则是给用户看的一句提醒。
     *
     * 为什么必须校验：选错目录不会报错，只会「扫出 0 个实例」
     * 或「识别不出版本」，用户完全无法把现象和"选错层"联系起来。
     */
    fun check(ctx: Context, uri: Uri, purpose: Purpose): String? {
        if (purpose == Purpose.FREE) return null
        val root = Fs.tree(ctx, uri.toString())
        if (root == null || !root.isDirectory) return "这个目录打不开，可能授权没生效，请重选一次"

        val names = root.listFiles().map { it.name ?: "" }.toSet()

        // 选到了启动器根 / Android/data 这一层：下面全是包名，不是游戏目录
        val looksLikeAppRoot = names.any {
            it.startsWith("com.") || it.startsWith("net.") || it.startsWith("org.")
        }
        if (looksLikeAppRoot && !names.contains("mods") && !names.contains("versions")) {
            return "这里像是启动器的上层目录（下面都是包名文件夹）。请再往里进一层，选到能直接看到 mods 的那一层。"
        }

        // 游戏目录的直接特征：这些文件夹就在它下面
        val direct = listOf("mods", "saves", "config", "versions", "resourcepacks", "shaderpacks")
        val hasDirect = direct.any { names.contains(it) }

        if (!hasDirect) {
            // 次级特征：实例目录（里面套着每个实例的文件夹）
            val instanceMarks = listOf(
                "instance.cfg", "mmc-pack.json", "manifest.json",
                "minecraftinstance.json", "level.dat"
            )
            val hasInstance = names.any { instanceMarks.contains(it) }
            if (!hasInstance && purpose != Purpose.SCAN_ROOT) {
                return "这个目录里没看到 mods / saves / config，可能选错了层级。选到能直接看到这些文件夹的那一层。"
            }
        }
        return null
    }
}
