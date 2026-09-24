package com.kongbai.modmigrator

import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 模组元数据统一解析。
 *
 * 按成熟工具的做法：**探测 sentinel 文件**来判断这是什么模组，
 * 而不是看文件名。用 ZipInputStream 直接读条目，
 * 不解压整个 jar——手机上解压一个大 jar 既慢又占内存。
 *
 * 支持的元数据文件（按优先级）：
 *   fabric.mod.json  → Fabric / Quilt
 *   quilt.mod.json   → Quilt
 *   neoforge.mods.toml → NeoForge
 *   mods.toml        → Forge
 *   mcmod.info       → 老版本 Forge
 *
 * 输出统一结构，供依赖图、冲突检测、启用禁用、图标提取共用。
 */
object ModMeta {

    data class Info(
        val id: String = "",
        val name: String = "",
        val version: String = "",
        val loader: String = "unknown",   // fabric / quilt / forge / neoforge / unknown
        val description: String = "",
        val authors: List<String> = emptyList(),
        val depends: List<String> = emptyList(),
        val breaks: List<String> = emptyList(),
        val mcVersions: List<String> = emptyList(),
        val iconPath: String = ""          // jar 内的图标路径，用于提取本地图标
    )

    /** 元数据文件 → 加载器类型 */
    private val SENTINELS = listOf(
        "fabric.mod.json" to "fabric",
        "quilt.mod.json" to "quilt",
        "neoforge.mods.toml" to "neoforge",
        "META-INF/neoforge.mods.toml" to "neoforge",
        "META-INF/mods.toml" to "forge",
        "mods.toml" to "forge",
        "mcmod.info" to "forge"
    )

    /** 图标候选路径（各加载器约定不同） */
    private val ICON_KEYS = listOf(
        "icon", "logoFile", "logo", "mod_icon", "iconFile"
    )

