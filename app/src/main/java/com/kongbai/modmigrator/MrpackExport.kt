package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出整合包。
 *
 * ## 为什么不再"什么都塞进去"
 *
 * 最早的实现把每个模组 jar 都塞进 overrides/mods/，files[] 反而空着。
 * 这样导出的包能装，但有三个问题：
 *  - 体积巨大：50 个模组就是几百 MB，分享、备份都很痛苦
 *  - 无法校验：没有哈希，启动器装完也不知道文件对不对
 *  - 无法更新：启动器看不出这个包里装的是哪个项目的哪个版本
 *
 * Modrinth 作为这个格式的发起者，设计意图本来就是
 * **"清单 + 远程下载"**：files[] 里写下载地址和哈希，
 * 包本体只有几十 KB，装的时候由启动器按需拉取并校验。
 *
 * ## 现在的行为
 *
 *  - 打包哪些类由 [Options.parts] 决定，迁移页和这里共用 [McParts]
 *  - Modrinth 格式：能按 SHA-1 反查到、且下载地址在白名单内 → 走 files[]；
 *    查不到的随包带走，**绝不静默丢弃**（丢一个依赖模组就是装完即崩）
 *  - CurseForge 格式：靠 murmur2 指纹反查项目号与文件号，没填 Key 就全部随包
 *  - MultiMC / 通用 zip：这两个格式没有远程下载，一律随包
 */
object MrpackExport {

    data class Options(
        val name: String,
        val summary: String = "",
        val mcVersion: String = "",
        val loader: String = "",
        val loaderVersion: String = "",
        val format: ModpackFormat = ModpackFormat.MODRINTH,
        /** 要打包哪些类。空 = 全部 */
        val parts: Collection<McParts.Part> = emptyList(),
        /** true = 全部随包带走（离线可用，但包会很大） */
        val embedAll: Boolean = false,
        val onProgress: ((String) -> Unit)? = null
    )

    data class Result(
        val file: File?,
        /** 走清单远程下载的文件数 */
        val indexed: Int,
        /** 随包带走的 overrides 文件数 */
        val embedded: Int,
        /** 需要告诉用户的事（比如为什么没走远程下载） */
        val note: String = ""
    )

