package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.URLDecoder

object Downloader {

    fun guessName(url: String, fallback: String = "mod.jar"): String {
        var name = url.substringBefore("?").substringAfterLast("/")
        name = try {
            URLDecoder.decode(name, "UTF-8")
        } catch (t: Throwable) {
            name
        }
        name = name.replace(Regex("[^A-Za-z0-9._+\\-]"), "_")
        if (name.isBlank() || !name.contains(".")) name = fallback
        return name
    }

    fun download(ctx: Context, url: String, dir: DocumentFile, name: String): DocumentFile? {
        return try {
            val resp = Http.call(url)
            if (!resp.isSuccessful) {
                resp.close()
                return null
            }
            val existing = dir.findFile(name)
            if (existing != null) existing.delete()
            val out = dir.createFile(Fs.mimeOf(name), name)
            if (out == null) {
                resp.close()
                return null
            }
            val body = resp.body
            if (body != null) {
                body.byteStream().use { input ->
                    ctx.contentResolver.openOutputStream(out.uri)?.use { o ->
                        input.copyTo(o, 1 shl 16)
                    }
                }
            }
            resp.close()
            out
        } catch (t: Throwable) {
            null
        }
    }

    fun toCache(ctx: Context, url: String, name: String): File? {
        return try {
            val resp = Http.call(url)
            if (!resp.isSuccessful) {
                resp.close()
                return null
            }
            val cache = File(ctx.cacheDir, "dl")
            if (!cache.exists()) cache.mkdirs()
            val f = File(cache, name)
            val body = resp.body
            if (body != null) {
                body.byteStream().use { input -> f.outputStream().use { o -> input.copyTo(o, 1 shl 16) } }
            }
            resp.close()
            f
        } catch (t: Throwable) {
            null
        }
    }
}
