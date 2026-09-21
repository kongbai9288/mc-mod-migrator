package com.kongbai.modmigrator

object Translator {

    private const val MYMEMORY = "https://api.mymemory.translated.net/get"

    private val cache = LinkedHashMap<String, String>(300)

    /** 英文 -> 简体中文，失败返回 null。MyMemory 免费额度：匿名约 5000 词/天/IP */
    fun toZh(text: String): String? {
        val t = text.trim()
        if (t.isBlank()) return null
        cache[t]?.let { return it }
        if (!t.any { it.code in 65..122 }) return null
        val chunk = if (t.length > 450) t.substring(0, 450) else t
        val url = "$MYMEMORY?q=${Http.enc(chunk)}&langpair=en%7Czh-CN"
        return try {
            val o = Json.obj(Http.get(url)) ?: return null
            val data = o.asJsonObject.get("responseData") ?: return null
            val out = Json.s(data, "translatedText")
            if (out.isBlank() || out.equals(chunk, true) || out.startsWith("MYMEMORY WARNING")) {
                null
            } else {
                cache[t] = out
                out
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** 翻译代理页：把英文网页整页交给翻译服务渲染 */
    fun pageProxy(engine: String, url: String): String =
        when (engine) {
            "google" -> "https://translate.google.com/translate?sl=en&tl=zh-CN&u=" + Http.enc(url)
            "bing" -> "https://www.translatetheweb.com/?from=en&to=zh-CHS&a=" + Http.enc(url)
            else -> url
        }
}
