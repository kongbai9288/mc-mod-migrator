package com.kongbai.modmigrator

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 各启动器的 `.minecraft` 目录在哪。
 *
 * 本文件**只有一份数据**（[TABLE]），改目录改包名改版本都只动那张表，
 * 逻辑不用碰。启动器换路径是常态（Pojav 换过、FCL 换过包名），
 * 所以目录同时记「旧」和「新」两套，两套都会去找。
 *
 * ## 每条记录都要标明证据等级（[Evidence]）
 *
 * 这份清单过去混进了凭印象写的路径和包名。应用要对外发布，
 * 显示给用户的东西不能有编造成分，所以现在分三档：
 *
 * - [Evidence.SOURCE] —— 有上游源码或官方文件佐证，[Entry.source] 写明出处
 * - [Evidence.COMMUNITY] —— 社区资料整理，**未经核实**，只能当候选去试
 * - [Evidence.RUNTIME] —— 在这台设备上探测到了，属于本机事实
 *
 * **候选路径一律"存在才算"**（见 [detect]），猜错不会误导用户，
 * 但 [Evidence.COMMUNITY] 的条目在界面上应当标明未核实，
 * 免得用户以为这是官方确认的路径。
 *
 * ⚠️ 官方 Android 11 存储更新：**SAF 无法授权 `Android/data` 及其子目录**，
 * 且从 11 起就是如此，不是 Android 14 才收紧（见 [StoreRules]）。
 * 因此 [Entry.privNew] / [Entry.privOld] 里的私有路径只能拿来看、
 * 以及交给同步器读取（见 [PrivSync]），绝不能拿去发起目录授权。
 *
 * [Entry.mcFrom] / [Entry.mcTo] 是社区整理的适配区间，
 * 会随启动器更新变化，**仅用于提示，不用来拦截操作**。
 *
 * ## 三个版本维度，各管一件事
 *
 * 用户装的可能不是最新版启动器（老手机、或刻意装旧版），
 * 所以「目录在哪」要同时看三个版本，缺一个都会找错地方：
 *
 * - [Entry.sysFrom] / [Entry.sysTo] —— **安卓系统**版本，见 [appliesToSdk]
 * - [Entry.appFrom] / [Entry.appTo] —— **启动器自身**版本，见 [appliesToApp]
 * - [Entry.mcFrom] / [Entry.mcTo] —— 支持的 MC 版本，仅用于显示
 *
 * 三个区间都遵循同一条规则：**没填或读不到就不过滤**。
 * 拿不到证据时宁可多试几个候选路径，也不能把对的路径过滤掉。
 */
object LauncherDirs {

    /**
     * 一条记录的依据有多硬。
     *
     * 对外发布后，界面上会据此决定要不要标注"未核实"。
     */
    enum class Evidence {
        /** 有上游源码 / 官方文件佐证，见 [Entry.source] */
        SOURCE,

        /** 社区资料整理，未核实 */
        COMMUNITY,

        /** 本机探测到确实存在 */
        RUNTIME,
    }

