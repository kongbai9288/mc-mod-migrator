package com.kongbai.modmigrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录层级判定的回归测试。
 *
 * 为什么单开一个：选错层级**不报错也不崩溃**，只是静悄悄地迁移 0 个模组，
 * 或者把别的版本的模组搬过来。界面上表现为"功能没反应"，
 * 没人会怀疑是目录选高了一层。
 *
 * 这里造的是 [McProbe.Node] 树，不需要 Android 环境，CI 上能真跑。
 * 结构照着实际见过的情况来：
 *  - 版本隔离：`.minecraft/versions/<名字>/mods`（HMCL、FCL 都这样）
 *  - 不隔离：`.minecraft/mods` + `.minecraft/versions/<版本>.jar`
 *  - 启动器根：`FCL/.minecraft`
 */
class McProbeTest {

    private fun d(name: String, vararg kids: McProbe.Node) =
        McProbe.Node(name, true, kids.toList())

    private fun f(name: String) = McProbe.Node(name, false)

    // ── 选对了：直接是游戏版本目录 ────────────────────────

    @Test
    fun instanceLayerIsUsable() {
        val root = d(
            ".minecraft",
            d("mods", f("sodium.jar"), f("iris.jar")),
            d("saves", d("新的世界", f("level.dat"))),
            d("config", f("sodium.json"))
        )
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.INSTANCE, r.kind)
        assertTrue(r.usable)
        assertEquals(2, r.mods)
        assertEquals(1, r.saves)
        assertEquals("", r.suggest)
        assertFalse(r.empty())
    }

    // ── 没开版本隔离的 .minecraft 根 ──────────────────────

    @Test
    fun plainRootIsUsableAndCountsRootMods() {
        val root = d(
            ".minecraft",
            d("mods", f("jei.jar")),
            // versions 里只有游戏核心，不是实例
            d("versions", d("1.20.1", f("1.20.1.jar"), f("1.20.1.json")))
        )
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.PLAIN_ROOT, r.kind)
        assertTrue(r.usable)
        assertEquals(1, r.mods)
        // 游戏核心不能算成实例
        assertTrue(r.isolated().isEmpty())
        assertEquals(1, r.versions.size)
        assertFalse(r.versions[0].isolated)
    }

    // ── 开版本隔离：最危险的一种 ──────────────────────────

    @Test
    fun isolatedRootIsNotUsable() {
        val root = d(
            ".minecraft",
            d("versions",
                d("1.20.1", d("mods", f("sodium.jar")), d("saves", d("w", f("level.dat")))),
                d("1.21.1", d("mods", f("iris.jar")))
            )
        )
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.ISOLATED_ROOT, r.kind)
        // 关键：不能判成可用，否则会迁到根目录那 0 个模组
        assertFalse(r.usable)
        assertEquals(0, r.mods)
        assertEquals(2, r.isolated().size)
        assertEquals(1, r.isolated()[0].mods)
        assertEquals(1, r.isolated()[0].saves)
        // 有两个版本，不能只推荐其中一个
        assertEquals("versions/<版本名>", r.suggest)
    }

    @Test
    fun isolatedRootWithRootModsWarnsAboutMixing() {
        // 根目录也有 mods，同时 versions 下也有 —— 直接用会把版本混在一起
        val root = d(
            ".minecraft",
            d("mods", f("old.jar")),
            d("versions", d("1.20.1", d("mods", f("sodium.jar"))))
        )
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.ISOLATED_ROOT, r.kind)
        assertFalse(r.usable)
        assertEquals(1, r.mods)
        assertTrue(r.summary.contains("混在一起"))
    }

    @Test
    fun severalIsolatedVersionsSuggestPlaceholder() {
        // 多个版本时不能只推荐其中一个，给占位符让用户自己选
        val root = d(
            ".minecraft",
            d("versions",
                d("a", d("mods", f("x.jar"))),
                d("b", d("mods", f("y.jar")))
            )
        )
        val r = McProbe.report(root)
        assertEquals("versions/<版本名>", r.suggest)
    }

    // ── 选高了：启动器目录 ────────────────────────────────

    @Test
    fun launcherRootSuggestsGoingDeeper() {
        val root = d(
            "FCL",
            d(".minecraft", d("mods", f("sodium.jar")))
        )
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.LAUNCHER_ROOT, r.kind)
        assertFalse(r.usable)
        assertEquals(".minecraft", r.suggest)
    }

    @Test
    fun launcherRootAlsoMatchesUnnamedGameDir() {
        // 有些启动器实例目录不叫 .minecraft，靠内容认
        val root = d("instances", d("我的整合包", d("mods", f("sodium.jar"))))
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.LAUNCHER_ROOT, r.kind)
        assertEquals("我的整合包", r.suggest)
    }

    // ── 完全不是 ──────────────────────────────────────────

    @Test
    fun unrelatedDirIsNotMc() {
        val root = d("Download", d("pdf", f("a.pdf")), f("b.txt"))
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.NOT_MC, r.kind)
        assertFalse(r.usable)
        assertTrue(r.empty())
    }

    // ── 计数细节 ──────────────────────────────────────────

    @Test
    fun disabledModsStillCount() {
        // 禁用后的模组还是模组，漏掉会让"我有 30 个模组"变成 28 个
        val root = d(
            ".minecraft",
            d("mods", f("a.jar"), f("b.jar.disabled"), f("c.zip.disabled"), f("readme.txt"))
        )
        assertEquals(3, McProbe.report(root).mods)
    }

    @Test
    fun savesOnlyCountsDirsWithLevelDat() {
        val root = d(
            ".minecraft",
            d("saves",
                d("真世界", f("level.dat")),
                d("空目录"),
                f("level.dat") // 散落在外的 level.dat 不算一个世界
            )
        )
        assertEquals(1, McProbe.report(root).saves)
    }

    @Test
    fun emptySourceDirIsFlagged() {
        // 空的实例目录：当目标用没问题，当源用等于空跑一趟
        val root = d("1.21.1", d("mods"))
        val r = McProbe.report(root)
        assertTrue(r.usable)
        assertTrue(r.empty())
        assertEquals(0, r.mods)
    }

    // ── versions/ 下的游戏核心也要报出来 ──────────────────

    @Test
    fun coresAreCountedNotDropped() {
        // 开了版本隔离，但只有其中一个装了模组，另两个是纯游戏核心
        val root = d(
            ".minecraft",
            d("mods"),
            d(
                "versions",
                d("1.20.1-fabric", d("mods", f("sodium.jar"))),
                d("1.16.5", f("1.16.5.jar"), f("1.16.5.json")),
                d("1.18.2", f("1.18.2.jar"), f("1.18.2.json"))
            )
        )
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.ISOLATED_ROOT, r.kind)
        assertEquals(1, r.isolated().size)
        //
        // 以前只报隔离出来的那一个，另外两个在结论里彻底消失，
        // 用户会以为自己的版本丢了。现在必须数得出来。
        //
        assertEquals(2, r.cores())
        assertEquals(3, r.versions.size)
        assertTrue(r.summary.contains("游戏核心"))
    }

    @Test
    fun plainRootTellsVersionsAreCores() {
        val root = d(
            ".minecraft",
            d("mods", f("sodium.jar")),
            d("versions", d("1.20.1", f("1.20.1.jar"), f("1.20.1.json")))
        )
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.PLAIN_ROOT, r.kind)
        assertEquals(1, r.cores())
        assertTrue(r.summary.contains("游戏核心"))
    }

    // ── Node 辅助 ─────────────────────────────────────────

    @Test
    fun childLookupIgnoresCase() {
        val root = d("x", d("MODS", f("a.jar")))
        assertTrue(root.child("mods") != null)
        assertTrue(root.names().contains("mods"))
    }

    // ── MC 版本：从 versions/<名字>/*.json 的 id 认 ──────────

    /** 造一个带版本描述文件的版本目录 */
    private fun vjson(name: String, id: String, vararg kids: McProbe.Node): McProbe.Node {
        val json = McProbe.Node("$name.json", false, emptyList(),
            "{\"id\": \"$id\", \"type\": \"release\"}")
        val all = kids.toList() + json
        return d(name, *all.toTypedArray())
    }

    @Test
    fun jsonIdReadsTopLevelId() {
        assertEquals("1.20.1", McProbe.jsonId("{\"id\": \"1.20.1\"}"))
        assertEquals("1.21.4", McProbe.jsonId("{\n  \"id\" : \"1.21.4\",\n  \"type\": \"release\"\n}"))
    }

    @Test
    fun jsonIdIgnoresNestedAssetIndex() {
        // assetIndex 里也有 id，但它在文件后面，不能被先命中
        val text = "{\"id\":\"1.20.1\",\"assetIndex\":{\"id\":\"3\"}}"
        assertEquals("1.20.1", McProbe.jsonId(text))
    }

    @Test
    fun jsonIdReturnsBlankWhenAbsent() {
        assertEquals("", McProbe.jsonId("{}"))
        assertEquals("", McProbe.jsonId(""))
        assertEquals("", McProbe.jsonId("not json at all"))
    }

    @Test
    fun isolatedRootReportsThePlayedVersion() {
        // 版本隔离：三个版本里只有 1.20.1 装了模组，它才是真正在用的那个
        val root = d(
            ".minecraft",
            d("mods"),
            d(
                "versions",
                vjson("1.20.1", "1.20.1", d("mods", f("sodium.jar"))),
                vjson("1.19.4", "1.19.4", f("1.19.4.jar")),
                vjson("1.21", "1.21", f("1.21.jar"))
            )
        )
        val r = McProbe.report(root)
        assertEquals(McProbe.Kind.ISOLATED_ROOT, r.kind)
        assertEquals("1.20.1", r.mcVersion())
        assertTrue(r.summary.contains("MC 1.20.1"))
    }

    @Test
    fun plainRootFallsBackToAnyVersion() {
        // 没隔离时 versions/ 下全是游戏核心，没有 isolated 的那一个，
        // 这时取第一个认出版本的
        val root = d(
            ".minecraft",
            d("mods", f("sodium.jar")),
            d("versions", vjson("1.20.1", "1.20.1", f("1.20.1.jar")))
        )
        val r = McProbe.report(root)
        assertEquals("1.20.1", r.mcVersion())
    }

    @Test
    fun launcherNameIsNotTrimmed() {
        // 启动器写的自定义名原样返回，不做猜测式裁剪
        val root = d(
            ".minecraft",
            d("versions", vjson("1.20.1-fabric", "1.20.1-fabric", d("mods", f("a.jar"))))
        )
        assertEquals("1.20.1-fabric", McProbe.report(root).mcVersion())
    }

    @Test
    fun noJsonMeansBlankVersion() {
        val root = d(
            ".minecraft",
            d("versions", d("1.20.1", d("mods", f("sodium.jar"))))
        )
        assertEquals("", McProbe.report(root).mcVersion())
    }

    @Test
    fun jsonTextIsOnlyReadInsideVersions() {
        // 其它地方的 .json 不该被当成版本描述文件
        val root = d(".minecraft", d("config", McProbe.Node("sodium.json", false, emptyList(),
            "{\"id\":\"bogus\"}")))
        assertEquals("", McProbe.report(root).mcVersion())
    }
}
