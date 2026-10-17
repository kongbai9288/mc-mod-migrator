package com.kongbai.modmigrator

import java.io.File

/**
 * 存档目录里每种数据放在哪 —— **旧版一套、26.1 起一套**。
 *
 * ## 为什么单独抽一张表
 *
 * 26.1 snapshot-6 把整个存档格式重排了一遍：主世界区块进了
 * `dimensions/minecraft/overworld/`，玩家数据收进 `players/`，
 * `data/` 下的文件全部按命名空间分层。
 * 这些字符串以前散在扫描、迁移、地图、NBT 各处各写一份，
 * 改一处漏一处；现在只在这张表里写一次。
 *
 * ## 怎么改
 *
 * 只动 [RULES]。一条 Rule = 一种数据的「旧路径 / 新路径 / 从哪个版本起走新的」。
 * 路径相对存档根目录，用 `/` 分隔；`{id}` 是地图编号这类可变部分，`{ns}` 是命名空间。
 * Mojang 以后再挪，加一行或改一行即可，逻辑不用碰。
 *
 * ## 版本判定
 *
 * [atLeast] 只做数字比较，26.1 大于 1.21.11（26 > 1）。
 * 快照号（"26.1 snapshot-6"）取空格前那段。
 * 判定不出来时一律**两套路径都找**（[candidates]），
 * 宁可多找一次，也不要因为版本号认不出就漏掉数据。
 */
object WorldLayout {

    /** 存档里的数据种类 */
    enum class Kind {
        /** 主世界区块 */
        OVERWORLD,

        /** 下界区块 */
        NETHER,

        /** 末地区块 */
        END,

        /** 玩家背包与位置 */
        PLAYER_DATA,

        /** 玩家进度 */
        PLAYER_ADV,

        /** 玩家统计 */
        PLAYER_STATS,

        /** 世界自带资源包 */
        WORLD_PACK,

        /** 结构方块存下来的结构 */
        GENERATED,

        /** 地图编号计数 */
        MAP_INDEX,

        /** 单张地图数据 */
        MAP_ITEM,

        /** 强制加载的区块标签 */
        CHUNK_TICKETS,

        /** 袭击数据 */
        RAIDS,

        /** 命令存储 */
        COMMAND_STORAGE
    }

    /**
     * 一条对照规则。
     *
     * @param since 从哪个 MC 版本起走 [new]；空串表示只看 [old]
     */
    data class Rule(
        val kind: Kind,
        val label: String,
        val old: String,
        val new: String,
        val since: String,
        val note: String = ""
    )

    // ══════════════════════════════════════════════════════════
    //  数据区：目录再变，只改这一段
    //  路径一律相对存档根目录（saves/<世界>/）
    // ══════════════════════════════════════════════════════════
    private val RULES = listOf(
        Rule(
            Kind.OVERWORLD, "主世界区块",
            old = "region",
            new = "dimensions/minecraft/overworld/region",
            since = "26.1",
            note = "26.1 前主世界区块就在存档根目录"
        ),
        Rule(
            Kind.NETHER, "下界区块",
            old = "DIM-1/region",
            new = "dimensions/minecraft/the_nether/region",
            since = "26.1",
            note = "26.1 前是 DIM-1，且不带命名空间"
        ),
        Rule(
            Kind.END, "末地区块",
            old = "DIM1/region",
            new = "dimensions/minecraft/the_end/region",
            since = "26.1",
            note = "26.1 前是 DIM1"
        ),
        Rule(
            Kind.PLAYER_DATA, "玩家数据",
            old = "playerdata",
            new = "players/data",
            since = "26.1",
            note = "26.1 把 playerdata 收进 players/"
        ),
        Rule(
            Kind.PLAYER_ADV, "玩家进度",
            old = "advancements",
            new = "players/advancements",
            since = "26.1"
        ),
        Rule(
            Kind.PLAYER_STATS, "玩家统计",
            old = "stats",
            new = "players/stats",
            since = "26.1"
        ),
        Rule(
            Kind.WORLD_PACK, "世界资源包",
            old = "resources.zip",
            new = "resourcepacks/resources.zip",
            since = "26.1",
            note = "26.1 起和玩家加的材质包放在同一层"
        ),
        Rule(
            Kind.GENERATED, "结构方块结构",
            old = "generated/{ns}/structures",
            new = "generated/{ns}/structure",
            since = "26.1",
            note = "注意是 structures → structure"
        ),
        Rule(
            Kind.MAP_INDEX, "地图编号计数",
            old = "data/idcounts.dat",
            new = "data/minecraft/maps/last_id.dat",
            since = "26.1"
        ),
        Rule(
            Kind.MAP_ITEM, "地图数据",
            old = "data/map_{id}.dat",
            new = "data/minecraft/maps/{id}.dat",
            since = "26.1",
            note = "26.1 起文件名只剩编号"
        ),
        Rule(
            Kind.CHUNK_TICKETS, "强制加载区块",
            old = "data/chunks.dat",
            new = "data/minecraft/chunk_tickets.dat",
            since = "26.1"
        ),
        Rule(
            Kind.RAIDS, "袭击数据",
            old = "data/raids_end.dat",
            new = "data/minecraft/raids.dat",
            since = "26.1",
            note = "26.1 起末地不再单独一个文件"
        ),
        Rule(
            Kind.COMMAND_STORAGE, "命令存储",
            old = "data/command_storage_{ns}.dat",
            new = "data/{ns}/command_storage.dat",
            since = "26.1",
            note = "26.1 起命名空间变成一层目录"
        )
    )
    // ══════════════════════════════════════════════════════════