    /** 一条启动器记录。改目录只改这里。 */
    data class Entry(
        /** 显示名 */
        val name: String,
        /** 品牌 key，对应 [LauncherBrand]，用于显示图标 */
        val key: String = "",
        /** 包名（可能有多个：历史包名 + 现用包名） */
        val pkgs: List<String> = emptyList(),
        /** 旧公开目录，相对 /sdcard */
        val pubOld: List<String> = emptyList(),
        /** 新公开目录，相对 /sdcard */
        val pubNew: List<String> = emptyList(),
        /** 旧私有目录（Android/data 下），仅供显示与同步器读取 */
        val privOld: List<String> = emptyList(),
        /** 新私有目录（Android/data 下），仅供显示与同步器读取 */
        val privNew: List<String> = emptyList(),
        /** 适配的 MC 版本下限 */
        val mcFrom: String = "",
        /** 适配的 MC 版本上限，"" = 跟随最新 */
        val mcTo: String = "",
        /**
         * 这条记录适用的**安卓系统**版本下限（数字，如 "10"）。
         * 空串 = 不限。
         *
         * 启动器的目录不只会随启动器版本变，**还会随安卓版本变**：
         * PojavLauncher 就是典型——Android 9 及更早放在公开的
         * `games/PojavLauncher`，Android 10 起被迫挪进 `Android/data`
         *（来源：PojavLauncher issue #2122）。所以同一款启动器
         * 在系统版本不同时是两条记录，否则会去找一个根本不存在的目录。
         */
        val sysFrom: String = "",
        /** 适用的安卓系统版本上限，空串 = 不限 */
        val sysTo: String = "",
        /**
         * 适用的**启动器自身**版本下限（比对 PackageInfo.versionName）。
         * 空串 = 不限。
         *
         * 老版本启动器和新版本启动器的目录可能不同（换过包名、换过
         * 存储策略），同一款启动器因此可能要拆成两条记录。
         * 比较规则见 [verCmp]。
         *
         * ⚠️ 只有核到「某版本起目录变了」才填。没核到就留空——
         * 空串表示不过滤，猜一个版本区间反而会把对的路径挡掉。
         */
        val appFrom: String = "",
        /** 适用的启动器版本上限，空串 = 不限 */
        val appTo: String = "",
        /** 备注 */
        val note: String = "",
        /** 这条记录的依据有多硬 */
        val evidence: Evidence = Evidence.COMMUNITY,
        /** [Evidence.SOURCE] 时写明出处，例如上游文件路径 */
        val source: String = ""
    )

