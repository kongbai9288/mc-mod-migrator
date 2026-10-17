package com.kongbai.modmigrator

import java.io.File

/**
 * 存档目录里每种数据放在哪 —— **旧版一套、新版一套**。
 *
 * ## 为什么单独抽一张表
 *
 * 这些目录字符串以前散在扫描、迁移、地图、NBT 各处各写一份，
 * 改一处漏一处；现在只在这张表里写一次。
 *
 * ## 证据状态（2026-10-17 核对）
 *
 * 已用真实存档样本逐版核对，样本来自两处：
 *
 * - `kongbaisever/mc-java-save-samples`：b1.3、1.2.1、1.13、1.16、1.18、1.20.5
 * - 本仓库 `ai.zip`：26.2（世界名「新的世界 (9)」，DataVersion 4903）
 *
 * [Rule.new] 里带「样本实证」字样的，是在上述样本里**逐个数出来**的，
 * 可以当事实用；没带的仍是社区转述，只作候选。
 *
 * 26.2 样本实测的顶层结构：
 * ```
 * data/minecraft/{game_rules,world_gen_settings,scoreboard,weather,…}.dat
 * dimensions/minecraft/{overworld,the_nether,the_end}/region|entities|poi
 * dimensions/minecraft/<维度>/data/minecraft/{chunk_tickets,raids,world_border}.dat
 * players/{advancements,stats}/<uuid>.json      ← 注意是 .json
 * players/data/<uuid>.dat                        ← 只有这个是 .dat
 * datapacks/  scripts/  icon.png  level.dat  session.lock
 * ```
 *
 * 由此修正了两处此前照传闻写错的路径：
 * `chunk_tickets` 与 `raids` 在 26.2 **位于维度目录之下**，
 * 不在根目录的 `data/minecraft/`（根目录那个目录下根本没有这两个文件）。
 *
 * ## 怎么改
 *
 * 只动 [RULES]。一条 Rule = 一种数据的「旧路径 / 新路径 / 从哪个版本起优先走新的」。
 * 路径相对存档根目录，用 `/` 分隔；`{id}` 是地图编号，`{ns}` 是命名空间，
 * `{dim}` 是维度名（默认 overworld）。
 * 即便版本判断错了，[candidates] 两套都给，不会漏数据。
 *
 * ## 版本判定
 *
 * [atLeast] 只做数字比较，26.2 大于 1.21.11（26 > 1）。
 * 快照号（"26.1 snapshot-6"）取空格前那段。
 * **判定不出来时两套路径都找**，宁可多找一次，也不要漏掉数据。
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

        /** 区块里的实体。1.18 起从 .mca 里拆出来单独存 */
        ENTITIES,

        /** 兴趣点：床、工作方块。1.14 起才有，取代了 villages.dat */
        POI,

        /** 村庄数据。1.13 时代叫 villages.dat，1.14 起被 [POI] 取代 */
        VILLAGES,

        /** 世界级数据目录。1.13–1.20.5 是 data/，26.2 多了一层 minecraft/ */
        WORLD_DATA,

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
        COMMAND_STORAGE,

        /** 世界生成设置。1.16 起在 level.dat 内，26.2 抽成独立文件 */
        WORLD_GEN,

        /** 存档自带数据包 */
        DATAPACKS,

        /** 脚本。26.2 样本里出现 */
        SCRIPTS,
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
        /**
         * 额外候选。主路径找不到时一并试。
         *
         * 用在哪：某些维度的文件名跟主世界不一样（末地的袭击叫
         * `raids_end.dat` 而不是 `raids.dat`），只靠一个模板写不出来。
         */
        val alt: List<String> = emptyList(),
        val note: String = ""
    )

    // ══════════════════════════════════════════════════════════
    //  数据区：目录再变，只改这一段
    //  路径一律相对存档根目录（saves/<世界>/）
    //  标注「样本实证」的是在 b1.3 / 1.2.1 / 1.13 / 1.16 / 1.18 /
    //  1.20.5 / 26.2 这七个真实存档里数出来的，改之前先想清楚。
    //
    //  七个样本实测出来的版本对照（✅ = 该版本样本里确实有）：
    //
    //  目录/文件              b1.3  1.2.1  1.13  1.16  1.18  1.20.5  26.2
    //  ─────────────────────────────────────────────────────────────────
    //  region/               .mcr   .mca   ✅    ✅    ✅    ✅      —
    //  dimensions/.../region  —      —      —     —     —     —      ✅
    //  entities/              —      —      —     —     ✅    ✅      —
    //  dimensions/.../entities —     —      —     —     —     —      ✅
    //  poi/                   —      —      —     ✅    ✅    ✅      ✅(维度下)
    //  data/villages*.dat     —      —      ✅    —     —     —      —
    //  data/*.dat             —      —      ✅    ✅    ✅    ✅      —
    //  data/minecraft/*.dat   —      —      —     —     —     —      ✅
    //  DIM-1/ DIM1/           —      ✅     ✅    ✅    ✅    ✅      —
    //  dimensions/the_nether  —      —      —     —     —     —      ✅
    //  playerdata/            —      —      ✅*   ✅*   ✅*   ✅*     —
    //  players/data/          —      —      —     —     —     —      ✅
    //  players/*.json         —      —      —     —     —     —      ✅
    //  datapacks/             —      —      ✅    ✅    ✅    ✅      ✅
    //  scripts/               —      —      —     —     —     —      ✅
    //
    //  * playerdata 是 README 说法，样本里没出现（全新世界没人进去过），
    //    所以这条不算实证，只作候选。
    //
    //  两个由此定下的变迁点：
    //  1. 1.14 起 villages.dat 被 poi/ 取代；1.18 起实体拆出到 entities/
    //  2. 26.2 把维度目录换成 dimensions/minecraft/<名字>/，
    //     并把世界级 .dat 收进 data/minecraft/ 这一层
    // ══════════════════════════════════════════════════════════
    private val RULES = listOf(
        Rule(
            Kind.OVERWORLD, "主世界区块",
            old = "region",
            new = "dimensions/minecraft/overworld/region",
            since = "26.1",
            note = "样本实证：26.2 在此；1.20.5 及更早都在根目录 region"
        ),
        Rule(
            Kind.NETHER, "下界区块",
            old = "DIM-1/region",
            new = "dimensions/minecraft/the_nether/region",
            since = "26.1",
            note = "样本实证：26.2 目录名是 the_nether；1.16–1.20.5 是 DIM-1"
        ),
        Rule(
            Kind.END, "末地区块",
            old = "DIM1/region",
            new = "dimensions/minecraft/the_end/region",
            since = "26.1",
            note = "样本实证：26.2 目录名是 the_end；1.16–1.20.5 是 DIM1"
        ),
        Rule(
            Kind.PLAYER_DATA, "玩家数据",
            old = "playerdata",
            new = "players/data",
            since = "26.1",
            note = "样本实证：26.2 是 players/data/<uuid>.dat"
        ),
        Rule(
            Kind.PLAYER_ADV, "玩家进度",
            old = "advancements",
            new = "players/advancements",
            since = "26.1",
            note = "样本实证：26.2 是 players/advancements/<uuid>.json，后缀变 json"
        ),
        Rule(
            Kind.PLAYER_STATS, "玩家统计",
            old = "stats",
            new = "players/stats",
            since = "26.1",
            note = "样本实证：26.2 是 players/stats/<uuid>.json，后缀变 json"
        ),
        Rule(
            Kind.ENTITIES, "区块实体",
            old = "entities",
            new = "dimensions/minecraft/{dim}/entities",
            since = "26.1",
            note = "样本实证：1.18 与 1.20.5 在根目录 entities/；" +
                "26.2 挪到每个维度下；1.16 及更早根本没有（实体内嵌在 .mca 里）"
        ),
        Rule(
            Kind.POI, "兴趣点",
            old = "poi",
            new = "dimensions/minecraft/{dim}/poi",
            since = "26.1",
            note = "样本实证：1.16 / 1.18 / 1.20.5 在根目录 poi/；" +
                "26.2 挪到每个维度下；1.13 及更早没有（那时代是 villages.dat）"
        ),
        Rule(
            Kind.VILLAGES, "村庄数据",
            old = "data/villages.dat",
            new = "",
            since = "",
            alt = listOf("data/villages_end.dat", "data/villages_nether.dat"),
            note = "样本实证：只有 1.13 有这三个文件；1.14 起被 poi/ 取代，" +
                "1.16 及之后的样本里一个都没有。new 留空表示新版下这种数据已不存在"
        ),
        Rule(
            Kind.WORLD_DATA, "世界级数据目录",
            old = "data",
            new = "data/minecraft",
            since = "26.1",
            note = "样本实证：1.13–1.20.5 的 raids/villages 直接躺在 data/ 下；" +
                "26.2 多了一层 minecraft/（data/minecraft/game_rules.dat 等）。" +
                "模组数据也随之挪到 data/<命名空间>/，样本里就有 " +
                "data/shulkerboxtooltip_ender_chest_cache.dat"
        ),
        Rule(
            Kind.CHUNK_TICKETS, "强制加载区块",
            old = "data/chunks.dat",
            new = "dimensions/minecraft/{dim}/data/minecraft/chunk_tickets.dat",
            since = "26.1",
            note = "样本实证：26.2 在每个维度各自的 data/minecraft/ 下，" +
                "根目录 data/minecraft/ 里没有这个文件"
        ),
        Rule(
            Kind.RAIDS, "袭击数据",
            old = "data/raids.dat",
            new = "dimensions/minecraft/{dim}/data/minecraft/raids.dat",
            since = "26.1",
            alt = listOf(
                "data/raids_end.dat",
                "DIM-1/data/raids.dat",
                "DIM1/data/raids_end.dat"
            ),
            note = "样本实证：主世界叫 raids.dat，末地叫 raids_end.dat —— " +
                "三个样本（1.16/1.18/1.20.5）都是这个命名。" +
                "此前这里写死 raids_end.dat，会漏掉主世界那份"
        ),
        Rule(
            Kind.WORLD_GEN, "世界生成设置",
            old = "",
            new = "data/minecraft/world_gen_settings.dat",
            since = "26.1",
            note = "样本实证：26.2 抽成独立文件；" +
                "1.16–1.20.5 是 level.dat 里的 WorldGenSettings 复合，没有单独文件"
        ),
        Rule(
            Kind.DATAPACKS, "存档数据包",
            old = "datapacks",
            new = "datapacks",
            since = "",
            note = "样本实证：26.2 与 1.13+ 都在根目录，位置没变"
        ),
        Rule(
            Kind.SCRIPTS, "脚本",
            old = "",
            new = "scripts",
            since = "26.1",
            note = "样本实证：26.2 根目录有 scripts/；更老的版本没有"
        ),
        // ── 以下三种手头没有样本，仍是候选，别当事实 ──
        Rule(
            Kind.WORLD_PACK, "世界资源包",
            old = "resources.zip",
            new = "resourcepacks/resources.zip",
            since = "26.1",
            note = "无样本：七个样本里都没有 resources.zip，未获证实"
        ),
        Rule(
            Kind.GENERATED, "结构方块结构",
            old = "generated/{ns}/structures",
            new = "generated/{ns}/structure",
            since = "26.1",
            note = "无样本：未获证实"
        ),
        Rule(
            Kind.MAP_INDEX, "地图编号计数",
            old = "data/idcounts.dat",
            new = "data/minecraft/maps/last_id.dat",
            since = "26.1",
            note = "无样本：未获证实"
        ),
        Rule(
            Kind.MAP_ITEM, "地图数据",
            old = "data/map_{id}.dat",
            new = "data/minecraft/maps/{id}.dat",
            since = "26.1",
            note = "无样本：未获证实"
        ),
        Rule(
            Kind.COMMAND_STORAGE, "命令存储",
            old = "data/command_storage_{ns}.dat",
            new = "data/{ns}/command_storage.dat",
            since = "26.1",
            note = "无样本：未获证实"
        )
    )
    // ══════════════════════════════════════════════════════════

    fun rule(kind: Kind): Rule? = RULES.firstOrNull { it.kind == kind }

    /** 全部规则，给设置页「内置数据」那类地方列出来看 */
    fun all(): List<Rule> = RULES

    /**
     * 版本比较。只比数字段，26.2 > 1.21.11。
     * 认不出来的段一律按 0 处理，不会抛异常。
     */
    fun cmp(a: String, b: String): Int {
        fun parts(s: String): List<Int> {
            val t = s.trim().substringBefore(" ")
            val segs = t.split(".")
                .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
                .toMutableList()
            // Beta（b1.3 / Beta 1.3）整体早于正式版 1.x。
            // 不加这句的话 "b1.3" 会被拆成 [1,3]，反而比 "1.2" 的 [1,2] 大，
            // 于是 Beta 存档被当成新版 —— 表现就是去找 .mca 而漏掉真实的 .mcr。
            if (t.firstOrNull() == 'b' || t.firstOrNull() == 'B') segs.add(0, 0)
            return segs
        }
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

    /**
     * 是否进入保守模式。
     *
     * 版本认不出（空串、解析失败）时返回 true，调用方应当：
     * **整棵目录原样复制，不改名、不挪位、不删任何旧文件**，
     * 并在界面上提示用户先备份。
     */
    fun conservative(mcVersion: String?): Boolean = mcVersion.isNullOrBlank()

    /**
     * 区域文件的后缀。
     *
     * 样本实证：b1.3 是 `.mcr`（Region 格式），1.2.1 起换成 `.mca`（Anvil）。
     * 只认 `.mca` 会把 Beta 时代的存档整个漏掉。
     *
     * 返回的是候选，按「该版本最可能的排前面」排序，调用方逐个试。
     */
    fun regionExtensions(mcVersion: String?): List<String> =
        if (atLeast(mcVersion, "1.2")) listOf("mca", "mcr") else listOf("mcr", "mca")

    /** 填掉路径里的 {id} / {ns} / {dim} */
    private fun fill(
        tpl: String,
        id: String,
        ns: String,
        dim: String
    ): String = tpl
        .replace("{id}", id)
        .replace("{ns}", ns)
        .replace("{dim}", dim)

    /**
     * 该版本下这种数据**应该用**的路径。
     * 版本认不出（空串）时按旧路径返回 —— 调用方要兜底请用 [candidates]。
     * 空串表示该版本下没有这种数据（例如 1.16 的 world_gen_settings 在 level.dat 内）。
     */
    fun path(
        kind: Kind,
        mcVersion: String?,
        id: String = "",
        ns: String = "minecraft",
        dim: String = "overworld"
    ): String? {
        val r = rule(kind) ?: return null
        return if (atLeast(mcVersion, r.since)) fill(r.new, id, ns, dim) else fill(r.old, id, ns, dim)
    }

    /**
     * 候选路径：按版本把**该用的那个排前面**，另一个兜底。
     *
     * 版本认不出（空串）时两个都给，顺序按「新 → 旧」。
     * 调用方逐个找到存在的为止 —— 版本号判断错了也不会漏数据。
     * 空串候选会被丢掉，免得匹配到存档根目录。
     */
    fun candidates(
        kind: Kind,
        mcVersion: String?,
        id: String = "",
        ns: String = "minecraft",
        dim: String = "overworld"
    ): List<String> {
        val r = rule(kind) ?: return emptyList()
        val nw = fill(r.new, id, ns, dim)
        val od = fill(r.old, id, ns, dim)
        val both = if (atLeast(mcVersion, r.since)) listOf(nw, od) else listOf(od, nw)
        val alts = r.alt.map { fill(it, id, ns, dim) }
        return (both + alts).filter { it.isNotBlank() }.distinct()
    }

    /** 在存档目录里找这种数据，返回第一个真实存在的 */
    fun find(
        worldDir: File,
        kind: Kind,
        mcVersion: String?,
        id: String = "",
        ns: String = "minecraft",
        dim: String = "overworld"
    ): File? {
        for (p in candidates(kind, mcVersion, id, ns, dim)) {
            val f = File(worldDir, p)
            if (f.exists()) return f
        }
        return null
    }

    /** 同 [find]，但按拿不到 [File] 的场合用：只回相对路径 */
    fun findRelative(
        worldDir: File,
        kind: Kind,
        mcVersion: String?,
        id: String = "",
        ns: String = "minecraft",
        dim: String = "overworld"
    ): String? {
        val f = find(worldDir, kind, mcVersion, id, ns, dim) ?: return null
        return f.relativeTo(worldDir).path
    }

    /**
     * 这个目录像一个存档吗。
     *
     * 26.2 起主世界区块不在根目录，所以只认 `region` 会**漏掉新存档** ——
     * 必须连 `dimensions/minecraft/overworld/region` 一起认
     *（26.2 样本实证）。level.dat 一直都在根目录，那个仍是主力判据。
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
     * 难度在 level.dat 里的取法。
     *
     * 样本实证，**三个时代三种写法**：
     *
     * - b1.3 / 1.2.1：`Data` 里**根本没有难度字段**（那两个样本连
     *   `Difficulty` 键都没有），返回 -1 显示「未知」才是对的，
     *   不要拿 0 去冒充「和平」
     * - 1.13 – 1.20.5：`Data.Difficulty` 是 0..3 的数字（样本实测 1.13 = 1）
     * - 26.2：`Data.difficulty_settings.difficulty` 是字符串，
     *   样本实测值 `"normal"`；注意是 **settings 复数**，
     *   单数 `difficulty_setting` 是此前照传闻写的错名，这里一并兼容
     *
     * 统一回 0..3，取不到返回 -1（界面显示"未知"）。
     */
    fun difficultyOf(data: com.viaversion.nbt.tag.CompoundTag?): Int {
        val d = data ?: return -1
        d.getNumberTag("Difficulty")?.getValue()?.toInt()?.let { return it }
        for (key in listOf("difficulty_settings", "difficulty_setting")) {
            val s = d.getCompoundTag(key)
                ?.getStringTag("difficulty")?.getValue()
                ?.trim()?.lowercase()
            if (!s.isNullOrBlank()) {
                return when (s) {
                    "peaceful" -> 0
                    "easy" -> 1
                    "normal" -> 2
                    "hard" -> 3
                    else -> -1
                }
            }
        }
        return -1
    }

    /**
     * 是否硬核。
     *
     * 样本实证：1.2.1 – 1.20.5 是 `Data.hardcore`（顶层数字）；
     * 26.2 顶层没有这个键，挪进了 `difficulty_settings.hardcore`。
     * 读不到返回 null —— b1.3 样本里确实没有，不能当成 false。
     */
    fun hardcoreOf(data: com.viaversion.nbt.tag.CompoundTag?): Boolean? {
        val d = data ?: return null
        d.getNumberTag("hardcore")?.getValue()?.toInt()?.let { return it != 0 }
        for (key in listOf("difficulty_settings", "difficulty_setting")) {
            d.getCompoundTag(key)?.getNumberTag("hardcore")?.getValue()?.toInt()
                ?.let { return it != 0 }
        }
        return null
    }

    //
    // 出生点也有一样的变迁，但项目里目前没有读它的地方，
    // 等界面要用时再补：1.20.5 及更早是 SpawnX/SpawnY/SpawnZ 三个数字键，
    // 26.2 换成 spawn 复合里的 pos 数组（样本实测 [0, -60, 0]）。
    //
}
