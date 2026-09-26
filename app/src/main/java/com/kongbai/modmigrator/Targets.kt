package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

object Targets {

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
