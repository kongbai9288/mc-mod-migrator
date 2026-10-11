package com.kongbai.modmigrator

/**
 * Anvil 存档格式的尺寸。
 *
 * 这些数字以前散在解析、渲染、缓存三个文件里各写一份字面量。
 * 它们出自同一份格式定义，Mojang 动了任何一个（section 尺寸是社区一直在谈的方向），
 * 散落的字面量就得一处一处找出来改，漏一处就是静默错位或越界。
 *
 * 现在三处都从这里取，改格式只改这一个文件。
 */
object McaFormat {

    /** 扇区大小（字节）。区域文件按扇区对齐读写 */
    const val SECTOR = 4096

    /** 区域边长（区块数）：32×32 */
    const val REGION_SPAN = 32

    /** 一个区域文件的槽位数：32×32 个区块 */
    const val SLOTS = REGION_SPAN * REGION_SPAN

    /** 槽位下标 → 区块坐标用的位移量（log2(REGION_SPAN)） */
    const val REGION_SHIFT = 5

    /** 槽位下标 → 区块坐标用的掩码（REGION_SPAN - 1） */
    const val REGION_MASK = REGION_SPAN - 1

    /** 区域文件的头部：位置表 + 时间戳表，各占一个扇区 */
    const val HEADER = SECTOR * 2

    /** section 边长（格） */
    const val SECTION_SPAN = 16

    /** 一个 section 内的方块数：16×16×16 */
    const val SECTION_ENTRIES = SECTION_SPAN * SECTION_SPAN * SECTION_SPAN

    /** 位打包用的 long 的位数 */
    const val LONG_BITS = 64

    // ---- 槽位 / 区块坐标换算 ----
    // 这些换算以前在各处直接写 `slot and 31` `(slot shr 5) and 31` `cx / 32`，
    // 数字跟着区域边长走，写死了就改不动。

    /** 槽位下标 → 区块 x */
    fun cx(slot: Int): Int = slot and REGION_MASK

    /** 槽位下标 → 区块 z */
    fun cz(slot: Int): Int = (slot shr REGION_SHIFT) and REGION_MASK

    /** 区块坐标 → 槽位下标 */
    fun slot(cx: Int, cz: Int): Int = cz * REGION_SPAN + cx

    /** 区块坐标 → 所在区域的坐标（负数向下取整，-1..-32 属于 -1） */
    fun regionOf(c: Int): Int = Math.floorDiv(c, REGION_SPAN)

}
