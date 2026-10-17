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
        val kids: List<Node> = emptyList()
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
        val isolated: Boolean
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
    fun fromFile(f: File): Node = Node(f.name.ifBlank { "/" }, true, kidsOf(f, 0, IntRef(BUDGET)))

    /** 从 SAF 树读（绝大多数情况走这条） */
    fun fromDoc(d: DocumentFile): Node =
        Node(d.name ?: "", true, kidsOf(d, 0, IntRef(BUDGET)))

    /** 按用途读：URI 可能是 file:// 也可能是 content://…/tree/… */
    fun fromUri(ctx: Context, uri: String): Node? {
        if (uri.isBlank()) return null
        val d = Fs.tree(ctx, uri) ?: return null
        return runCatching { fromDoc(d) }.getOrNull()
    }

    private class IntRef(var v: Int)

    private fun kidsOf(f: File, depth: Int, budget: IntRef): List<Node> {
        if (budget.v <= 0) return emptyList()
        val arr = f.listFiles() ?: return emptyList()
        val out = ArrayList<Node>(arr.size)
        for (c in arr) {
            if (budget.v-- <= 0) break
            out.add(
                if (c.isDirectory) {
                    val dig = depth < 3 || c.name.lowercase(Locale.ROOT) in DIG
                    Node(c.name, true, if (dig) kidsOf(c, depth + 1, budget) else emptyList())
                } else Node(c.name, false)
            )
        }
        return out
    }

    private fun kidsOf(d: DocumentFile, depth: Int, budget: IntRef): List<Node> {
        if (budget.v <= 0) return emptyList()
        val arr = Fs.children(d)
        val out = ArrayList<Node>(arr.size)
        for (c in arr) {
            if (budget.v-- <= 0) break
            val n = c.name ?: continue
            out.add(
                if (c.isDirectory) {
                    val dig = depth < 3 || n.lowercase(Locale.ROOT) in DIG
                    Node(n, true, if (dig) kidsOf(c, depth + 1, budget) else emptyList())
                } else Node(n, false)
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
                        isolated
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

        val summary = when (kind) {
            Kind.INSTANCE -> "游戏版本目录：模组 $mods 个，存档 $saves 个"
            Kind.PLAIN_ROOT -> buildString {
                append("这是 .minecraft 根目录，没开版本隔离：模组 $mods 个，存档 $saves 个。")
                if (versions.isNotEmpty()) {
                    append("versions/ 下 ${versions.size} 个都是游戏核心，不是实例。")
                }
            }
            Kind.ISOLATED_ROOT -> buildString {
                append("这是 .minecraft 根目录，里面开了版本隔离：")
                append("真正的模组在 versions/ 下（${iso.size} 个版本），")
                if (cores > 0) append("另有 $cores 个没装模组的是游戏核心，")
                append("根目录自己的 mods/ 有 $mods 个。")
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
