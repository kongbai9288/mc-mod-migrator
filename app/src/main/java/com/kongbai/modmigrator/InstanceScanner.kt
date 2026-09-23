package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

data class InstanceInfo(
    var name: String = "",
    var uri: String = "",
    var mcVersion: String = "",
    var loader: String = "auto",
    var modCount: Int = 0,
    var hasConfig: Boolean = false,
    var kind: String = ""
)

object InstanceScanner {

    val ROOT_HINTS = listOf(
        "games", "Games", "minecraft", "Minecraft",
        "HMCL", "PCL", "mcinaBox", "MCinaBox"
    )

    /** 扫描时直接跳过的目录名：这些目录体积大且不可能含实例 */
    private val SKIP = setOf(
        "dcim", "DCIM", "pictures", "Pictures",
        "movies", "Movies", "download", "Download", "music", "Music",
        "documents", "Documents", "tencent", "Tencent", "qq", "QQ",
        "weixin", "WeChat", "alipay", "cache", ".cache", "thumbnails",
        "screenshots", "recordings", "podcasts", "alarms", "ringtones",
        "notifications", "audiobooks", "system", "lost.dir"
    )

    private val LAUNCHER_DIRS = listOf(
        "pojav", "PojavLauncher", "zalithlauncher", "ZalithLauncher",
        "instances", "versions", "hmcl", "HMCL"
    )

    fun looksLikeInstance(dir: DocumentFile): Boolean {
        if (!dir.isDirectory) return false
        val names = Fs.children(dir).map { (it.name ?: "").lowercase(Locale.ROOT) }.toSet()
        val hasMods = names.contains("mods")
        val hasConfig = names.contains("config")
        val hasVersion = names.contains("version.json")
        val hasPack = names.contains("mmc-pack.json") || names.contains("manifest.json")
        val hasJar = names.contains("versions") || names.contains("libraries")
        return hasMods || hasConfig || hasVersion || hasPack || hasJar
    }

