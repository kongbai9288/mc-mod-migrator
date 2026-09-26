package com.kongbai.modmigrator

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Http {

    const val UA = "ModMigrator/1.0 (https://github.com/kongbai9288/mc-mod-migrator)"

    /**
     * 装上 WebView 的 cookie 桥：
     * 登录在内置浏览器里完成，cookie 存在 WebView 的 CookieManager 里，
     * OkHttp 从这里读，两边才是同一份登录态。
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(WebCookies())
        .build()

    /**
     * 批量请求用的短超时客户端。
     *
     * 扫描模组时要对几十上百个文件逐个反查，
     * 主 client 的 readTimeout 是 120 秒——一个请求卡住就要等 2 分钟，
     * 几十个下来用户以为应用死了。批量场景改用短超时，
     * 连不上就快速失败并跳过，最后统一告诉用户哪些没查到。
     */
    const val SHORT = 1
    const val NORMAL = 0

    private val shortClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(WebCookies())
        .build()

    /** 把异常翻译成用户能看懂的话 */
    fun describeError(t: Throwable): String {
        val m = (t.message ?: "").lowercase()
        return when {
            m.contains("timeout") || m.contains("timed out") -> "连接超时"
            m.contains("unable to resolve") || m.contains("unknownhost") -> "域名解析失败"
            m.contains("connectionreset") || m.contains("reset") -> "连接被重置"
            m.contains("failed to connect") || m.contains("econnrefused") -> "连不上服务器"
            m.contains("network is unreachable") -> "网络不可用"
            // 401 单列：CurseForge 官方博客明确说明，自 2025-07-16 起
            // edge.forgecdn.net 的直连下载**强制要求 API Key**，
            // 不带有效 Key 一律返回 401 Unauthorized。
            // 之前 401 被笼统归到"请求被拒绝"，用户只知道下载失败，
            // 不知道根因是没填 Key。这里直说，省得反复试。
            m.contains("http 401") -> "未授权（401）：API Key 无效或没填。CurseForge 官方 CDN 已强制要求 Key，请在设置里填写，或改用镜像"
            m.startsWith("http 4") -> "请求被拒绝（${t.message?.take(24)}）"
            m.startsWith("http 5") -> "服务器出错（${t.message?.take(24)}）"
            m.startsWith("http") -> "HTTP 异常（${t.message?.take(24)}）"
            else -> t.message?.take(40) ?: "未知错误"
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun call(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = NORMAL): Response {
        val c = if (timeout == SHORT) shortClient else client
        val b = Request.Builder().url(url).header("User-Agent", UA)
        for ((k, v) in headers) b.header(k, v)
        return c.newCall(b.build()).execute()
    }

    /**
     * HEAD 请求：只取响应头，不下载正文。
     *
     * 探测文件长度、是否支持断点续传这类场景必须用 HEAD ——
     * 之前全用 GET，服务器会把整个文件发过来再被丢弃，
     * 等于**每下一个模组前先白下载一遍**，流量和时间都翻倍。
     * 部分服务器不支持 HEAD（返回 405），调用方要准备回退。
     */
    fun head(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = NORMAL): Response {
        val c = if (timeout == SHORT) shortClient else client
        val b = Request.Builder().url(url).head().header("User-Agent", UA)
        for ((k, v) in headers) b.header(k, v)
        return c.newCall(b.build()).execute()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = NORMAL): String {
        val r = call(url, headers, timeout)
        r.use {
            val body = it.body?.string() ?: ""
            if (!it.isSuccessful) throw RuntimeException("HTTP ${it.code} ${body.take(160)}")
            // 流量统计：移动网络下累计，超阈值由调用方提示
            runCatching {
                Prefs.appCtx()?.let { c -> Traffic.record(c, body.length.toLong() + 512) }
            }
            return body
        }
    }

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        for ((k, v) in headers) b.header(k, v)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        b.post(body)
        val r = client.newCall(b.build()).execute()
        r.use {
            val s = it.body?.string() ?: ""
            if (!it.isSuccessful) throw RuntimeException("HTTP ${it.code} ${s.take(200)}")
            return s
        }
    }

    /**
     * POST JSON，并**保留完整响应元信息**（状态码 + 响应头）。
     *
     * 给 Groq 这类带配额接口的调用用：它的限速信息全在响应头里
     * （`x-ratelimit-remaining-requests` / `x-ratelimit-remaining-tokens` /
     * `retry-after`），普通 `postJson` 一失败就抛异常，头信息全丢，
     * 调用方既不知道还剩多少配额，也不知道 429 之后该等多久。
     *
     * 注意：这里**不抛异常**，由调用方按 code 自行判断。
     */
    fun postDetailed(
        url: String, json: String, headers: Map<String, String>,
        timeout: Int = NORMAL
    ): Detailed {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        for ((k, v) in headers) b.header(k, v)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        b.post(body)
        val c = if (timeout == SHORT) shortClient else client
        val r = c.newCall(b.build()).execute()
        r.use {
            val s = try { it.body?.string() ?: "" } catch (t: Throwable) { "" }
            runCatching {
                Prefs.appCtx()?.let { ctx -> Traffic.record(ctx, s.length.toLong() + 512) }
            }
            return Detailed(it.code, s, it.headers)
        }
    }

    /** 一次 POST 的完整结果 */
    class Detailed(
        val code: Int,
        val body: String,
        private val headers: okhttp3.Headers
    ) {
        /** 取响应头，取不到返回 null（不要返回 ""，那和"空值"分不清） */
        fun header(name: String): String? =
            try { headers[name] } catch (t: Throwable) { null }

        fun isOk(): Boolean = code in 200..299
    }

    fun put(url: String, json: String, headers: Map<String, String>): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        for ((k, v) in headers) b.header(k, v)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        b.put(body)
        val r = client.newCall(b.build()).execute()
        r.use {
            val s = it.body?.string() ?: ""
            if (!it.isSuccessful) throw RuntimeException("HTTP ${it.code} ${s.take(200)}")
            return s
        }
    }

    /**
     * 以 multipart/form-data 上传一个文件。
     *
     * 用于云盘备份：上传地址是用户自己在网盘上拿到的，
     * 各家网盘接受的字段名各不相同（file / upload / upfile…），
     * 这里按最常见的 `file` 提交；返回 false 时调用方提示用户重新取地址。
     *
     * @return 服务端返回 2xx 视为成功
     */
    fun postFile(
        url: String, file: java.io.File, fileName: String,
        field: String = "file", headers: Map<String, String> = emptyMap()
    ): Boolean {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart(
                field, fileName,
                file.asRequestBody("application/zip".toMediaType())
            )
            .build()
        val b = Request.Builder().url(url).header("User-Agent", UA)
        for ((k, v) in headers) b.header(k, v)
        b.post(body)
        val r = client.newCall(b.build()).execute()
        r.use {
            runCatching { Prefs.appCtx()?.let { c -> Traffic.record(c, file.length()) } }
            return it.isSuccessful
        }
    }
}
