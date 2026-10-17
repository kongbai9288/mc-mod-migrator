package com.kongbai.modmigrator

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 弱网重试：指数退避 + 抖动。
 *
 * 为什么需要它：手机网络的特点是"短时间断一下又回来"——
 * 进电梯、切基站、Wi-Fi 与移动数据切换。一次 SocketTimeout
 * 不代表这个地址下不下来，直接判失败会让用户在 4G 上
 * 反复手动重试，几十 MB 的模组每次都从头开始。
 *
 * 退避而不是立刻重试：网络刚拥塞或服务端刚限流时，
 * 立刻重发只会把对方再打一次；拉开间隔才有机会恢复。
 *
 * 抖动而不是固定间隔：批量下载几十个任务时，
 * 如果大家都在失败后等同样的 700ms 再一起发，
 * 就形成了"重试风暴"——刚恢复的网络被瞬间打满，
 * 于是又集体失败。加一点随机让重试错开。
 *
 * 这里只放纯逻辑（不 import 任何 Android 类），
 * 好让 `app/src/test` 下的单测能直接跑。
 */
object Retry {

    /** 首次尝试之外还允许多少次 */
    const val EXTRA = 3

    private const val BASE_MS = 700L

    /** 退避上限：再长用户会以为卡死了 */
    private const val CAP_MS = 12_000L

    /** 2 的幂最大取到 6，防止 attempt 很大时移位溢出 */
    private const val MAX_SHIFT = 6

    /**
     * 第 [attempt] 次重试（从 1 起）之前要等多久。
     *
     * `capped/2 + capped * jitter`，jitter ∈ [0,1]：
     * 取 0.5 时正好等于 [capped]，便于单测钉死；
     * 取值范围是 [0.5×capped, 1.5×capped]，即"等 jitter 抖动"策略。
     */
    fun backoffMs(attempt: Int, jitter: Double = 0.5): Long {
        if (attempt < 1) return 0L
        val shift = (attempt - 1).coerceAtMost(MAX_SHIFT)
        val capped = minOf(BASE_MS shl shift, CAP_MS)
        val j = jitter.coerceIn(0.0, 1.0)
        return (capped / 2L + (capped * j).toLong()).coerceAtLeast(0L)
    }

    /**
     * 哪些 HTTP 状态码值得重试。
     *
     * 429 是限流（CurseForge 官方接口和自建后端都会给），
     * 必须退避重试，否则用户什么也下不到；
     * 408/425/509 分别是请求超时、过早、带宽超限，同样属于"再来一次可能就好"。
     * 5xx 一律重试。
     *
     * 401/403/404 不重试：重试一万次也是同样的答案，
     * 只会让用户多等几秒才看到"Key 无效"或"文件不存在"。
     */
    fun retryableCode(code: Int): Boolean = when (code) {
        408, 425, 429, 500, 502, 503, 504, 509 -> true
        else -> code >= 500
    }

    /**
     * 哪些异常值得重试。
     *
     * 不重试的：
     *  - SSLException：证书/协议问题，重来还是一样
     *  - 被中断（InterruptedException / "interrupted"）：用户或系统主动取消，
     *    接着重试是跟取消意图对着干
     *  - 非 IO 异常（比如 URL 写错抛的 IllegalArgumentException）
     *
     * 重试的：超时、连不上、连接被重置、DNS 暂时解析不出。
     * UnknownHostException 在手机上常常是"网络刚切换"的瞬时状态，
     * 值得再试。
     */
    fun retryable(t: Throwable): Boolean {
        val cause = t.cause
        if (cause != null && cause !== t && retryable(cause)) return true

        when (t) {
            is SocketTimeoutException, is ConnectException, is UnknownHostException -> return true
            is SocketException -> return true
            is SSLException -> return false
            is InterruptedException -> return false
        }
        if (t is InterruptedIOException) {
            // SocketTimeoutException 也继承它，上面已经先判过了；
            // 走到这里的是"被中断"，不该重试
            return false
        }
        val m = (t.message ?: "").lowercase()
        if (m.contains("interrupted") || m.contains("canceled") || m.contains("cancelled")) return false
        if (m.contains("timeout") || m.contains("timed out")) return true
        if (m.contains("reset") || m.contains("closed") || m.contains("unexpected end of stream")) return true
        if (m.contains("unable to resolve") || m.contains("network is unreachable")) return true
        return t is IOException
    }

    /** 明确放弃：抛它表示"这不是网络抖动，别再试了"，会立刻向上抛不再退避 */
    class Abort(reason: String) : RuntimeException(reason)

    /**
     * 带退避地重复执行 [block]，直到成功或次数用尽。
     *
     * @param extra 除首次外还试几次
     * @param sleep 等待多久才重试，抽成参数是为了单测里能跳过真睡
     * @param onWait 每次重试前回调（第几次、要等多久），给界面显示"3 秒后重试"
     */
    fun <T> loop(
        tag: String,
        extra: Int = EXTRA,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        onWait: ((Int, Long) -> Unit)? = null,
        block: (attempt: Int) -> T
    ): T {
        var last: Throwable? = null
        val times = extra.coerceAtLeast(0)
        for (i in 0..times) {
            try {
                if (i > 0) {
                    val wait = backoffMs(i, Math.random())
                    onWait?.invoke(i, wait)
                    sleep(wait)
                }
                return block(i)
            } catch (a: Abort) {
                throw a
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (t: Throwable) {
                last = t
                if (!retryable(t)) throw t
            }
        }
        throw last ?: IOException("$tag：重试 $times 次后仍失败")
    }
}

/**
 * 下载的分块与续传规划（纯逻辑，不碰 Android，方便单测）。
 *
 * 和 [Retry] 一样放在这里就是为了可测：
 * 区间算错会拼出"体积对、内容坏"的 jar，
 * 这种错误在真机上只会表现为"装上去游戏起不来"，极难定位。
 */
object Resume {

    /**
     * 续传该发的 Range 头；返回 null 表示从头下。
     *
     * - 服务端不支持 Range → 只能从头
     * - 本地没有已下字节 → 从头
     * - 已下的字节数已达到/超过总长 → 说明要么下完了，
     *   要么服务端内容换了（本地这份是旧的），从头
     */
    fun range(have: Long, total: Long, acceptRange: Boolean): String? {
        if (!acceptRange) return null
        if (have <= 0) return null
        if (total > 0 && have >= total) return null
        return "bytes=$have-"
    }

    /**
     * 把 [total] 字节切成 [chunks] 段，返回闭区间列表。
     *
     * 最后一段吃掉除法的余数，保证末段终点一定是 total-1
     * （否则会漏掉尾部几个字节，拼出来的 jar 打不开）。
     */
    fun plan(total: Long, chunks: Int): List<LongRange> {
        val n = chunks.coerceAtLeast(1)
        if (total <= 0) return emptyList()
        val size = total / n
        if (size <= 0) return listOf(0 until total)
        return (0 until n).map { i ->
            val start = i * size
            val end = if (i == n - 1) total - 1 else (start + size - 1)
            start..end
        }
    }
}
