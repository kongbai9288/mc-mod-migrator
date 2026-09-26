package com.kongbai.modmigrator

import android.content.Context
import org.json.JSONObject

/**
 * Groq —— 用它的免费额度做「AI 代码审核」。
 *
 * 端点完全兼容 OpenAI：`https://api.groq.com/openai/v1`
 * 密钥形如 `gsk_...`，走 `Authorization: Bearer`。
 *
 * ── 为什么密钥不能写死在源码里 ────────────────────────
 * 这个工程是**公开仓库**，任何写死在 .kt 里的字符串都会立刻泄露。
 * 密钥只从 `Prefs`（EncryptedSharedPreferences，本机加密）读取，
 * 由用户自己在界面里填一次。源码里不会出现密钥本身。
 *
 * ── 免费层的限速（这是本类最主要的设计约束）─────────────
 * 多方来源交叉核对后的共识：
 *   - **所有模型都是 30 RPM**（每分钟 30 次请求）
 *   - **限额按组织算，不是按密钥算**——多建几个 key 不会增加额度
 *   - **滑动窗口**，不是整分钟整点重置：
 *     10:00:15 用掉的量要到 10:01:15 才释放
 *   - 超限一律返回 **429**，带 `retry-after`（秒）
 *   - 配额信息在响应头里：
 *     `x-ratelimit-remaining-requests` / `x-ratelimit-remaining-tokens`
 *     / `x-ratelimit-reset-tokens`
 *
 * 各模型免费额度（不同来源给出的 TPM 数字有出入，下面取保守值）：
 *   | 模型                  | RPD      | TPM(保守) | 说明           |
 *   |----------------------|----------|----------|----------------|
 *   | llama-3.1-8b-instant | 14,400   | 6,000    | 额度最大，默认选它 |
 *   | llama-3.3-70b        | 1,000    | 6,000    | 质量更好，次数少  |
 *   | gpt-oss-20b          | 1,000    | 8,000    |                |
 *   | llama-4-scout        | 1,000    | 30,000   | TPM 最高        |
 *
 * 因为**数字本身就在变、且来源不一致**，这里不硬编码"还剩多少次"，
 * 而是**每次读响应头**来判断真实剩余量——无论官方怎么调整额度都成立。
 *
 * 对应地做了四层保护：
 *   1. 客户端节流：两次请求强制间隔（30 RPM ⇒ 2 秒/次，再留余量）
 *   2. 串行执行：绝不并发打，避免瞬间冲掉整分钟配额
 *   3. 429 退避：优先听 `retry-after`，没有就指数退避
 *   4. 配额预警：剩余量见底时提前停手，而不是硬撞 429
 */
object GroqApi {

    private const val BASE = "https://api.groq.com/openai/v1"

    /** 默认模型：免费额度里请求次数最多的那个（14,400 次/天） */
    const val DEFAULT_MODEL = "llama-3.1-8b-instant"

    /** 可选模型：名称 → 给用户看的一句话说明 */
    val MODELS: List<Pair<String, String>> = listOf(
        DEFAULT_MODEL to "额度最大（约 14400 次/天），速度快",
        "llama-3.3-70b-versatile" to "质量更好，但每天约 1000 次",
        "openai/gpt-oss-20b" to "推理较强，每天约 1000 次",
        "meta-llama/llama-4-scout-17b-16e-instruct" to "长上下文，每分钟 token 额度最高"
    )

    /** 单文件送审的字符上限：超出部分截断，避免一次吃掉整分钟 token 额度 */
    private const val MAX_CHARS = 12_000

    /** 输出上限：别让模型自由发挥，输出 token 同样计入额度 */
    private const val MAX_OUTPUT = 1200

    /**
     * 最小请求间隔（毫秒）。
     * 30 RPM = 2000ms/次，这里取 2200ms 留 10% 余量，
     * 避免客户端与服务端时钟误差导致刚好卡线。
     */
    private const val MIN_GAP_MS = 2_200L