    fun export(ctx: Context, opts: Options, src: DocumentFile, outZip: File): Result {
        return try {
            if (outZip.exists()) outZip.delete()
            outZip.parentFile?.mkdirs()

            val want: Set<McParts.Part> = if (opts.parts.isEmpty()) {
                McParts.ALL.toSet()
            } else LinkedHashSet(opts.parts)

            val prefix = opts.format.overridesPrefix
            fun inZip(rel: String): String = if (prefix.isEmpty()) rel else "$prefix/$rel"

            var indexed = 0
            var embedded = 0
            val note = StringBuilder()

            // ---- 模组 ----
            val jars = ArrayList<DocumentFile>()
            if (want.contains(McParts.MODS)) {
                Fs.find(src, McDirs.MODS)?.let { modsDir ->
                    for (f in Fs.children(modsDir)) {
                        if (!f.isFile) continue
                        val n = f.name ?: continue
                        // 禁用的也带上，保留 .disabled 后缀，装完仍是禁用状态
                        if (n.endsWith(".jar", true) || n.endsWith(".jar.disabled", true)) jars.add(f)
                    }
                }
            }

            opts.onProgress?.invoke("正在计算 ${jars.size} 个模组的指纹…")
            // 一次遍历同时算 SHA-512 和 SHA-1，别把 mods 目录读两遍
            val hashOf = LinkedHashMap<String, Pair<String, String>>()
            for (j in jars) hashOf[j.uri.toString()] = Mrpack.hashes(ctx, j)

            val offline = Prefs.get(ctx).getBoolean(K.OFFLINE, false)

            // ---- 反查远程下载地址 ----
            val mrpackFiles = ArrayList<Mrpack.FileEntry>()
            val cfFiles = ArrayList<Pair<Long, Long>>()
            val remote = BooleanArray(jars.size) // 这个 jar 已经写进清单了

            when (opts.format) {
                ModpackFormat.MODRINTH -> {
                    if (!opts.embedAll && !offline && jars.isNotEmpty()) {
                        opts.onProgress?.invoke("正在向 Modrinth 查询可远程下载的文件…")
                        val lookup = runCatching {
                            ModrinthApi.lookupHashes(hashOf.values.map { it.second }.filter { it.isNotBlank() })
                        }.getOrDefault(emptyMap())
                        if (lookup.isEmpty()) note.append("Modrinth 反查没有结果，模组已全部随包带走。")
                        for ((i, j) in jars.withIndex()) {
                            val n = j.name ?: continue
                            val (sha512, sha1) = hashOf[j.uri.toString()] ?: Pair("", "")
                            val hit = if (sha1.isBlank()) null else lookup[sha1.lowercase()]
                            if (hit != null && hit.found && hit.downloadUrl.isNotBlank() &&
                                Mrpack.allowedUrl(hit.downloadUrl)
                            ) {
                                mrpackFiles.add(
                                    Mrpack.FileEntry(
                                        path = "mods/$n",
                                        sha512 = sha512,
                                        sha1 = sha1,
                                        downloads = listOf(hit.downloadUrl),
                                        fileSize = j.length()
                                    )
                                )
                                remote[i] = true
                            }
                        }
                    } else if (offline && jars.isNotEmpty()) {
                        note.append("当前是离线模式，模组全部随包带走。")
                    }
                }
                ModpackFormat.CURSEFORGE -> {
                    val key = Prefs.get(ctx).getString(K.CF_KEY, "") ?: ""
                    if (key.isBlank()) {
                        if (jars.isNotEmpty()) {
                            note.append("没有填 CurseForge Key，无法反查项目号与文件号，模组已随包带走。")
                        }
                    } else if (!opts.embedAll && !offline && jars.isNotEmpty()) {
                        opts.onProgress?.invoke("正在向 CurseForge 反查文件指纹…")
                        val fps = jars.map { CfFingerprint.of(ctx, it) }
                        val hit = CfFingerprint.match(fps, key)
                        for ((i, fp) in fps.withIndex()) {
                            val pair = hit[fp] ?: continue
                            cfFiles.add(pair)
                            remote[i] = true
                        }
                        if (cfFiles.isEmpty()) {
                            note.append("CurseForge 没有匹配到任何文件，模组已随包带走。")
                        }
                    }
                }
                else -> Unit // MultiMC / 通用 zip 没有远程下载这一说
            }

            // ---- 随包的内容 ----
            val toEmbed = ArrayList<Pair<DocumentFile, String>>()
            for ((i, j) in jars.withIndex()) {
                if (remote[i]) continue
                val n = j.name ?: continue
                toEmbed.add(Pair(j, inZip("mods/$n")))
            }
            for (p in McParts.ALL) {
                if (p.id == McDirs.MODS) continue // 模组上面单独处理过
                if (!want.contains(p)) continue
                if (p.personal && p.id == McDirs.SAVES) {
                    opts.onProgress?.invoke("正在打包存档…")
                }
                if (p.isDir) addDir(src, p.name, inZip(p.name), toEmbed)
                else addFile(src, p.name, inZip(p.name), toEmbed)
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outZip))).use { zos ->
                for ((f, entry) in toEmbed) {
                    if (putEntry(ctx, zos, f, entry)) embedded++
                }
                val manifest = when (opts.format) {
                    ModpackFormat.MODRINTH -> Mrpack.toJson(
                        Mrpack.Index(
                            name = opts.name,
                            versionId = opts.mcVersion.ifBlank { "1.0.0" },
                            summary = opts.summary.ifBlank { "由 ModMigrator 导出" },
                            files = mrpackFiles,
                            dependencies = depsOf(opts)
                        )
                    )
                    ModpackFormat.CURSEFORGE -> ModpackManifest.curseforge(
                        name = opts.name,
                        version = opts.mcVersion.ifBlank { "1.0.0" },
                        mcVersion = opts.mcVersion,
                        loader = opts.loader,
                        loaderVersion = opts.loaderVersion,
                        files = cfFiles
                    )
                    ModpackFormat.MULTIMC -> ModpackManifest.multimc(
                        mcVersion = opts.mcVersion,
                        loader = opts.loader,
                        loaderVersion = opts.loaderVersion
                    )
                    ModpackFormat.PLAIN -> null
                }
                val name = opts.format.manifestName
                if (manifest != null && name != null) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(manifest.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                indexed = mrpackFiles.size + cfFiles.size
            }

            if (outZip.exists() && outZip.length() > 0) Result(outZip, indexed, embedded, note.toString())
            else Result(null, indexed, embedded, note.toString())
        } catch (t: Throwable) {
            Err.ignore(t, "导出整合包")
            Result(null, 0, 0, "导出失败：${Err.humanMessage(t)}")
        }
    }

    private fun depsOf(opts: Options): Map<String, String> {
        val d = LinkedHashMap<String, String>()
        if (opts.mcVersion.isNotBlank()) d["minecraft"] = opts.mcVersion
        val v = opts.loaderVersion.ifBlank { "*" }
        when (opts.loader.lowercase()) {
            "fabric" -> d["fabric-loader"] = v
            "forge" -> d["forge"] = v
            "quilt" -> d["quilt-loader"] = v
            "neoforge" -> d["neoforge"] = v
        }
        return d
    }

    private fun addDir(
        src: DocumentFile, name: String, base: String,
        out: MutableList<Pair<DocumentFile, String>>
    ) {
        val dir = Fs.find(src, name) ?: return
        collect(dir, base, out)
    }

    private fun addFile(
        src: DocumentFile, name: String, entry: String,
        out: MutableList<Pair<DocumentFile, String>>
    ) {
        val f = Fs.find(src, name) ?: return
        if (f.isFile) out.add(Pair(f, entry))
    }

    private fun collect(
        dir: DocumentFile, base: String, out: MutableList<Pair<DocumentFile, String>>
    ) {
        var count = 0
        for (f in Fs.children(dir)) {
            if (count++ > 500) return // 防止配置/存档目录里的海量小文件把包撑爆
            val n = f.name ?: continue
            if (n in McParts.NEVER) continue
            if (f.isDirectory) collect(f, "$base/$n", out)
            else out.add(Pair(f, "$base/$n"))
        }
    }

    private fun putEntry(
        ctx: Context, zos: ZipOutputStream, f: DocumentFile, name: String
    ): Boolean {
        return try {
            zos.putNextEntry(ZipEntry(name))
            ctx.contentResolver.openInputStream(f.uri)?.use { raw ->
                // SAF 的流每次 read 都是一次跨进程调用，必须包缓冲
                java.io.BufferedInputStream(raw, 1 shl 18).use {
                    it.copyTo(zos, 1 shl 18)
                }
            }
            zos.closeEntry()
            true
        } catch (t: Throwable) {
            Err.ignore(t, "写入整合包中的单个文件")
            false
        }
    }
}
