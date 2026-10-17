package com.kongbai.modmigrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存档目录对照的回归测试。
 *
 * 为什么单开一个：这一类错误**不报错、不崩溃**，只是静悄悄地找不到文件 ——
 * 界面上表现为"这个存档里没有地图/没有袭击数据"，没人会怀疑是路径写错了。
 * 所以把七份真实样本数出来的结论钉在这里。
 *
 * 样本来源：
 *  - `kongbaisever/mc-java-save-samples`：b1.3、1.2.1、1.13、1.16、1.18、1.20.5
 *  - 本仓库 `ai.zip`：26.2（世界名「新的世界 (9)」，DataVersion 4903）
 *
 * 这里只测**不依赖样本文件**的纯逻辑（版本比较、路径模板、候选顺序），
 * 这样在本地和 CI 上都能跑；拿真实文件比对的那部分在
 * [McaSamplesTest] 里（它要联网取样本）。
 */
class WorldLayoutTest {

    // ── 版本比较 ──────────────────────────────────────────
    //
    // 26.x 的排法是「年.序号」，跟 1.21.11 这种三级号不是一套。
    // 只按第一段比会得出 26 < 1.21 的荒唐结论。

    @Test
    fun newVersionBeatsOld() {
        assertTrue("26.2 必须晚于 1.21.11", WorldLayout.cmp("26.2", "1.21.11") > 0)
        assertTrue("26.2 必须晚于 26.1", WorldLayout.cmp("26.2", "26.1") > 0)
        assertTrue("1.20.5 必须早于 26.1", WorldLayout.cmp("1.20.5", "26.1") < 0)
        assertTrue("1.13 必须早于 1.20.5", WorldLayout.cmp("1.13", "1.20.5") < 0)
        assertEquals(0, WorldLayout.cmp("1.16", "1.16"))
    }

    @Test
    fun snapshotTagIsIgnored() {
        // 「26.1 snapshot-6」取空格前那段，快照号不参与比较
        assertTrue(WorldLayout.atLeast("26.1 snapshot-6", "26.1"))
        assertFalse(WorldLayout.atLeast("1.20.5 snapshot-1", "26.1"))
    }

    @Test
    fun unknownVersionIsConservative() {
        // 认不出版本时一律 false → 走两套路径，宁可多找一次也别漏
        assertFalse(WorldLayout.atLeast(null, "26.1"))
        assertFalse(WorldLayout.atLeast("", "26.1"))
        assertFalse(WorldLayout.atLeast("26.2", ""))
        assertTrue(WorldLayout.conservative(null))
        assertTrue(WorldLayout.conservative(""))
        assertFalse(WorldLayout.conservative("26.2"))
    }

    // ── 袭击数据：最容易写错的一条 ────────────────────────
    //
    // 主世界叫 raids.dat，末地才叫 raids_end.dat。
    // 此前这里写的是 raids_end.dat —— 三个样本（1.16/1.18/1.20.5）
    // 里主世界那份全都叫 raids.dat，于是主世界的袭击数据被整个跳过。

    @Test
    fun raidsUsesOverworldName() {
        val r = WorldLayout.rule(WorldLayout.Kind.RAIDS)
        assertEquals("主世界袭击文件是 raids.dat", "data/raids.dat", r?.old)
        assertTrue(
            "末地的 raids_end.dat 要留作候选",
            r?.alt.orEmpty().contains("data/raids_end.dat")
        )
    }

    // ── 1.14 / 1.18 两个变迁点 ────────────────────────────
    //
    // 1.14 起 villages.dat 被 poi/ 取代；1.18 起实体从 .mca 拆出到 entities/。
    // 这两个目录此前代码里根本没提过。

    @Test
    fun poiAndEntitiesExist() {
        val poi = WorldLayout.rule(WorldLayout.Kind.POI)
        assertEquals("poi", poi?.old)
        assertEquals("dimensions/minecraft/{dim}/poi", poi?.new)

        val ent = WorldLayout.rule(WorldLayout.Kind.ENTITIES)
        assertEquals("entities", ent?.old)
        assertEquals("dimensions/minecraft/{dim}/entities", ent?.new)
    }

