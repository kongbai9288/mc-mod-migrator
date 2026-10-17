package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

/**
 * 目录探测与验收。
 *
 * 只回答两个问题：
 * 1. 用户选的（或扫到的）这个目录，**是不是**一个游戏目录？
 * 2. 它里面的 `.minecraft/versions/` **到底装了什么**？
 *
 * ## 为什么单独拆一个文件
 *
 * 「认目录」这件事以前散在三处，各写各的：
 * - [InstanceScanner.looksLikeInstance] 看子目录里有没有 mods/saves/config
 * - [LauncherDirs.looksLikeMinecraft] 看有没有 ROOT_MARKS
 * - 迁移页自己又判一次「有没有 versions」
 *
 * 三处规则不一致，于是出现这种事：一个**空壳目录**（手工建了个
 * 空的 `mods` 文件夹）在三处分别被判成「是」「是」「否」。
 * 界面上就表现为「选了这个目录、扫描却说没有版本」。
 *
 * ## versions/ 里什么才算「一个版本」
 *
 * 官方启动器和绝大多数第三方启动器的 `.minecraft/versions/<名字>/`
 * 里，三种东西会混在一起，必须分开认：
 *
 * | 内容 | 是什么 | 能不能迁移 |
 * |---|---|---|
 * | `<名字>.json` + `<名字>.jar` | 游戏核心 | 不能，它不是实例 |
 * | 里面有 `mods/` `saves/` `config/` | 开了版本隔离的实例 | **能** |
 * | 只有 `<名字>.json` | 继承过来的依赖声明 | 不能 |
 *
 * 以前只看「有没有 mods 或 config」，于是**没装模组的原版版本**
 * 在扫描结果里彻底消失，用户会以为自己的版本丢了。
 * 现在 [Version.kind] 明确区分，界面能说清「这里有 3 个游戏核心、
 * 其中 1 个装了模组」。
 *
 * ⚠️ 本文件只做**只读探测**，不写任何文件，不申请任何权限。
 */
object DirProbe {

    /** 探测结论的分类 */
    enum class Kind {
        /** 确定的游戏目录（.minecraft 或等价结构） */
        MINECRAFT,

        /** 装了多个实例的总目录（instances/ 之类），本身不是游戏目录 */
        PARENT,

        /** 什么都不是 */
        NONE,
    }

    /** `versions/` 下面一个条目的性质 */
    enum class VersionKind {
        /** 装了模组/存档/配置 —— 版本隔离出来的真实例 */
        INSTANCE,

        /** 只有核心（.json + .jar），是游戏本体不是实例 */
        CORE,

        /** 只有 .json，多半是被别的版本 inheritFrom 的依赖声明 */
        STUB,

        /** 空目录或认不出 */
        UNKNOWN,
    }

    data class Version(
        val name: String,
        val path: String,
        val kind: VersionKind,
        /** 从 `<名字>.json` 的 id 字段读出来的 MC 版本 */
        val mcVersion: String = "",
        val modCount: Int = 0,
    )

    data class Result(
        val path: String,
        val kind: Kind,
        /** 命中了哪些标志（mods / saves / versions …），给界面显示用 */
        val marks: List<String> = emptyList(),
        /** `versions/` 下的条目，已逐个验过 */
        val versions: List<Version> = emptyList(),
        /** `instances/` 下的目录名 */
        val instances: List<String> = emptyList(),
        /** 能确定的 MC 版本，认不出是空串 */
        val mcVersion: String = "",
        /** 一句话结论，直接能显示给用户 */
        val reason: String = "",
    ) {
        /** 有没有能迁移的东西 */
        val usable: Boolean
            get() = kind == Kind.MINECRAFT || kind == Kind.PARENT

        /** 版本隔离出来的实例数 */
        val isolatedCount: Int
            get() = versions.count { it.kind == VersionKind.INSTANCE }
    }

