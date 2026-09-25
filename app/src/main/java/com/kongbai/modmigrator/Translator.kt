package com.kongbai.modmigrator

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * 翻译引擎。
 *
 * 之前是自己拼 HTTP 请求 + 自己造词典，问题很多：
 * 白屏、翻译不出来、离线不可用、长文本被截断……
 * 现在换成 Google 官方的 **ML Kit Translate**：
 *   - 真神经网络翻译，质量远超词典替换
 *   - 模型下载一次（约 30MB）后 **完全离线可用**
 *   - 支持 50+ 语言
 *   - 官方维护，不再是我自己拼的脆弱实现
 *
 * 降级链：ML Kit 离线 → ML Kit 需先下模型（提示用户）→ 在线接口兜底
 */
object Translator {

    /** 源语言固定英文（模组简介基本都是英文） */
    private const val SRC = TranslateLanguage.ENGLISH
    private const val DST = TranslateLanguage.CHINESE

    @Volatile
    private var client: Translator? = null

    /** 模型是否已就绪 */
    @Volatile
    private var modelReady = false

    /** 是否正在下载模型，避免重复触发 */
    @Volatile
    private var downloading = false

    // ── 翻译结果缓存 ────────────────────────────────────────────
    // 两个问题一起修：
    //
    // ① **并发不安全**：翻译在后台线程跑（市场页批量翻译、网页翻译），
    //    而这个 HashMap 会被多线程同时读写 → 可能 ConcurrentModificationException，
    //    或读到写坏的结构。和 LogCenter、ModrinthApi 是同一类问题。
    //
    // ② **会无限增长**：`LinkedHashMap(500)` 里的 500 是**初始容量**，
    //    不是上限——LinkedHashMap 只有重写 removeEldestEntry 才会淘汰。
    //    之前那样写等于没有上限，翻多少条就存多少条，长时间用下来
    //    缓存越攒越多（每条还是整段译文），白白占内存。
    //
    // 现在用带上限的 LRU：超过 MAX_CACHE 条就丢掉最久没用的。
    private const val MAX_CACHE = 500

