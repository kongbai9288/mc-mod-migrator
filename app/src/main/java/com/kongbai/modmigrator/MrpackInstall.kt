package com.kongbai.modmigrator

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * .mrpack 的导入、登记与安装。
 *
 * ## 为什么要登记
 *
 * 「整合包更新检查」只能用于**用户主动导入**的整合包 ——
 * 这条既是隐私上的要求，也是逻辑上的要求：
 *  - 应用自己扫出来的目录不是一个"包"，没有版本基线，拿什么比新旧？
 *  - 只有用户亲手导入的那个 .mrpack 才带着明确的 versionId 和文件清单，
 *    才知道"当前装的应该是哪一版"。
 *
 * 所以导入时把清单存下来形成一份登记，更新检查只认这份登记里的项；
 * 用户移除登记后，对应的检查也就停止了。
 */
object MrpackInstall {

    enum class Side { CLIENT, SERVER }

    data class Pack(
        val id: String,
        val name: String,
        val versionId: String,
        val mc: String,
        val loader: String,
        val fileCount: Int,
        val importedAt: Long,
        /** 用户选的那个 .mrpack 的 Uri，安装时还要从它里面取 overrides */
        val source: String,
        /** 原始清单，更新检查要用 */
        val indexJson: String
    ) {
        fun label(): String =
            "$name ${versionId}${if (mc.isNotBlank()) " · MC $mc" else ""}"
    }

    private const val KEY = "mrpack_imported"

    // ---------------- 登记 ----------------

