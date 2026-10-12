package com.kongbai.modmigrator

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * 整合包的导入、登记与安装。支持 Modrinth / CurseForge / MultiMC / 通用 zip。
 *
 * 更新检查只对用户主动导入的包生效：导入时存下清单形成登记，移除登记即停止。
 * 应用自己扫出来的目录不是包，没有版本基线可比对。
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
        /** 用户选的那个包的 Uri，安装时还要从它里面取内容 */
        val source: String,
        /** 原始清单，更新检查要用。通用 zip 没有清单，为空 */
        val indexJson: String,
        val kind: String = KIND_MODRINTH,
        /** 包内 overrides 所在的目录前缀 */
        val overrides: String = Mrpack.OVERRIDES
    ) {
        fun label(): String =
            "$name ${versionId}${if (mc.isNotBlank()) " · MC $mc" else ""}"

        fun kindLabel(): String = when (kind) {
            KIND_CURSEFORGE -> "CurseForge"
            KIND_MULTIMC -> "MultiMC / Prism"
            KIND_PLAIN -> "通用 zip"
            else -> "Modrinth"
        }
    }

    private const val KEY = "mrpack_imported"

    const val KIND_MODRINTH = "MODRINTH"
    const val KIND_CURSEFORGE = "CURSEFORGE"
    const val KIND_MULTIMC = "MULTIMC"
    const val KIND_PLAIN = "PLAIN"

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
                        indexJson = o.optString("indexJson"),
                        kind = o.optString("kind", KIND_MODRINTH),
                        overrides = o.optString("overrides", Mrpack.OVERRIDES)
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
                put("kind", p.kind)
                put("overrides", p.overrides)
            })
        }
        Prefs.get(ctx).edit().putString(KEY, a.toString()).apply()
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
    }

    // ---------------- 导入 ----------------

    /** 读取一个整合包并登记。只读清单，不落盘任何文件 */
    fun importFrom(ctx: Context, uri: Uri, log: (String) -> Unit): Pack? {
        val (mans, count) = readManifests(ctx, uri)
        val pack = when {
            mans.containsKey(Mrpack.INDEX_NAME) ->
                fromModrinth(mans[Mrpack.INDEX_NAME].orEmpty(), uri, log)
            mans.containsKey("manifest.json") ->
                fromCurseForge(mans["manifest.json"].orEmpty(), uri, log)
            mans.containsKey("mmc-pack.json") ->
                fromMultiMC(mans["mmc-pack.json"].orEmpty(), uri, count, log)
            else -> fromPlain(uri, count, log)
        }
        if (pack == null) return null
        val items = list(ctx).filter { it.source != uri.toString() }.toMutableList()
        items.add(0, pack)
        save(ctx, items)
        return pack
    }

    private fun fromModrinth(raw: String, uri: Uri, log: (String) -> Unit): Pack? {
        val index = Mrpack.parse(raw) ?: run {
            log("清单解析失败，可能是格式不受支持")
            return null
        }
        log("${index.name} ${index.versionId} · ${index.files.size} 个待下载文件")
        return Pack(
            id = "${index.name}_${System.currentTimeMillis()}",
            name = index.name,
            versionId = index.versionId,
            mc = index.mcVersion,
            loader = index.loader,
            fileCount = index.files.size,
            importedAt = System.currentTimeMillis(),
            source = uri.toString(),
            indexJson = raw,
            kind = KIND_MODRINTH,
            overrides = Mrpack.OVERRIDES
        )
    }

    private fun fromCurseForge(
        raw: String, uri: Uri, log: (String) -> Unit
    ): Pack? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val files = o.optJSONArray("files") ?: run {
            log("manifest.json 里没有 files，不是 CurseForge 整合包")
            return null
        }
        val mcO = o.optJSONObject("minecraft")
        val mc = mcO?.optString("version").orEmpty()
        val loader = mcO?.optJSONArray("modLoaders")?.optJSONObject(0)
            ?.optString("id").orEmpty().substringBefore('-')
        val name = o.optString("name").ifBlank { "CurseForge 整合包" }
        val version = o.optString("version").ifBlank { "1.0.0" }
        log("$name $version · MC $mc · ${files.length()} 个文件")
        return Pack(
            id = "${name}_${System.currentTimeMillis()}",
            name = name,
            versionId = version,
            mc = mc,
            loader = loader,
            fileCount = files.length(),
            importedAt = System.currentTimeMillis(),
            source = uri.toString(),
            indexJson = raw,
            kind = KIND_CURSEFORGE,
            overrides = "overrides"
        )
    }

    private fun fromMultiMC(
        raw: String, uri: Uri, count: Int, log: (String) -> Unit
    ): Pack? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val comps = o.optJSONArray("components") ?: JSONArray()
        var mc = ""
        var loader = ""
        for (i in 0 until comps.length()) {
            val c = comps.optJSONObject(i) ?: continue
            val uid = c.optString("uid")
            when {
                uid == "net.minecraft" -> mc = c.optString("version")
                uid.contains("neoforge") || uid.contains("neo") -> loader = "neoforge"
                uid.contains("fabric") -> loader = "fabric"
                uid.contains("quilt") -> loader = "quilt"
                uid.contains("forge") -> loader = "forge"
            }
        }
        val name = o.optString("name").ifBlank { "MultiMC 整合包" }
        log("$name · MC $mc · 包内 $count 个文件（这个格式没有远程下载）")
        return Pack(
            id = "${name}_${System.currentTimeMillis()}",
            name = name,
            versionId = o.optString("version").ifBlank { "1.0.0" },
            mc = mc,
            loader = loader,
            fileCount = count,
            importedAt = System.currentTimeMillis(),
            source = uri.toString(),
            indexJson = raw,
            kind = KIND_MULTIMC,
            overrides = ".minecraft"
        )
    }

    private fun fromPlain(uri: Uri, count: Int, log: (String) -> Unit): Pack? {
        if (count <= 0) {
            log("读不出 zip 内容，或者这个文件不是 zip")
            return null
        }
        val seg = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        val name = seg.substringBeforeLast('.').ifBlank { "整合包" }
        log("$name · $count 个文件（无清单，按目录结构解压）")
        return Pack(
            id = "${name}_${System.currentTimeMillis()}",
            name = name,
            versionId = "1.0.0",
            mc = "",
            loader = "",
            fileCount = count,
            importedAt = System.currentTimeMillis(),
            source = uri.toString(),
            indexJson = "",
            kind = KIND_PLAIN,
            overrides = ""
        )
    }

    /** 一趟流式读完 zip，取出可能存在的清单并统计条目数 */
    private fun readManifests(ctx: Context, uri: Uri): Pair<Map<String, String>, Int> {
        val want = setOf(Mrpack.INDEX_NAME, "manifest.json", "mmc-pack.json")
        val out = LinkedHashMap<String, String>()
        var count = 0
        try {
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(java.io.BufferedInputStream(raw, 1 shl 18)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            count++
                            val n = e.name.substringAfterLast('/')
                            if (want.contains(n) && !out.containsKey(n)) {
                                val txt = zis.readBytes().toString(Charsets.UTF_8)
                                if (txt.length < 2_000_000) out[n] = txt
                            }
                        }
                        e = zis.nextEntry
                    }
                }
            }
        } catch (t: Throwable) {
            Err.ignore(t, "读整合包")
        }
        return out to count
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
        val uri = Uri.parse(pack.source)
        val remote = remoteFiles(ctx, pack, log)
        val prefixes = overridePrefixes(pack, side)
        var ok = 0
        for (t in targets) {
            val name = t.name ?: "目标"
            if (installOne(ctx, remote, prefixes, uri, t, log)) {
                ok++
                log("「$name」安装完成")
            } else {
                log("「$name」安装未完成，看上面的报错")
            }
        }
        return ok
    }

    private data class RemoteFile(val path: String, val url: String, val sha1: String)

    /** 清单里需要联网下载的文件。MultiMC / 通用 zip 没有远程文件 */
    private fun remoteFiles(
        ctx: Context, pack: Pack, log: (String) -> Unit
    ): List<RemoteFile> {
        if (pack.kind == KIND_MODRINTH) {
            val index = Mrpack.parse(pack.indexJson) ?: run {
                log("登记的清单读不出来，请重新导入")
                return emptyList()
            }
            return index.files.map {
                RemoteFile(it.path, it.downloads.firstOrNull().orEmpty(), it.sha1)
            }
        }
        if (pack.kind == KIND_CURSEFORGE) {
            val key = Prefs.get(ctx).getString(K.CF_KEY, "") ?: ""
            if (key.isBlank()) {
                log("没填 CurseForge Key，取不到下载地址；包内随带的文件仍会解压")
                return emptyList()
            }
            val o = runCatching { JSONObject(pack.indexJson) }.getOrNull()
                ?: return emptyList()
            val arr = o.optJSONArray("files") ?: return emptyList()
            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val fid = f.optString("fileID")
                if (fid.isNotBlank()) ids.add(fid)
            }
            val urls = CurseForgeApi.fileUrls(ids, key)
            val out = ArrayList<RemoteFile>()
            var miss = 0
            for (id in ids) {
                val hit = urls[id]
                if (hit == null) {
                    miss++
                    continue
                }
                out.add(RemoteFile("${McDirs.MODS}/${hit.second}", hit.first, ""))
            }
            if (miss > 0) log("$miss 个文件没查到下载地址，已跳过")
            return out
        }
        return emptyList()
    }

    private fun overridePrefixes(pack: Pack, side: Side): List<String> = when (pack.kind) {
        KIND_CURSEFORGE -> listOf("overrides")
        KIND_MULTIMC -> listOf(".minecraft")
        KIND_PLAIN -> listOf("")
        else -> listOf(
            Mrpack.OVERRIDES,
            if (side == Side.CLIENT) Mrpack.CLIENT_OVERRIDES else Mrpack.SERVER_OVERRIDES
        )
    }

    private fun installOne(
        ctx: Context, remote: List<RemoteFile>, prefixes: List<String>,
        uri: Uri, root: DocumentFile, log: (String) -> Unit
    ): Boolean {
        var failed = 0

        // ① 清单里的远程文件
        for (f in remote) {
            if (f.url.isBlank()) {
                log("跳过 ${f.path}：没有可用的下载地址")
                failed++
                continue
            }
            val slash = f.path.lastIndexOf('/')
            val dirRel = if (slash > 0) f.path.substring(0, slash) else ""
            val fileName = if (slash >= 0) f.path.substring(slash + 1) else f.path
            if (fileName.isBlank()) continue
            val dir = if (dirRel.isBlank()) root else mkdirs(ctx, root, dirRel) ?: root
            val out = runCatching {
                Downloader.download(ctx, f.url, dir, fileName, emptyMap())
            }.getOrNull()
            if (out == null) {
                log("下载失败：$fileName")
                failed++
                continue
            }
            // ② 校验哈希：清单写了哈希就必须比对，不比对等于装了一个和清单不符的实例
            if (f.sha1.isNotBlank()) {
                val got = runCatching { Fs.sha1(ctx, out) }.getOrNull().orEmpty()
                if (!got.equals(f.sha1, true)) {
                    log("校验不通过，已删除：$fileName")
                    runCatching { out.delete() }
                    failed++
                    continue
                }
            }
        }

        // ③ 包内文件
        for (p in prefixes) extractOverrides(ctx, uri, root, p, log)
        return failed == 0
    }

    /** 通用 zip 常常整包套一层目录，解压时要剥掉，否则会多出一层 */
    private fun commonRoot(ctx: Context, uri: Uri): String {
        var root = ""
        var multi = false
        try {
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(java.io.BufferedInputStream(raw, 1 shl 18)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val n = e.name
                            val first = n.substringBefore('/')
                            if (first != n) {
                                if (root.isEmpty()) root = first
                                else if (root != first) multi = true
                            } else multi = true
                        }
                        e = zis.nextEntry
                    }
                }
            }
        } catch (t: Throwable) {
            Err.ignore(t, "判断压缩包顶层目录")
        }
        return if (multi) "" else root
    }

    private fun extractOverrides(
        ctx: Context, uri: Uri, root: DocumentFile, prefix: String, log: (String) -> Unit
    ) {
        val want = if (prefix.isBlank()) "" else "$prefix/"
        val strip = if (prefix.isBlank()) commonRoot(ctx, uri) else ""
        try {
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(java.io.BufferedInputStream(raw, 1 shl 18)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val n = e.name
                        var rel: String? = null
                        if (!e.isDirectory) {
                            if (want.isEmpty()) {
                                val r = if (strip.isNotBlank() && n.startsWith("$strip/")) {
                                    n.substring(strip.length + 1)
                                } else n
                                if (r.isNotBlank()) rel = r
                            } else if (n.startsWith(want) && n.length > want.length) {
                                rel = n.substring(want.length)
                            }
                        }
                        if (rel != null) {
                            if (Mrpack.safePath(rel)) writeTo(ctx, root, rel, zis, log)
                            else log("跳过不安全的路径：$rel")
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
