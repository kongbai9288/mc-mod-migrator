package com.kongbai.modmigrator

import com.viaversion.nbt.tag.CompoundTag
import java.io.File

/**
 * 世界存档信息。
 *
 * ⚠️ 这个功能**不依赖**数据包生成器（misode）也不依赖 NBT 编辑器界面 ——
 * 它只是把存档目录里已有的东西读出来展示，属于独立的查看功能。
 * 解析 level.dat 仍然要读 NBT（那是 Minecraft 的格式），
 * 但整个流程与"编辑"无关，只读不写。
 *
 * level.dat 的结构（1.13+）：
 *   根 → Data (compound)
 *     LevelName      世界名
 *     Version.Name   游戏版本
 *     WorldGenSettings.seed / 或 Data.Seed
 *     LastPlayed     最后游玩时间（毫秒时间戳）
 *     Time           游戏内已过的 tick（1 tick = 1/20 秒）
 *     Difficulty     难度 0和平 1简单 2普通 3困难
 *     allowCommands  是否允许作弊
 *     GameType       0生存 1创造 2冒险 3旁观
 *     DataVersion    存档数据版本（可反推 MC 版本范围）
 */
object WorldInfo {

    data class World(
        val dir: File,
        val name: String,
        val mcVersion: String,
        val seed: Long?,
        val lastPlayed: Long,
        val gameTimeTicks: Long,
        val difficulty: Int,
        val allowCommands: Boolean,
        val gameType: Int,
        val dataVersion: Int,
        val iconPath: String?,
        val sizeBytes: Long
    ) {
        /** 游戏内天数：一天 = 24000 tick */
        val days: Long get() = gameTimeTicks / 24000L

        fun difficultyText(): String = when (difficulty) {
            0 -> "和平"; 1 -> "简单"; 2 -> "普通"; 3 -> "困难"; else -> "未知($difficulty)"
        }

        fun gameTypeText(): String = when (gameType) {
            0 -> "生存"; 1 -> "创造"; 2 -> "冒险"; 3 -> "旁观"; else -> "未知($gameType)"
        }

        fun sizeText(): String = humanSize(sizeBytes)
    }

    /** 字节数转 1.2 MB 这种 */
    private fun humanSize(b: Long): String {
        if (b <= 0L) return "0"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$b B" else "%.1f %s".format(java.util.Locale.getDefault(), v, u[i])
    }

    /** 从一个存档目录读信息。读不到就用目录名兜底，绝不留空。 */
    fun read(dir: File): World {
        val levelDat = File(dir, "level.dat")
        var name = dir.name
        var mc = ""
        var seed: Long? = null
        var last = 0L
        var ticks = 0L
        var diff = -1
        var cmd = false
        var gt = -1
        var dv = 0

        val data: CompoundTag? = try {
            if (levelDat.exists()) {
                val root = NbtFile.read(levelDat)
                root.getCompoundTag("Data")
            } else null
        } catch (t: Throwable) {
            // level.dat 损坏或版本太新读不出 —— 仍然把目录信息显示出来，
            // 用户至少能看到有哪些存档、占多大地方。
            Err.ignore(t, "读取 level.dat：${dir.name}")
            null
        }

        if (data != null) {
            name = data.getStringTag("LevelName")?.getValue()?.takeIf { it.isNotBlank() } ?: dir.name
            mc = data.getCompoundTag("Version")?.getStringTag("Name")?.getValue() ?: ""

            //
            // 种子在 1.16 之后挪进了 WorldGenSettings.dimensions... 太深了，
            // 不同版本结构还不一样。这里试几个已知位置，取第一个有的。
            // 拿不到就显示"未公开"，不能显示 0 —— 0 是合法种子，
            // 显示 0 会让人以为种子真的是 0。
            //
            seed = data.getNumberTag("RandomSeed")?.getValue()?.toLong()
                ?: data.getNumberTag("Seed")?.getValue()?.toLong()
                ?: data.getCompoundTag("WorldGenSettings")?.getNumberTag("seed")?.getValue()?.toLong()

            last = data.getNumberTag("LastPlayed")?.getValue()?.toLong() ?: 0L
            ticks = data.getNumberTag("Time")?.getValue()?.toLong() ?: 0L
            diff = data.getNumberTag("Difficulty")?.getValue()?.toInt() ?: -1
            cmd = (data.getNumberTag("allowCommands")?.getValue()?.toInt() ?: 0) != 0
            gt = data.getNumberTag("GameType")?.getValue()?.toInt() ?: -1
            dv = data.getNumberTag("DataVersion")?.getValue()?.toInt() ?: 0
        }

        val icon = File(dir, "icon.png").takeIf { it.exists() }?.absolutePath

        return World(
            dir = dir, name = name, mcVersion = mc, seed = seed,
            lastPlayed = last, gameTimeTicks = ticks, difficulty = diff,
            allowCommands = cmd, gameType = gt, dataVersion = dv,
            iconPath = icon, sizeBytes = dirSize(dir)
        )
    }

    /**
     * 从 level.dat 的字节直接解析（SAF 路径拿不到 File 时用）。
     * 拿不到目录大小，记为 0，界面显示 "—" 而不是编一个数。
     */
    fun fromBytes(dirName: String, levelDat: ByteArray): World {
        val data: CompoundTag? = try {
            NbtFile.readBytes(levelDat).getCompoundTag("Data")
        } catch (t: Throwable) {
            Err.ignore(t, "解析 level.dat：$dirName")
            null
        }
        if (data == null) {
            return World(
                dir = File(dirName), name = dirName, mcVersion = "", seed = null,
                lastPlayed = 0L, gameTimeTicks = 0L, difficulty = -1,
                allowCommands = false, gameType = -1, dataVersion = 0,
                iconPath = null, sizeBytes = 0L
            )
        }
        val w = World(
            dir = File(dirName),
            name = data.getStringTag("LevelName")?.getValue()?.takeIf { it.isNotBlank() } ?: dirName,
            mcVersion = data.getCompoundTag("Version")?.getStringTag("Name")?.getValue() ?: "",
            seed = data.getNumberTag("RandomSeed")?.getValue()?.toLong()
                ?: data.getNumberTag("Seed")?.getValue()?.toLong(),
            lastPlayed = data.getNumberTag("LastPlayed")?.getValue()?.toLong() ?: 0L,
            gameTimeTicks = data.getNumberTag("Time")?.getValue()?.toLong() ?: 0L,
            difficulty = data.getNumberTag("Difficulty")?.getValue()?.toInt() ?: -1,
            allowCommands = (data.getNumberTag("allowCommands")?.getValue()?.toInt() ?: 0) != 0,
            gameType = data.getNumberTag("GameType")?.getValue()?.toInt() ?: -1,
            dataVersion = data.getNumberTag("DataVersion")?.getValue()?.toInt() ?: 0,
            iconPath = null, sizeBytes = 0L
        )
        return w
    }

    /** 找出 saves 目录下的所有存档 */
    fun listIn(saves: File): List<World> {
        val dirs = saves.listFiles()?.filter {
            it.isDirectory && (File(it, "level.dat").exists() || File(it, "region").isDirectory)
        } ?: return emptyList()
        return dirs.sortedByDescending { it.lastModified() }.map { read(it) }
    }

    private fun dirSize(d: File): Long {
        return try {
            d.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (t: Throwable) {
            0L
        }
    }

    /** 把毫秒时间戳转成"YYYY-MM-DD HH:MM" */
    fun timeText(ms: Long): String {
        if (ms <= 0L) return "—"
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            f.format(java.util.Date(ms))
        } catch (t: Throwable) {
            "—"
        }
    }
}