    /**
     * 从 uri 读取模组元数据。
     * 读不到就返回 unknown，绝不抛异常——坏 jar 不该让界面崩。
     */
    fun read(ctx: android.content.Context, uri: android.net.Uri): Info {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                parse(ZipInputStream(input))
            } ?: Info()
        } catch (t: Throwable) {
            Info()
        }
    }

    /** 从本地 File 读取 */
    fun readFile(f: java.io.File): Info {
        return try {
            if (!f.exists() || !f.canRead()) return Info()
            parse(ZipInputStream(f.inputStream()))
        } catch (t: Throwable) {
            Info()
        }
    }

    private fun parse(zip: ZipInputStream): Info {
        var best: Pair<String, String>? = null   // sentinel 文件名 → loader
        var payload = ""
        var iconPath = ""
        var mcmodInfo = ""

        var e = zip.nextEntry
        var scanned = 0
        // 只扫前若干条目：元数据文件一定在靠前的位置，
        // 全扫一遍大 jar 会明显卡顿
        while (e != null && scanned < 400) {
            scanned++
            val n = e.name ?: ""
            val lower = n.lowercase(Locale.ROOT)

            val hit = SENTINELS.firstOrNull { it.first.equals(n, true) }
            if (hit != null) {
                val text = runCatching { String(zip.readBytes(), Charsets.UTF_8) }.getOrDefault("")
                if (text.isNotBlank()) {
                    // fabric/quilt/neoforge 优先于老 mcmod.info
                    if (best == null || best!!.second != "forge" || hit.second != "forge") {
                        best = hit
                        payload = text
                    }
                    if (n.equals("mcmod.info", true)) mcmodInfo = text
                }
            } else if (lower.endsWith(".png") && iconPath.isBlank()) {
                // 位于根目录的 png 才当图标，避免抓到资源包里的图
                if (!lower.contains('/')) iconPath = n
            }
            e = zip.nextEntry
        }
        zip.closeEntry()

        val loader = best?.second ?: "unknown"
        val text = payload.ifBlank { mcmodInfo }
        if (text.isBlank()) return Info(loader = if (loader == "unknown") "unknown" else loader)

        return when (loader) {
            "fabric", "quilt" -> parseFabricJson(text, loader, iconPath)
            else -> parseTomlOrInfo(text, loader, iconPath)
        }
    }

    /** fabric.mod.json / quilt.mod.json：JSON */
    private fun parseFabricJson(t: String, loader: String, iconPath: String): Info {
        val declaredIcon = pick(t, "\"icon\"")
            .ifBlank { pick(t, "\"logoFile\"") }
        return Info(
            id = pick(t, "\"id\""),
            name = pickName(t),
            version = pick(t, "\"version\""),
            loader = loader,
            description = pick(t, "\"description\""),
            authors = pickAuthors(t),
            depends = pickBlockKeys(t, "depends"),
            breaks = pickBlockKeys(t, "breaks"),
            mcVersions = emptyList(),
            iconPath = declaredIcon.ifBlank { iconPath }
        )
    }

    /** mods.toml / mcmod.info：TOML 或老 JSON */
    private fun parseTomlOrInfo(t: String, loader: String, iconPath: String): Info {
        // mcmod.info 是 JSON 数组
        if (t.trimStart().startsWith("[")) {
            return Info(
                id = pick(t, "\"modid\"").ifBlank { pick(t, "\"modId\"") },
                name = pick(t, "\"name\""),
                version = pick(t, "\"version\""),
                loader = loader,
                description = pick(t, "\"description\""),
                authors = pickAuthors(t),
                depends = pickBlockKeys(t, "dependencies"),
                iconPath = pick(t, "\"logoFile\"").ifBlank { iconPath }
            )
        }
        // TOML：抓 [[mods]] 段
        val modBlock = Regex("\\[\\[?mods?\\]?\\]?([\\s\\S]*?)(?=\\n\\[|\\Z)")
            .find(t)?.groupValues?.get(1) ?: t
        val deps = Regex("modId\\s*=\\s*\"([^\"]+)\"")
            .findAll(t).map { it.groupValues[1] }.distinct().toList()
        val mcRange = pickToml(t, "mcversion")
        return Info(
            id = pickToml(modBlock, "modId"),
            name = pickToml(modBlock, "displayName").ifBlank { pickToml(modBlock, "modId") },
            version = pickToml(modBlock, "version"),
            loader = loader,
            description = pickToml(modBlock, "description"),
            authors = pickToml(modBlock, "authors").split(",")
                .map { it.trim() }.filter { it.isNotBlank() },
            depends = deps,
            mcVersions = listOf(mcRange).filter { it.isNotBlank() },
            iconPath = pickToml(modBlock, "logoFile").ifBlank { iconPath }
        )
    }

    // ---------- 取值小工具（不引第三方 JSON 库，避免大依赖） ----------

    /** 取 "key": "value" */
    private fun pick(t: String, key: String): String {
        val r = Regex("$key\\s*:\\s*\"([^\"]*)\"").find(t)
        return r?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun pickName(t: String): String {
        return pick(t, "\"name\"").ifBlank { pick(t, "\"id\"") }
    }

    /** TOML 的 key = "value" */
    private fun pickToml(t: String, key: String): String {
        val r = Regex("(?m)^\\s*$key\\s*=\\s*\"([^\"]*)\"").find(t)
            ?: Regex("$key\\s*=\\s*\"([^\"]*)\"").find(t)
        return r?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun pickAuthors(t: String): List<String> {
        val block = Regex("\"authors\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(t)?.groupValues?.get(1)
        if (block != null) {
            return Regex("\"([^\"]+)\"").findAll(block)
                .map { it.groupValues[1] }.toList()
        }
        val one = pick(t, "\"author\"")
        return if (one.isBlank()) emptyList() else listOf(one)
    }

    /** 取某个 JSON 块（如 depends / breaks）里的所有 key */
    private fun pickBlockKeys(t: String, block: String): List<String> {
        val b = Regex("\"$block\"\\s*:\\s*\\{([\\s\\S]*?)\\}", RegexOption.DOT_MATCHES_ALL)
            .find(t)?.groupValues?.get(1) ?: return emptyList()
        return Regex("\"([^\"]+)\"\\s*:").findAll(b)
            .map { it.groupValues[1] }.distinct().toList()
    }
}
