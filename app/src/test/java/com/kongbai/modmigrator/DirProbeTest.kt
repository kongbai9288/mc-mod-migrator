package com.kongbai.modmigrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [DirProbe] 的目录验收。
 *
 * 只测不碰 Android 框架的纯逻辑（File 那一半）。
 * SAF 那一半要真机，但「怎么算一个版本」的规则两边共用，
 * 这里钉死了 SAF 侧也就不会跑偏。
 *
 * 专门留了用例的是这几个容易搞错的地方：
 *  - `versions/<名字>/` 里 **同时**住着游戏核心和隔离实例，必须分开认
 *  - 只有 `.json` 没有 `.jar` 的是被 inheritFrom 的依赖声明，不是实例
 *  - 版本号优先取「装了模组」的那个，而不是列表里排第一的
 */
class DirProbeTest {

    private fun tmp(): File {
        val f = File.createTempFile("probe", "")
        f.delete()
        f.mkdirs()
        return f
    }

    private fun dir(parent: File, name: String): File =
        File(parent, name).apply { mkdirs() }

    private fun file(parent: File, name: String, text: String = ""): File =
        File(parent, name).apply { parent.mkdirs(); writeText(text) }

    // ── 是不是游戏目录 ──

    @Test
    fun emptyDirIsNone() {
        val r = DirProbe.of(tmp())
        assertEquals(DirProbe.Kind.NONE, r.kind)
        assertFalse(r.usable)
        assertTrue(r.reason.contains("空目录"))
    }

    @Test
    fun fileIsNone() {
        val f = file(tmp(), "a.txt", "x")
        val r = DirProbe.of(f)
        assertEquals(DirProbe.Kind.NONE, r.kind)
        assertTrue(r.reason.contains("不是目录"))
    }

    @Test
    fun missingDirIsNone() {
        val r = DirProbe.of(File("/nonexistent/definitely/not/here"))
        assertEquals(DirProbe.Kind.NONE, r.kind)
        assertTrue(r.reason.contains("不存在"))
    }

    @Test
    fun dirWithModsIsMinecraft() {
        val d = tmp()
        dir(d, McDirs.MODS)
        dir(d, McDirs.SAVES)
        val r = DirProbe.of(d)
        assertEquals(DirProbe.Kind.MINECRAFT, r.kind)
        assertTrue(r.marks.contains(McDirs.MODS))
        assertTrue(r.marks.contains(McDirs.SAVES))
    }

    @Test
    fun dotMinecraftByNameEvenWhenEmpty() {
        val d = dir(tmp(), ".minecraft")
        val r = DirProbe.of(d)
        // 名字就是 .minecraft 的，即使内容还没生成也算游戏目录
        assertEquals(DirProbe.Kind.MINECRAFT, r.kind)
        assertTrue(r.reason.contains("游戏目录"))
    }

    @Test
    fun instancesParentIsParent() {
        val d = tmp()
        dir(d, "${McDirs.INSTANCES}/1.20.1")
        dir(d, "${McDirs.INSTANCES}/1.21")
        val r = DirProbe.of(d)
        assertEquals(DirProbe.Kind.PARENT, r.kind)
        assertEquals(2, r.instances.size)
        assertTrue(r.usable)
    }

    // ── versions/ 下三种条目 ──

    @Test
    fun coreHasJsonAndJar() {
        val mc = dir(tmp(), ".minecraft")
        val v = dir(mc, "${McDirs.VERSIONS}/1.20.1")
        file(v, "1.20.1.json", """{"id":"1.20.1"}""")
        file(v, "1.20.1.jar", "x")
        val vs = DirProbe.readVersions(mc)
        assertEquals(1, vs.size)
        assertEquals(DirProbe.VersionKind.CORE, vs[0].kind)
        assertEquals("1.20.1", vs[0].mcVersion)
        assertEquals(0, vs[0].modCount)
    }

    @Test
    fun isolatedHasMods() {
        val mc = dir(tmp(), ".minecraft")
        val v = dir(mc, "${McDirs.VERSIONS}/1.20.1-fabric")
        file(v, "1.20.1-fabric.json", """{"id":"1.20.1"}""")
        val mods = dir(v, McDirs.MODS)
        file(mods, "sodium.jar", "x")
        file(mods, "iris.jar", "x")
        val vs = DirProbe.readVersions(mc)
        assertEquals(1, vs.size)
        assertEquals(DirProbe.VersionKind.INSTANCE, vs[0].kind)
        assertEquals(2, vs[0].modCount)
        assertEquals(1, DirProbe.of(mc).isolatedCount)
    }

