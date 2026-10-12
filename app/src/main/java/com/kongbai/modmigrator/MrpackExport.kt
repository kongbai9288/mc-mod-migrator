package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 按 Modrinth 官方 .mrpack 规范导出整合包。
 *
 * ## 为什么不再"什么都塞进去"
 *
 * 之前的实现把每个模组 jar 都塞进 overrides/mods/，
 * files[] 反而空着。这样导出的包能装，但有三个问题：
 *  - 体积巨大：50 个模组就是几百 MB，分享、备份都很痛苦
 *  - 无法校验：没有哈希，启动器装完也不知道文件对不对
 *  - 无法更新：启动器看不出这个包里装的是哪个项目的哪个版本
 *
 * Modrinth 作为这个格式的发起者，设计意图本来就是
 * **"清单 + 远程下载"**：files[] 里写下载地址和哈希，
 * 包本体只有几十 KB，装的时候由启动器按需拉取并校验。
 *
 * 所以现在的规则是：
 *  - 能在 Modrinth 上按 SHA-1 反查到、且下载地址在白名单内 → 走 files[]（远程下载）
 *  - 查不到的（CurseForge 独有、自制构建、改过的包）→ 塞进 overrides/mods/，不能丢
 *
 * 想强制全量随包（离线可用）就把 embedAll 打开。
 */
object MrpackExport {

    data class Options(
        val name: String,
        val summary: String = "",
        val mcVersion: String = "",
        val loader: String = "",          // fabric / forge / quilt / neoforge
        val loaderVersion: String = "",
        val kind: Mrpack.Kind = Mrpack.Kind.FULL,
        /** true = 全部随包带走（离线可用，但包会很大） */
        val embedAll: Boolean = false,
        val onProgress: ((String) -> Unit)? = null
    )

    data class Result(
        val file: File?,
        /** 走 files[] 远程下载的文件数 */
        val indexed: Int,
        /** 随包带走的 overrides 文件数 */
        val embedded: Int
    )

    fun export(ctx: Context, opts: Options, src: DocumentFile, outZip: File): Result {
        return try {
            if (outZip.exists()) outZip.delete()
            outZip.parentFile?.mkdirs()

            var indexed = 0
            var embedded = 0

            val modsDir = Fs.find(src, McDirs.MODS)
            val jars = ArrayList<DocumentFile>()
            if (modsDir != null) {
                for (f in Fs.children(modsDir)) {
                    if (!f.isFile) continue
                    val n = f.name ?: continue
                    // 禁用的模组也带上，保留 .disabled 后缀，装完仍是禁用状态
                    if (n.endsWith(".jar", true) || n.endsWith(".jar.disabled", true)) jars.add(f)
                }
            }

            opts.onProgress?.invoke("正在计算 ${jars.size} 个模组的指纹…")
            // 一次遍历同时算 SHA-512 和 SHA-1，别把 mods 目录读两遍
            val hashOf = LinkedHashMap<String, Pair<String, String>>()
            for (j in jars) {
                hashOf[j.uri.toString()] = Mrpack.hashes(ctx, j)
            }

            // 反查：拿到下载地址才能写进 files[]
            val offline = Prefs.get(ctx).getBoolean(K.OFFLINE, false)
            val lookup = if (!opts.embedAll && !offline && jars.isNotEmpty()) {
                opts.onProgress?.invoke("正在向 Modrinth 查询可远程下载的文件…")
                runCatching {
                    ModrinthApi.lookupHashes(hashOf.values.map { it.second }.filter { it.isNotBlank() })
                }.getOrDefault(emptyMap())
            } else emptyMap()

            val files = ArrayList<Mrpack.FileEntry>()
            val toEmbed = ArrayList<Pair<DocumentFile, String>>()

            for (j in jars) {
                val n = j.name ?: continue
                val (sha512, sha1) = hashOf[j.uri.toString()] ?: Pair("", "")
                val hit = if (sha1.isBlank()) null else lookup[sha1.lowercase()]
                val ok = hit != null && hit.found &&
                    hit.downloadUrl.isNotBlank() &&
                    Mrpack.allowedUrl(hit.downloadUrl)
                if (ok) {
                    files.add(
                        Mrpack.FileEntry(
                            path = "mods/$n",
                            sha512 = sha512,
                            sha1 = sha1,
                            downloads = listOf(hit!!.downloadUrl),
                            fileSize = j.length()
                        )
                    )
                } else {
                    // 查不到就随包带走，绝不静默丢弃——
                    // 丢一个依赖模组，装完就是启动即崩，用户根本查不到原因
                    toEmbed.add(Pair(j, "overrides/mods/$n"))
                }
            }

            // ---- 随包的内容（配置、脚本、存档）----
            when (opts.kind) {
                Mrpack.Kind.FULL -> {
                    addDir(src, McDirs.CONFIG, toEmbed)
                    addDir(src, McDirs.SCRIPTS, toEmbed)
                }
                Mrpack.Kind.CONFIG -> addDir(src, McDirs.CONFIG, toEmbed)
                Mrpack.Kind.MAP -> addDir(src, McDirs.SAVES, toEmbed)
                Mrpack.Kind.MODS -> Unit
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outZip))).use { zos ->
                for ((f, entry) in toEmbed) {
                    if (putEntry(ctx, zos, f, entry)) embedded++
                }
                val index = Mrpack.Index(
                    name = opts.name,
                    versionId = opts.mcVersion.ifBlank { "1.0.0" },
                    summary = opts.summary.ifBlank { "由 ModMigrator 导出（${opts.kind.label}）" },
                    files = files,
                    dependencies = depsOf(opts)
                )
                indexed = files.size
                zos.putNextEntry(ZipEntry(Mrpack.INDEX_NAME))
                zos.write(Mrpack.toJson(index).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            if (outZip.exists() && outZip.length() > 0) Result(outZip, indexed, embedded)
            else Result(null, indexed, embedded)
        } catch (t: Throwable) {
            Err.ignore(t, "导出整合包")
            Result(null, 0, 0)
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

    private fun addDir(src: DocumentFile, name: String, out: MutableList<Pair<DocumentFile, String>>) {
        val dir = Fs.find(src, name) ?: return
        collect(dir, "overrides/$name", out)
    }

    private fun collect(
        dir: DocumentFile, base: String, out: MutableList<Pair<DocumentFile, String>>
    ) {
        var count = 0
        for (f in Fs.children(dir)) {
            if (count++ > 500) return // 防止配置/存档目录里的海量小文件把包撑爆
            val n = f.name ?: continue
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
