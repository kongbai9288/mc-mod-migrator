package com.kongbai.modmigrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 用**真实存档**验证区块编辑器 —— 这是本工程第一次真正把存档跑一遍。
 *
 * 背景：前面几版反复出现「编译过了，但区块编辑器用不了」。根因是从来
 * 没有任何一处拿真实存档跑过：解析对不对、渲染画不画得出东西，全靠推断。
 * 最近一次的真凶就是这样被藏住的 —— 区块 NBT 根标签带空名字，
 * ViaNBT 默认 named=false，于是把「名字长度」两字节当成第一个 tag 的 id，
 * 读到 0x00 = TAG_End，复合标签当场结束。不报错、不崩溃，
 * 就是一个 section 都没有，界面只提示空。
 *
 * 这里直接把仓库里的 ai.zip（26.2 的「新的世界 (9)」）拉下来，
 * 走完整的 区域 → 区块 → section → 渲染 → 改方块 → 重新打包 链路。
 * 任何一环退化（压缩类型、调色板写法、位运算、named 读取）
 * 都会在这里失败，而不是等到用户手机上才表现为「用不了」。
 */
class McaRealWorldTest {

    companion object {
        private const val ZIP_URL =
            "https://raw.githubusercontent.com/kongbai9288/mc-mod-migrator/main/ai.zip"

        private val workDir = File(System.getProperty("java.io.tmpdir"), "mm-mca-realworld")

        /** 下载并解出所有 .mca。二进制不进仓库，所以每次从远端取。 */
        private fun regionFiles(): List<File> {
            val marker = File(workDir, ".ok")
            if (!marker.exists()) {
                workDir.mkdirs()
                val conn = URL(ZIP_URL).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 60_000
                conn.readTimeout = 180_000
                conn.inputStream.use { input ->
                    ZipInputStream(input).use { zis ->
                        while (true) {
                            val e = zis.nextEntry ?: break
                            if (e.isDirectory || !e.name.endsWith(".mca")) continue
                            File(workDir, e.name.replace('/', '_')).outputStream()
                                .use { zis.copyTo(it) }
                        }
                    }
                }
                marker.writeText("ok")
            }
            return (workDir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".mca") && it.length() > 8192 }
                // ⚠️ 一个世界目录下有三类 .mca：region / poi / entities。
                // 只有 region/ 存方块；poi 是兴趣点、entities 是实体，
                // 里面根本没有 sections，拿它们测渲染必然全空 ——
                // 这正是应用里「点进去全提示无数据」的来源之一。
                .filter { "_region_" in it.name }
                .sortedBy { it.name }
        }
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun regionOpensAndHasChunks() {
        val files = regionFiles()
        assertTrue("ai.zip 里没解出 .mca（下载失败？）", files.isNotEmpty())
        for (f in files) {
            val r = McaEdit.Region(f.readBytes())
            val present = r.present()
            assertTrue("${f.name} 没有任何区块", present.isNotEmpty())
            println("${f.name}: ${present.size} 个区块")
        }
    }

    /** 这条就是之前静默失败的那条：不报错，只是复合标签是空的。 */
    @Test
    fun chunkParsesIntoSections() {
        val f = regionFiles().first()
        val r = McaEdit.Region(f.readBytes())
        var checked = 0
        for (slot in r.present().take(40)) {
            val c = r.chunk(slot)
            assertTrue("slot $slot 解析不出 CompoundTag", c != null)
            assertTrue("slot $slot 解析出来是**空**复合标签（named 读取又错了？）", !c!!.isEmpty())
            val secs = McaEdit.sections(c)
            assertTrue("slot $slot 一个 section 都没有", secs.isNotEmpty())
            assertTrue("slot $slot section 数量异常：${secs.size}", secs.size in 1..64)
            checked++
        }
        assertTrue("一个区块都没验到", checked > 0)
        println("抽样 $checked 个区块，section 解析正常")
    }

    @Test
    fun paletteAndSectionYAreReadable() {
        var withRealPalette = 0
        var ySeen = 0
        // 只取第一个区域文件的话，很可能刚好是一片没怎么生成的地形，
        // 所有 section 都是单一方块（整片石头或整片空气），
        // 于是永远验不到「调色板含多种方块」这条。改成遍历全部区域文件。
        for (f in regionFiles()) {
            val r = McaEdit.Region(f.readBytes())
            for (slot in r.present().take(256)) {
                val c = r.chunk(slot) ?: continue
                for (sec in McaEdit.sections(c)) {
                    val names = McaEdit.paletteNames(sec)
                    assertTrue("section 的调色板读不出来", names.isNotEmpty())
                    assertTrue("调色板第一个方块名不合法：${names[0]}", names[0].startsWith("minecraft:"))
                    if (names.size > 1) withRealPalette++
                    ySeen++
                }
            }
            if (withRealPalette > 0) break
        }
        assertTrue("没读到任何 Y", ySeen > 0)
        assertTrue("整片存档都没有多个方块的 section，取样太偏", withRealPalette > 0)
        println("读到 $ySeen 个 section，其中 $withRealPalette 个调色板含多种方块")
    }

