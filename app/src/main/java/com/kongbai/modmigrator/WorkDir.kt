package com.kongbai.modmigrator

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * 自有工作目录。
 *
 * 申请一次「整个外部存储 / 单个目录」的授权后，所有导出包、下载缓存、
 * 配置备份、翻译缓存都放进这一个目录，不再散落在各处。
 */
object WorkDir {

    fun uri(ctx: Context): String = Prefs.get(ctx).getString(K.WORKDIR_URI, "") ?: ""

    /**
     * 工作目录根。
     *
     * 没授权就用 App 私有目录（不需要任何权限，开箱可用），
     * 授权了就用用户选的那个。这样"不授权就什么都干不了"的情况不再存在。
     */
    fun root(ctx: Context): DocumentFile? {
        val u = uri(ctx)
        if (u.isNotBlank()) {
            val t = Fs.tree(ctx, u)
            if (t != null && t.isDirectory) return t
        }
        return try {
            androidx.documentfile.provider.DocumentFile.fromFile(DefaultDir.root(ctx))
        } catch (e: Throwable) {
            null
        }
    }

    /** 是否用的是用户授权的外部目录（false 表示用的 App 私有目录） */
    fun isCustom(ctx: Context): Boolean {
        val u = uri(ctx)
        if (u.isBlank()) return false
        return Fs.tree(ctx, u)?.isDirectory == true
    }

    /** 给用户看的路径说明 */
    fun describe(ctx: Context): String =
        if (isCustom(ctx)) "已授权外部目录" else "App 私有目录（免授权）：${DefaultDir.path(ctx)}"

    /** 现在始终可用（有私有目录兜底），保留这个方法是为了兼容旧调用 */
    fun ready(ctx: Context): Boolean = root(ctx)?.isDirectory == true

    /**
     * 子目录缓存。
     *
     * 之前每次 sub() 都会走一遍 findFile/createDirectory，
     * 在树授权下这是跨进程调用，一次迁移可能重复几十上百次，
     * 既慢又容易触发"反复请求授权"的错觉。
     * 这里按 目录URI+名字 缓住，同一进程内只解析一次。
     */
    private val dirCache = java.util.concurrent.ConcurrentHashMap<String, DocumentFile?>()

    /** 换工作目录时必须清缓存，否则会拿到旧目录 */
    fun invalidate() {
        dirCache.clear()
    }

    /** 在工作目录下取（或建）一个子目录。 */
    fun sub(ctx: Context, name: String): DocumentFile? {
        val r = root(ctx) ?: return null
        val key = "${r.uri}|$name"
        val hit = dirCache[key]
        if (hit != null && hit.isDirectory) return hit
        val got = if (r.uri.scheme == "file") {
            val f = java.io.File(r.uri.path, name)
            if (!f.exists()) f.mkdirs()
            androidx.documentfile.provider.DocumentFile.fromFile(f)
        } else {
            Fs.ensureDir(r, name)
        }
        if (got != null) dirCache[key] = got
        return got
    }

    fun packs(ctx: Context): DocumentFile? = sub(ctx, "packs")
    fun mods(ctx: Context): DocumentFile? = sub(ctx, "mods")
    fun configs(ctx: Context): DocumentFile? = sub(ctx, "configs")
    fun cache(ctx: Context): DocumentFile? = sub(ctx, "cache")
    fun translate(ctx: Context): DocumentFile? = sub(ctx, "translate")

    /** 运行日志 */
    fun logs(ctx: Context): DocumentFile? = sub(ctx, "logs")

    /** 收藏与用户数据（收藏列表、标记链接等，跟着工作目录走，换设备可带走） */
    fun data(ctx: Context): DocumentFile? = sub(ctx, "data")

    /** 目标 mods 目录：优先工作目录，没授权才退回应用私有目录 */
    /** 回收站目录 */
    fun trash(ctx: Context) = sub(ctx, "trash")

    /** 模组图标缓存目录 */
    fun icons(ctx: Context) = sub(ctx, "icons")

    fun modsDir(ctx: Context): DocumentFile? {
        return mods(ctx) ?: Targets.modsDir(ctx)
    }

    fun persist(ctx: Context, treeUri: Uri): Boolean {
        // 统一走 Perms：复用已有授权、自动回收配额，避免越用越卡
        val ok = Perms.take(ctx, treeUri)
        Prefs.get(ctx).edit().putString(K.WORKDIR_URI, treeUri.toString()).apply()
        WorkDir.invalidate()
        return ok
    }
}
