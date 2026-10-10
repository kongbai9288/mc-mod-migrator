package com.kongbai.modmigrator

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 跨版本存档结构样本验证。
 *
 * 为什么单开一个测试：区块编辑器之前只拿一个 26.2 的存档验过，
 * 于是"换个版本就打不开"这类问题完全测不到 —— 而用户手上的存档
 * 什么版本都有。这里用另一份真实样本（Beta 1.3 / 1.2.1 / 1.13 /
 * 1.16 / 1.18 / 1.20.5，均由对应版本官方服务端生成的默认世界）
 * 逐版本跑一遍解析。
 *
 * 每一条断言的依据都是**实测出来的格式差异**，不是推断：
 *  · b1.3 用 .mcr，整个区块柱 16×16×128 存在一个字节数组里，y 变化最快
 *  · 1.2.1 起每个 section 一个 16³ 数组，x 变化最快
 *  · 1.13 / 1.16 在 Level.Sections 下，用 Palette + BlockStates（long[] 打包）
 *  · 1.18 / 1.20.5 在根级 sections 下，用 block_states.{palette,data}
 *
 * 索引顺序写反是最隐蔽的一类错误：不报错，只是地形变成水平条纹。
 * 所以 [oldTerrainProfileIsSane] 专门守这一条。
 */
class McaSamplesTest {

    companion object {
        private const val BASE =
            "https://raw.githubusercontent.com/kongbaisever/mc-java-save-samples/main/saves/"

        private val dir = File(System.getProperty("java.io.tmpdir"), "mm-mca-samples")

        class Sample(val ver: String, val path: String, val expect: McaEdit.RawMode)

        /** 只挑体积最小的区域文件：样本不进仓库，测试时从远端取。 */
        private val ALL = listOf(
            Sample("b1.3", "b1.3/region/r.-1.0.mcr", McaEdit.RawMode.FLAT_Y_FAST),
            Sample("1.2.1", "1.2.1/region/r.0.0.mca", McaEdit.RawMode.SECTION_X_FAST),
            Sample("1.13", "1.13/region/r.-1.-1.mca", McaEdit.RawMode.NONE),
            Sample("1.16", "1.16/region/r.0.0.mca", McaEdit.RawMode.NONE),
            Sample("1.18", "1.18/region/r.-1.0.mca", McaEdit.RawMode.NONE),
            Sample("1.20.5", "1.20.5/region/r.-2.-1.mca", McaEdit.RawMode.NONE)
        )

        private fun fetch(s: Sample): File? {
            dir.mkdirs()
            val f = File(dir, s.ver + "_" + s.path.substringAfterLast('/'))
            if (f.exists() && f.length() > 0) return f
            return try {
                val c = URL(BASE + s.path).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 30_000
                c.readTimeout = 120_000
                c.inputStream.use { it.copyTo(f.outputStream()) }
                if (f.length() > 0) f else null
            } catch (_: Throwable) {
                null
            }
        }

        private fun firstChunk(f: File): com.viaversion.nbt.tag.CompoundTag? {
            val r = McaEdit.Region(f.readBytes())
            for (slot in r.present()) {
                val c = r.chunk(slot) ?: continue
                if (!c.isEmpty() && McaEdit.sections(c).isNotEmpty()) return c
            }
            return null
        }

        private fun nonAir(sec: McaEdit.Sec): Int {
            var n = 0
            for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                if (McaEdit.blockAt(sec, x, y, z) != "minecraft:air") n++
            }
            return n
        }
    }

    @Test
    fun everyVersionParsesIntoSections() {
        for (s in ALL) {
            val f = fetch(s)
            assumeTrue("样本 ${s.ver} 下载失败（CI 网络？）", f != null)
            val c = firstChunk(f!!)
            assertTrue("${s.ver}：解析不出任何区块", c != null)
            val secs = McaEdit.sections(c!!)
            assertTrue("${s.ver}：一个 section 都没有", secs.isNotEmpty())
            println("${s.ver}: ${secs.size} 个 section，Y ${secs.minOf { it.y }}..${secs.maxOf { it.y }}")
        }
    }

    @Test
    fun rawModeMatchesVersion() {
        for (s in ALL) {
            val f = fetch(s) ?: continue
            val c = firstChunk(f) ?: continue
            val secs = McaEdit.sections(c)
            for (sec in secs) {
                assertTrue(
                    "${s.ver}：期望 ${s.expect}，实际 ${sec.rawMode}",
                    sec.rawMode == s.expect
                )
            }
            // b1.3 的区块柱 128 高，切成 16 高一段正好 8 段
            if (s.ver == "b1.3") {
                assertTrue(
                    "b1.3 应当切成 8 段（128/16），实际 ${secs.size}",
                    secs.size == 8
                )
            }
        }
    }

    /**
     * 守索引顺序。
     * 顺序写反的话，按 y 分层统计会变成"每一层都一样满"，
     * 或者顶层全是石头 —— 两种情况都在这里被挡住。
     */
    @Test
    fun oldTerrainProfileIsSane() {
        for (s in ALL.filter { it.expect != McaEdit.RawMode.NONE }) {
            val f = fetch(s) ?: continue
            val c = firstChunk(f) ?: continue
            val secs = McaEdit.sections(c).sortedBy { it.y }
            val counts = secs.map { nonAir(it) }
            val top = counts.last()
            val bottom = counts.first()
            println("${s.ver} 非空气分布（低→高）：$counts")
            assertTrue("${s.ver}：整柱都没有方块", counts.sum() > 0)
            assertTrue(
                "${s.ver}：越往上非空气越多（$bottom → $top），索引顺序写反了？",
                bottom >= top
            )
            assertTrue(
                "${s.ver}：最高一段是满的（$top/4096），索引顺序写反了？",
                top < 4096
            )
        }
    }

    /** 旧格式读出来必须是能认出的方块名，不能一片 unknown。 */
    @Test
    fun legacyIdsResolve() {
        for (s in ALL.filter { it.expect != McaEdit.RawMode.NONE }) {
            val f = fetch(s) ?: continue
            val c = firstChunk(f) ?: continue
            val names = HashSet<String>()
            for (sec in McaEdit.sections(c)) {
                for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                    names.add(McaEdit.blockAt(sec, x, y, z))
                }
            }
            val known = names.filter { it != "minecraft:air" && !it.startsWith("minecraft:unknown") }
            println("${s.ver} 识别到方块：${known.sorted().take(10)}")
            assertTrue("${s.ver}：没有一个方块 ID 被认出来", known.isNotEmpty())
        }
    }
}
