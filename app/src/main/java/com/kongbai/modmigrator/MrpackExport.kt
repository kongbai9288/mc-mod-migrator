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
                if (opts.includeOverrides && modsDir != null) {
                    for (f in modsDir.listFiles()) {
                        if (f.isDirectory) continue
                        val n = f.name ?: continue
                        if (!n.endsWith(".jar", true) && !n.endsWith(".jar.disabled", true)) continue
                        // 禁用的模组也带上，但保持 .disabled 后缀，装完仍是禁用状态
                        val entryName = "overrides/mods/$n"
                        putEntry(ctx, zos, f, entryName)
                        files.add(
                            fileObj(
                                path = "mods/$n",
                                sha512 = sha512Of(ctx, f),
                                sha1 = sha1Of(ctx, f),
                                size = f.length()
                            )
                        )
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

    /** 极简 JSON 构造：不引第三方库，字段固定可控 */
    private fun buildIndex(opts: Options, files: List<JSONObject>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"formatVersion\": 1,\n")
        sb.append("  \"game\": \"minecraft\",\n")
        sb.append("  \"versionId\": \"${esc(opts.name)}-${System.currentTimeMillis()}\",\n")
        sb.append("  \"name\": \"${esc(opts.name)}\",\n")
        sb.append("  \"summary\": \"${esc(opts.summary.ifBlank { "由 ModMigrator 导出" })}\",\n")

        // dependencies：MC 版本 + 加载器
        val deps = ArrayList<String>()
        if (opts.mcVersion.isNotBlank()) {
            deps.add("\"minecraft\": \"${esc(opts.mcVersion)}\"")
        }
        when (opts.loader.lowercase()) {
            "fabric" -> deps.add("\"fabric-loader\": \"${esc(opts.loaderVersion.ifBlank { "*" })}\"")
            "forge" -> deps.add("\"forge\": \"${esc(opts.loaderVersion.ifBlank { "*" })}\"")
            "quilt" -> deps.add("\"quilt-loader\": \"${esc(opts.loaderVersion.ifBlank { "*" })}\"")
            "neoforge" -> deps.add("\"neoforge\": \"${esc(opts.loaderVersion.ifBlank { "*" })}\"")
        }
        sb.append("  \"dependencies\": { ${deps.joinToString(", ")} },\n")

        // files
        sb.append("  \"files\": [\n")
        files.forEachIndexed { i, f ->
            sb.append("    {\n")
            sb.append("      \"path\": \"${esc(f.path)}\",\n")
            sb.append("      \"hashes\": { \"sha512\": \"${f.sha512}\", \"sha1\": \"${f.sha1}\" },\n")
            sb.append("      \"fileSize\": ${f.size}\n")
            sb.append("    }${if (i < files.size - 1) "," else ""}\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private data class JSONObject(val path: String, val sha512: String, val sha1: String, val size: Long)

    private fun fileObj(path: String, sha512: String, sha1: String, size: Long) =
        JSONObject(path, sha512, sha1, size)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", " ")

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