    // ══════════════════════════════════════════════════════════
    //  数据区：改目录 / 改包名 / 改版本，只改这一段
    //  路径一律相对 /sdcard（多数设备是 /storage/emulated/0）
    // ══════════════════════════════════════════════════════════
    private val TABLE = listOf(
        Entry(
            name = "FCL", key = "fcl",
            pkgs = listOf(
                "com.tungsten.fcl", "com.tungsten.fcl.debug",
                // 整合包版：上游 README 里 -DpkgName 的默认值，
                // 存在的目的就是和官方版/调试版共存，所以是第三个包名
                "com.tungsten.fcl.modpack",
                // 社区资料里还出现过这个包名，可能是旧版或另一分支；
                // 没在上游源码里核到，只当候选，探不到就不显示
                "com.foldcraft.launcher"
            ),
            pubNew = listOf("FCL/.minecraft"),
            pubOld = listOf("games/FCL/.minecraft"),
            privNew = listOf(
                "Android/data/com.tungsten.fcl/files/.minecraft",
                // 整合包版的私有目录：路径由包名决定（Android/data/<包名>/files/），
                // 这是安卓的通用规则，不是猜的路径；但里面的子目录名未核到
                "Android/data/com.tungsten.fcl.modpack/files/.minecraft"
            ),
            privOld = listOf("Android/data/com.tungsten.fcl.debug/files/.minecraft"),
            mcFrom = "1.0", mcTo = "",
            note = "开版本隔离则在 versions/<版本>/ 下；" +
                "games/FCL 那条是社区说法，未在上游源码中确认",
            evidence = Evidence.SOURCE,
            source = "FCLPath.java 的 SHARED_COMMON_DIR 与 PRIVATE_COMMON_DIR；" +
                "FCL/build.gradle.kts 的 applicationId；" +
                "整合包版包名见上游 README 的 -DpkgName 默认值"
        ),
        Entry(
            name = "Zalith Launcher 2", key = "zalith2",
            pkgs = listOf("com.movtery.zalithlauncher.v2", "com.movtery.zalithlauncher.debug"),
            privNew = listOf("Android/data/com.movtery.zalithlauncher.v2/files/.minecraft"),
            privOld = listOf("Android/data/com.movtery.zalithlauncher.debug/files/.minecraft"),
            mcFrom = "1.7.10", mcTo = "",
            note = "默认就放在私有目录；但它支持用户自定义多个游戏目录，" +
                "改过之后位置由用户决定，猜不到——请让用户在文件管理器里选",
            evidence = Evidence.SOURCE,
            source = "GamePathManager.kt 的 defaultGamePath = File(DIR_FILES_EXTERNAL, \".minecraft\")；" +
                "ZalithLauncher/build.gradle.kts 的 applicationId + \".v2\""
        ),
        Entry(
            name = "Zalith Launcher", key = "zalith",
            pkgs = listOf("com.movtery.zalithlauncher"),
            pubNew = listOf("games/ZalithLauncher/.minecraft"),
            privNew = listOf("Android/data/com.movtery.zalithlauncher/files/.minecraft"),
            mcFrom = "1.7.10", mcTo = "",
            note = "支持自定义游戏目录"
        ),
        Entry(
            name = "PojavLauncher（测试版，Android 9 起）", key = "pojav",
            pkgs = listOf("net.kdt.pojavlaunch.debug", "net.kdt.pojavlaunch"),
            privNew = listOf("Android/data/net.kdt.pojavlaunch.debug/files/.minecraft"),
            privOld = listOf("Android/data/net.kdt.pojavlaunch/files/.minecraft"),
            mcFrom = "1.0", mcTo = "1.21",
            // 分界是 API 28（Android 9），不是 Android 10。
            // 中文 Minecraft Wiki 写的是「API<28 时和正式版相同，API≥28 时挪进
            // Android/data/net.kdt.pojavlaunch.debug」；社区帖子常写成「Android
            // 10 起」，那是按大版本名称近似的说法，差了整整一个 API 等级，
            // 会让 Android 9（API 28）的用户去找一个不存在的公开目录。
            sysFrom = "9",
            note = "已停止维护，建议换 Zalith；测试版在 API 28 起挪进私有目录",
            evidence = Evidence.SOURCE,
            source = "中文 Minecraft Wiki「在安卓设备上玩MC-java版/Pojav」：" +
                "测试版 API≥28 时保存在 Android/data/net.kdt.pojavlaunch.debug/files/.minecraft"
        ),
        Entry(
            name = "PojavLauncher（正式版）", key = "pojav",
            pkgs = listOf("net.kdt.pojavlaunch"),
            pubNew = listOf("games/PojavLauncher/.minecraft"),
            pubOld = listOf("games/PojavLauncher"),
            mcFrom = "1.0", mcTo = "1.21",
            // 正式版据 Wiki 一直是 games/PojavLauncher，不限系统版本；
            // 老系统上的测试版也落在这里。这里**不设** sysTo ——
            // 候选一律「存在才算」，多列一个候选不会误导，漏掉才会。
            note = "正式版一直放在公开目录；老系统上的测试版也在这里",
            evidence = Evidence.SOURCE,
            source = "中文 Minecraft Wiki「在安卓设备上玩MC-java版/Pojav」：" +
                "正式版为内部存储/games/PojavLauncher/.minecraft"
        ),
        Entry(
            name = "Amethyst", key = "amethyst",
            pkgs = listOf("org.angelauramc.amethyst", "org.angelauramc.amethyst.debug"),
            privNew = listOf("Android/data/org.angelauramc.amethyst/files/.minecraft"),
            privOld = listOf("Android/data/org.angelauramc.amethyst.debug/files/.minecraft"),
            mcFrom = "1.14", mcTo = "",
            note = "仅私有目录"
        ),
        Entry(
            name = "HMCL PE", key = "hmcl",
            pkgs = listOf("com.tungsten.hmclpe"),
            pubNew = listOf("HMCLPE/.minecraft"),
            privNew = listOf("Android/data/com.tungsten.hmclpe/files/.minecraft"),
            mcFrom = "1.0", mcTo = "1.20",
            note = "开版本隔离则在 versions/<版本>/ 下",
            evidence = Evidence.SOURCE,
            source = "HMCL-PE 用户手册「Some tips about file location」：" +
                "HMCLPE/.minecraft，版本隔离时是 HMCLPE/.minecraft/versions/<版本名>"
        ),
        Entry(
            name = "澪 MioLauncher", key = "mio",
            pkgs = listOf("com.mio.launcher", "com.tungsten.mio"),
            pubNew = listOf("MioLauncher/.minecraft"),
            mcFrom = "1.7.10", mcTo = "",
            // 澪（澪Ultimate）**不开源**，Minecraft Wiki 的启动器条目也标了
            // 「是否开源：否」，所以拿不到上游源码佐证。
            // 包名 com.mio.launcher 来自应用市场信息，未经源码核实；
            // 目录 MioLauncher/.minecraft 是社区整理。两者都只能当候选去试。
            note = "模组放在 versions/<版本>/mods；启动器不开源，包名与目录未获源码佐证"
        ),
        Entry(
            //
            // ⚠️ AOF-Dev/Boat **不是启动器**，是给启动器用的运行库
            // （提供 JRE、GLFW、X server 等运行环境）。
            // 上游 README 原话："Boat is NOT a Minecraft launcher"。
            // 真正在用 Boat 的是 MCinaBox 和澪，所以这里没有包名可言，
            // 下面这些目录只是「用户自定义后可能落在哪」的候选。
            //
            name = "Boat（运行库，非启动器）", key = "other",
            pubNew = listOf("boat/gamedir"),
            pubOld = listOf("games/boat/.minecraft"),
            mcFrom = "1.7.10", mcTo = "",
            note = "Boat 本身不是启动器；用它的启动器是 MCinaBox 与澪，目录由各分支自定"
        ),
        Entry(
            name = "MojoLauncher / MJLauncher", key = "other",
            pkgs = listOf("com.mojolauncher.android"),
            privNew = listOf("Android/data/com.mojolauncher.android/files/mc_data"),
            mcFrom = "1.0", mcTo = "",
            note = "注意是 mc_data 不是 .minecraft"
        ),
        Entry(
            name = "MCinaBox", key = "mcinabox",
            pkgs = listOf("com.aof.mcinabox"),
            //
            // ⚠️ 之前这里写的是 pubNew = "MCinaBox/.minecraft"，是照社区说法
            // 猜的，真机上根本不存在这个目录。
            // 上游 README 里明写的是（一手证据）：
            //   运行时放到 /sdcard/Android/com.aof.mcinabox/files/MCinaBox/runtime
            //   且游戏目录可以在启动器的「Game Directory」里**自定义**
            // 也就是说它默认落在**私有目录** files/MCinaBox 下，不是 /sdcard 根。
            // 私有路径只能显示、或交给同步器读，不能拿去发起目录授权。
            //
            privNew = listOf(
                "Android/data/com.aof.mcinabox/files/MCinaBox/.minecraft",
                "Android/data/com.aof.mcinabox/files/MCinaBox/gamedir"
            ),
            // 用户如果把游戏目录自定义到了公开位置，这两条还有可能命中，
            // 留作候选（探测一律「存在才算」，猜错不会误导）
            pubNew = listOf("MCinaBox/.minecraft"),
            // 大小写两种写法在社区资料里都出现过，安卓外存多数不区分大小写，
            // 但部分 ROM 区分；两条都留，按「存在才算」探测
            pubOld = listOf("mcinaBox/.minecraft"),
            mcFrom = "1.7.10", mcTo = "1.17",
            note = "游戏目录可在启动器内自定义；默认在私有目录，需要同步器读取"
        ),

        // ── 下面几条来自旧版扫描器里写死的绝对路径清单 ──
        // 那份清单和本表重复，且没有任何证据等级，
        // 编造的路径和核过的路径混在一起显示给用户。
        // 现在收进本表并按 COMMUNITY 标注：界面上可以据此写「未核实」。
        Entry(
            name = "FoldCraftLauncher", key = "fcl",
            pubNew = listOf("FoldCraftLauncher/.minecraft"),
            mcFrom = "1.0", mcTo = "",
            note = "社区资料里的旧目录名，未在上游源码中核到",
            evidence = Evidence.COMMUNITY
        ),
        Entry(
            name = "FCL（服务端版）", key = "fcl",
            pkgs = listOf("com.tungsten.fcl.server"),
            privNew = listOf("Android/data/com.tungsten.fcl.server/files/.minecraft"),
            mcFrom = "1.0", mcTo = "",
            note = "私有目录路径由包名推导（安卓通用规则），子目录名未核到",
            evidence = Evidence.COMMUNITY
        ),
        Entry(
            name = "HMCL（games 下）", key = "hmcl",
            pubNew = listOf("games/HMCL/.minecraft"),
            mcFrom = "1.0", mcTo = "",
            note = "桌面版 HMCL 的目录名搬到安卓上的说法，未核到",
            evidence = Evidence.COMMUNITY
        ),
        Entry(
            name = "PCL", key = "other",
            pubNew = listOf("games/PCL/.minecraft"),
            mcFrom = "1.0", mcTo = "",
            note = "PCL 是桌面启动器，安卓上的目录未经核实",
            evidence = Evidence.COMMUNITY
        )
    )
    // ══════════════════════════════════════════════════════════

