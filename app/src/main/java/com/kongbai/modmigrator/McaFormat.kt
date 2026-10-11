package com.kongbai.modmigrator

/**
 * Anvil 存档格式的尺寸常量。
 *
 * 为什么单独放这里：
 * 这些数字（扇区 4096、一个区域 1024 个槽位、section 边长 16、
 * 一个 section 内 16³ 个格子、打包用的 long 是 64 位）
 * 以前是直接写在解析、渲染、缓存各自的文件里的字面量。
 * 它们全部来自**同一份格式定义**，一旦 Mojang 改了其中任何一个
 * （比如 section 尺寸变动，这是社区一直在讨论的方向），
 * 散落的字面量就得一个个人肉找出来改，漏一处就是静默的错位或越界。
 *
 * 现在解析、渲染、缓存都从这里取。改格式只需要改这一个文件。
 */
object McaFormat {

    /** 扇区大小（字节）。区域文件按扇区对齐读写 */
    const val SECTOR = 4096

    /** 一个区域文件的槽位数：32×32 个区块 */
    const val SLOTS = 1024

    /** 区域文件的头部：位置表 + 时间戳表，各占一个扇区 */
    const val HEADER = SECTOR * 2

    /** section 边长（格） */
    const val SECTION_SPAN = 16

    /** 一个 section 内的方块数：16×16×16 */
    const val SECTION_ENTRIES = SECTION_SPAN * SECTION_SPAN * SECTION_SPAN

    /** 位打包用的 long 的位数 */
    const val LONG_BITS = 64
}
