package com.kongbai.modmigrator

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
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

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun call(url: String, headers: Map<String, String> = emptyMap()): Response {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        for ((k, v) in headers) b.header(k, v)
        return client.newCall(b.build()).execute()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val r = call(url, headers)
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
}
