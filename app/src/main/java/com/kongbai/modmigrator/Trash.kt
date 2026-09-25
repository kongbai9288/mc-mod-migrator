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
        var name: String = "",        // 在回收站里的文件名（同名时带序号）
        var origName: String = "",    // 原始文件名（还原回原目录时用）
        var from: String = "",        // 原父目录 URI
        var trashUri: String = "",    // 在回收站里的 URI
        var at: Long = 0L,            // 删除时间
        var size: Long = 0L
    ) {
        /** 还原时应该用的文件名 */
        val restoreName: String get() = origName.ifBlank { name }
    }

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
                        // 旧数据没有 origName，用 name 兜底，保证老记录也能还原
                        origName = o.optString("origName", o.optString("name")),
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
                o.put("origName", it.origName)
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
            val rawName = file.name ?: return false

            // ── 同名必须改名，不能直接覆盖 ────────────────────────
            // 之前的做法是"有同名就先删掉旧的"：
            //   删 sodium.jar → 回收站存 sodium.jar
            //   装回来再删一次 → **把回收站里那份删了**，再存一份新的
            // 结果：第一次删的那份再也还原不回来，回收站形同虚设。
            // 现在改成同名自动加序号（sodium.jar → sodium(1).jar），
            // 两份都留着，各自都能还原。
            var name = rawName
            var seq = 1
            while (dir.findFile(name) != null && seq < 100) {
                val dot = rawName.lastIndexOf('.')
                name = if (dot > 0) {
                    "${rawName.substring(0, dot)}($seq).${rawName.substring(dot + 1)}"
                } else {
                    "$rawName($seq)"
                }
                seq++
            }

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
                    // name 存**回收站里的名字**（可能带序号），
                    // 还原时要按这个名字在回收站里找到文件；
                    // 原文件名另存 origName，还原回目录时用。
                    name = name,
                    origName = rawName,
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

            // ── 还原时用**原始文件名**，不是回收站里那个带序号的名字 ──
            // 否则还原出来的是 sodium(1).jar，模组加载器认不出。
            val restoreName = item.restoreName
            val dst = parent.createFile(Fs.mimeOf(restoreName), restoreName)
                ?: parent.findFile(restoreName)
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

                    // ── 只认"看起来像模组配置"的文件 ──────────────
                    // 之前没有任何扩展名过滤：config 目录下的**所有**文件
                    // 只要匹配不到已装模组就一律算残留 —— 包括
                    // options.txt、服务器配置、用户手写的设置文件。
                    // 这个功能会**误删正在用的配置**，风险太高。
                    // 现在只处理常见的模组配置扩展名。
                    val lower = n.lowercase()
                    if (!RESIDUE_EXT.any { lower.endsWith(it) }) continue
                    if (base.length() < 3) continue   // 太短的名字不猜

                    // 配置名能对应到某个已安装模组 → 在用，跳过。
                    // 用**词边界**匹配，避免 "sodium" 命中 "sodiumextra" 这类无关项。
                    if (installed.any { matchesWord(base, it) }) continue
                    out.add(f)
                }
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 只把这些扩展名视为可能的模组配置残留，避免误删用户自己的设置 */
    private val RESIDUE_EXT = listOf(
        ".toml", ".cfg", ".json", ".properties", ".yml", ".yaml"
    )

    private fun baseName(n: String): String =
        n.substringBeforeLast(".")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    /**
     * 词边界匹配。
     *
     * 之前用双向 contains：`base.contains(it) || it.contains(base)`
     * —— 装了 "jei" 就会把 "jeiintegration"、"projecte" 之类
     * 全都当成"在用"而跳过（漏清理）；
     * 反过来短名字也会命中一堆无关文件（误清理）。
     * 改成：相等，或以分隔符（.-_）为边界的前缀关系。
     */
    private fun matchesWord(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val (long, short) = if (a.length >= b.length) a to b else b to a
        if (!long.startsWith(short)) return false
        val next = long[short.length]
        return next == '-' || next == '_' || next == '.'
    }
}