    /** 当前生效的表：远端更新过的用远端的，否则用内置 */
    private var entries: List<Entry> = TABLE

    /** 全部（含未安装的） */
    fun all(): List<Entry> = entries

    /** 按品牌 key 找 */
    fun byKey(key: String): Entry? = entries.firstOrNull { it.key == key }

    private fun sdcard(): File = Environment.getExternalStorageDirectory()

    private fun installed(ctx: Context, e: Entry): Boolean {
        if (e.pkgs.isEmpty()) return false
        val pm = ctx.packageManager
        return e.pkgs.any { p ->
            try { pm.getPackageInfo(p, 0) != null } catch (t: Throwable) { false }
        }
    }

    /** 该启动器已装吗 */
    fun isInstalled(ctx: Context, e: Entry): Boolean = installed(ctx, e)

    /** 适配版本的一句话，如「1.7.10 起」 */
    fun mcRange(e: Entry): String = when {
        e.mcFrom.isBlank() && e.mcTo.isBlank() -> ""
        e.mcTo.isBlank() -> "${e.mcFrom} 起"
        e.mcFrom.isBlank() -> "到 ${e.mcTo}"
        else -> "${e.mcFrom} – ${e.mcTo}"
    }

    /**
     * 这条记录在当前**安卓系统**上适用吗。
     *
     * 用户装的老版本启动器在新系统上、或新启动器装在老系统上，
     * 目录位置都可能不同，所以按系统版本过一遍再去找，
     * 免得去探测一个根本不存在的目录（Pojav 就是这么分开的）。
     *
     * 区间没填的（sysFrom / sysTo 都空）一律适用。
     */
    fun appliesToSdk(e: Entry, sdk: Int = android.os.Build.VERSION.SDK_INT): Boolean {
        val from = e.sysFrom.filter(Char::isDigit).toIntOrNull()?.let(::apiOf)
        val to = e.sysTo.filter(Char::isDigit).toIntOrNull()?.let(::apiOf)
        if (from != null && sdk < from) return false
        if (to != null && sdk > to) return false
        return true
    }

