package com.kongbai.modmigrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 暴力测试：拿大量**畸形 + 边界**输入去撞 [WorldLayout] 的纯逻辑。
 *
 * 目的不是验证"结果对不对"，而是验证两件事：
 * 1. 任何输入都不许抛异常 —— 版本号来自存档目录名、用户输入、
 *    网络接口，什么脏数据都可能有，解析崩了整个界面就白屏。
 * 2. 几条必须成立的不变量不能破：
 *    - 区块扩展名永远给得出（空了就直接漏掉整个存档）
 *    - 候选路径里不能有空白项（空白会匹配到存档根目录）
 *    - 找不到的东西返回 null，不能抛
 *
 * 固定随机种子，保证每次跑的都是同一批数据，失败了能复现。
 */
class WorldLayoutStressTest {

    /** 固定种子，保证可复现 */
    private val seed = 20261018

    /** 真实版本 + 已知的变迁点，用来做对照基准 */
    private val bases = listOf(
        "", "b1.3", "1.2", "1.2.1", "1.13", "1.14", "1.16",
        "1.18", "1.20.5", "26.1", "26.2"
    )

    /** 畸形 / 边界输入：空、空白、字母、负数、前导零、超长、超多段 */
    private fun wildVersions(): List<String> = listOf(
        "", " ", "  ", "b1.3", "B1.3", "b1.0", "1.0", "1.9", "1.10",
        "abc", "1.a.3", "-1.2", "1.-2", "01.02", "1..2", ".1", "1.",
        "1.2.3.4.5", "26.2-snapshot", "99.99", "0", "0.0",
        "1".repeat(300),
        (1..150).joinToString(".") { "9" },
        "  1.2  ", "1.2\n", "1.2\t"
    )

    /** 随机数字版本号 */
    private fun randomVersions(n: Int): List<String> {
        val rnd = Random(seed)
        val out = ArrayList<String>(n)
        repeat(n) {
            out.add(buildString {
                repeat(1 + rnd.nextInt(4)) {
                    if (length > 0) append('.')
                    append(rnd.nextInt(200))
                }
            })
        }
        return out
    }

    /** 畸形输入只要求"不崩"，不要求结果符合直觉 */
    @Test
    fun cmpNeverThrowsOnWildInput() {
        for (v in wildVersions() + randomVersions(2000)) {
            for (b in bases) {
                WorldLayout.cmp(v, b)
                WorldLayout.cmp(b, v)
                WorldLayout.atLeast(v, b)
            }
            WorldLayout.cmp(v, v)
        }
    }

    /** 正规数字版本必须满足反对称：cmp(a,b) == -cmp(b,a) */
    @Test
    fun cmpIsAntisymmetricOnNumeric() {
        val vs = randomVersions(150)
        for (a in vs) {
            for (b in vs) {
                val ab = WorldLayout.cmp(a, b)
                val ba = WorldLayout.cmp(b, a)
                assertEquals("cmp($a,$b) 与 cmp($b,$a) 必须互为相反数", 0, ab + ba)
            }
        }
    }

    /** 自己跟自己比必须是 0 */
    @Test
    fun cmpSelfIsZero() {
        for (v in wildVersions() + randomVersions(500)) {
            assertEquals("cmp($v,$v) 必须为 0", 0, WorldLayout.cmp(v, v))
        }
    }

    /**
     * 区块扩展名永远要给得出东西。
     * 这一条最要命：返回空列表的话，扫描器会认为目录里没有区块，
     * 整个存档被当成"不是存档"跳过 —— 用户的存档就这么凭空消失了。
     */
    @Test
    fun regionExtensionsAlwaysUsable() {
        for (v in wildVersions() + randomVersions(2000)) {
            val e = WorldLayout.regionExtensions(v)
            assertTrue("regionExtensions($v) 不能为空", e.isNotEmpty())
            assertTrue(
                "regionExtensions($v) 必须包含 mca 或 mcr，实际是 $e",
                e.contains("mca") || e.contains("mcr")
            )
        }
    }

    /** Beta 存档是 .mcr，1.2.1 起是 .mca，顺序不能反 */
    @Test
    fun regionExtensionsOrderMatchesVersion() {
        assertTrue(
            "b1.3 应当优先 .mcr，实际 ${WorldLayout.regionExtensions("b1.3")}",
            WorldLayout.regionExtensions("b1.3").first() == "mcr"
        )
        assertTrue(
            "1.2.1 应当优先 .mca，实际 ${WorldLayout.regionExtensions("1.2.1")}",
            WorldLayout.regionExtensions("1.2.1").first() == "mca"
        )
        assertTrue(
            "26.2 应当优先 .mca，实际 ${WorldLayout.regionExtensions("26.2")}",
            WorldLayout.regionExtensions("26.2").first() == "mca"
        )
    }

    /**
     * 候选路径不许有空白项。
     * 空白路径会被解析成存档根目录本身，于是"找到"了错误的目录。
     */
    @Test
    fun candidatesNeverBlank() {
        val vs = wildVersions() + randomVersions(400)
        for (k in WorldLayout.Kind.values()) {
            for (v in vs) {
                val c = WorldLayout.candidates(k, v)
                assertTrue(
                    "candidates($k,$v) 含空白项：$c",
                    c.none { it.isBlank() }
                )
                WorldLayout.path(k, v)
            }
        }
    }

    /** 找不到的目录必须返回 null，不能抛异常 */
    @Test
    fun findOnMissingDirReturnsNull() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wlt_stress_${System.nanoTime()}")
        for (k in WorldLayout.Kind.values()) {
            assertNull(WorldLayout.find(dir, k, "1.20.5"))
            assertNull(WorldLayout.find(File(dir, "no/such/path"), k, ""))
            assertNull(WorldLayout.find(File(dir, "a/b/c/d"), k, "26.2"))
        }
    }

    /** 版本认不出时应当走保守模式：整棵原样复制，不做目录改名 */
    @Test
    fun conservativeOnUnknownVersion() {
        assertTrue("空版本必须走保守模式", WorldLayout.conservative(""))
        assertTrue("null 版本必须走保守模式", WorldLayout.conservative(null))
        assertTrue("纯空白必须走保守模式", WorldLayout.conservative("   "))
    }
}