    fun rule(kind: Kind): Rule? = RULES.firstOrNull { it.kind == kind }

    /** 全部规则，给设置页「内置数据」那类地方列出来看 */
    fun all(): List<Rule> = RULES

    /**
     * 版本比较。只比数字段，26.1 > 1.21.11。
     * 认不出来的段一律按 0 处理，不会抛异常。
     */
    fun cmp(a: String, b: String): Int {
        fun parts(s: String): List<Int> =
            s.trim().substringBefore(" ").split(".")
                .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** v 是否不早于 since。任一为空时返回 false（保守，走两套路径） */
    fun atLeast(v: String?, since: String): Boolean {
        if (v.isNullOrBlank() || since.isBlank()) return false
        return cmp(v, since) >= 0
    }

    /** 填掉路径里的 {id} / {ns} */
    private fun fill(tpl: String, id: String, ns: String): String =
        tpl.replace("{id}", id).replace("{ns}", ns)

    /**
     * 该版本下这种数据**应该用**的路径。
     * 版本认不出（空串）时按旧路径返回 —— 调用方要兜底请用 [candidates]。
     */
    fun path(kind: Kind, mcVersion: String?, id: String = "", ns: String = "minecraft"): String? {
        val r = rule(kind) ?: return null
        return if (atLeast(mcVersion, r.since)) fill(r.new, id, ns) else fill(r.old, id, ns)
    }

    /**
     * 候选路径：按版本把**该用的那个排前面**，另一个兜底。
     *
     * 版本认不出（空串）时两个都给，顺序按「新 → 旧」。
     * 调用方逐个找到存在的为止 —— 版本号判断错了也不会漏数据。
     */
    fun candidates(
        kind: Kind,
        mcVersion: String?,
        id: String = "",
        ns: String = "minecraft"
    ): List<String> {
        val r = rule(kind) ?: return emptyList()
        val nw = fill(r.new, id, ns)
        val od = fill(r.old, id, ns)
        return if (atLeast(mcVersion, r.since)) listOf(nw, od) else listOf(od, nw)
    }

    /** 在存档目录里找这种数据，返回第一个真实存在的 */
    fun find(
        worldDir: File,
        kind: Kind,
        mcVersion: String?,
        id: String = "",
        ns: String = "minecraft"
    ): File? {
        for (p in candidates(kind, mcVersion, id, ns)) {
            val f = File(worldDir, p)
            if (f.exists()) return f
        }
        return null
    }

    /** 同 [find]，但按 [DocumentFile] 之外拿不到 File 的场合用：只回相对路径 */
    fun findRelative(
        worldDir: File,
        kind: Kind,
        mcVersion: String?,
        id: String = "",
        ns: String = "minecraft"
    ): String? {
        val f = find(worldDir, kind, mcVersion, id, ns) ?: return null
        return f.relativeTo(worldDir).path
    }

    /**
     * 这个目录像一个存档吗。
     *
     * 26.1 起主世界区块不在根目录，所以只认 `region` 会**漏掉新存档** ——
     * 必须连 `dimensions/minecraft/overworld/region` 一起认。
     * level.dat 一直都在根目录，那个仍是主力判据。
     */
    fun looksLikeWorld(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (File(dir, "level.dat").exists()) return true
        for (p in candidates(Kind.OVERWORLD, null)) {
            if (File(dir, p).isDirectory) return true
        }
        return false
    }

    /**
     * 难度在 level.dat 里的取法：26.1 起换成字符串并挪进 difficulty_setting。
     *
     * 旧：`Data.Difficulty` 是 0..3 的数字
     * 新：`Data.difficulty_setting.difficulty` 是 peaceful/easy/normal/hard
     *
     * 统一回 0..3，取不到返回 -1（界面显示"未知"）。
     */
    fun difficultyOf(data: com.viaversion.nbt.tag.CompoundTag?): Int {
        val d = data ?: return -1
        d.getNumberTag("Difficulty")?.getValue()?.toInt()?.let { return it }
        val s = d.getCompoundTag("difficulty_setting")
            ?.getStringTag("difficulty")?.getValue()
            ?.trim()?.lowercase() ?: return -1
        return when (s) {
            "peaceful" -> 0
            "easy" -> 1
            "normal" -> 2
            "hard" -> 3
            else -> -1
        }
    }
}
