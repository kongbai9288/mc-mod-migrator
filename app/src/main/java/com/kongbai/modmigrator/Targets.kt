package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

object Targets {

    fun modsDir(ctx: Context): DocumentFile? {
        val uri = Prefs.get(ctx).getString(K.DST_URI, null)
        if (!uri.isNullOrBlank()) {
            val t = Fs.tree(ctx, uri)
            if (t != null) return Fs.ensureDir(t, "mods")
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
