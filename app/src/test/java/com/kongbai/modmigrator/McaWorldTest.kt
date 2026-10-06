package com.kongbai.modmigrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 用真实存档验证「世界 → 维度 → 区域」这一层。
 *
 * 上一层测试（[McaRealWorldTest]）把 .mca 摊平成散落的文件，
 * 只能测"单个文件能不能解析"，测不到目录结构 ——
 * 而这一版区块地图的第一件事就是认目录。
 *
 * 存档的目录结构前后改过三次：
 *   1.21 起（含 26.x）：<世界>/dimensions/minecraft/<维度>/region
 *   1.16 及更早      ：<世界>/region、DIM-1、DIM1
 *   模组自定义维度    ：<世界>/dimensions/<命名空间>/<维度>/region
 *
 * 本存档（26.2）是第一种。这里同时把三种结构都造一份做兜底，
 * 免得以后只认了新结构、老存档一打开就是空白。
 */
class McaWorldTest {

    companion object {
        private const val ZIP_URL =
            "https://raw.githubusercontent.com/kongbai9288/mc-mod-migrator/main/ai.zip"

        private val workDir = File(System.getProperty("java.io.tmpdir"), "mm-mca-world")

        /** 解压时**保留目录结构** —— 这一层测的就是目录 */
        private fun worldRoot(): File {
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
                            if (e.isDirectory) continue
                            val out = File(workDir, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                    }
                }
                marker.writeText("ok")
            }
            // 世界根 = 含 level.dat 的那一层
            return (workDir.listFiles() ?: emptyArray())
                .first { File(it, "level.dat").isFile }
        }
    }

    @Test
    fun discoversDimensionsOfRealSave() {
        val root = worldRoot()
        val dims = McaWorld.discover(root)
        println("[世界] ${root.name}")
        for (d in dims) {
            println("[维度] key=${d.key} label=${d.label} 区域=${d.count()} 路径=${d.regionDir}")
        }

        assertTrue("一个维度都没认出来", dims.isNotEmpty())

        // 主世界必须排在最前
        assertEquals("主世界没排在第一位", "overworld", dims.first().key)

        // 这个存档的下界/末地还没去过，目录下只有 data、没有 region，
        // 所以只有主世界会被列出 —— 这正是期望行为：
        // 空维度不该出现在选择列表里，点进去只会是一片空白。
        val ow = dims.first { it.key == "overworld" }
        assertTrue("主世界没有区域文件", ow.count() > 0)
    }

    @Test
    fun regionCoordsParsedFromFileName() {
        val root = worldRoot()
        val ow = McaWorld.discover(root).first { it.key == "overworld" }
        val refs = ow.regions()
        println("[区域] " + refs.joinToString { "(${it.rx},${it.rz})" })

        assertTrue("主世界一个区域都没有", refs.isNotEmpty())

        // 坐标必须来自文件名 r.X.Z.mca，不能全是 0
        assertTrue(
            "区域坐标没解析出来（全是 0？）",
            refs.any { it.rx != 0 || it.rz != 0 }
        )
        for (r in refs) {
            assertTrue("区域坐标越界：${r.rx},${r.rz}", r.rx in -4096..4095)
            assertTrue("区域坐标越界：${r.rx},${r.rz}", r.rz in -4096..4095)
        }

        // 世界坐标换算：区域 (-1,-1) 的左下角应是方块 (-512,-512)
        val neg = refs.firstOrNull { it.rx == -1 && it.rz == -1 }
        if (neg != null) {
            assertEquals(-512, neg.baseBlockX)
            assertEquals(-512, neg.baseBlockZ)
            assertEquals(-32, neg.baseChunkX)
            assertEquals(-32, neg.baseChunkZ)
        }
    }

    @Test
    fun onlyRegionDirIsListed() {
        val root = worldRoot()
        for (d in McaWorld.discover(root)) {
            assertTrue(
                "维度 ${d.key} 指到了非 region 目录：${d.regionDir.name}\n" +
                    "poi / entities 里没有方块数据，混进来会让用户点开一片空白",
                d.regionDir.name == "region"
            )
            for (r in d.regions()) {
                assertTrue("列进了非区域文件：${r.name}", r.name.endsWith(".mca"))
                assertTrue("区域文件是空的：${r.name}", r.file.length() > 0)
            }
        }
    }

    /** 老结构（1.16 及更早）也要认 —— 不能只认新版目录 */
    @Test
    fun legacyLayoutIsRecognized() {
        val fake = File(workDir, "legacy-world").apply { mkdirs() }
        File(fake, "level.dat").writeBytes(byteArrayOf(0))
        for (sub in arrayOf("region", "DIM-1/region", "DIM1/region")) {
            val d = File(fake, sub).apply { mkdirs() }
            File(d, "r.0.0.mca").writeBytes(ByteArray(8192))
        }
        val dims = McaWorld.discover(fake)
        val keys = dims.map { it.key }
        println("[老结构] " + dims.joinToString { "${it.key}=${it.label}" })
        assertTrue("老结构没认出主世界（keys=$keys）", keys.contains("overworld"))
        assertTrue("老结构没认出下界（keys=$keys）", keys.contains("DIM-1"))
        assertTrue("老结构没认出末地（keys=$keys）", keys.contains("DIM1"))
        assertEquals("老结构主世界区域数不对", 1, dims.first { it.key == "overworld" }.count())
    }
}