    @Test
    fun villagesOnlyHasOldForm() {
        // 1.13 样本里有这三个文件，1.14 起被 poi/ 取代，新版下不存在
        val v = WorldLayout.rule(WorldLayout.Kind.VILLAGES)
        assertEquals("data/villages.dat", v?.old)
        assertTrue("新版没有对应物", v?.new.isNullOrEmpty())
        assertTrue(v?.alt.orEmpty().contains("data/villages_nether.dat"))
    }

    // ── 候选顺序 ──────────────────────────────────────────

    @Test
    fun candidatesTryLikelyOneFirst() {
        // 26.2：先试维度目录，再退回根目录
        val nw = WorldLayout.candidates(WorldLayout.Kind.OVERWORLD, "26.2")
        assertEquals("dimensions/minecraft/overworld/region", nw.first())

        // 1.20.5：根目录 region 才是真的
        val od = WorldLayout.candidates(WorldLayout.Kind.OVERWORLD, "1.20.5")
        assertEquals("region", od.first())

        // 版本认不出：两套都给，且去重后不为空
        val both = WorldLayout.candidates(WorldLayout.Kind.OVERWORLD, null)
        assertTrue(both.size >= 2)
        assertTrue(both.contains("region"))
        assertTrue(both.contains("dimensions/minecraft/overworld/region"))
    }

    @Test
    fun candidatesDropBlank() {
        // 空串模板不能进候选，否则会匹配到存档根目录
        WorldLayout.all().forEach { r ->
            WorldLayout.candidates(r.kind, "26.2").forEach {
                assertTrue("${r.kind} 候选里不能有空串", it.isNotBlank())
            }
        }
    }

    // ── 区块文件后缀 ──────────────────────────────────────

    @Test
    fun regionExtensionDependsOnVersion() {
        // b1.3 是 .mcr，1.2.1 起换成 .mca
        assertEquals(listOf("mcr", "mca"), WorldLayout.regionExtensions("b1.3"))
        assertEquals(listOf("mca", "mcr"), WorldLayout.regionExtensions("1.2.1"))
        assertEquals(listOf("mca", "mcr"), WorldLayout.regionExtensions("26.2"))
        // 认不出时 mcr 在前也不至于漏 —— 两个都在列表里
        assertTrue(WorldLayout.regionExtensions(null).containsAll(listOf("mca", "mcr")))
    }

    // ── 难度：三个时代三种写法 ────────────────────────────
    //
    // b1.3 / 1.2.1 根本没有难度字段；1.13–1.20.5 是数字；
    // 26.2 是 difficulty_settings.difficulty 字符串。

    @Test
    fun difficultyReadsBothForms() {
        val old = com.viaversion.nbt.tag.CompoundTag()
        old.putInt("Difficulty", 1)
        assertEquals(1, WorldLayout.difficultyOf(old))

        val newer = com.viaversion.nbt.tag.CompoundTag()
        val box = com.viaversion.nbt.tag.CompoundTag()
        box.putString("difficulty", "normal")
        newer.put("difficulty_settings", box)
        assertEquals(2, WorldLayout.difficultyOf(newer))

        // 没有难度字段时必须是 -1，不能拿 0 冒充"和平"
        val none = com.viaversion.nbt.tag.CompoundTag()
        assertEquals(-1, WorldLayout.difficultyOf(none))
        assertEquals(-1, WorldLayout.difficultyOf(null))
    }

    @Test
    fun hardcoreMovedIntoDifficultySettings() {
        val old = com.viaversion.nbt.tag.CompoundTag()
        old.putByte("hardcore", 1.toByte())
        assertEquals(true, WorldLayout.hardcoreOf(old))

        val newer = com.viaversion.nbt.tag.CompoundTag()
        val box = com.viaversion.nbt.tag.CompoundTag()
        box.putByte("hardcore", 0.toByte())
        newer.put("difficulty_settings", box)
        assertEquals(false, WorldLayout.hardcoreOf(newer))

        // b1.3 样本里确实没有这个键，返回 null 而不是 false
        assertEquals(null, WorldLayout.hardcoreOf(com.viaversion.nbt.tag.CompoundTag()))
    }
}
