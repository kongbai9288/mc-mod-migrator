package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

object Targets {

    /**
     * 只取**用户选定的迁移目标**下的 mods 目录；没授权就返回 null。
     *
     * ⚠️ 之前模组管理页写的是 `WorkDir.modsDir() ?: Targets.modsDir()`，
     * 而 WorkDir.modsDir 一旦设过工作目录就**总会命中**，
     * 于是列出的永远是应用工作目录里的 mod，
     * 而不是用户在迁移页选定的那个游戏的 mods ——
     * 表现就是"模组管理里看到的和我游戏里的对不上"。
     *
     * 注意这个不带兜底：拿不到就返回 null，让调用方决定接下来怎么办，
     * 不要静默退回私有目录（那会让人以为列的就是游戏里的模组）。
     */
    fun dstModsDir(ctx: Context): DocumentFile? {
        val uri = Prefs.get(ctx).getString(K.DST_URI, null)
        if (uri.isNullOrBlank()) return null
        val t = Fs.tree(ctx, uri) ?: return null
        return Fs.ensureDir(t, "mods")
    }

    fun modsDir(ctx: Context): DocumentFile? {
        val uri = Prefs.get(ctx).getString(K.DST_URI, null)
        if (!uri.isNullOrBlank()) {
            val t = Fs.tree(ctx, uri)
            // ensureDir 现在失败返回 null（以前会错误地返回父目录），
            // 这里要在失败时**继续往下走**用应用私有目录兜底，
            // 不能直接把 null 返回出去。
            if (t != null) {
                val m = Fs.ensureDir(t, "mods")
                if (m != null) return m
            }
        }
        val f = File(ctx.getExternalFilesDir(null), "mods")
        if (!f.exists()) f.mkdirs()
        return DocumentFile.fromFile(f)
    }

    fun root(ctx: Context): DocumentFile? {
        val uri = Prefs.get(ctx).getString(K.DST_URI, null)
        if (!uri.isNullOrBlank()) return Fs.tree(ctx, uri)
        val f = File(ctx.getExternalFilesDir(null), "instance")
        if (!f.exists()) f.mkdirs()
        return DocumentFile.fromFile(f)
    }
}
