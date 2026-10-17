package com.kongbai.modmigrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * 弱网重试与断点续传的纯逻辑。
 *
 * 这些地方写错的后果都不会当场报错，而是变成"看起来在下载、
 * 最后装上去打不开"或者"4G 上永远下不完"，真机上极难定位，
 * 所以在这里钉死。
 *
 * 全部不碰 Android 框架，[Retry] / [Resume] 也刻意不 import 任何 android 类。
 */
class RetryTest {

    private val noSleep: (Long) -> Unit = { }

    // ── 退避时长 ────────────────────────────────────────────

    @Test
    fun backoffDoublesThenCaps() {
        assertEquals(0L, Retry.backoffMs(0))
        assertEquals(700L, Retry.backoffMs(1))
        assertEquals(1400L, Retry.backoffMs(2))
        assertEquals(2800L, Retry.backoffMs(3))
        // 再往后指数增长会被 CAP_MS 压住，不能让用户等几十秒
        assertEquals(12_000L, Retry.backoffMs(9))
        assertTrue(Retry.backoffMs(50) <= 12_000L)
    }

    @Test
    fun jitterSpreadsRetries() {
        val lo = Retry.backoffMs(1, 0.0)
        val hi = Retry.backoffMs(1, 1.0)
        // 抖动范围 [0.5×, 1.5×]，批量下载时靠它错开重试时刻
        assertEquals(350L, lo)
        assertEquals(1050L, hi)
        assertTrue(lo < hi)
    }

    // ── 什么值得重试 ────────────────────────────────────────

    @Test
    fun rateLimitAndServerErrorsAreRetried() {
        assertTrue(Retry.retryableCode(429))
        assertTrue(Retry.retryableCode(500))
        assertTrue(Retry.retryableCode(503))
        assertTrue(Retry.retryableCode(504))
    }

    @Test
    fun clientErrorsAreNotRetried() {
        // 401/403/404 再来一万次也是同样答案，只会让人多等几秒才看到提示
        assertFalse(Retry.retryableCode(401))
        assertFalse(Retry.retryableCode(403))
        assertFalse(Retry.retryableCode(404))
    }

    @Test
    fun networkGlitchesAreRetriedButCertIssuesAreNot() {
        assertTrue(Retry.retryable(SocketTimeoutException("timeout")))
        assertTrue(Retry.retryable(ConnectException("failed to connect")))
        // 证书/协议问题重来还是一样
        assertFalse(Retry.retryable(SSLException("bad cert")))
        assertFalse(Retry.retryable(IllegalArgumentException("bad url")))
        // 用户主动取消，不该跟取消意图对着干
        assertFalse(Retry.retryable(IOException("canceled")))
    }

    // ── 重试循环 ────────────────────────────────────────────

    @Test
    fun loopStopsAtFirstSuccess() {
        var calls = 0
        val v = Retry.loop("t", sleep = noSleep) { calls++; 42 }
        assertEquals(42, v)
        assertEquals(1, calls)
    }

    @Test
    fun loopRetriesUntilSuccess() {
        var calls = 0
        val v = Retry.loop("t", sleep = noSleep) {
            calls++
            if (calls < 3) throw SocketTimeoutException("read timed out")
            "ok"
        }
        assertEquals("ok", v)
        assertEquals(3, calls)
    }

    @Test
    fun loopGivesUpAfterExtraAttempts() {
        var calls = 0
        try {
            Retry.loop("t", extra = 2, sleep = noSleep) {
                calls++
                throw SocketTimeoutException("read timed out")
            }
            fail("应当抛出")
        } catch (t: Throwable) {
            // 首次 + 2 次重试
            assertEquals(3, calls)
        }
    }

    @Test
    fun nonRetryableIsThrownImmediately() {
        var calls = 0
        try {
            Retry.loop("t", sleep = noSleep) {
                calls++
                throw SSLException("bad cert")
            }
            fail("应当抛出")
        } catch (t: Throwable) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun abortSkipsRemainingAttempts() {
        var calls = 0
        try {
            Retry.loop("t", sleep = noSleep) {
                calls++
                throw Retry.Abort("服务端忽略 Range，分块走不通")
            }
            fail("应当抛出")
        } catch (t: Throwable) {
            assertTrue(t is Retry.Abort)
            assertEquals(1, calls)
        }
    }

    // ── 续传区间 ────────────────────────────────────────────

    @Test
    fun resumeRangeIsNullWhenItCannotResume() {
        // 服务端不支持 Range、本地没下过：都只能从头
        assertNull(Resume.range(1000, 5000, acceptRange = false))
        assertNull(Resume.range(0, 5000, acceptRange = true))
        // 下完了，或本地比服务端还长（内容换过），不能再续
        assertNull(Resume.range(5000, 5000, acceptRange = true))
        assertNull(Resume.range(6000, 5000, acceptRange = true))
    }

    @Test
    fun resumeRangeContinuesFromHave() {
        assertEquals("bytes=1000-", Resume.range(1000, 5000, acceptRange = true))
        // 总长未知也要能续：只要不是"已经下完"就不该从头
        assertEquals("bytes=1000-", Resume.range(1000, -1, acceptRange = true))
    }

    // ── 分块计划 ────────────────────────────────────────────

    @Test
    fun planCoversEveryByteExactlyOnce() {
        val plan = Resume.plan(1000, 4)
        assertEquals(4, plan.size)
        assertEquals(0L, plan.first().first)
        assertEquals(999L, plan.last().last)
        for (i in 1 until plan.size) {
            // 段与段之间不能留缝，也不能重叠
            assertEquals(plan[i - 1].last + 1, plan[i].first)
        }
    }

    @Test
    fun planLastChunkTakesTheRemainder() {
        // 10 分 3 段：3 / 3 / 4，最后一段吃掉余数
        val plan = Resume.plan(10, 3)
        assertEquals(3, plan.size)
        assertEquals(0L..2L, plan[0])
        assertEquals(3L..5L, plan[1])
        assertEquals(6L..9L, plan[2])
        assertEquals(9L, plan.last().last)
    }

    @Test
    fun planCollapsesWhenFileIsTiny() {
        // 块比段数还少时合成一段，否则调用方按 chunks 取 plan[i] 会越界
        val plan = Resume.plan(2, 8)
        assertEquals(1, plan.size)
        assertEquals(0L..1L, plan[0])
    }

    @Test
    fun planIsEmptyForNonPositiveTotal() {
        assertTrue(Resume.plan(0, 4).isEmpty())
        assertTrue(Resume.plan(-1, 4).isEmpty())
    }
}
