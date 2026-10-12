package com.kongbai.modmigrator

import android.content.Context

/**
 * 服务器模组 / 插件的更新查询。
 * 直连各平台接口，不经过中转后端。
 */
object ServerUpdate {

    const val MODRINTH = "modrinth"
    const val CURSEFORGE = "curseforge"
    const val BACKEND = "backend"

    const val DEFAULT = "$MODRINTH,$CURSEFORGE"

    /** CurseForge 的 Bukkit 插件是独立分类（classId 5），和模组（6）不是一个池子 */
    private val PLUGIN_LOADERS = setOf("bukkit", "spigot", "paper", "purpur", "folia")

    data class Hit(
        var source: String = "",
        var projectId: String = "",
        var projectName: String = "",
        var version: String = "",
        var fileName: String = "",
        var url: String = "",
        var environment: String = ""
    )

    fun sources(ctx: Context): Set<String> {
        val raw = Prefs.get(ctx).getString(K.SERVER_SRC, DEFAULT) ?: DEFAULT
        val s = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        return if (s.isEmpty()) setOf(MODRINTH, CURSEFORGE) else s
    }

    fun saveSources(ctx: Context, set: Set<String>) {
        val v = set.filter { it.isNotBlank() }.joinToString(",")
        Prefs.get(ctx).edit().putString(K.SERVER_SRC, v.ifBlank { DEFAULT }).apply()
    }

    fun label(src: String): String = when (src) {
        MODRINTH -> "Modrinth"
        CURSEFORGE -> "CurseForge"
        BACKEND -> "后端"
        else -> src
    }

    /**
     * 按名字在选中的平台里找可更新版本。
     * 各平台各出一个候选，由调用方决定用哪个。
     */
    fun find(
        ctx: Context,
        query: String,
        mc: String,
        loader: String,
        side: Environ.Side,
        kind: String = "mod",
        srcs: Set<String> = sources(ctx)
    ): List<Hit> {
        val out = ArrayList<Hit>()
        if (srcs.contains(MODRINTH)) {
            runCatching { modrinth(query, mc, loader, side) }
                .getOrDefault(emptyList())
                .let { out.addAll(it) }
        }
        if (srcs.contains(CURSEFORGE)) {
            runCatching { curseforge(ctx, query, mc, loader, kind) }
                .getOrDefault(emptyList())
                .let { out.addAll(it) }
        }
        if (srcs.contains(BACKEND)) {
            runCatching { backend(ctx, query, mc, loader) }
                .getOrDefault(emptyList())
                .let { out.addAll(it) }
        }
        return out
    }

    /** 从文件名里取出用于搜索的名字 */
    fun queryOf(fileName: String): String {
        var s = fileName
            .replace(Regex("\\.jar$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[-_](fabric|forge|neoforge|quilt|bukkit|spigot|paper)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[-_]mc1?[._-]?\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[._-]"), " ")
            .trim()
        while (s.contains("  ")) s = s.replace("  ", " ")
        return s
    }

    /** 手动搜索：把各平台的候选都列出来给人挑 */
    fun searchAll(
        ctx: Context, query: String, mc: String, loader: String, srcs: Set<String> = sources(ctx)
    ): List<MarketMod> {
        val out = ArrayList<MarketMod>()
        if (srcs.contains(MODRINTH)) {
            runCatching { out.addAll(ModrinthApi.search(query, mc, loader, 10, 0)) }
        }
        if (srcs.contains(CURSEFORGE)) {
            val key = Prefs.get(ctx).getString(K.CF_KEY, "") ?: ""
            runCatching { out.addAll(CurseForgeApi.search(query, mc, loader, key, 10, 0, 6)) }
            runCatching { out.addAll(CurseForgeApi.search(query, mc, loader, key, 10, 0, 5)) }
        }
        if (srcs.contains(BACKEND)) {
            runCatching { out.addAll(BackendApi.search(ctx, query, mc, loader)) }
        }
        return out
    }

