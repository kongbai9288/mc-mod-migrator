package com.kongbai.modmigrator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 模组图标：优先从本地 jar 里提取，取不到才算网络。
 *
 * 为什么优先本地：
 *   1. 迁移列表里的模组本来就在手机里，读 jar 比联网快得多，也不耗流量
 *   2. 离线可用
 *   3. 有些模组在 Modrinth/CurseForge 上没有图标，但 jar 里是有的
 *
 * 提取顺序（覆盖 Fabric / Forge / Quilt / 资源包）：
 *   1. fabric.mod.json 或 quilt.mod.json 的 "icon" 字段
 *   2. META-INF/mods.toml（Forge 1.13+）的 logoFile
 *   3. mcmod.info（旧 Forge）
 *   4. 压缩包顶层常见的 logo.png / icon.png / pack.png / assets 下的图标
 *   5. pack.mcmeta（说明是资源包/数据包）
 *
 * 结果按文件大小+名称做 key 缓存在内存，并按模组名落盘缓存，
 * 避免每次刷新列表都重新解压一遍。
 */
object ModIcons {

    /** 内存缓存：key -> Bitmap */
    private val mem = ConcurrentHashMap<String, Bitmap>()

    /** 全局开关：设置里可以关掉（低端机解压 jar 有开销） */
    fun enabled(ctx: Context): Boolean =
        Prefs.get(ctx).getBoolean(K.LOCAL_ICON, true)

    /** 缓存 key：文件名 + 大小，内容变了自然失效 */
    private fun keyOf(file: DocumentFile): String =
        "${file.name ?: ""}_${file.length()}"

    /**
     * 取图标。主线程安全，但解压本身有开销，建议后台调用。
     * @return Bitmap 或 null
     */
    fun of(ctx: Context, file: DocumentFile): Bitmap? {
        if (!enabled(ctx)) return null
        val key = keyOf(file)
        mem[key]?.let { return it }

        // 先看磁盘缓存
        val disk = WorkDir.icons(ctx)?.findFile(key + ".png")
        if (disk != null) {
            val bmp = decode(ctx, disk)
            if (bmp != null) {
                mem[key] = bmp
                return bmp
            }
        }

        val bmp = try {
            extract(ctx, file)
        } catch (t: Throwable) {
            null
        }
        if (bmp != null) {
            mem[key] = bmp
            saveToDisk(ctx, key, bmp)
        }
        return bmp
    }

    /** 从 jar 里解压出图标 */
    private fun extract(ctx: Context, file: DocumentFile): Bitmap? {
        ctx.contentResolver.openInputStream(file.uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry?
                var declared: String? = null   // 清单文件里声明的图标路径
                var fallback: ByteArray? = null
                val metaBuf = HashMap<String, String>()
                // 只扫一遍，最多看 200 个条目，避免大整合包卡住
                var count = 0
                while (zip.nextEntry.also { entry = it } != null && count < 200) {
                    val e = entry ?: break
                    count++
                    val n = e.name
                    if (e.isDirectory) continue

                    when {
                        // 1) 清单文件：先记下来，条目遍历完再决定
                        n.equals("fabric.mod.json", true) ||
                            n.equals("quilt.mod.json", true) ||
                            n.equals("mcmod.info", true) ||
                            n.equals("META-INF/mods.toml", true) -> {
                            val txt = String(zip.readBytes(), Charsets.UTF_8)
                            metaBuf[n] = txt
                        }

                        // 2) 图片：可能是清单声明的那个，也可能是通用名
                        n.endsWith(".png", true) -> {
                            val short = n.substringAfterLast('/')
                            val low = short.lowercase(Locale.ROOT)
                            val isLogo = low == "logo.png" || low == "icon.png" ||
                                low == "pack.png" || low == "mod.png" ||
                                low == "logo128.png" || low == "icon128.png"
                            if (isLogo && fallback == null) {
                                fallback = zip.readBytes()
                            }
                            // 清单已声明过路径就精确命中
                            if (declared != null && n.equals(declared, true)) {
                                return BitmapFactory.decodeByteArray(
                                    zip.readBytes(), 0, zip.readBytes().size
                                )
                            }
                        }
                    }
                }

                // 清单里声明的图标优先
                declared = parseDeclaredIcon(metaBuf)
                if (!declared.isNullOrBlank()) {
                    val bmp = readNamed(ctx, file, declared)
                    if (bmp != null) return bmp
                }
                if (fallback != null) {
                    return BitmapFactory.decodeByteArray(fallback, 0, fallback!!.size)
                }
            }
        }
        return null
    }

    /** 从各种清单里解析出图标路径 */
    private fun parseDeclaredIcon(metas: Map<String, String>): String? {
        // fabric.mod.json / quilt.mod.json：{"icon": "assets/xxx/icon.png"}
        for (k in listOf("fabric.mod.json", "quilt.mod.json")) {
            val txt = metas[k] ?: continue
            val m = Regex("\"icon\"\\s*:\\s*\"([^\"]+)\"").find(txt)
            if (m != null) return m.groupValues[1]
        }
        // META-INF/mods.toml：logoFile = "logo.png"
        val toml = metas["META-INF/mods.toml"]
        if (toml != null) {
            val m = Regex("logoFile\\s*=\\s*\"([^\"]+)\"").find(toml)
            if (m != null) return m.groupValues[1]
        }
        // mcmod.info：{"logoFile": "..."}
        val info = metas["mcmod.info"]
        if (info != null) {
            val m = Regex("\"logoFile\"\\s*:\\s*\"([^\"]+)\"").find(info)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /** 按清单里的路径单独再打开一次 zip 读取（第二遍，只在声明存在时才做） */
    private fun readNamed(ctx: Context, file: DocumentFile, path: String): Bitmap? {
        return try {
            ctx.contentResolver.openInputStream(file.uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var e: ZipEntry?
                    while (zip.nextEntry.also { e = it } != null) {
                        val en = e ?: break
                        // 兼容声明里写 "assets/foo/icon.png" 或 "/icon.png"
                        val cand = listOf(path, path.trimStart('/'), "assets/$path")
                        if (cand.any { it.equals(en.name, true) }) {
                            val bytes = zip.readBytes()
                            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun decode(ctx: Context, f: DocumentFile): Bitmap? =
        try {
            ctx.contentResolver.openInputStream(f.uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (t: Throwable) {
            null
        }

    /** 落盘缓存，下次直接读，不用重新解压 */
    private fun saveToDisk(ctx: Context, key: String, bmp: Bitmap) {
        try {
            val dir = WorkDir.icons(ctx) ?: return
            val f = dir.findFile("$key.png")
                ?: dir.createFile("image/png", "$key.png")
                ?: return
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use {
                it.write(out.toByteArray())
            }
        } catch (t: Throwable) {
        }
    }

    /**
     * 按 URI 字符串取图标（列表里用的是这个入口）。
     * 内部有内存缓存，重复调用不会重复解压。
     */
    fun ofUri(ctx: Context, uriStr: String): Bitmap? {
        if (uriStr.isBlank() || !enabled(ctx)) return null
        return try {
            val uri = android.net.Uri.parse(uriStr)
            val f = androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)
            if (f != null && f.isFile) of(ctx, f) else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 清理图标缓存（工具箱里用） */
    fun clear(ctx: Context): Int {
        mem.clear()
        var n = 0
        try {
            val dir = WorkDir.icons(ctx) ?: return 0
            for (f in Fs.children(dir)) {
                if (f.isFile && f.delete()) n++
            }
        } catch (t: Throwable) {
        }
        return n
    }
}