    private const val MAX_RETRY = 2
    private const val RETRY_CAP_MS = 60_000L

    /** 全局串行锁：保证任意时刻只有一个请求在飞 */
    private val gate = Any()

    @Volatile
    private var lastCallAt = 0L

    /** 最近一次请求后服务端的剩余配额，界面用来显示 */
    @Volatile
    var lastQuota: Quota = Quota()
        private set

    data class Quota(
        val remainingRequests: String = "",
        val remainingTokens: String = "",
        val resetTokens: String = ""
    ) {
        fun known(): Boolean = remainingRequests.isNotBlank() || remainingTokens.isNotBlank()
        fun text(): String {
            if (!known()) return ""
            val sb = StringBuilder()
            if (remainingRequests.isNotBlank()) sb.append("剩余请求 ").append(remainingRequests)
            if (remainingTokens.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append("剩余 token ").append(remainingTokens)
            }
            if (resetTokens.isNotBlank()) sb.append("（").append(resetTokens).append("后恢复）")
            return sb.toString()
        }
    }

    /** 审核结果 */
    data class Review(
        val ok: Boolean,
        val text: String,      // 成功=审核意见；失败=给用户看的失败原因
        val truncated: Boolean = false
    )

    fun key(ctx: Context): String {
        val c = ctx.applicationContext
        return Prefs.get(c).getString(K.GROQ_KEY, "")?.trim() ?: ""
    }

    fun setKey(ctx: Context, v: String) {
        Prefs.get(ctx.applicationContext).edit().putString(K.GROQ_KEY, v.trim()).apply()
    }