    /**
     * 扫描实例。quick = true 时只进入「名字像启动器」的目录，
     * 跳过其余子树，速度能快一个量级；扫不到再用完整模式。
     */
    fun scan(
        ctx: Context,
        rootUri: String,
        log: (String) -> Unit,
        quick: Boolean = true
    ): List<InstanceInfo> {
        val root = Fs.tree(ctx, rootUri) ?: return emptyList()
        val out = mutableListOf<InstanceInfo>()
        if (quick) collectQuick(ctx, root, 0, out, log) else collect(ctx, root, 0, out, log)
        if (out.isEmpty() && quick) {
            log("快速扫描没找到，再完整扫一遍…")
            out.clear()
            collect(ctx, root, 0, out, log)
        }
        return out.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun collectQuick(
        ctx: Context,
        dir: DocumentFile,
        depth: Int,
        out: MutableList<InstanceInfo>,
        log: (String) -> Unit
    ) {
        if (depth > 4) return
        if (depth > 0 && looksLikeInstance(dir)) {
            val info = readInfo(ctx, dir)
            if (info != null) {
                out.add(info)
                log("发现实例：${info.name}")
                return
            }
        }
        for (c in Fs.children(dir)) {
            if (!c.isDirectory) continue
            val n = c.name ?: ""
            if (n in SKIP || n.startsWith(".")) continue
            // 快速模式：只进「像启动器」的目录，其余跳过
            if (depth == 0 && !LauncherHelper.isKnownDir(n) && n.lowercase(Locale.ROOT) != "games") continue
            collectQuick(ctx, c, depth + 1, out, log)
        }
    }

    private fun collect(
        ctx: Context,
        dir: DocumentFile,
        depth: Int,
        out: MutableList<InstanceInfo>,
        log: (String) -> Unit
    ) {
        if (depth > 3) return
        if (depth > 0 && looksLikeInstance(dir)) {
            val info = readInfo(ctx, dir)
            if (info != null) {
                out.add(info)
                log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                return
            }
        }
        for (c in Fs.children(dir)) {
            if (!c.isDirectory) continue
            val n = c.name ?: ""
            if (n in SKIP || n.startsWith(".")) continue
            collect(ctx, c, depth + 1, out, log)
        }
    }

    fun readInfo(ctx: Context, dir: DocumentFile): InstanceInfo? {
        val name = dir.name ?: return null
        val info = InstanceInfo(name = name, uri = dir.uri.toString())

        val mmc = Fs.find(dir, "mmc-pack.json", 0)
        if (mmc != null) {
            val o = Json.obj(Fs.readText(ctx, mmc))
            val comps = Json.a(o, "components")
            if (comps != null) {
                for (c in comps) {
                    val uid = Json.s(c, "uid")
                    val ver = Json.s(c, "version")
                    when {
                        uid == "net.minecraft" -> info.mcVersion = ver
                        uid.startsWith("net.fabricmc") -> info.loader = "fabric"
                        uid.startsWith("org.quiltmc") -> info.loader = "quilt"
                        uid.startsWith("net.minecraftforge") -> info.loader = "forge"
                        uid.startsWith("net.neoforged") -> info.loader = "neoforge"
                    }
                }
            }
            info.kind = "MultiMC/Prism"
        }

        val man = Fs.find(dir, "manifest.json", 0)
        if (man != null && info.mcVersion.isBlank()) {
            val o = Json.obj(Fs.readText(ctx, man))
            val mcObj = o?.asJsonObject?.get("minecraft")
            info.mcVersion = Json.s(mcObj, "version")
            val ls = Json.a(mcObj, "modLoaders")
            if (ls != null && ls.size() > 0) {
                val idv = Json.s(ls[0], "id")
                info.loader = when {
                    idv.startsWith("forge") -> "forge"
                    idv.startsWith("neoforge") -> "neoforge"
                    idv.startsWith("fabric") -> "fabric"
                    idv.startsWith("quilt") -> "quilt"
                    else -> "auto"
                }
            }
            info.kind = "CurseForge"
        }

        val vj = Fs.find(dir, "version.json", 0)
        if (vj != null && info.mcVersion.isBlank()) {
            info.mcVersion = Json.s(Json.obj(Fs.readText(ctx, vj)), "id")
            info.kind = "原版/Forge"
        }

        val mods = Fs.find(dir, "mods", 0)
        if (mods != null) {
            info.modCount = Fs.children(mods).count {
                it.isFile && (it.name ?: "").endsWith(".jar", true)
            }
        }
        info.hasConfig = Fs.find(dir, "config", 0) != null
        if (info.kind.isBlank()) info.kind = "目录"
        return info
    }

    /** 常见启动器数据目录（Android 11+ 只有拿到「所有文件访问」才能读这里） */
    private val DATA_DIRS = listOf(
        "/storage/emulated/0/Android/data",
        "/storage/emulated/0/games",
        "/storage/emulated/0/games/HMCL",
        "/storage/emulated/0/games/PCL",
        "/storage/emulated/0/games/MCinaBox",
        "/storage/emulated/0/HMCL",
        "/storage/emulated/0/PCL",
        "/storage/emulated/0/MCinaBox",
        "/storage/emulated/0/minecraft",
        "/storage/emulated/0/MultiMC",
        "/storage/emulated/0/PojavLauncher",
        "/storage/emulated/0/ZalithLauncher"
    )

    fun hasAllFilesAccess(): Boolean =
        try {
            android.os.Environment.isExternalStorageManager()
        } catch (t: Throwable) {
            false
        }

    /**
     * 直接扫文件系统。这是主力路径：启动器实例几乎都在 Android/data 下，
     * 而 Android 11+ 通过 SAF 根本进不去，必须用 File API + 所有文件访问权限。
     */
    fun scanFiles(log: (String) -> Unit, maxDepth: Int = 4): List<InstanceInfo> {
        if (!hasAllFilesAccess()) {
            log("没有「所有文件访问」权限，读不到 Android/data")
            return emptyList()
        }
        val out = LinkedHashMap<String, InstanceInfo>()
        val skip = setOf("cache", "code_cache", "files_cache", ".thumbnails", "obb")

        fun walk(dir: java.io.File, depth: Int) {
            if (depth > maxDepth) return
            if (dir.name.startsWith(".") || dir.name in skip) return
            if (depth >= 1 && looksLikeInstanceFile(dir)) {
                val info = readFromFile(dir)
                if (info != null && !out.containsKey(info.uri)) {
                    out[info.uri] = info
                    log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                    return
                }
            }
            val kids = dir.listFiles() ?: return
            for (c in kids) {
                if (!c.isDirectory) continue
                walk(c, depth + 1)
            }
        }

        // 先精确扫已知启动器数据目录，再兜底扫整个 Android/data
        for (d in DATA_DIRS) {
            val f = java.io.File(d)
            if (!f.exists()) continue
            log("检查：$d")
            walk(f, 0)
        }
        return out.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun looksLikeInstanceFile(dir: java.io.File): Boolean {
        if (!dir.isDirectory) return false
        val kids = dir.listFiles() ?: return false
        val names = kids.map { it.name.lowercase(Locale.ROOT) }.toSet()
        return names.contains("mods") || names.contains("config") ||
            names.contains("version.json") || names.contains("mmc-pack.json") ||
            names.contains("manifest.json") || names.contains("instance.cfg")
    }

    fun readFromFile(dir: java.io.File): InstanceInfo? {
        val kids = dir.listFiles() ?: return null
        val map = kids.associateBy { it.name.lowercase(Locale.ROOT) }
        val info = InstanceInfo(
            name = dir.name ?: return null,
            uri = "file://" + dir.absolutePath,
            hasConfig = map.containsKey("config")
        )

        val mmc = map["mmc-pack.json"]
        if (mmc != null && mmc.isFile) {
            val o = Json.obj(readFileText(mmc))
            val comps = Json.a(o, "components")
            if (comps != null) {
                for (c in comps) {
                    val uid = Json.s(c, "uid")
                    val ver = Json.s(c, "version")
                    when {
                        uid == "net.minecraft" -> info.mcVersion = ver
                        uid.startsWith("net.fabricmc") -> info.loader = "fabric"
                        uid.startsWith("org.quiltmc") -> info.loader = "quilt"
                        uid.startsWith("net.minecraftforge") -> info.loader = "forge"
                        uid.startsWith("net.neoforged") -> info.loader = "neoforge"
                    }
                }
            }
            info.kind = "MultiMC/Prism"
        }

        val man = map["manifest.json"]
        if (man != null && man.isFile && info.mcVersion.isBlank()) {
            val o = Json.obj(readFileText(man))
            val mcObj = o?.asJsonObject?.get("minecraft")
            info.mcVersion = Json.s(mcObj, "version")
            val ls = Json.a(mcObj, "modLoaders")
            if (ls != null && ls.size() > 0) {
                val idv = Json.s(ls[0], "id")
                info.loader = when {
                    idv.startsWith("forge") -> "forge"
                    idv.startsWith("neoforge") -> "neoforge"
                    idv.startsWith("fabric") -> "fabric"
                    idv.startsWith("quilt") -> "quilt"
                    else -> "auto"
                }
            }
            info.kind = "CurseForge"
        }

        val vj = map["version.json"]
        if (vj != null && vj.isFile && info.mcVersion.isBlank()) {
            info.mcVersion = Json.s(Json.obj(readFileText(vj)), "id")
            info.kind = "原版/Forge"
        }

        val cfg = map["instance.cfg"]
        if (cfg != null && cfg.isFile && info.mcVersion.isBlank()) {
            val t = readFileText(cfg)
            val m = Regex("IntendedVersion=(.+)").find(t)
            if (m != null) info.mcVersion = m.groupValues[1].trim()
            info.kind = "MultiMC"
        }

        val mods = map["mods"]
        if (mods != null && mods.isDirectory) {
            info.modCount = mods.listFiles()?.count {
                it.isFile && it.name.endsWith(".jar", true)
            } ?: 0
        }
        if (info.kind.isBlank()) info.kind = "目录"
        return info
    }

    private fun readFileText(f: java.io.File): String =
        try {
            f.readText()
        } catch (t: Throwable) {
            ""
        }
}
