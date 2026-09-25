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