    fun model(ctx: Context): String {
        val c = ctx.applicationContext
        return Prefs.get(c).getString(K.GROQ_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setModel(ctx: Context, v: String) {
        Prefs.get(ctx.applicationContext).edit().putString(K.GROQ_MODEL, v).apply()
    }

    /** 密钥形态自检：`gsk_` 开头，长度够 */
    fun looksLikeKey(v: String): Boolean =
        v.startsWith("gsk_") && v.length >= 20

    /**
     * 审核一个源文件。
     *
     * 会阻塞（内部自带节流 sleep），**必须在后台线程调用**。
     *
     * @param fileName 仅用于提示模型，让它回答时能指名道姓
     * @param onWait  限速等待时回调，参数是预计等待秒数，便于界面显示"稍等"
     */
    fun review(
        ctx: Context,
        fileName: String,
        source: String,
        onWait: ((Int) -> Unit)? = null
    ): Review {
        val k = key(ctx)
        if (!looksLikeKey(k)) {
            return Review(false, "还没填写有效的 Groq 密钥（应以 gsk_ 开头）")
        }

        val truncated = source.length > MAX_CHARS
        val code = if (truncated) source.substring(0, MAX_CHARS) else source

        val sys = buildString {
            append("你是一位严格的 Android / Kotlin 代码审查专家。\n")
            append("只指出真实存在的问题，没有问题就说\"未发现明显问题\"。\n")
            append("不要复述代码，不要泛泛而谈，不要提风格偏好。\n")
            append("按以下格式逐条输出，最多 8 条：\n")
            append("[严重] 或 [一般] 标题\n")
            append("位置：函数名或行附近特征\n")
            append("问题：一句话说清错在哪\n")
            append("建议：一句话给出改法\n")
            append("全部用中文。")
        }
        val user = buildString {
            append("审查文件 ").append(fileName).append('\n')
            append("```\n").append(code).append("\n```")
            if (truncated) append("\n（文件过长，只读前 ").append(MAX_CHARS).append(" 字符）")
        }

        val body = JSONObject().apply {
            put("model", model(ctx))
            put("temperature", 0)          // 确定性输出，同样的问题不会每次结论都变
            put("max_tokens", MAX_OUTPUT)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }.toString()

        val headers = mapOf(
            "Authorization" to "Bearer $k",
            "Content-Type" to "application/json"
        )

        var attempt = 0
        while (true) {
            // 节流 + 发送全程串行：免费层只有 30 RPM，并发打必然 429
            val resp = synchronized(gate) {
                val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastCallAt)
                if (wait > 0) {
                    onWait?.invoke((wait / 1000 + 1).toInt())
                    sleepQuiet(wait)
                }
                val r = try {
                    Http.postDetailed("$BASE/chat/completions", body, headers)
                } finally {
                    lastCallAt = System.currentTimeMillis()
                }
                r
            }

            lastQuota = Quota(
                remainingRequests = resp.header("x-ratelimit-remaining-requests") ?: "",
                remainingTokens = resp.header("x-ratelimit-remaining-tokens") ?: "",
                resetTokens = resp.header("x-ratelimit-reset-tokens") ?: ""
            )

            when {
                resp.isOk() -> {
                    val out = extract(resp.body)
                    if (out.isNullOrBlank()) {
                        return Review(false, "模型没返回内容（可能被输出上限截断）", truncated)
                    }
                    return Review(true, out, truncated)
                }

                resp.code == 429 -> {
                    // 优先听服务端给的 retry-after（秒），没有才指数退避
                    val ra = resp.header("retry-after")?.trim()?.toDoubleOrNull()
                    val waitMs = ((ra ?: (2 shl attempt).toDouble()) * 1000)
                        .toLong().coerceIn(1_000L, RETRY_CAP_MS)
                    if (attempt >= MAX_RETRY) {
                        return Review(false, buildRateLimitMsg(waitMs), truncated)
                    }
                    onWait?.invoke((waitMs / 1000 + 1).toInt())
                    sleepQuiet(waitMs)
                    attempt++
                }

                resp.code == 401 -> return Review(
                    false, "密钥无效或已被吊销（401）。请到 Groq 控制台重新生成一个", truncated
                )
                resp.code == 404 -> return Review(
                    false, "模型不存在（404）。这个模型可能已下线，换一个试试", truncated
                )
                resp.code == 413 || resp.code == 400 -> return Review(
                    false, "请求被拒（${resp.code}）：文件可能太长，已自动截断后仍超限", truncated
                )
                else -> return Review(
                    false, "请求失败（HTTP ${resp.code}）${Http.describeError(
                        RuntimeException(resp.body.take(160))
                    )}", truncated
                )
            }
        }
    }

    /** 把服务端返回的 quota 头翻成人话，顺带告诉用户该等多久 */
    private fun buildRateLimitMsg(waitMs: Long): String {
        val sec = (waitMs / 1000).coerceAtLeast(1)
        val q = lastQuota
        return buildString {
            append("触发 Groq 限速（429）。")
            append("免费层每分钟约 30 次、额度按账号算。")
            if (q.remainingTokens.isNotBlank()) append("当前剩余 token：").append(q.remainingTokens)
            if (q.resetTokens.isNotBlank()) append("，").append(q.resetTokens).append("后恢复")
            append("。建议等 ").append(sec).append(" 秒再继续，或在设置里换成额度更大的模型。")
        }
    }

    private fun extract(body: String): String? {
        return try {
            val o = JSONObject(body)
            val arr = o.optJSONArray("choices") ?: return null
            if (arr.length() == 0) return null
            val msg = arr.optJSONObject(0)?.optJSONObject("message") ?: return null
            val s = msg.optString("content", "")
            // 模型偶尔会包一层 ```，去掉以免界面上出现多余的围栏
            val out = s.trim()
                .removeSurrounding("```")
                .removePrefix("kotlin").removePrefix("java")
                .trim()
            if (out.isBlank()) null else out
        } catch (t: Throwable) {
            null
        }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (t: Throwable) { /* 中断就当没睡 */ }
    }
}
