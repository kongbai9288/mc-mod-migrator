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

    fun root(ctx: Context): DocumentFile? {
        val u = uri(ctx)
        if (u.isBlank()) return null
        return Fs.tree(ctx, u)
    }

    fun ready(ctx: Context): Boolean = root(ctx)?.isDirectory == true

    /** 在工作目录下取（或建）一个子目录。工作目录没授权时返回 null。 */
    fun sub(ctx: Context, name: String): DocumentFile? {
        val r = root(ctx) ?: return null
        return Fs.ensureDir(r, name)
    }

    fun packs(ctx: Context): DocumentFile? = sub(ctx, "packs")
    fun mods(ctx: Context): DocumentFile? = sub(ctx, "mods")
    fun configs(ctx: Context): DocumentFile? = sub(ctx, "configs")
    fun cache(ctx: Context): DocumentFile? = sub(ctx, "cache")
    fun translate(ctx: Context): DocumentFile? = sub(ctx, "translate")

    /** 目标 mods 目录：优先工作目录，没授权才退回应用私有目录 */
    fun modsDir(ctx: Context): DocumentFile? {
        return mods(ctx) ?: Targets.modsDir(ctx)
    }

    fun persist(ctx: Context, treeUri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        Prefs.get(ctx).edit().putString(K.WORKDIR_URI, treeUri.toString()).apply()
    }
}