    /** 判断「这算不算一个版本目录」时看的子目录 */
    private val ISOLATE_MARKS = listOf(McDirs.MODS, McDirs.SAVES, McDirs.CONFIG)

    /**
     * 探测一个目录。
     *
     * 不存在 / 读不了 / 是文件 —— 一律返回 [Kind.NONE] 并写明原因，
     * **不抛异常**。调用方把它放在扫描热路径上，抛异常会中断整轮扫描。
     */
    fun of(dir: File?): Result {
        if (dir == null) return Result("", Kind.NONE, reason = "没有给目录")
        val path = dir.absolutePath
        if (!dir.exists()) return Result(path, Kind.NONE, reason = "目录不存在")
        if (!dir.isDirectory) return Result(path, Kind.NONE, reason = "这是一个文件，不是目录")

        val names = dir.list()?.toList() ?: return Result(path, Kind.NONE, reason = "目录读不了（没有权限或已损坏）")
        val lower = names.map { it.lowercase(Locale.ROOT) }.toSet()

        val marks = McDirs.ROOT_MARKS.filter { m -> lower.contains(m.lowercase(Locale.ROOT)) }
        val name = dir.name?.lowercase(Locale.ROOT) ?: ""

        // .minecraft / minecraft 本身就是游戏目录，不看内容
        val byName = name == ".minecraft" || name == "minecraft"

        val versions = readVersions(dir)
        val instances = readInstances(dir)

        val kind = when {
            byName || marks.isNotEmpty() -> Kind.MINECRAFT
            // 本身不是游戏目录，但装着实例：启动器的总目录
            instances.isNotEmpty() -> Kind.PARENT
            versions.isNotEmpty() -> Kind.PARENT
            else -> Kind.NONE
        }

        val mc = mcVersionOf(dir, versions)
        return Result(
            path = path,
            kind = kind,
            marks = marks,
            versions = versions,
            instances = instances,
            mcVersion = mc,
            reason = reason(kind, marks, versions, instances, mc, names.size)
        )
    }