    fun list(ctx: Context): List<Pack> {
        val raw = Prefs.get(ctx).getString(KEY, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            val out = ArrayList<Pack>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val src = o.optString("source")
                if (src.isBlank()) continue
                out.add(
                    Pack(
                        id = o.optString("id").ifBlank { src },
                        name = o.optString("name", "未命名整合包"),
                        versionId = o.optString("versionId", "1.0.0"),
                        mc = o.optString("mc"),
                        loader = o.optString("loader"),
                        fileCount = o.optInt("fileCount", 0),
                        importedAt = o.optLong("importedAt", 0L),
                        source = src,
                        indexJson = o.optString("indexJson")
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, items: List<Pack>) {
        val a = JSONArray()
        for (p in items) {
            a.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("versionId", p.versionId)
                put("mc", p.mc)
                put("loader", p.loader)
                put("fileCount", p.fileCount)
                put("importedAt", p.importedAt)
                put("source", p.source)
                put("indexJson", p.indexJson)
            })
        }
        Prefs.get(ctx).edit().putString(KEY, a.toString()).apply()
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
    }

    // ---------------- 导入 ----------------

    /**
     * 读一个 .mrpack 并登记。
     *
     * 只读清单、不落盘任何文件 —— 用户可能只是想看看包里有什么。
     * 真正装到实例里要另调 [install]。
     */
    fun importFrom(ctx: Context, uri: Uri, log: (String) -> Unit): Pack? {
        val raw = readEntry(ctx, uri, Mrpack.INDEX_NAME)
        if (raw.isNullOrBlank()) {
            log("这个文件里没有 ${Mrpack.INDEX_NAME}，不是有效的 .mrpack")
            return null
        }
        val index = Mrpack.parse(raw)
        if (index == null) {
            log("清单解析失败，可能是格式不受支持")
            return null
        }
        log("${index.name} ${index.versionId} · ${index.files.size} 个待下载文件")

        val pack = Pack(
            id = "${index.name}_${System.currentTimeMillis()}",
            name = index.name,
            versionId = index.versionId,
            mc = index.mcVersion,
            loader = index.loader,
            fileCount = index.files.size,
            importedAt = System.currentTimeMillis(),
            source = uri.toString(),
            indexJson = raw
        )
        val items = list(ctx).filter { it.source != uri.toString() }.toMutableList()
        items.add(0, pack)
        save(ctx, items)
        return pack
    }

    /** 从 zip 里读一个条目（顺序流式，找不到就返回 null） */
    fun readEntry(ctx: Context, uri: Uri, name: String): String? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(java.io.BufferedInputStream(raw, 1 shl 18)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name == name) {
                            return zis.readBytes().toString(Charsets.UTF_8)
                        }
                        e = zis.nextEntry
                    }
                }
            }
            null
        } catch (t: Throwable) {
            Err.ignore(t, "读取整合包条目")
            null
        }
    }

    // ---------------- 安装 ----------------

    /**
     * 把整合包装到**多个目标**上。
     *
     * 同一个包装到多个实例、多个启动器是很常见的需求：
     * 手机上有 FCL 和 Zalith 各一个实例，两边都要能进同一个包。
     * 所以这里收的是目标列表，一次调用统一装完。
     *
     * @return 装成功的目标数
     */
    fun install(
        ctx: Context, pack: Pack, targets: List<DocumentFile>,
        side: Side = Side.CLIENT, log: (String) -> Unit
    ): Int {
        val index = Mrpack.parse(pack.indexJson)
        if (index == null) {
            log("登记的清单读不出来，请重新导入")
            return 0
        }
        val uri = Uri.parse(pack.source)
        var ok = 0
        for (t in targets) {
            val name = t.name ?: "目标"
            if (installOne(ctx, index, uri, t, side, log)) {
                ok++
                log("「$name」安装完成")
            } else {
                log("「$name」安装未完成，看上面的报错")
            }
        }
        return ok
    }

    private fun installOne(
        ctx: Context, index: Mrpack.Index, uri: Uri,
        root: DocumentFile, side: Side, log: (String) -> Unit
    ): Boolean {
        var failed = 0

        // ① files[]：远程下载
        for (f in index.files) {
            val url = f.downloads.firstOrNull()
            if (url.isNullOrBlank()) {
                log("跳过 ${f.path}：清单里没有可用的下载地址")
                failed++
                continue
            }
            val slash = f.path.lastIndexOf('/')
            val dirRel = if (slash > 0) f.path.substring(0, slash) else ""
            val fileName = if (slash >= 0) f.path.substring(slash + 1) else f.path
            if (fileName.isBlank()) continue
            val dir = if (dirRel.isBlank()) root else mkdirs(ctx, root, dirRel) ?: root
            val out = runCatching {
                Downloader.download(ctx, url, dir, fileName, emptyMap())
            }.getOrNull()
            if (out == null) {
                log("下载失败：${fileName}")
                failed++
                continue
            }
            // ② 校验哈希：规范写明哈希不匹配应当拒绝安装。
            //    静默放过的话，装出来的是一个和清单不符的实例，
            //    出了兼容性问题根本查不到是哪个文件不对。
            if (f.sha1.isNotBlank()) {
                val got = runCatching { Fs.sha1(ctx, out) }.getOrNull().orEmpty()
                if (!got.equals(f.sha1, true)) {
                    log("校验不通过，已删除：${fileName}")
                    runCatching { out.delete() }
                    failed++
                    continue
                }
            }
        }

        // ③ overrides：先 overrides/，再按端覆盖一层
        extractOverrides(ctx, uri, root, Mrpack.OVERRIDES, log)
        when (side) {
            Side.CLIENT -> extractOverrides(ctx, uri, root, Mrpack.CLIENT_OVERRIDES, log)
            Side.SERVER -> extractOverrides(ctx, uri, root, Mrpack.SERVER_OVERRIDES, log)
        }
        return failed == 0
    }

    private fun extractOverrides(
        ctx: Context, uri: Uri, root: DocumentFile, prefix: String, log: (String) -> Unit
    ) {
        val want = "$prefix/"
        try {
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(java.io.BufferedInputStream(raw, 1 shl 18)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val n = e.name
                        if (!e.isDirectory && n.startsWith(want) && n.length > want.length) {
                            val rel = n.substring(want.length)
                            if (Mrpack.safePath(rel)) {
                                writeTo(ctx, root, rel, zis, log)
                            } else {
                                log("跳过不安全的路径：$rel")
                            }
                        }
                        e = zis.nextEntry
                    }
                }
            }
        } catch (t: Throwable) {
            Err.ignore(t, "解压 overrides")
        }
    }

    private fun writeTo(
        ctx: Context, root: DocumentFile, rel: String,
        zis: ZipInputStream, log: (String) -> Unit
    ) {
        try {
            val slash = rel.lastIndexOf('/')
            val dirRel = if (slash > 0) rel.substring(0, slash) else ""
            val name = if (slash >= 0) rel.substring(slash + 1) else rel
            if (name.isBlank()) return
            val dir = if (dirRel.isBlank()) root else mkdirs(ctx, root, dirRel) ?: root
            val exist = dir.findFile(name)
            val file = if (exist != null && exist.isFile) exist
            else dir.createFile(Fs.mimeOf(name), name)
            if (file == null) {
                log("写不进去：$rel")
                return
            }
            ctx.contentResolver.openOutputStream(file.uri, "w")?.use { os ->
                java.io.BufferedOutputStream(os, 1 shl 18).use {
                    zis.copyTo(it, 1 shl 18)
                }
            }
        } catch (t: Throwable) {
            Err.ignore(t, "写入 overrides 中的文件")
        }
    }

    /** 按相对路径逐级建目录 */
    private fun mkdirs(ctx: Context, root: DocumentFile, rel: String): DocumentFile? {
        var cur = root
        for (part in rel.split('/')) {
            if (part.isBlank()) continue
            val next = cur.findFile(part) ?: cur.createDirectory(part)
            if (next == null) return null
            cur = next
        }
        return cur
    }
}