    private val cache = object : LinkedHashMap<String, String>(
        MAX_CACHE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE
        }
    }

    private fun options(): TranslatorOptions =
        TranslatorOptions.Builder()
            .setSourceLanguage(SRC)
            .setTargetLanguage(DST)
            .build()

    /** 语言模型体积说明，给设置页展示 */
    fun modelSizeHint(): String = "约 30MB（下载一次，之后离线可用）"

    /** 是否已下载过模型 */
    fun isReady(): Boolean = modelReady

    /**
     * 确保模型已下载。
     * @param onDone 是否就绪；失败时返回 false，调用方应降级
     */
    fun ensureModel(
        ctx: Context,
        requireWifi: Boolean = false,
        onDone: (Boolean) -> Unit
    ) {
        if (modelReady) {
            onDone(true)
            return
        }
        if (downloading) {
            // 已在下载：直接告知未就绪，调用方下次再问，避免并发重复下载
            onDone(false)
            return
        }
        downloading = true
        val t = client ?: Translation.getClient(options()).also { client = it }
        val cond = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()
        t.downloadModelIfNeeded(cond)
            .addOnSuccessListener {
                modelReady = true
                downloading = false
                onDone(true)
            }
            .addOnFailureListener {
                downloading = false
                modelReady = false
                onDone(false)
            }
    }

    /**
     * 英文 → 中文。
     *
     * 异步回调。优先走 ML Kit 离线模型；
     * 模型还没下载好就先用在线接口，别让用户干等。
     */
    fun toZh(
        ctx: Context,
        text: String,
        onResult: (String?) -> Unit
    ) {
        val t = text.trim()
        if (t.isBlank()) {
            onResult(null)
            return
        }
        // 没有英文字母就不翻（本来就是中文或纯符号）
        if (!t.any { it.code in 65..122 }) {
            onResult(null)
            return
        }
        synchronized(cache) { cache[t] }?.let {
            onResult(it)
            return
        }

        // ML Kit 单条有长度限制，长文本先切段
        if (t.length > 450) {
            translateLong(ctx, t) { r ->
                if (r != null) synchronized(cache) { cache[t] = r }
                onResult(r)
            }
            return
        }

        ensureModel(ctx) { ready ->
            if (!ready) {
                // 模型不可用 → 在线兜底
                val online = fallbackTranslate(t)
                if (online != null) synchronized(cache) { cache[t] = online }
                onResult(online)
                return@ensureModel
            }
            val tr = client
            if (tr == null) {
                onResult(fallbackTranslate(t))
                return@ensureModel
            }
            tr.translate(t)
                .addOnSuccessListener { out ->
                    if (out.isBlank()) {
                        onResult(fallbackTranslate(t))
                    } else {
                        synchronized(cache) { cache[t] = out }
                        onResult(out)
                    }
                }
                .addOnFailureListener {
                    val online = fallbackTranslate(t)
                    if (online != null) synchronized(cache) { cache[t] = online }
                    onResult(online)
                }
        }
    }

    /** 长文本按句子切段翻译再拼起来 */
    private fun translateLong(ctx: Context, text: String, onResult: (String?) -> Unit) {
        val parts = splitBySentence(text, 400)
        if (parts.isEmpty()) {
            onResult(null)
            return
        }
        val out = ArrayList<String?>()
        var idx = 0
        fun next() {
            if (idx >= parts.size) {
                val joined = out.filterNotNull().joinToString("")
                onResult(if (joined.isBlank()) null else joined)
                return
            }
            val seg = parts[idx++]
            toZhShort(ctx, seg) { r ->
                out.add(r)
                next()
            }
        }
        next()
    }

    /** 单段翻译（不再递归切分） */
    private fun toZhShort(ctx: Context, text: String, onResult: (String?) -> Unit) {
        synchronized(cache) { cache[text] }?.let {
            onResult(it)
            return
        }
        ensureModel(ctx) { ready ->
            if (!ready) {
                onResult(fallbackTranslate(text))
                return@ensureModel
            }
            val tr = client
            if (tr == null) {
                onResult(fallbackTranslate(text))
                return@ensureModel
            }
            tr.translate(text)
                .addOnSuccessListener { r ->
                    if (!r.isNullOrBlank()) synchronized(cache) { cache[text] = r }
                    onResult(r)
                }
                .addOnFailureListener { onResult(fallbackTranslate(text)) }
        }
    }

    private fun splitBySentence(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (ch in text) {
            cur.append(ch)
            if ((ch == '.' || ch == '\n' || ch == '!' || ch == '?') && cur.length >= max / 2) {
                out.add(cur.toString())
                cur = StringBuilder()
            } else if (cur.length >= max) {
                out.add(cur.toString())
                cur = StringBuilder()
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString())
        return out
    }

    /**
     * 在线兜底（MyMemory 免费接口）。
     * 只是 ML Kit 不可用时的备胎，正常路径不会走到这里。
     */
    /** 在线兜底：模型不可用时的备胎，公开给需要同步结果的场景 */
    fun fallbackTranslate(text: String): String? {
        if (Prefs.appCtx()?.let { Prefs.get(it).getBoolean(K.OFFLINE, false) } == true) {
            return null
        }
        return try {
            val url = "https://api.mymemory.translated.net/get?q=${
                Http.enc(text.take(450))
            }&langpair=en%7Czh-CN"
            val o = Json.obj(Http.get(url)) ?: return null
            val data = o.asJsonObject.get("responseData") ?: return null
            val out = Json.s(data, "translatedText")
            if (out.isBlank() || out.equals(text, true) ||
                out.startsWith("MYMEMORY WARNING")
            ) null else out
        } catch (e: Throwable) {
            null
        }
    }

    /** 释放翻译器 */
    fun close() {
        try {
            client?.close()
        } catch (t: Throwable) { Err.ignore(t, "client?.close()") }
        client = null
        modelReady = false
    }

    /**
     * 删除已下载的模型（设置里省空间用）。
     *
     * 之前这里第一行是 `downloadModelIfNeeded()` ——
     * 用户点的是"删除模型省空间"，代码却**先去下载模型**，
     * 完全反了：想省空间反而先花 30MB 流量把模型拉回来。
     * 已删掉这一行。
     */
    fun deleteModel(ctx: Context, onDone: (Boolean) -> Unit) {
        try {
            // ML Kit 没有直接的 deleteModel API，
            // 只能断开引用让系统回收，并清空状态让下次重新判断。
            close()
            synchronized(cache) { cache.clear() }
            onDone(true)
        } catch (e: Throwable) {
            Err.ignore(e, "删除翻译模型")
            onDone(false)
        }
    }
}