    /**
     * 安卓大版本号 → API 等级。
     *
     * [Entry.sysFrom] / [Entry.sysTo] 写的是「Android 10」这种人读的版本号，
     * 而 [android.os.Build.VERSION.SDK_INT] 是 API 等级（Android 10 = 29），
     * 两者不能直接比，必须先过这张表。
     * 表里没有的版本返回 null，调用方按「不过滤」处理，避免误杀。
     */
    private fun apiOf(major: Int): Int? = when (major) {
        9 -> 28
        10 -> 29
        11 -> 30
        12 -> 31   // 12L 是 32，这里按 12 算
        13 -> 33
        14 -> 34
        15 -> 35
        16 -> 36
        else -> null
    }

    /**
     * 版本号比较，给 [appliesToApp] 用。
     *
     * 启动器的 versionName 没有统一格式：FCL 是 `1.3.4.0`，
     * Zalith 是 `2.0.0_beta-20251016a`。所以按「数字段优先」比——
     * 先逐段比数字（`1.10` > `1.3`，按字符串比会正好反过来），
     * 数字段都一样再按完整字符串定序。
     * 一个数字段都解析不出来时退回字符串比较，不因为格式怪就判等。
     */
    fun verCmp(a: String, b: String): Int {
        if (a.isBlank() && b.isBlank()) return 0
        if (a.isBlank()) return -1
        if (b.isBlank()) return 1
        val pa = numParts(a)
        val pb = numParts(b)
        if (pa.isEmpty() || pb.isEmpty()) return a.compareTo(b)
        val n = minOf(pa.size, pb.size)
        for (i in 0 until n) {
            if (pa[i] != pb[i]) return if (pa[i] < pb[i]) -1 else 1
        }
        return when {
            pa.size == pb.size -> a.compareTo(b)
            pa.size < pb.size -> -1
            else -> 1
        }
    }

