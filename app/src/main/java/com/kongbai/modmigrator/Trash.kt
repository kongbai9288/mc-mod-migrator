package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * 回收站。
 *
 * 删模组不是直接删，先挪到工作目录的 trash/ 下，
 * 同时记一份清单（原路径、时间、大小），这样能：
 *   - 撤销删除（还原回原路径）
 *   - 按自定义天数自动清理过期的
 *   - 手动清空
 *
 * 残留清理也走这里：装了又卸的模组，会在 config/ 之类的地方
 * 留下孤儿配置文件，扫出来一并挪进回收站（同样可撤销）。
 */
object Trash {

    data class Item(
        var name: String = "",        // 文件名
        var from: String = "",        // 原父目录 URI
        var trashUri: String = "",    // 在回收站里的 URI
        var at: Long = 0L,            // 删除时间
        var size: Long = 0L
    )

    private fun indexFile(ctx: Context): java.io.File =
        java.io.File(ctx.filesDir, "trash_index.json")

    fun items(ctx: Context): MutableList<Item> {
        return try {
            val f = indexFile(ctx)
            if (!f.exists()) return mutableListOf()
            val arr = JSONArray(f.readText())
            val out = mutableListOf<Item>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Item(
                        name = o.optString("name"),
                        from = o.optString("from"),
                        trashUri = o.optString("trashUri"),
                        at = o.optLong("at"),
                        size = o.optLong("size")
                    )
                )
            }
            out
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    private fun save(ctx: Context, list: List<Item>) {
        try {
            val arr = JSONArray()
            for (it in list) {
                val o = JSONObject()
                o.put("name", it.name)
                o.put("from", it.from)
                o.put("trashUri", it.trashUri)
                o.put("at", it.at)
                o.put("size", it.size)
                arr.put(o)
            }
            indexFile(ctx).writeText(arr.toString())
        } catch (t: Throwable) { Err.ignore(t, "indexFile(ctx).writeText(arr.toString())") }
    }

    fun days(ctx: Context): Int = Prefs.get(ctx).getInt(K.TRASH_DAYS, 7).coerceIn(1, 90)

    fun setDays(ctx: Context, d: Int) {
        Prefs.get(ctx).edit().putInt(K.TRASH_DAYS, d.coerceIn(1, 90)).apply()
    }

    /**
     * 把文件挪进回收站。
     * @return 是否成功
     */
    fun moveToTrash(ctx: Context, file: DocumentFile): Boolean {
        return try {
            val dir = WorkDir.trash(ctx) ?: return false
            val name = file.name ?: return false
            // 同名加时间戳，避免覆盖
            val target = dir.findFile(name)
            if (target != null) target.delete()
            val dst = dir.createFile(Fs.mimeOf(name), name) ?: return false

            ctx.contentResolver.openInputStream(file.uri)?.use { input ->
                ctx.contentResolver.openOutputStream(dst.uri, "wt")?.use { o ->
                    input.copyTo(o, 1 shl 16)
                }
            } ?: return false

            val parentUri = file.parentFile?.uri?.toString() ?: ""
            val size = file.length()
            file.delete()

            val list = items(ctx)
            list.add(
                0,
                Item(
                    name = name,
                    from = parentUri,
                    trashUri = dst.uri.toString(),
                    at = System.currentTimeMillis(),
                    size = size
                )
            )
            save(ctx, list)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 撤销：还原回原目录 */
    fun restore(ctx: Context, item: Item): Boolean {
        return try {
            val src = DocumentFile.fromSingleUri(
                ctx, android.net.Uri.parse(item.trashUri)
            ) ?: return false
            if (!src.exists()) {
                remove(ctx, item)
                return false
            }
            val parent = if (item.from.isBlank()) {
                WorkDir.modsDir(ctx)
            } else {
                DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(item.from))
            } ?: return false

            val dst = parent.createFile(Fs.mimeOf(item.name), item.name)
                ?: parent.findFile(item.name)
                ?: return false
            ctx.contentResolver.openInputStream(src.uri)?.use { input ->
                ctx.contentResolver.openOutputStream(dst.uri, "wt")?.use { o ->
                    input.copyTo(o, 1 shl 16)
                }
            } ?: return false
            src.delete()
            remove(ctx, item)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 从清单里移除（已还原或已彻底删除） */
    fun remove(ctx: Context, item: Item) {
        val list = items(ctx).filter { it.trashUri != item.trashUri }.toMutableList()
        save(ctx, list)
    }

    /** 彻底删除某一个 */
    fun deleteForever(ctx: Context, item: Item): Boolean {
        return try {
            val f = DocumentFile.fromSingleUri(
                ctx, android.net.Uri.parse(item.trashUri)
            )
            f?.delete()
            remove(ctx, item)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 清空回收站 */
    fun empty(ctx: Context): Int {
        var n = 0
        for (it in items(ctx)) {
            try {
                DocumentFile.fromSingleUri(ctx, android.net.Uri.parse(it.trashUri))?.delete()
                n++
            } catch (t: Throwable) { Err.ignore(t, "n++") }
        }
        save(ctx, emptyList())
        return n
    }

    /** 自动清理超过保留天数的 */
    fun purgeExpired(ctx: Context): Int {
        val days = days(ctx)
        val deadline = System.currentTimeMillis() - days * 24L * 3600L * 1000L
        val all = items(ctx)
        val keep = mutableListOf<Item>()
        var n = 0
        for (it in all) {
            if (it.at < deadline) {
                try {
                    DocumentFile.fromSingleUri(ctx, android.net.Uri.parse(it.trashUri))?.delete()
                    n++
                } catch (t: Throwable) {
                    keep.add(it)
                }
            } else {
                keep.add(it)
            }
        }
        save(ctx, keep)
        return n
    }

    fun totalSize(ctx: Context): Long = items(ctx).sumOf { it.size }

    /** 回收站里有多少个 */
    fun count(ctx: Context): Int = items(ctx).size

    /**
     * 残留清理：找出「模组已经不在了，但配置还留着」的孤儿文件。
     *
     * 判定：config / defaultconfigs 等目录下，文件名里能匹配到某个已删模组名
     * （或反过来：配置文件的 basename 在 mods 目录里找不到对应 jar）的，
     * 都算残留。保守起见只处理常见扩展名，且不会动还在用的。
     */
    fun findResidue(ctx: Context): List<DocumentFile> {
        val out = mutableListOf<DocumentFile>()
        return try {
            val mods = WorkDir.modsDir(ctx) ?: return emptyList()
            val installed = Fs.children(mods)
                .filter { it.isFile }
                .mapNotNull { it.name }
                .map { baseName(it) }
                .toSet()

            val dirs = listOfNotNull(
                WorkDir.configs(ctx),
                WorkDir.sub(ctx, "defaultconfigs"),
                WorkDir.sub(ctx, "kubejs"),
                WorkDir.sub(ctx, "scripts")
            )
            for (d in dirs) {
                for (f in Fs.children(d)) {
                    if (!f.isFile) continue
                    val n = f.name ?: continue
                    val base = baseName(n)
                    // 配置名能对应到某个已安装模组 → 在用，跳过
                    if (installed.any { base.contains(it, true) || it.contains(base, true) }) continue
                    out.add(f)
                }
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun baseName(n: String): String =
        n.substringBeforeLast(".")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
}