    /**
     * 读 `.minecraft/versions/` 下的条目并逐个验收。
     *
     * 目录本身也可能就是 `versions`（用户直接选了这一层），两种都认。
     */
    fun readVersions(dir: File): List<Version> {
        val vs = if ((dir.name ?: "").equals(McDirs.VERSIONS, true)) {
            dir
        } else {
            File(dir, McDirs.VERSIONS)
        }
        if (!vs.isDirectory) return emptyList()
        val kids = vs.listFiles() ?: return emptyList()
        val out = ArrayList<Version>()
        for (f in kids) {
            if (!f.isDirectory) continue
            val v = inspect(f) ?: continue
            out.add(v)
        }
        return out.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /** `instances/` 下的目录名。启动器把各个版本收在这里，不是 .minecraft 内部目录 */
    fun readInstances(dir: File): List<String> {
        val ins = File(dir, McDirs.INSTANCES)
        if (!ins.isDirectory) return emptyList()
        return (ins.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .map { it.name ?: "" }
            .filter { it.isNotBlank() }
            .sortedBy { it.lowercase(Locale.ROOT) }
    }

    /**
     * 验一个 `versions/<名字>/` 到底是什么。
     *
     * 返回 null 表示连目录都打不开，调用方跳过。
     */
    fun inspect(f: File): Version? {
        if (!f.isDirectory) return null
        val name = f.name ?: return null
        val kids = f.listFiles() ?: return null
        val lower = kids.map { (it.name ?: "").lowercase(Locale.ROOT) }.toSet()

        val json = kids.firstOrNull { it.isFile && (it.name ?: "").endsWith(".json", true) }
        val jar = kids.firstOrNull { it.isFile && (it.name ?: "").endsWith(".jar", true) }
        val hasIsolate = ISOLATE_MARKS.any { lower.contains(it.lowercase(Locale.ROOT)) }

        val mc = if (json != null) idFromJson(json) else ""

        val kind = when {
            hasIsolate -> VersionKind.INSTANCE
            json != null && jar != null -> VersionKind.CORE
            json != null -> VersionKind.STUB
            else -> VersionKind.UNKNOWN
        }

        val modCount = if (hasIsolate) {
            val mods = kids.firstOrNull { (it.name ?: "").equals(McDirs.MODS, true) && it.isDirectory }
            mods?.listFiles()?.count { it.isFile && (it.name ?: "").endsWith(".jar", true) } ?: 0
        } else 0

        return Version(name, f.absolutePath, kind, mc, modCount)
    }

    /**
     * 从版本描述文件里读 MC 版本。
     *
     * 官方 `<版本>.json` 的 `id` 字段就是版本号；
     * 有些启动器写的是自定义名（"1.20.1-fabric"），
     * 这里**原样返回**，不做猜测式裁剪——认不出就交给 [Loaders.normalize] 处理。
     */
    fun idFromJson(f: File): String {
        return try {
            if (f.length() > 2 * 1024 * 1024) return ""
            val o = Json.obj(f.readText()) ?: return ""
            Json.s(o, "id").trim()
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 猜这个目录在跑哪个 MC 版本。认不出返回空串。
     *
     * 顺序是「越确定的越优先」：
     * 1. `versions/` 里**装了模组**的那个 —— 版本隔离时它才是真正在玩的那个
     * 2. `versions/` 里第一个有 .json 的
     * 3. 认不出
     *
     * ⚠️ 只是给界面显示用的提示，不参与迁移决策。
     * 迁移时的版本以用户在迁移页选的为准。
     */
    fun mcVersionOf(dir: File, versions: List<Version> = readVersions(dir)): String {
        versions.firstOrNull { it.kind == VersionKind.INSTANCE && it.mcVersion.isNotBlank() }
            ?.let { return it.mcVersion }
        versions.firstOrNull { it.mcVersion.isNotBlank() }?.let { return it.mcVersion }
        return ""
    }

    /** 一句话结论。写给用户看的，不写技术细节 */
    private fun reason(
        kind: Kind,
        marks: List<String>,
        versions: List<Version>,
        instances: List<String>,
        mc: String,
        childCount: Int
    ): String {
        val mcTip = if (mc.isNotBlank()) "，MC $mc" else ""
        return when (kind) {
            Kind.MINECRAFT -> buildString {
                append("这是游戏目录")
                if (marks.isNotEmpty()) append("（有 ${marks.joinToString("、")}）")
                if (versions.isNotEmpty()) {
                    val iso = versions.count { it.kind == VersionKind.INSTANCE }
                    append("，versions 下 ${versions.size} 个")
                    if (iso > 0) append("、其中 $iso 个装了模组")
                }
                append(mcTip)
            }

            Kind.PARENT -> buildString {
                append("这是启动器的总目录")
                if (instances.isNotEmpty()) append("，instances 下 ${instances.size} 个")
                if (versions.isNotEmpty()) append("，versions 下 ${versions.size} 个")
                append(mcTip)
            }

            Kind.NONE ->
                if (childCount == 0) "这是个空目录，里面什么都没有"
                else "这个目录里没有 mods / saves / versions，不是游戏目录"
        }
    }

    /** 一句短话，给列表项副标题用 */
    fun shortOf(r: Result): String {
        if (!r.usable) return r.reason
        val iso = r.isolatedCount
        return when {
            r.instances.isNotEmpty() -> "总目录 · ${r.instances.size} 个实例"
            iso > 0 -> "versions · $iso 个装了模组"
            r.versions.isNotEmpty() -> "versions · ${r.versions.size} 个游戏核心"
            else -> r.marks.joinToString("、").ifBlank { "游戏目录" }
        }
    }

    // ══════════════════════════════════════════════════════
    //  SAF 版：用户通过系统文件选择器选中的目录
    // ══════════════════════════════════════════════════════

    /**
     * SAF 版探测。
     *
     * 只做「是不是游戏目录」和「versions 下有什么」两件事，
     * 结构判断规则和 File 版**完全一致**——以前两版规则不同，
     * 同一个目录走 SAF 和走 File 会得出相反结论。
     */
    fun ofTree(ctx: Context, dir: DocumentFile?): Result {
        if (dir == null || !dir.isDirectory) {
            return Result(dir?.uri?.toString() ?: "", Kind.NONE, reason = "目录打不开")
        }
        val kids = Fs.children(dir)
        val lower = kids.map { (it.name ?: "").lowercase(Locale.ROOT) }.toSet()
        val marks = McDirs.ROOT_MARKS.filter { m -> lower.contains(m.lowercase(Locale.ROOT)) }

        val name = (dir.name ?: "").lowercase(Locale.ROOT)
        val byName = name == ".minecraft" || name == "minecraft"

        val versions = readVersionsTree(ctx, dir)
        val instances = kids.firstOrNull { (it.name ?: "").equals(McDirs.INSTANCES, true) }
            ?.let { Fs.children(it).filter { c -> c.isDirectory }.map { c -> c.name ?: "" } }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val kind = when {
            byName || marks.isNotEmpty() -> Kind.MINECRAFT
            instances.isNotEmpty() || versions.isNotEmpty() -> Kind.PARENT
            else -> Kind.NONE
        }
        val mc = mcVersionOfTree(versions)
        return Result(
            path = dir.uri.toString(),
            kind = kind,
            marks = marks,
            versions = versions,
            instances = instances,
            mcVersion = mc,
            reason = reason(kind, marks, versions, instances, mc, kids.size)
        )
    }

    private fun readVersionsTree(ctx: Context, dir: DocumentFile): List<Version> {
        val vs = if ((dir.name ?: "").equals(McDirs.VERSIONS, true)) {
            dir
        } else {
            Fs.children(dir).firstOrNull { (it.name ?: "").equals(McDirs.VERSIONS, true) }
        } ?: return emptyList()
        val out = ArrayList<Version>()
        for (c in Fs.children(vs)) {
            if (!c.isDirectory) continue
            val v = inspectTree(ctx, c) ?: continue
            out.add(v)
        }
        return out.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun inspectTree(ctx: Context, f: DocumentFile): Version? {
        val name = f.name ?: return null
        val kids = Fs.children(f)
        val lower = kids.map { (it.name ?: "").lowercase(Locale.ROOT) }.toSet()
        val json = kids.firstOrNull { it.isFile && (it.name ?: "").endsWith(".json", true) }
        val jar = kids.firstOrNull { it.isFile && (it.name ?: "").endsWith(".jar", true) }
        val hasIsolate = ISOLATE_MARKS.any { lower.contains(it.lowercase(Locale.ROOT)) }

        val mc = if (json != null) {
            try {
                val o = Json.obj(Fs.readText(ctx, json))
                Json.s(o, "id").trim()
            } catch (t: Throwable) {
                ""
            }
        } else ""

        val kind = when {
            hasIsolate -> VersionKind.INSTANCE
            json != null && jar != null -> VersionKind.CORE
            json != null -> VersionKind.STUB
            else -> VersionKind.UNKNOWN
        }
        val modCount = if (hasIsolate) {
            kids.firstOrNull { (it.name ?: "").equals(McDirs.MODS, true) }
                ?.let { Fs.children(it).count { c -> c.isFile && (c.name ?: "").endsWith(".jar", true) } }
                ?: 0
        } else 0
        return Version(name, f.uri.toString(), kind, mc, modCount)
    }

    private fun mcVersionOfTree(versions: List<Version>): String {
        versions.firstOrNull { it.kind == VersionKind.INSTANCE && it.mcVersion.isNotBlank() }
            ?.let { return it.mcVersion }
        versions.firstOrNull { it.mcVersion.isNotBlank() }?.let { return it.mcVersion }
        return ""
    }
}