    @Test
    fun stubIsJsonOnly() {
        val mc = dir(tmp(), ".minecraft")
        val v = dir(mc, "${McDirs.VERSIONS}/1.20.1-forge-dep")
        file(v, "1.20.1-forge-dep.json", """{"id":"1.20.1"}""")
        val vs = DirProbe.readVersions(mc)
        // 只有 .json 没有 .jar：多半是被别的版本 inheritFrom 的依赖声明
        assertEquals(DirProbe.VersionKind.STUB, vs[0].kind)
        assertFalse(vs[0].kind == DirProbe.VersionKind.INSTANCE)
    }

    @Test
    fun emptyVersionDirIsUnknown() {
        val mc = dir(tmp(), ".minecraft")
        dir(mc, "${McDirs.VERSIONS}/junk")
        val vs = DirProbe.readVersions(mc)
        assertEquals(1, vs.size)
        assertEquals(DirProbe.VersionKind.UNKNOWN, vs[0].kind)
    }

    @Test
    fun versionsDirItselfIsAccepted() {
        // 用户可能直接选到 .minecraft/versions 这一层
        val mc = dir(tmp(), ".minecraft")
        val vs = dir(mc, McDirs.VERSIONS)
        val v = dir(vs, "1.20.1")
        file(v, "1.20.1.json", """{"id":"1.20.1"}""")
        val got = DirProbe.readVersions(vs)
        assertEquals(1, got.size)
        assertEquals("1.20.1", got[0].mcVersion)
    }

    // ── 版本号 ──

    @Test
    fun mcVersionPrefersIsolatedOverCore() {
        val mc = dir(tmp(), ".minecraft")
        // 核心排在前面（字典序 "1.16.5" < "1.20.1"）
        val a = dir(mc, "${McDirs.VERSIONS}/1.16.5")
        file(a, "1.16.5.json", """{"id":"1.16.5"}""")
        file(a, "1.16.5.jar", "x")
        // 真正装了模组的是这个
        val b = dir(mc, "${McDirs.VERSIONS}/1.20.1")
        file(b, "1.20.1.json", """{"id":"1.20.1"}""")
        file(dir(b, McDirs.MODS), "sodium.jar", "x")

        assertEquals("1.20.1", DirProbe.mcVersionOf(mc))
    }

    @Test
    fun mcVersionFallsBackToFirstKnown() {
        val mc = dir(tmp(), ".minecraft")
        val a = dir(mc, "${McDirs.VERSIONS}/1.18.2")
        file(a, "1.18.2.json", """{"id":"1.18.2"}""")
        assertEquals("1.18.2", DirProbe.mcVersionOf(mc))
    }

    @Test
    fun mcVersionBlankWhenNothingKnown() {
        assertEquals("", DirProbe.mcVersionOf(tmp()))
    }

    @Test
    fun badJsonDoesNotThrow() {
        val v = dir(tmp(), "x")
        val f = file(v, "x.json", "这不是 json")
        assertEquals("", DirProbe.idFromJson(f))
    }

    // ── 给界面用的短句 ──

    @Test
    fun shortOfDescribesParentAndIsolated() {
        val d = tmp()
        dir(d, "${McDirs.INSTANCES}/a")
        assertTrue(DirProbe.shortOf(DirProbe.of(d)).contains("实例"))

        val mc = dir(tmp(), ".minecraft")
        val v = dir(mc, "${McDirs.VERSIONS}/1.20.1")
        file(v, "1.20.1.json", """{"id":"1.20.1"}""")
        file(dir(v, McDirs.MODS), "a.jar", "x")
        assertTrue(DirProbe.shortOf(DirProbe.of(mc)).contains("装了模组"))
    }

    @Test
    fun shortOfExplainsWhyRejected() {
        val r = DirProbe.of(tmp())
        assertFalse(r.usable)
        assertEquals(r.reason, DirProbe.shortOf(r))
    }
}
