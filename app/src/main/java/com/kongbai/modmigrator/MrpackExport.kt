package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出 .mrpack（Modrinth 整合包格式）。
 *
 * 格式规范（Modrinth 官方）：一个 zip，里面有
 *   - modrinth.index.json  （必需，描述包的内容）
 *   - overrides/           （可选，直接覆盖到实例根目录的文件）
 *  index.json 结构：
 *  {
 *    "formatVersion": 1,
 *    "game": "minecraft",
 *    "versionId": "...",
 *    "name": "...",
 *    "summary": "...",
 *    "files": [
 *      { "path": "mods/xxx.jar",
 *        "hashes": { "sha512": "...", "sha1": "..." },
 *        "downloads": ["https://..."],
 *        "fileSize": 12345 }
 *    ],
 *    "dependencies": { "minecraft": "1.20.1", "fabric-loader": "0.15.x" }
 *  }
 *
 * 关键约定：
 *  - path 里的文件**不**放进 zip（它们靠 downloads 远程拉取）
 *  - 要随包一起带的文件放 overrides/，path 写成 overrides 之后的相对路径
 *  - overrides 里的文件不写 hashes/downloads
 *
 * 我们这里做**离线可用**的导出：把所有模组本体放进 overrides/，
 * 这样导出的包不依赖网络也能装。
 */
object MrpackExport {

    data class Options(
        val name: String,
        val summary: String = "",
        val mcVersion: String = "",
        val loader: String = "",       // fabric / forge / ...
        val loaderVersion: String = "",
        val includeConfig: Boolean = true,
        val includeOverrides: Boolean = true // true=把模组本体打包进去（离线可用）
    )

    /**
     * 导出。
     * @param modsDir mods 目录
     * @param configDir config 目录（可为 null）
     * @param outZip 输出的 zip 文件（本地 cache 路径）
     */
    fun export(
        ctx: Context,
        opts: Options,
        modsDir: DocumentFile?,
        configDir: DocumentFile?,
        outZip: File
    ): File? {
        return try {
            if (outZip.exists()) outZip.delete()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outZip))).use { zos ->
                val files = ArrayList<JSONObject>()

                // ---------- overrides：模组本体 ----------
                // ⚠️ 关键：**打进 overrides/ 的文件不能再写进 files[]**。
                // 按 Modrinth 官方规范：
                //   · files[] 是「需要**下载**的那些文件」，每项必带 downloads 下载地址
                //   · overrides/ 里的文件由启动器**直接复制**，不参与下载
                // 之前这里两边都写：jar 进了 overrides/mods/，
                // 同时又往 files[] 里加了一条**没有 downloads** 的记录。
                // 启动器读到这条会试图去下载它，却没有地址可用 ——
                // 表现就是导出的 .mrpack 在 Prism / Modrinth App 里导入失败或漏装，
                // 而本地看 zip 内容明明是齐的，非常难查。
                // 离线导出（模组本体已随包）时 files[] 应为空。
                if (opts.includeOverrides && modsDir != null) {
                    for (f in modsDir.listFiles()) {
                        if (f.isDirectory) continue
                        val n = f.name ?: continue
                        if (!n.endsWith(".jar", true) && !n.endsWith(".jar.disabled", true)) continue
                        // 禁用的模组也带上，但保持 .disabled 后缀，装完仍是禁用状态
                        val entryName = "overrides/mods/$n"
                        putEntry(ctx, zos, f, entryName)
                    }
                }

                // ---------- overrides：配置 ----------
                if (opts.includeConfig && configDir != null) {
                    addDirRecursive(ctx, zos, configDir, "overrides/config")
                }

                // ---------- index.json ----------
                val json = buildIndex(opts, files)
                zos.putNextEntry(ZipEntry("modrinth.index.json"))
                zos.write(json.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            if (outZip.exists() && outZip.length() > 0) outZip else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 递归加入目录里的所有文件 */
    private fun addDirRecursive(
        ctx: Context, zos: ZipOutputStream, dir: DocumentFile, base: String
    ) {
        var count = 0
        for (f in dir.listFiles()) {
            if (count++ > 300) return // 防止配置目录里有海量小文件把包撑爆
            if (f.isDirectory) {
                addDirRecursive(ctx, zos, f, "$base/${f.name}")
            } else {
                putEntry(ctx, zos, f, "$base/${f.name}")
            }
        }
    }

    private fun putEntry(ctx: Context, zos: ZipOutputStream, f: DocumentFile, name: String) {
        try {
            zos.putNextEntry(ZipEntry(name))
            ctx.contentResolver.openInputStream(f.uri)?.use { it.copyTo(zos, 1 shl 16) }
            zos.closeEntry()
        } catch (t: Throwable) {
            // 单个文件失败不影响整个包
                 Err.ignore(t, "单个文件失败不影响整个包")
             }
    }

    /**
     * 生成 modrinth.index.json。
     *
     * 用 org.json 而不是手写拼字符串：包名来自用户输入，
     * 手写的 esc() 只转义了反斜杠、双引号和换行，漏掉回车、制表符等控制字符 ——
     * 生成的 JSON 一旦非法，启动器只会报清单解析失败，
     * 用户完全看不出是名字里有个奇怪字符导致的。
     */
    private fun buildIndex(opts: Options, files: List<JSONObject>): String {
        val deps = org.json.JSONObject()
        if (opts.mcVersion.isNotBlank()) deps.put("minecraft", opts.mcVersion)
        when (opts.loader.lowercase()) {
            "fabric" -> deps.put("fabric-loader", opts.loaderVersion.ifBlank { "*" })
            "forge" -> deps.put("forge", opts.loaderVersion.ifBlank { "*" })
            "quilt" -> deps.put("quilt-loader", opts.loaderVersion.ifBlank { "*" })
            "neoforge" -> deps.put("neoforge", opts.loaderVersion.ifBlank { "*" })
        }

        val arr = org.json.JSONArray()
        for (f in files) {
            arr.put(
                org.json.JSONObject()
                    .put("path", f.path)
                    .put(
                        "hashes",
                        org.json.JSONObject()
                            .put("sha512", f.sha512)
                            .put("sha1", f.sha1)
                    )
                    .put("fileSize", f.size)
            )
        }

        return org.json.JSONObject()
            .put("formatVersion", 1)
            .put("game", "minecraft")
            .put("versionId", "${opts.name}-${System.currentTimeMillis()}")
            .put("name", opts.name)
            .put("summary", opts.summary.ifBlank { "由 ModMigrator 导出" })
            .put("files", arr)
            .put("dependencies", deps)
            .toString(2)
    }

    private data class JSONObject(val path: String, val sha512: String, val sha1: String, val size: Long)

    private fun fileObj(path: String, sha512: String, sha1: String, size: Long) =
        JSONObject(path, sha512, sha1, size)

    private fun sha512Of(ctx: Context, f: DocumentFile): String = digest(ctx, f, "SHA-512")
    private fun sha1Of(ctx: Context, f: DocumentFile): String = digest(ctx, f, "SHA-1")

    private fun digest(ctx: Context, f: DocumentFile, alg: String): String {
        return try {
            val md = MessageDigest.getInstance(alg)
            ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                val buf = ByteArray(1 shl 16)
                var n: Int
                while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            ""
        }
    }
}