    /** 把人挑中的项目解析成可下载的版本 */
    fun resolve(ctx: Context, mod: MarketMod, mc: String, loader: String, side: Environ.Side): Hit? {
        return when (mod.source) {
            MODRINTH -> {
                val vers = runCatching { ModrinthApi.versions(mod.id, mc, loader) }.getOrDefault(emptyList())
                val v0 = vers.firstOrNull { Environ.okFor(it.environment, side) }
                    ?: vers.firstOrNull() ?: return null
                Hit(
                    source = MODRINTH,
                    projectId = mod.id,
                    projectName = mod.name,
                    version = v0.version,
                    fileName = v0.fileName.ifBlank { Downloader.guessName(v0.url) },
                    url = v0.url,
                    environment = v0.environment
                )
            }
            CURSEFORGE -> {
                val key = Prefs.get(ctx).getString(K.CF_KEY, "") ?: ""
                val fs = runCatching { CurseForgeApi.files(mod.id, mc, loader, key) }.getOrDefault(emptyList())
                val f0 = fs.firstOrNull() ?: return null
                val url = runCatching { CurseForgeApi.fileUrl(mod.id, f0.id, f0.fileName, key) }.getOrDefault("")
                if (url.isBlank()) return null
                Hit(
                    source = CURSEFORGE,
                    projectId = mod.id,
                    projectName = mod.name,
                    version = f0.version,
                    fileName = f0.fileName,
                    url = url,
                    environment = ""
                )
            }
            else -> {
                val fs = runCatching { BackendApi.files(ctx, mod.id, mc, loader) }.getOrDefault(emptyList())
                val f0 = fs.firstOrNull() ?: return null
                val url = runCatching { BackendApi.absolute(ctx, f0.url) }.getOrDefault(f0.url)
                if (url.isBlank()) return null
                Hit(
                    source = BACKEND,
                    projectId = mod.id,
                    projectName = mod.name,
                    version = f0.display.ifBlank { f0.name },
                    fileName = f0.name,
                    url = url,
                    environment = mod.environment
                )
            }
        }
    }

    private fun modrinth(query: String, mc: String, loader: String, side: Environ.Side): List<Hit> {
        val res = runCatching { ModrinthApi.search(query, mc, loader, 8, 0) }
            .getOrDefault(emptyList())
        if (res.isEmpty()) return emptyList()
        val hit = CrossLoader.pickProject(res, query) ?: return emptyList()
        if (!Environ.okFor(hit.environment, side)) return emptyList()
        val vers = runCatching { ModrinthApi.versions(hit.id, mc, loader) }
            .getOrDefault(emptyList())
        val v0 = vers.firstOrNull { Environ.okFor(it.environment, side) }
            ?: vers.firstOrNull()
            ?: return emptyList()
        return listOf(
            Hit(
                source = MODRINTH,
                projectId = hit.id,
                projectName = hit.name,
                version = v0.version,
                fileName = v0.fileName.ifBlank { Downloader.guessName(v0.url) },
                url = v0.url,
                environment = v0.environment
            )
        )
    }

    private fun curseforge(ctx: Context, query: String, mc: String, loader: String, kind: String): List<Hit> {
        val key = Prefs.get(ctx).getString(K.CF_KEY, "") ?: ""
        val cls = if (kind == "plugin" || PLUGIN_LOADERS.contains(loader)) 5 else 6
        val res = runCatching { CurseForgeApi.search(query, mc, loader, key, 8, 0, cls) }
            .getOrDefault(emptyList())
        if (res.isEmpty()) return emptyList()
        val hit = CrossLoader.pickProject(res, query) ?: return emptyList()
        val files = runCatching { CurseForgeApi.files(hit.id, mc, loader, key) }
            .getOrDefault(emptyList())
        if (files.isEmpty()) return emptyList()
        val f0 = files.first()
        val url = runCatching { CurseForgeApi.fileUrl(hit.id, f0.id, f0.fileName, key) }
            .getOrDefault("")
        if (url.isBlank()) return emptyList()
        return listOf(
            Hit(
                source = CURSEFORGE,
                projectId = hit.id,
                projectName = hit.name,
                version = f0.version,
                fileName = f0.fileName,
                url = url,
                environment = ""
            )
        )
    }

    private fun backend(ctx: Context, query: String, mc: String, loader: String): List<Hit> {
        val res = runCatching { BackendApi.search(ctx, query, mc, loader) }
            .getOrDefault(emptyList())
        if (res.isEmpty()) return emptyList()
        val hit = CrossLoader.pickProject(res, query) ?: return emptyList()
        val files = runCatching { BackendApi.files(ctx, hit.id, mc, loader) }
            .getOrDefault(emptyList())
        val f0 = files.firstOrNull() ?: return emptyList()
        val url = runCatching { BackendApi.absolute(ctx, f0.url) }.getOrDefault(f0.url)
        if (url.isBlank()) return emptyList()
        return listOf(
            Hit(
                source = BACKEND,
                projectId = hit.id,
                projectName = hit.name,
                version = f0.display.ifBlank { f0.name },
                fileName = f0.name,
                url = url,
                environment = hit.environment
            )
        )
    }
}