    // ---------------------------------------------------------------- 渲染

    /** 渲染画不出东西 = 用户看到的「没数据」。这里必须用真实存档确认画得出。 */
    @Test
    fun drawTopPaintsSurface() {
        val f = regionFiles().first()
        val r = McaEdit.Region(f.readBytes())
        val px = IntArray(512 * 512)
        var painted = 0
        var scanned = 0
        for (slot in r.present()) {
            val c = r.chunk(slot) ?: continue
            scanned++
            if (McaRender.drawTop(c, px, 512, (slot and 31) * 16, ((slot shr 5) and 31) * 16, 1)) {
                painted++
            }
        }
        println("扫描 $scanned 个区块，画出 $painted 个")
        assertTrue("一个区块都没画出来 —— 渲染链路断了", painted > 0)
        // 存档本身就有一部分是没生成地形的空区块，但绝不该「全部」画不出
        assertTrue("画出的区块太少（$painted / $scanned），位运算或调色板多半不对",
            painted * 4 > scanned)
    }

    @Test
    fun layerRenderMatchesSectionY() {
        val f = regionFiles().first()
        val r = McaEdit.Region(f.readBytes())
        val px = IntArray(512 * 512)
        for (slot in r.present().take(60)) {
            val c = r.chunk(slot) ?: continue
            val secs = McaEdit.sections(c)
            for (sec in secs) {
                val has = McaRender.layerHasBlocks(c, sec.y)
                val drew = McaRender.drawLayer(c, sec.y, px, 512, 0, 0, 32)
                assertEquals("layerHasBlocks 与 drawLayer 对同一层结论不一致（Y=${sec.y}）", has, drew)
            }
        }
    }

    // ---------------------------------------------------------------- 写入

    /** 改一格 → 读回一致，且**不能**把旁边的格子打乱（位运算最容易错在这）。 */
    @Test
    fun setBlockRoundTripKeepsNeighbours() {
        val f = regionFiles().first()
        val r = McaEdit.Region(f.readBytes())
        for (slot in r.present()) {
            val c = r.chunk(slot) ?: continue
            val sec = McaEdit.sections(c).firstOrNull { McaEdit.paletteNames(it).size > 1 }
                ?: continue
            val before = Array(16) { Array(16) { "" } }
            for (z in 0 until 16) for (x in 0 until 16) before[z][x] =
                McaEdit.blockAt(sec, x, 8, z)

            McaEdit.setBlock(sec, 3, 8, 5, "minecraft:diamond_block")

            assertEquals("改完读不回设定的方块",
                "minecraft:diamond_block", McaEdit.blockAt(sec, 3, 8, 5))
            for (z in 0 until 16) for (x in 0 until 16) {
                if (x == 3 && z == 5) continue
                assertEquals("($x,$z) 被相邻写入打乱了 —— 位运算位数算错",
                    before[z][x], McaEdit.blockAt(sec, x, 8, z))
            }
            println("setBlock 往返一致，其余 255 格未受影响（section Y=${sec.y}）")
            return
        }
        assumeTrue("存档里没有多方块 section，跳过", false)
    }

    /** 重新打包 → 再打开，区块数必须对得上，否则等于存了个坏文件回去。 */
    @Test
    fun rebuildAndReopenKeepsChunks() {
        val f = regionFiles().first()
        val bytes = f.readBytes()
        val r1 = McaEdit.Region(bytes)
        val before = r1.present().size

        val rebuilt = r1.build()
        val r2 = McaEdit.Region(rebuilt)
        assertEquals("重新打包后区块数变了", before, r2.present().size)

        val sample = r1.present().first()
        val a = r1.chunk(sample)
        val b = r2.chunk(sample)
        assertTrue("重新打包后区块读不出来了", b != null && !b.isEmpty())
        assertEquals("重新打包后 section 数变了",
            McaEdit.sections(a!!).size, McaEdit.sections(b!!).size)
        println("重新打包 $before 个区块，重新打开一致")
    }

    @Test
    fun removeDropsTheSlot() {
        val f = regionFiles().first()
        val r = McaEdit.Region(f.readBytes())
        val before = r.present().size
        val victim = r.present().first()
        assertTrue(r.remove(victim))
        assertEquals("删除后区块数没减少", before - 1, r.present().size)
        val rebuilt = McaEdit.Region(r.build())
        assertEquals("删除并重新打包后槽位又回来了", before - 1, rebuilt.present().size)
        println("删除槽位 $victim 生效（$before → ${before - 1}）")
    }
}
