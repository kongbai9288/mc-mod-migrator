package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

/**
 * 目录探测与验证：回答「用户选的这一层到底是什么」。
 *
 * ## 为什么要有这个文件
 *
 * 选目录是这个应用最容易出错的一步，而且错了当时不说：
 *
 * - 选到 `.minecraft` 根（开了版本隔离）→ 根目录的 `mods/` 是空的，
 *   真正的模组在 `versions/<名字>/mods/`，于是迁移 0 个模组，
 *   界面上什么都不报，用户以为功能坏了
 * - 选到启动器根目录 → 扫出来一堆不相干的东西，或者一个都扫不到
 * - 选到某个实例的上级 → 迁移把**别的版本**的模组搬了过来
 *
 * 最后一条最危险：用户要的是甲，搬过来的是乙，而且看不出来。
 *
 * ## 两层结构
 *
 * 先把目录读成一棵**很小的树**（[Node]），再对这棵树做判断（[report]）。
 * 拆开是为了能测：单元测试里没有 Android 环境，
 * 造不出 `DocumentFile`，但能造 `Node`，所以判定逻辑可以真跑一遍。
 *
 * 读树时只往下钻 `versions / saves / mods / config / .minecraft` 这几个名字，
 * 并且有节点预算 —— SAF 的 `listFiles()` 很慢，
 * 全盘递归会把「选个目录」变成卡死几秒。
 *
 * ## 除了认层级，还认 MC 版本
 *
 * `versions/<名字>/<名字>.json` 的 `id` 字段就是版本号，
 * 读它才知道「这个目录在跑 MC 几」——只数目录名是数不出来的，
 * 因为启动器写的目录名经常是 `1.20.1-fabric` 这种自定义名。
 * 只读每个 json 的开头一小段（[JSON_CAP]），不是整个文件。
 */
object McProbe {

    /**
     * 目录树上的一个节点。
     *
     * @param dir 是不是目录；文件节点没有 [kids]
     */
    data class Node(
        val name: String,
        val dir: Boolean,
        val kids: List<Node> = emptyList(),
        /**
         * 文件开头的一小段文本（UTF-8）。
         *
         * ⚠️ **只有** `versions/<名字>/` 下的 `.json` 才会带，其余一律 null。
         * 读树时把每个文件的内容都读进来，等于把「选个目录」变成读半张卡。
         */
        val text: String? = null
    ) {
        /** 按名字找子节点（忽略大小写） */
        fun child(n: String): Node? =
            kids.firstOrNull { it.name.equals(n, ignoreCase = true) }

        /** 子节点名字的小写集合 */
        fun names(): Set<String> = kids.map { it.name.lowercase(Locale.ROOT) }.toSet()
    }

    /** 选中的这一层属于哪一种 */
    enum class Kind {
        /** 游戏版本目录：这一层就能看到 mods / saves / config，可以直接用 */
        INSTANCE,

        /** `.minecraft` 根，且**开了版本隔离**：真正的实例在 `versions/<名字>/` 下 */
        ISOLATED_ROOT,

        /** `.minecraft` 根，没开版本隔离：`versions/` 里是游戏核心，模组就在 `mods/` */
        PLAIN_ROOT,

        /** 再上一层的启动器目录：下面还藏着一个或多个游戏目录 */
        LAUNCHER_ROOT,

        /** 看不出是 MC 目录 */
        NOT_MC,
    }

    /** `versions/` 下的一个子项 */
    data class Version(
        val name: String,
        /** 这个版本自己的模组数（非隔离时为 0，因为那里放的只是游戏核心） */
        val mods: Int,
        val saves: Int,
        /** true = 版本隔离出来的实例；false = 只是游戏核心（.jar / .json） */
        val isolated: Boolean,
        /**
         * 这个版本是 MC 几，读自 `<名字>.json` 的 `id` 字段；认不出是空串。
         *
         * 原样返回启动器写的名字（比如 `1.20.1-fabric`），
         * **不做猜测式裁剪** —— 裁错了比不裁更糟。
         */
        val mc: String = ""
    )

