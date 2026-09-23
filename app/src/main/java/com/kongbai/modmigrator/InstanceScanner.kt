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

    /**
     * .minecraft 内部的功能子目录。
     * 它们是「游戏目录的一部分」，本身绝不是实例——
     * 之前就是没排除这些，才把 logs、share、saves 之类的目录当成了版本。
     */
    private val INNER = setOf(
        "logs", "log", "share", "crash-reports", "crashreport",
        "screenshots", "resourcepacks", "resource_packs", "texturepacks",
        "shaderpacks", "assets", "libraries", "natives", "runtime",
        "jre", "java", "backups", "saves", "mods", "config",
        "defaultconfigs", "kubejs", "scripts", "options.txt",
        "versions", "assets", "indexes", "objects", "virtual",
        "cache", "temp", "tmp", "plugins"
    )

    /** 游戏目录（= 实例）的标志性内容：有这些之一才算 */
    private val GAME_MARKS = setOf(
        "mods", "saves", "config", "options.txt", "level.dat",
        "mmc-pack.json", "manifest.json", "instance.cfg", "version.json"
    )

    /** 以点开头也要放行的目录名：.minecraft 才是真正的游戏目录 */
    private val DOT_ALLOW = setOf(".minecraft", ".fcl", ".pojav")

    /** 目录名是否要跳过（点目录默认跳过，但 .minecraft 必须放行） */
    private fun skipName(n: String): Boolean {
        if (n in DOT_ALLOW) return false
        if (n.startsWith(".")) return true
        return n in SKIP
    }

    private val LAUNCHER_DIRS = listOf(
        "pojav", "PojavLauncher", "zalithlauncher", "ZalithLauncher",
        "instances", "versions", "hmcl", "HMCL"
    )

    /**
     * 判断是不是一个「游戏目录 / 版本」。
     *
     * 规则（按 HMCL 官方文档与安卓各启动器实测结构）：
     * - .minecraft 本身就是游戏目录，它才是实例
     * - .minecraft 内部的 logs / saves / mods / config 等只是它的一部分，不是实例
     * - 版本隔离时 .minecraft/versions/<名字>/ 下若含 mods/saves/config 才是独立实例；
     *   只有 .jar 的那是游戏核心，不是实例
     * - 目录名是 logs/share/screenshots 之类的一律不算
     */
    fun looksLikeInstance(dir: DocumentFile): Boolean {
        if (!dir.isDirectory) return false
        val name = (dir.name ?: "").lowercase(Locale.ROOT)
        if (name in INNER) return false
        if (name == ".minecraft" || name == "minecraft") return true
        val names = Fs.children(dir).map { (it.name ?: "").lowercase(Locale.ROOT) }.toSet()
        return names.any { it in GAME_MARKS }
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
            if (skipName(n)) continue
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
            if (skipName(n)) continue
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
                        else -> {
                            val n = Loaders.normalize(uid)
                            if (n != "auto") info.loader = n
                        }
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
                info.loader = Loaders.normalize(idv)
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

    /**
     * 各启动器的游戏目录（.minecraft）真实路径。
     * 这份列表按公开资料核对过，覆盖安卓主流启动器：
     *   FCL        /storage/emulated/0/FCL/.minecraft
     *   Zalith     Android/data/com.movtery.zalithlauncher/files/.minecraft
     *   Pojav      Android/data/net.kdt.pojavlaunch(.debug)/files/.minecraft
     *              /storage/emulated/0/games/PojavLauncher/.minecraft
     *   Amethyst   Android/data/org.angelauramc.amethyst.debug/files/.minecraft
     *   HMCL-PE    /storage/emulated/0/HMCLPE/.minecraft
     *   澪-Ultimate /storage/emulated/0/MioLauncher/.minecraft
     * 注意：.minecraft 才是游戏目录；它下面的 versions/<名字> 只有在
     * 「版本隔离」且含 mods/saves/config 时才算独立实例。
     */
    private val MC_DIRS = listOf(
        "/storage/emulated/0/FCL/.minecraft",
        "/storage/emulated/0/FoldCraftLauncher/.minecraft",
        "/storage/emulated/0/Android/data/com.tungsten.fcl/files/.minecraft",
        "/storage/emulated/0/Android/data/com.tungsten.fcl.server/files/.minecraft",
        "/storage/emulated/0/Android/data/com.movtery.zalithlauncher/files/.minecraft",
        "/storage/emulated/0/Android/data/net.kdt.pojavlaunch/files/.minecraft",
        "/storage/emulated/0/Android/data/net.kdt.pojavlaunch.debug/files/.minecraft",
        "/storage/emulated/0/games/PojavLauncher/.minecraft",
        "/storage/emulated/0/Android/data/org.angelauramc.amethyst.debug/files/.minecraft",
        "/storage/emulated/0/HMCLPE/.minecraft",
        "/storage/emulated/0/MioLauncher/.minecraft",
        "/storage/emulated/0/games/HMCL/.minecraft",
        "/storage/emulated/0/games/PCL/.minecraft",
        "/storage/emulated/0/mcinaBox/.minecraft",
        "/storage/emulated/0/MCinaBox/.minecraft"
    )

    /** 兜底父目录：直接列 .minecraft 找不到时，再在这些目录里往下找 */
    private val DATA_DIRS = listOf(
        "/storage/emulated/0/Android/data",
        "/storage/emulated/0/FCL",
        "/storage/emulated/0/games",
        "/storage/emulated/0/HMCLPE",
        "/storage/emulated/0/minecraft",
        "/storage/emulated/0/MultiMC"
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

        // 第一步：直接命中已知启动器的 .minecraft（最准、最快）
        for (d in MC_DIRS) {
            val f = java.io.File(d)
            if (!f.exists() || !f.isDirectory) continue
            val info = readFromFile(f)
            if (info != null) {
                out[info.uri] = info
                log("发现游戏目录：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
            }
            // 版本隔离：.minecraft/versions/<名字> 下含 mods/saves/config 的才算独立实例
            val vs = java.io.File(f, "versions")
            if (vs.isDirectory) {
                for (g in vs.listFiles() ?: emptyArray()) {
                    if (!g.isDirectory) continue
                    val gi = readFromFile(g)
                    if (gi != null && (gi.modCount > 0 || gi.hasConfig)) {
                        out[gi.uri] = gi
                        log("发现隔离版本：${gi.name}（${gi.modCount} 个模组）")
                    }
                }
            }
        }

        // 第二步：没命中就在父目录里递归找
        if (out.isEmpty()) {
            val skip = setOf("cache", "code_cache", ".thumbnails", "obb")
            fun walk(dir: java.io.File, depth: Int) {
                if (depth > maxDepth) return
                if (skipName(dir.name) || dir.name.lowercase(Locale.ROOT) in skip) return
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
            for (d in DATA_DIRS) {
                val f = java.io.File(d)
                if (!f.exists()) continue
                log("在 $d 里查找…")
                walk(f, 0)
            }
        }
        return out.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun looksLikeInstanceFile(dir: java.io.File): Boolean {
        if (!dir.isDirectory) return false
        val name = dir.name.lowercase(Locale.ROOT)
        if (name in INNER) return false
        if (name == ".minecraft" || name == "minecraft") return true
        val kids = dir.listFiles() ?: return false
        return kids.any { it.name.lowercase(Locale.ROOT) in GAME_MARKS }
    }

    fun readFromFile(dir: java.io.File): InstanceInfo? {
        val kids = dir.listFiles() ?: return null
        val map = kids.associateBy { it.name.lowercase(Locale.ROOT) }
        // .minecraft 本身名字没有辨识度，用它的上级目录名（启动器/实例名）来显示
        var label = dir.name
        if (label == ".minecraft" || label == "minecraft") {
            val parent = dir.parentFile
            if (parent != null) label = parent.name
            val gp = dir.parentFile?.parentFile
            if (gp != null && label.equals("files", true)) label = gp.name
        }
        val info = InstanceInfo(
            name = label,
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
                        else -> {
                            val n = Loaders.normalize(uid)
                            if (n != "auto") info.loader = n
                        }
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
                info.loader = Loaders.normalize(idv)
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

    // ============ 以「启动器给的文件夹」为根扫描 ============
    // 现在主流启动器都把各个版本收进一个总目录里，而不是散在根目录：
    //   HMCL/.minecraft/versions/<版本>/
    //   PojavLauncher/instances/<实例>/
    //   FCL/instances/<实例>/
    //   Zalith/instances/<实例>/
    // 所以选中这个总目录后，要能往下钻到真正的版本目录。

    /** 中间容器层：这些目录本身不是实例，但它们里面就是各个版本 */
    private val CONTAINERS = setOf(
        "instances", "versions", "version", "minecraft", ".minecraft",
        "instance", "profiles", "modpacks", "packs", "game", "games"
    )

    /** SAF：以用户选中的目录为根扫描（可能是启动器总目录） */
    fun scanFrom(ctx: Context, rootUri: String, log: (String) -> Unit): List<InstanceInfo> {
        val root = Fs.tree(ctx, rootUri) ?: return emptyList()
        val out = LinkedHashMap<String, InstanceInfo>()
        log("扫描目录：${root.name ?: rootUri}")

        // 根自己就是一个版本目录的情况
        if (looksLikeInstance(root)) {
            val info = readInfo(ctx, root)
            if (info != null) {
                out[info.uri] = info
                log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                return out.values.toList()
            }
        }

        dig(ctx, root, 0, out, log)
        if (out.isEmpty()) {
            log("一级子目录没找到，再往下钻一层…")
            digDeep(ctx, root, 0, out, log)
        }
        return out.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /** 优先路径：子目录要么是版本本身，要么是容器（钻进去找版本） */
    private fun dig(
        ctx: Context, dir: DocumentFile, depth: Int,
        out: MutableMap<String, InstanceInfo>, log: (String) -> Unit
    ) {
        if (depth > 3) return
        for (c in Fs.children(dir)) {
            if (!c.isDirectory) continue
            val rawN = c.name ?: ""
            if (skipName(rawN)) continue
            val n = rawN.lowercase(Locale.ROOT)
            if (looksLikeInstance(c)) {
                val info = readInfo(ctx, c)
                if (info != null && !out.containsKey(info.uri)) {
                    out[info.uri] = info
                    log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                    continue
                }
            }
            // 容器层：钻进去，里面的每个子目录都当作版本候选
            if (n in CONTAINERS) {
                for (g in Fs.children(c)) {
                    if (!g.isDirectory) continue
                    val info = readInfo(ctx, g)
                    if (info != null && !out.containsKey(info.uri)) {
                        out[info.uri] = info
                        log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                    }
                }
            }
        }
    }

    /** 兜底：再深一层（有些启动器是 启动器/xxx/instances/版本） */
    private fun digDeep(
        ctx: Context, dir: DocumentFile, depth: Int,
        out: MutableMap<String, InstanceInfo>, log: (String) -> Unit
    ) {
        if (depth > 4) return
        for (c in Fs.children(dir)) {
            if (!c.isDirectory) continue
            val rawN = c.name ?: ""
            if (skipName(rawN)) continue
            val n = rawN.lowercase(Locale.ROOT)
            val info = readInfo(ctx, c)
            if (info != null && !out.containsKey(info.uri)) {
                out[info.uri] = info
                log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                continue
            }
            dig(ctx, c, depth + 1, out, log)
        }
    }

    /** File 版：以启动器总目录为根扫描 */
    fun scanFilesFrom(rootPath: String, log: (String) -> Unit): List<InstanceInfo> {
        val root = java.io.File(rootPath)
        if (!root.exists() || !root.isDirectory) {
            log("目录不存在：$rootPath")
            return emptyList()
        }
        val out = LinkedHashMap<String, InstanceInfo>()
        log("扫描目录：${root.name}")
        if (looksLikeInstanceFile(root)) {
            val info = readFromFile(root)
            if (info != null) {
                out[info.uri] = info
                log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                return out.values.toList()
            }
        }
        digFiles(root, 0, out, log)
        if (out.isEmpty()) digFilesDeep(root, 0, out, log)
        return out.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun digFiles(
        dir: java.io.File, depth: Int,
        out: MutableMap<String, InstanceInfo>, log: (String) -> Unit
    ) {
        if (depth > 3) return
        val kids = dir.listFiles() ?: return
        for (c in kids) {
            if (!c.isDirectory) continue
            if (skipName(c.name)) continue
            val n = c.name.lowercase(Locale.ROOT)
            if (looksLikeInstanceFile(c)) {
                val info = readFromFile(c)
                if (info != null && !out.containsKey(info.uri)) {
                    out[info.uri] = info
                    log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                    continue
                }
            }
            if (n in CONTAINERS) {
                val gs = c.listFiles() ?: continue
                for (g in gs) {
                    if (!g.isDirectory) continue
                    val info = readFromFile(g)
                    if (info != null && !out.containsKey(info.uri)) {
                        out[info.uri] = info
                        log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                    }
                }
            }
        }
    }

    private fun digFilesDeep(
        dir: java.io.File, depth: Int,
        out: MutableMap<String, InstanceInfo>, log: (String) -> Unit
    ) {
        if (depth > 4) return
        val kids = dir.listFiles() ?: return
        for (c in kids) {
            if (!c.isDirectory) continue
            if (skipName(c.name)) continue
            val n = c.name.lowercase(Locale.ROOT)
            val info = readFromFile(c)
            if (info != null && !out.containsKey(info.uri)) {
                out[info.uri] = info
                log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                continue
            }
            digFiles(c, depth + 1, out, log)
        }
    }
}