    /** 把版本号里的连续数字切成段：`1.3.4.0` → [1,3,4,0] */
    private fun numParts(s: String): List<Int> {
        val out = ArrayList<Int>()
        var cur = ""
        for (c in s) {
            if (c.isDigit()) cur += c
            else if (cur.isNotEmpty()) {
                out.add(cur.toIntOrNull() ?: 0)
                cur = ""
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toIntOrNull() ?: 0)
        return out
    }

    /**
     * 这台机器上装着的这个启动器是什么版本（versionName）。
     * 按 [Entry.pkgs] 顺序找，取第一个读到的。
     * 没装、或系统不给读，返回 null —— 这时 [appliesToApp] 一律放行。
     */
    fun installedVersion(ctx: Context, e: Entry): String? {
        val pm = ctx.packageManager
        for (p in e.pkgs) {
            val v = try {
                pm.getPackageInfo(p, 0)?.versionName
            } catch (t: Throwable) {
                null
            }
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    /**
     * 这条记录对**当前装着的启动器版本**适用吗。
     *
     * 和 [appliesToSdk] 同一条规则：区间没填、或读不到版本号，一律 true。
     * 用户装的是老版本还是新版本我们往往核不到证据，
     * 这时候多试几个候选路径，比把唯一对的那条过滤掉安全。
     */
    fun appliesToApp(e: Entry, ver: String?): Boolean {
        if (e.appFrom.isBlank() && e.appTo.isBlank()) return true
        if (ver.isNullOrBlank()) return true
        if (e.appFrom.isNotBlank() && verCmp(ver, e.appFrom) < 0) return false
        if (e.appTo.isNotBlank() && verCmp(ver, e.appTo) > 0) return false
        return true
    }

    /**
     * 这条记录在**这台机器**上适用吗：安卓系统版本 + 启动器版本都过一遍。
     * 探测入口统一走这里，免得哪天加了新维度漏改一处。
     */
    private fun appliesHere(ctx: Context, e: Entry): Boolean =
        appliesToSdk(e) && appliesToApp(e, installedVersion(ctx, e))

    /** 公开目录候选，新的排前面 */
    fun publicCandidates(e: Entry): List<String> = e.pubNew + e.pubOld

    /** 私有目录候选，新的排前面 */
    fun privateCandidates(e: Entry): List<String> = e.privNew + e.privOld

    /**
     * 探测：返回**确实存在**的公开目录。
     * 按「启动器已安装」优先排序。
     *
     * 签名保持不变，设置页与工具箱依赖它。
     */
    fun detect(ctx: Context): List<Pair<Entry, File>> {
        val root = sdcard()
        val out = ArrayList<Pair<Entry, File>>()
        for (e in entries.filter { appliesHere(ctx, it) }) {
            val f = publicCandidates(e).map { File(root, it) }.firstOrNull { it.isDirectory }
                ?: continue
            out.add(e to f)
            if (installed(ctx, e)) {
                val i = out.indexOfFirst { it.first === e }
                if (i > 0) {
                    val item = out.removeAt(i)
                    out.add(0, item)
                }
            }
        }
        return out
    }

    /**
     * 完整探测：公开目录存在就给出来，不存在但启动器已装的
     * 也列出来（这时只有私有目录，要走同步器）。
     */
    fun detectAll(ctx: Context): List<LauncherAccess.Found> {
        val root = sdcard()
        val out = ArrayList<LauncherAccess.Found>()
        for (e in entries.filter { appliesHere(ctx, it) }) {
            val ins = installed(ctx, e)
            val pub = publicCandidates(e).map { File(root, it) }.firstOrNull { it.isDirectory }
            if (pub != null) {
                out.add(LauncherAccess.Found(e, pub.absolutePath, LauncherAccess.Kind.PUBLIC, ins))
                continue
            }
            if (ins && privateCandidates(e).isNotEmpty()) {
                out.add(
                    LauncherAccess.Found(
                        e, privateCandidates(e).first(),
                        LauncherAccess.Kind.PRIVATE, true
                    )
                )
            }
        }
        return out.sortedWith(compareBy({ it.installed.not().toString() }, { it.entry.name }))
    }

    /** 该目录是不是一个真正的 .minecraft（有 mods / saves / versions 之一） */
    fun looksLikeMinecraft(f: File): Boolean {
        if (!f.isDirectory) return false
        val names = f.list()?.toSet() ?: return false
        return names.any { McDirs.ROOT_MARKS.any { m -> it.equals(m, true) } }
    }

    /**
     * 从远端补丁更新目录表。
     * 格式：[{"name":"…","pubNew":["…"],"privNew":["…"],"pkgs":["…"],"mcFrom":"…","mcTo":"…","note":"…"}, …]
     * 解析失败一律保留内置表 —— 补丁坏了不能让用户没得选。
     */
    fun updateFrom(json: String): Boolean {
        //
        // ⚠️ 这里原来是 org.json.JSONArray。
        // JVM 单元测试里 Android 的 org.json 是**空壳**：build.gradle 开了
        // `unitTests.returnDefaultValues = true`，桩方法不抛异常而是返回默认值，
        // 于是 `JSONArray("not json at all")` 不报错、`length()` 直接给 0。
        // 后果有两层：
        //   1. 坏补丁不会返回 false，坏数据照样写进表（测试里表现为 3 条用例挂掉）；
        //   2. 更要命的是 length()=0 —— 好补丁也会被当成空数组，
        //      一条都不解析，等于下发补丁永远是"什么都没发生"。
        // 换成项目里已经在用的 Gson（纯 Java，无 Android 依赖），
        // 解析失败就是失败，坏输入挡得住，这段逻辑也终于能在 JVM 上被真实测到。
        //
        val arr = Json.arr(json) ?: return false
        return try {
            val list = ArrayList<Entry>(TABLE)
            for (i in 0 until arr.size()) {
                val el = arr.get(i)
                if (el == null || !el.isJsonObject) continue
                val o = el.asJsonObject
                val name = Json.s(o, "name").trim()
                if (name.isBlank()) continue
                fun strs(k: String): List<String> = Json.sa(o, k).map { it.trim() }
                    .filter { it.isNotEmpty() }
                val e = Entry(
                    name = name,
                    key = Json.s(o, "key").trim(),
                    pkgs = strs("pkgs"),
                    pubOld = strs("pubOld"),
                    pubNew = strs("pubNew"),
                    privOld = strs("privOld"),
                    privNew = strs("privNew"),
                    mcFrom = Json.s(o, "mcFrom").trim(),
                    mcTo = Json.s(o, "mcTo").trim(),
                    //
                    // 下面这几个不能漏。远端补丁以前只解析目录和包名，
                    // 一旦下发过一次，Pojav 的 API 28 分界（sysFrom）、
                    // 启动器版本区间（appFrom/appTo）和证据等级都会被抹成
                    // 默认值 —— 分界没了就不过滤，等于又回到「按社区说法找目录」。
                    // 而且补丁里的路径本来就是社区整理的，默认按 COMMUNITY 记，
                    // 免得界面上把它当有源码佐证的条目显示。
                    sysFrom = Json.s(o, "sysFrom").trim(),
                    sysTo = Json.s(o, "sysTo").trim(),
                    appFrom = Json.s(o, "appFrom").trim(),
                    appTo = Json.s(o, "appTo").trim(),
                    note = Json.s(o, "note").trim(),
                    evidence = runCatching {
                        Evidence.valueOf(Json.s(o, "evidence").trim().uppercase())
                    }.getOrDefault(Evidence.COMMUNITY),
                    source = Json.s(o, "source").trim()
                )
                if (e.pubNew.isEmpty() && e.pubOld.isEmpty() &&
                    e.privNew.isEmpty() && e.privOld.isEmpty()
                ) continue
                list.removeAll { it.name == name }
                list.add(e)
            }
            if (list.isEmpty()) return false
            entries = list
            true
        } catch (t: Throwable) {
            Err.ignore(t, "更新启动器目录表")
            false
        }
    }

    /** 恢复内置表 */
    fun reset() { entries = TABLE }
}