    /** 探测结论 */
    data class Report(
        val kind: Kind,
        /** 这一层 `mods/` 里的模组数 */
        val mods: Int,
        /** 这一层 `saves/` 里的存档数（含 `level.dat` 的才算） */
        val saves: Int,
        /** `versions/` 下的子项 */
        val versions: List<Version>,
        /** 这一层能不能直接当「迁移前 / 迁移后」用 */
        val usable: Boolean,
        /** 一句话结论，给人看 */
        val summary: String,
        /** 该改选到哪一层；空串 = 不用改 */
        val suggest: String
    ) {
        /** 隔离出来的实例（版本隔离时真正要选的那些） */
        fun isolated(): List<Version> = versions.filter { it.isolated }

        /** 一个模组都没有（当源目录用时等于空跑一趟） */
        fun empty(): Boolean = mods == 0 && isolated().all { it.mods == 0 }

        /**
         * 只是游戏核心、没装模组的版本数。
         *
         * ⚠️ 以前结论里只报隔离出来的实例，于是 `versions/` 下那些
         * 没装模组的原版版本彻底消失——用户会以为自己的版本丢了，
         * 其实是探测没报。现在数量单独给出来，界面能写清楚。
         */
        fun cores(): Int = versions.count { !it.isolated }

        /**
         * 这个目录在跑 MC 几；认不出返回空串。
         *
         * 顺序是「越确定的越优先」：
         * 1. `versions/` 里**装了模组**的那个 —— 版本隔离时它才是真正在玩的那个
         * 2. `versions/` 里第一个认出版本的
         * 3. 认不出
         *
         * ⚠️ 只给界面显示用，不参与迁移决策；
         * 迁移时的版本一律以用户在迁移页选的为准。
         */
        fun mcVersion(): String = McProbe.mcOf(versions)
    }

    /** [Report.mcVersion] 的实现，抽出来是为了 summary 里也能用 */
    private fun mcOf(versions: List<Version>): String {
        versions.firstOrNull { it.isolated && it.mc.isNotBlank() }?.let { return it.mc }
        versions.firstOrNull { it.mc.isNotBlank() }?.let { return it.mc }
        return ""
    }

    /** 只往下钻这几个名字，避免把整个目录树读进来 */
    private val DIG = setOf(
        McDirs.VERSIONS, McDirs.SAVES, McDirs.MODS, McDirs.CONFIG,
        McDirs.INSTANCES, ".minecraft", "minecraft"
    )

    /** 节点预算：超了就不再往下读，防止超大目录把界面卡死 */
    private const val BUDGET = 4000

    /** 模组文件的后缀，含各启动器禁用后的写法 */
    private val MOD_EXT = listOf(".jar", ".zip", ".jar.disabled", ".zip.disabled")

    private fun isMod(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return MOD_EXT.any { n.endsWith(it) }
    }

    /** 只读版本描述文件的开头这么多字节 —— `id` 字段在文件最前面，够用了 */
    private const val JSON_CAP = 256 * 1024

    private fun isJson(name: String): Boolean = name.lowercase(Locale.ROOT).endsWith(".json")

