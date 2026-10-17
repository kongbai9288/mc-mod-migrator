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
    var kind: String = "",
    /** 从哪个已授权目录里找出来的，用来在列表里区分不同启动器 */
    var source: String = ""
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
        "screenshots", McDirs.RESOURCEPACKS, "resource_packs", "texturepacks",
        McDirs.SHADERPACKS, "assets", "libraries", "natives", "runtime",
        "jre", "java", "backups", McDirs.SAVES, McDirs.MODS, McDirs.CONFIG,
        "defaultconfigs", "kubejs", McDirs.SCRIPTS, McDirs.OPTIONS_TXT,
        McDirs.VERSIONS, "assets", "indexes", "objects", "virtual",
        "cache", "temp", "tmp", "plugins"
    )

    /** 游戏目录（= 实例）的标志性内容：有这些之一才算 */
    private val GAME_MARKS = setOf(
        McDirs.MODS, McDirs.SAVES, McDirs.CONFIG, McDirs.OPTIONS_TXT, McDirs.LEVEL_DAT,
        "mmc-pack.json", "manifest.json", "instance.cfg", "version.json",
        // CurseForge / HMCL 的实例清单（对照成熟启动器的探测顺序补充）
        "minecraftinstance.json", "launcher_profiles.json"
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
        McDirs.INSTANCES, McDirs.VERSIONS, "hmcl", "HMCL"
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

    /**
     * 逐个扫描「用户添加的每一个启动器目录」。
     *
     * 之前只认 K.SCAN_ROOT 一个目录：装了 FCL + Zalith 两个启动器、
     * 分别授权了两个 .minecraft 时，只有最后授权的那个会被扫，
     * 另一个启动器下的实例全部看不见 ——
     * 表现就是"我明明有版本，列表里却没有"。
     *
     * 这里遍历每一条已授权目录，各自单独扫，把来源记进
     * InstanceInfo.source，列表里能看出属于哪个启动器。
     * 同一目录被多条路径扫到时按 uri 去重，不重复列出。
     */
    fun scanAll(ctx: Context, log: (String) -> Unit): List<InstanceInfo> {
        val dirs = GameDirs.list(ctx)
        if (dirs.isEmpty()) return emptyList()
        val out = ArrayList<InstanceInfo>()
        val seen = HashSet<String>()
        for (e in dirs) {
            log("在「${e.label()}」里找版本…")
            val found = runCatching { scan(ctx, e.uri, log) }
                .onFailure { log("「${e.label()}」读取失败：${it.message}") }
                .getOrDefault(emptyList())
            var fresh = 0
            for (info in found) {
                if (!seen.add(info.uri)) continue
                info.source = e.label()
                out.add(info)
                fresh++
            }
            log("「${e.label()}」找到 ${found.size} 个${if (fresh < found.size) "，新增 $fresh 个" else ""}")
        }
        return out
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

    /**
     * 从 version.json 的 **patches 数组**里认加载器。
     *
     * ⚠️ 之前这里只取了顶层的 `id`（那是 MC 版本或自定义版本名），
     * 加载器信息一直没被读出来 —— 而 version.json 恰恰是
     * 手机启动器（Pojav / FCL / Zalith）最常用的实例描述文件，
     * 于是这些实例的加载器常年停在「自动」，筛查结果自然不对。
     *
     * 真实结构（HMCL / Pojav 系）：
     *   { "id":"1.20.1", "patches":[ {"id":"game",...},
     *                                {"id":"fabric-loader","version":"0.14.21"} ] }
     * 加载器藏在 patches[].id 里，可能有多个，取第一个认得出的。
     */
    private fun loaderFromPatches(o: com.google.gson.JsonElement?): String {
        val arr = Json.a(o, "patches") ?: return "auto"
        val cands = ArrayList<String>()
        for (e in arr) {
            val id = Json.s(e, "id")
            val n = Loaders.normalize(id)
            if (n != "auto") cands.add(n)
        }
        if (cands.isEmpty()) return "auto"
        // 一个实例只会有一个加载器；万一 patches 里出现多个
        // （比如同时带了 fabric 与 forge 的库），按"更具体的优先"挑，
        // 免得被列表中靠后的那个覆盖成错的。
        return pickLoader(cands)
    }

    /**
     * 多个候选里挑一个。
     *
     * 优先级按"越具体越可信"排：cleanroom / neoforge / quilt 这类
     * 是从 forge / fabric 派生的，识别到就该以它为准 ——
     * 否则会被后面的宽泛项（forge / fabric）盖掉。
     */
    private fun pickLoader(cands: List<String>): String {
        // 优先级名单在 Loaders.PRIORITY，这里不再自己写一份顺序
        for (r in Loaders.PRIORITY) if (r in cands) return r
        return cands.first()
    }

    fun readInfo(ctx: Context, dir: DocumentFile): InstanceInfo? {
        val name = dir.name ?: return null
        val info = InstanceInfo(name = name, uri = dir.uri.toString())

        val mmc = Fs.find(dir, "mmc-pack.json", 0)
        if (mmc != null) {
            val o = Json.obj(Fs.readText(ctx, mmc))
            val comps = Json.a(o, "components")
            if (comps != null) {
                //
                // ⚠️ 之前是"认出一个就覆盖 info.loader"，
                // 于是结果取决于 components 的排列顺序：
                // 同时出现多个加载器相关条目时，最后一个说的算，
                // 而它未必是真正的那一个。
                // 现在先全部收集，再按"越具体越可信"挑一个。
                //
                val cands = ArrayList<String>()
                for (c in comps) {
                    val uid = Json.s(c, "uid")
                    val ver = Json.s(c, "version")
                    when {
                        uid == "net.minecraft" -> info.mcVersion = ver
                        else -> {
                            val n = Loaders.normalize(uid)
                            if (n != "auto") cands.add(n)
                        }
                    }
                }
                if (cands.isNotEmpty()) info.loader = pickLoader(cands)
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
                val idv = if (ls.size() > 0) Json.s(ls.get(0), "id") else ""
                info.loader = Loaders.normalize(idv)
            }
            info.kind = "CurseForge"
        }

        val vj = Fs.find(dir, "version.json", 0)
        if (vj != null) {
            val o = Json.obj(Fs.readText(ctx, vj))
            if (info.mcVersion.isBlank()) info.mcVersion = Json.s(o, "id")
            // 顶层 id 有时是 "1.20.1-fabric" 这种自定义名，
            // 有时只是 "1.20.1"。真正的加载器在 patches 里。
            val fromTop = Loaders.normalize(Json.s(o, "id"))
            val fromPatches = loaderFromPatches(o)
            if (fromPatches != "auto") info.loader = fromPatches
            else if (fromTop != "auto") info.loader = fromTop
            else if (info.loader == "auto") {
                // 顶层 id 里没写加载器，再看版本字段和主类名（Forge 的 mainClass 很特征）
                info.loader = Loaders.normalize(Json.s(o, "version")) .let {
                    if (it != "auto") it else Loaders.normalize(Json.s(o, "mainClass"))
                }
            }
            if (info.kind.isBlank()) info.kind = "原版/Forge"
        }

        val mods = Fs.find(dir, McDirs.MODS, 0)
        if (mods != null) {
            info.modCount = Fs.children(mods).count {
                it.isFile && (it.name ?: "").endsWith(".jar", true)
            }
        }
        info.hasConfig = Fs.find(dir, McDirs.CONFIG, 0) != null
        if (info.kind.isBlank()) info.kind = "目录"
        return info
    }

    /**
     * 各启动器的游戏目录（.minecraft）真实路径。
     *
     * ⚠️ 这里以前是写死的 15 条绝对路径，和 [LauncherDirs] 的表**完全重复**：
     * 改一个忘另一个是常态，而且这份清单没有任何证据等级——
     * 编造的路径和核过的路径混在一起，界面上显示得一模一样。
     *
     * 现在统一从 [LauncherDirs] 推导（公开目录在前、私有目录在后）。
     * 目录表和证据等级只有 [LauncherDirs.TABLE] 一处，
     * 远端补丁更新目录时这里自动跟着变，不会各改各的。
     *
     * 注意：.minecraft 才是游戏目录；它下面的 versions/<名字> 只有在
     * 「版本隔离」且含 mods/saves/config 时才算独立实例（见 [DirProbe]）。
     */
    private fun knownDirs(): List<String> {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val pub = ArrayList<String>()
        val priv = ArrayList<String>()
        for (e in LauncherDirs.all()) {
            for (p in LauncherDirs.publicCandidates(e)) pub += "$root/$p"
            for (p in LauncherDirs.privateCandidates(e)) priv += "$root/$p"
        }
        return (pub + priv).distinct()
    }

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
        for (d in knownDirs()) {
            val f = java.io.File(d)
            if (!f.exists() || !f.isDirectory) continue
            val info = readFromFile(f)
            if (info != null) {
                out[info.uri] = info
                log("发现游戏目录：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
            }
            //
            // 版本隔离：.minecraft/versions/<名字> 下含 mods/saves/config 的才算独立实例。
            //
            // ⚠️ 以前这里把「没装模组、没配置」的版本直接丢掉，连日志都不写一句，
            // 于是原版（vanilla）版本在扫描结果里彻底消失——
            // 用户会以为自己的版本丢了，其实是扫描器没报。
            // 现在它们确实**不计入实例**（没有模组可迁移），
            // 但数量会写进日志，界面上看得到「这里还有 N 个没装模组的版本」。
            //
            val vers = DirProbe.readVersions(f)
            var iso = 0
            for (v in vers) {
                if (v.kind != DirProbe.VersionKind.INSTANCE) continue
                val gi = readFromFile(java.io.File(v.path)) ?: continue
                if (gi.modCount > 0 || gi.hasConfig) {
                    out[gi.uri] = gi
                    iso++
                    log("发现隔离版本：${gi.name}（${gi.modCount} 个模组）")
                }
            }
            val rest = vers.size - iso
            if (rest > 0) log("　└ versions 下另有 $rest 个没装模组的版本，不算实例")
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
                //
                // ⚠️ 之前是"认出一个就覆盖 info.loader"，
                // 于是结果取决于 components 的排列顺序：
                // 同时出现多个加载器相关条目时，最后一个说的算，
                // 而它未必是真正的那一个。
                // 现在先全部收集，再按"越具体越可信"挑一个。
                //
                val cands = ArrayList<String>()
                for (c in comps) {
                    val uid = Json.s(c, "uid")
                    val ver = Json.s(c, "version")
                    when {
                        uid == "net.minecraft" -> info.mcVersion = ver
                        else -> {
                            val n = Loaders.normalize(uid)
                            if (n != "auto") cands.add(n)
                        }
                    }
                }
                if (cands.isNotEmpty()) info.loader = pickLoader(cands)
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
                val idv = if (ls.size() > 0) Json.s(ls.get(0), "id") else ""
                info.loader = Loaders.normalize(idv)
            }
            info.kind = "CurseForge"
        }

        val vj = map["version.json"]
        if (vj != null && vj.isFile) {
            val o = Json.obj(readFileText(vj))
            if (info.mcVersion.isBlank()) info.mcVersion = Json.s(o, "id")
            val fromTop = Loaders.normalize(Json.s(o, "id"))
            val fromPatches = loaderFromPatches(o)
            if (fromPatches != "auto") info.loader = fromPatches
            else if (fromTop != "auto") info.loader = fromTop
            else if (info.loader == "auto") {
                info.loader = Loaders.normalize(Json.s(o, "version")).let {
                    if (it != "auto") it else Loaders.normalize(Json.s(o, "mainClass"))
                }
            }
            if (info.kind.isBlank()) info.kind = "原版/Forge"
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

        //
        // 版本兜底：mmc-pack / manifest / version.json 都没给出版本号时，
        // 到 versions/<名字>/<名字>.json 里找（官方启动器和多数第三方
        // 启动器都这样存）。以前这条路没走，于是这类实例的版本常年是
        // 空串，界面显示 "MC ?"，用户以为扫错了。
        //
        if (info.mcVersion.isBlank()) {
            info.mcVersion = DirProbe.mcVersionOf(dir)
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
        McDirs.INSTANCES, McDirs.VERSIONS, "version", "minecraft", ".minecraft",
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
