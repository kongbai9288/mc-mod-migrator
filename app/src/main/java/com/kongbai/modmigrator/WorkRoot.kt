package com.kongbai.modmigrator

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * 应用往哪放东西 —— 全工程只有这一处说了算。
 *
 * ## 为什么要有这一层
 *
 * 之前每个要落盘的模块各自挑目录：瓦片写应用私有目录、
 * 导出写公有 Pictures、日志写另一处。于是同一个应用的东西散在四五个位置，
 * 用户明明在设置里指定了工作目录，很多模块却当它不存在 ——
 * 这是"功能 A 能找到文件、功能 B 找不到"的直接来源。
 *
 * 现在所有落盘都先问这里要根，改一处就全改了。
 *
 * ## 优先级
 *
 * 1. 用户在设置里指定的工作目录（能解析成真实路径时）
 * 2. 应用私有外部目录（不需要任何授权，开箱可用）
 *
 * 第 1 条为什么是"能解析成真实路径时"：
 *   SAF 给的 `content://` 树只能走 DocumentFile，那是另一套 API，
 *   给不了 `File`。缓存、瓦片这类需要 File 的地方只能等它解析成功，
 *   解析不了就退回第 2 条 —— 宁可放私有目录，也不要放进去再读不出来。
 */
object WorkRoot {

    /** 根目录名。所有子目录都挂在它下面，方便用户一眼认出是谁的 */
    private const val NAME = "ModMigrator"

    /**
     * SAF 树 Uri → 真实路径。解析不了返回 null。
     *
     * 只认系统自带的文件管理器给的格式：
     * `content://com.android.externalstorage.documents/tree/primary%3ADCIM`
     * → `/sdcard/DCIM`。第三方管理器格式各异，认不出来就交给调用方兜底。
     */
    fun realPath(uriStr: String?): String? {
        if (uriStr.isNullOrBlank()) return null
        return runCatching {
            val u = Uri.parse(uriStr)
            if (u.authority != "com.android.externalstorage.documents") return null
            val id = u.lastPathSegment ?: return null
            // id 形如 "primary:DCIM" 或 "primary:DCIM/子目录"
            val i = id.indexOf(':')
            if (i < 0) return null
            val vol = id.substring(0, i)
            val rel = id.substring(i + 1)
            val base = if (vol == "primary") {
                Environment.getExternalStorageDirectory()
            } else {
                File("/storage/$vol")
            }
            if (!base.exists()) return null
            val f = if (rel.isEmpty()) base else File(base, rel)
            if (!f.exists() && !f.mkdirs()) return null
            f.absolutePath
        }.getOrNull()
    }

    /** 用户在设置里指定的工作目录。没指定或解析不了就 null */
    fun userDir(ctx: Context): File? {
        val s = Prefs.get(ctx).getString(K.WORKDIR_URI, null)
        val p = realPath(s)
        return if (p == null) null else File(p, NAME)
    }

    /** 兜底目录：应用私有外部存储，不需要任何存储权限 */
    fun privateDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, NAME)

    /** 根。优先用户目录，拿不到就用私有目录 */
    fun base(ctx: Context): File {
        val d = userDir(ctx) ?: privateDir(ctx)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 根下的子目录，不存在就建 */
    fun sub(ctx: Context, name: String): File {
        val d = File(base(ctx), name)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 给用户看的路径与来源说明 */
    fun describe(ctx: Context): String {
        val u = userDir(ctx)
        return if (u != null) "${u.absolutePath}\n（你在设置里指定的目录）"
        else "${privateDir(ctx).absolutePath}\n（应用目录，未指定时用这个）"
    }
}