    /**
     * 从版本描述文件（`<版本>.json`）里取 `id` 字段。
     *
     * 手写扫描而不是引 org.json：这函数在单元测试里也要跑，
     * 而 org.json 是 Android 框架里的类，纯 JVM 单测上不存在。
     *
     * 只找**第一个** `"id"`。官方 json 里 `id` 在最外层、位置靠前，
     * 后面那个 `assetIndex.id` 不会被先命中。
     * 取不到就返回空串——宁可不显示，也不要显示错的版本。
     */
    fun jsonId(text: String): String {
        val at = text.indexOf("\"id\"")
        if (at < 0) return ""
        var i = text.indexOf(':', at + 4)
        if (i < 0) return ""
        i++
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '"') return ""
        val sb = StringBuilder()
        var j = i + 1
        while (j < text.length) {
            val c = text[j]
            if (c == '"') break
            if (c == '\\' && j + 1 < text.length) {
                sb.append(text[j + 1])
                j += 2
                continue
            }
            sb.append(c)
            j++
        }
        return sb.toString().trim()
    }

    private fun countMods(node: Node?): Int =
        node?.kids?.count { !it.dir && isMod(it.name) } ?: 0

    /** 存档数：含 `level.dat` 的子目录才算一个世界 */
    private fun countWorlds(node: Node?): Int {
        if (node == null) return 0
        var n = 0
        for (k in node.kids) {
            if (!k.dir) continue
            if (k.child(McDirs.LEVEL_DAT) != null) n++
        }
        return n
    }

    // ── 读树 ────────────────────────────────────────────────

    /** 从 `java.io.File` 读（有「所有文件访问」权限时走这条） */
    fun fromFile(f: File): Node =
        Node(f.name.ifBlank { "/" }, true, kidsOf(f, 0, IntRef(BUDGET)))

    /**
     * 从 SAF 树读（绝大多数情况走这条）。
     *
     * @param ctx 传了才会顺带读 `versions/<名字>/*.json` 的开头（用来认 MC 版本）；
     *            不传就只认目录结构，不读文件内容
     */
    fun fromDoc(d: DocumentFile, ctx: Context? = null): Node =
        Node(d.name ?: "", true, kidsOf(d, 0, IntRef(BUDGET), false, ctx))

    /** 按用途读：URI 可能是 file:// 也可能是 content://…/tree/… */
    fun fromUri(ctx: Context, uri: String): Node? {
        if (uri.isBlank()) return null
        val d = Fs.tree(ctx, uri) ?: return null
        return runCatching { fromDoc(d, ctx) }.getOrNull()
    }

    private class IntRef(var v: Int)

    /** 读文件开头的一小段；太大或读不了返回 null */
    private fun headText(f: File): String? = try {
        if (!f.isFile || f.length() > JSON_CAP) null else f.readText(Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }

    /** 同 [headText]，走 SAF */
    private fun headText(ctx: Context?, d: DocumentFile): String? {
        if (ctx == null) return null
        return try {
            if (d.length() > JSON_CAP) null else Fs.readText(ctx, d).ifBlank { null }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * @param inVersion 当前列出的这一层是不是 `versions/<名字>/`。
     *                  是的话，它下面的 `.json` 才会带文本（用来认 MC 版本）
     */
    private fun kidsOf(f: File, depth: Int, budget: IntRef, inVersion: Boolean = false): List<Node> {
        if (budget.v <= 0) return emptyList()
        val arr = f.listFiles() ?: return emptyList()
        val out = ArrayList<Node>(arr.size)
        for (c in arr) {
            if (budget.v-- <= 0) break
            out.add(
                if (c.isDirectory) {
                    val dig = depth < 3 || c.name.lowercase(Locale.ROOT) in DIG
                    Node(
                        c.name, true,
                        if (dig) kidsOf(c, depth + 1, budget, f.name.equals(McDirs.VERSIONS, true))
                        else emptyList()
                    )
                } else {
                    Node(
                        c.name, false, emptyList(),
                        if (inVersion && isJson(c.name)) headText(c) else null
                    )
                }
            )
        }
        return out
    }

    private fun kidsOf(
        d: DocumentFile,
        depth: Int,
        budget: IntRef,
        inVersion: Boolean = false,
        ctx: Context? = null
    ): List<Node> {
        if (budget.v <= 0) return emptyList()
        val arr = Fs.children(d)
        val out = ArrayList<Node>(arr.size)
        for (c in arr) {
            if (budget.v-- <= 0) break
            val n = c.name ?: continue
            out.add(
                if (c.isDirectory) {
                    val dig = depth < 3 || n.lowercase(Locale.ROOT) in DIG
                    Node(
                        n, true,
                        if (dig) kidsOf(c, depth + 1, budget, d.name.equals(McDirs.VERSIONS, true), ctx)
                        else emptyList()
                    )
                } else {
                    Node(
                        n, false, emptyList(),
                        if (inVersion && isJson(n)) headText(ctx, c) else null
                    )
                }
            )
        }
        return out
    }

    // ── 判定 ────────────────────────────────────────────────

    /** 这一层自己是不是一个游戏目录（能直接看到 mods / saves / config 等） */
    private fun isGameLayer(names: Set<String>): Boolean =
        McDirs.ROOT_MARKS.any { it.lowercase(Locale.ROOT) in names }

    /** 是不是启动器目录：子目录里藏着游戏目录 */
    private fun isLauncherLayer(root: Node): Boolean {
        for (k in root.kids) {
            if (!k.dir) continue
            val n = k.name.lowercase(Locale.ROOT)
            if (n == ".minecraft" || n == "minecraft") return true
            if (isGameLayer(k.names())) return true
        }
        return false
    }

    /**
     * 判定。纯函数，输入 [Node] 输出 [Report]，不碰 Android。
     *
     * 判「版本隔离」看的是 `versions/<名字>/` 下面有没有
     * mods / saves / config / options.txt —— 有就是独立实例，
     * 只有 `.jar` `.json` 的那是游戏核心，不是实例。
     */
    fun report(root: Node): Report {
        val names = root.names()
        val mods = countMods(root.child(McDirs.MODS))
        val saves = countWorlds(root.child(McDirs.SAVES))

        val versions = ArrayList<Version>()
        root.child(McDirs.VERSIONS)?.let { vs ->
            for (v in vs.kids) {
                if (!v.dir) continue
                val vn = v.names()
                // 只有游戏核心的（.jar / .json）不算实例
                val isolated = vn.any {
                    it == McDirs.MODS || it == McDirs.SAVES || it == McDirs.CONFIG ||
                        it == McDirs.OPTIONS_TXT || it == McDirs.RESOURCEPACKS
                }
                versions.add(
                    Version(
                        v.name,
                        if (isolated) countMods(v.child(McDirs.MODS)) else 0,
                        if (isolated) countWorlds(v.child(McDirs.SAVES)) else 0,
                        isolated,
                        v.kids.firstOrNull { !it.dir && isJson(it.name) && it.text != null }
                            ?.let { jsonId(it.text ?: "") } ?: ""
                    )
                )
            }
        }

        val iso = versions.filter { it.isolated }
        val cores = versions.size - iso.size
        val game = isGameLayer(names)

        val kind = when {
            iso.isNotEmpty() -> Kind.ISOLATED_ROOT
            game && McDirs.VERSIONS in names -> Kind.PLAIN_ROOT
            game -> Kind.INSTANCE
            isLauncherLayer(root) -> Kind.LAUNCHER_ROOT
            else -> Kind.NOT_MC
        }

        val usable = kind == Kind.INSTANCE || kind == Kind.PLAIN_ROOT

        // 建议改选到哪一层：只给一个明确目标，不给一堆让用户自己挑
        val suggest = when {
            kind == Kind.ISOLATED_ROOT && iso.size == 1 ->
                "${McDirs.VERSIONS}/${iso[0].name}"
            kind == Kind.ISOLATED_ROOT -> "${McDirs.VERSIONS}/<版本名>"
            kind == Kind.LAUNCHER_ROOT -> {
                val hit = root.kids.firstOrNull {
                    it.dir && (
                        it.name.equals(".minecraft", true) ||
                            it.name.equals("minecraft", true) ||
                            isGameLayer(it.names())
                        )
                }
                hit?.name ?: ".minecraft"
            }
            else -> ""
        }

        val mc = mcOf(versions)
        val mcTip = if (mc.isNotBlank()) "，MC $mc" else ""

        val summary = when (kind) {
            Kind.INSTANCE -> "游戏版本目录：模组 $mods 个，存档 $saves 个$mcTip"
            Kind.PLAIN_ROOT -> buildString {
                append("这是 .minecraft 根目录，没开版本隔离：模组 $mods 个，存档 $saves 个。")
                if (versions.isNotEmpty()) {
                    append("versions/ 下 ${versions.size} 个都是游戏核心，不是实例。")
                }
                if (mc.isNotBlank()) append("（MC $mc）")
            }
            Kind.ISOLATED_ROOT -> buildString {
                append("这是 .minecraft 根目录，里面开了版本隔离：")
                append("真正的模组在 versions/ 下（${iso.size} 个版本），")
                if (cores > 0) append("另有 $cores 个没装模组的是游戏核心，")
                append("根目录自己的 mods/ 有 $mods 个。")
                if (mc.isNotBlank()) append("当前在用的版本是 MC $mc。")
                if (mods > 0) append("直接用这一层会把 ${iso.size} 个版本的模组混在一起。")
            }
            Kind.LAUNCHER_ROOT ->
                "这是启动器目录，游戏目录还在下一层（$suggest），直接迁移会找不到模组"
            Kind.NOT_MC ->
                "这一层没有 mods / saves / versions，看起来不是游戏目录"
        }

        return Report(kind, mods, saves, versions, usable, summary, suggest)
    }

    /** 一步到位：给 URI，出结论。读不到返回 null（调用方按「未选目录」处理） */
    fun of(ctx: Context, uri: String): Report? {
        val n = fromUri(ctx, uri) ?: return null
        return runCatching { report(n) }.getOrNull()
    }
}
