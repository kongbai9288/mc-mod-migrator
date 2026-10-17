package com.kongbai.modmigrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动器目录表的**版本过滤**。
 *
 * 只测不碰 Android 框架的纯逻辑：版本号比较、系统版本区间、启动器版本区间。
 * 真正去磁盘探测目录留到真机 —— 但过滤算错会把唯一对的那条路径挡掉，
 * 用户看到的是「找不到启动器」，很难猜到是版本比较写反了，所以先在这里钉死。
 *
 * 两条容易写反的地方专门留了用例：
 *  - `1.10` 比 `1.3` 新（按字符串比会反过来）
 *  - Pojav 的分界是 API 28 而不是 29（写成 Android 10 会漏掉 Android 9）
 */
class LauncherDirsTest {

    // ── 版本号比较 ──────────────────────────────────────────

    @Test
    fun numericPartsBeatTextOrder() {
        // "1.10" < "1.3" 是字符串比较的结果，正好是错的
        assertTrue(LauncherDirs.verCmp("1.10", "1.3") > 0)
        assertTrue(LauncherDirs.verCmp("1.3", "1.10") < 0)
    }

    @Test
    fun sameVersionIsZero() {
        assertEquals(0, LauncherDirs.verCmp("1.3.4.0", "1.3.4.0"))
        assertEquals(0, LauncherDirs.verCmp("", ""))
    }

    @Test
    fun blankLosesToAnything() {
        assertTrue(LauncherDirs.verCmp("", "1.0") < 0)
        assertTrue(LauncherDirs.verCmp("1.0", "") > 0)
    }

    @Test
    fun zalithStyleVersionComparesByDatePart() {
        // versionName 里带日期：20251016 比 20250517 新
        assertTrue(
            LauncherDirs.verCmp("2.0.0_beta-20251016a", "2.0.0_alpha-20250517a") > 0
        )
        assertTrue(
            LauncherDirs.verCmp("2.0.0_alpha-20250517a", "2.0.0_beta-20251016a") < 0
        )
    }

    @Test
    fun noDigitsFallsBackToString() {
        assertTrue(LauncherDirs.verCmp("beta", "alpha") > 0)
        assertEquals(0, LauncherDirs.verCmp("beta", "beta"))
    }

    // ── 启动器自身版本区间 ──────────────────────────────────

    @Test
    fun appRangeKeepsInsideDropsOutside() {
        val e = LauncherDirs.Entry(name = "t", appFrom = "1.2", appTo = "2.0")
        assertTrue(LauncherDirs.appliesToApp(e, "1.5"))
        assertTrue(LauncherDirs.appliesToApp(e, "1.2"))
        assertTrue(LauncherDirs.appliesToApp(e, "2.0"))
        assertFalse(LauncherDirs.appliesToApp(e, "1.1"))
        assertFalse(LauncherDirs.appliesToApp(e, "2.1"))
    }

    @Test
    fun appRangeUsesNumericNotText() {
        // 下限 1.10：装的是 1.3 时应当被挡掉
        val e = LauncherDirs.Entry(name = "t", appFrom = "1.10")
        assertFalse(LauncherDirs.appliesToApp(e, "1.3"))
        assertTrue(LauncherDirs.appliesToApp(e, "1.10"))
        assertTrue(LauncherDirs.appliesToApp(e, "1.20"))
    }

    @Test
    fun unknownLauncherVersionNeverBlocks() {
        // 读不到版本号（没装、或系统不给读）时一律放行，
        // 拿不到证据宁可多试候选，也不能把对的路径过滤掉
        val e = LauncherDirs.Entry(name = "t", appFrom = "2.0", appTo = "3.0")
        assertTrue(LauncherDirs.appliesToApp(e, null))
        assertTrue(LauncherDirs.appliesToApp(e, ""))
    }

    @Test
    fun emptyRangeAppliesToEverything() {
        val e = LauncherDirs.Entry(name = "t")
        assertTrue(LauncherDirs.appliesToApp(e, "0.1"))
        assertTrue(LauncherDirs.appliesToApp(e, "99.0"))
    }

    // ── 安卓系统版本区间 ────────────────────────────────────

    @Test
    fun pojavPrivateStartsAtApi28() {
        // 中文 Minecraft Wiki：测试版 API≥28 起挪进 Android/data。
        // 社区帖子常写成「Android 10 起」，那是 API 29，会漏掉 Android 9。
        val e = LauncherDirs.all().first { it.name.contains("测试版") }
        assertTrue(LauncherDirs.appliesToSdk(e, 28))
        assertTrue(LauncherDirs.appliesToSdk(e, 29))
        assertTrue(LauncherDirs.appliesToSdk(e, 34))
        assertFalse(LauncherDirs.appliesToSdk(e, 27))
        assertFalse(LauncherDirs.appliesToSdk(e, 26))
    }

    @Test
    fun pojavPublicHasNoSystemLimit() {
        // 正式版不设区间：老系统上有它，新系统上也可能有
        val e = LauncherDirs.all().first { it.name.contains("正式版") }
        assertTrue(LauncherDirs.appliesToSdk(e, 27))
        assertTrue(LauncherDirs.appliesToSdk(e, 28))
        assertTrue(LauncherDirs.appliesToSdk(e, 34))
    }

    @Test
    fun noSystemRangeAppliesEverywhere() {
        val e = LauncherDirs.Entry(name = "t")
        assertTrue(LauncherDirs.appliesToSdk(e, 26))
        assertTrue(LauncherDirs.appliesToSdk(e, 36))
    }
}
