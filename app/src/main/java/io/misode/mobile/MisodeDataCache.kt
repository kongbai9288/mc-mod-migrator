package io.misode.mobile

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * misode 网页运行时需要的**外部数据**（方块/物品 ID 表、生成器结构等）的本地缓存。
 *
 * # 为什么要这一层
 *
 * 网页包（assets/misode）里只有代码，不含 Minecraft 数据。
 * 它运行时要去 `raw.githubusercontent.com` 拉这些数据，
 * 拉不到就是一直「正在载入」——表现和代码坏了完全一样。
 *
 * 这里在 WebView 的请求链路上做拦截：
 *
 * 1. 命中本地缓存 → 直接给，**之后完全离线可用**；
 * 2. 没命中 → 依次尝试官方源和各个镜像，下载完落盘再给。
 *
 * 第一次用到某个版本会下一份（约 1 MB），之后不再联网。
 *
 * # 镜像
 *
 * 官方源在国内常常连不上，所以准备了一串备用地址，按顺序试，
 * 第一个成功的就用。全部失败则返回 null，让 WebView 自己处理
 * （不缓存失败的空文件，下次还会重试）。
 */
object MisodeDataCache {

	private const val TAG = "MisodeData"
	private const val DIR_NAME = "misode-data"

	/** 只接管这几个域名，其余请求一律放行。 */
	private val HOSTS = setOf(
		"raw.githubusercontent.com",
		"raw.staticdn.net",
		"cdn.jsdelivr.net",
		"fastly.jsdelivr.net",
		"gcore.jsdelivr.net",
	)

	/**
	 * 全部 22 个版本的 ref（对应 misode config.json 里的 `ref` / `id`）。
	 * 「下载全部版本」按钮按这个清单逐个拉。
	 */
	val VERSION_REFS: List<String> = listOf(
		"1.15.2", "1.16.5", "1.17.1", "1.18.1", "1.18.2",
		"1.19.2", "1.19.3", "1.19.4", "1.20.1", "1.20.2",
		"1.20.4", "1.20.6", "1.21.1", "1.21.3", "1.21.4",
		"1.21.5", "1.21.8", "1.21.10", "1.21.11", "26.1.2",
		"26.2", "26.3",
	)

	/** 每个版本要预取的文件（相对 `<ref>-summary/`）。 */
	private val PREFETCH_PARTS = listOf(
		"registries/data.min.json",
		"blocks/data.min.json",
		"item_components/data.min.json",
		"sounds/data.min.json",
	)

	private val client: OkHttpClient = OkHttpClient.Builder()
		.connectTimeout(12, TimeUnit.SECONDS)
		.readTimeout(30, TimeUnit.SECONDS)
		.followRedirects(true)
		.build()

	@Volatile
	private var root: File? = null

	fun init(context: Context) {
		if (root == null) {
			synchronized(this) {
				if (root == null) {
					root = File(context.filesDir, DIR_NAME).apply { mkdirs() }
				}
			}
		}
	}

	private fun dir(): File = root ?: error("MisodeDataCache.init() 还没调用")

	/* ------------------------------------------------------------------ *
	 * 拦截入口
	 * ------------------------------------------------------------------ */

	/**
	 * 给 WebViewClient.shouldInterceptRequest 用。
	 * 返回 null 表示这个请求不归它管，交给 WebView 自己走网络。
	 */
	fun resolve(uri: Uri): WebResourceResponse? {
		val r = root ?: return null
		val host = uri.host ?: return null
		if (host !in HOSTS) return null
		val path = uri.path
		if (path.isNullOrBlank() || path == "/") return null

		val file = fileFor(host, path)
		if (file.isFile && file.length() > 0) return serve(file, path)

		// 首次访问：同步下载。这里跑在 WebView 的后台线程上，
		// 阻塞是允许的，否则页面会先拿到一次失败再靠重试补上。
		return if (download(uri.toString(), file) && file.isFile && file.length() > 0) {
			serve(file, path)
		} else {
			Log.w(TAG, "取不到 $host$path")
			null
		}
	}

	private fun serve(file: File, path: String): WebResourceResponse {
		// ⚠️ 页面是 fetch() 跨域拿这些数据的。
		// GitHub 的官方源会带 CORS 头，我们自己返回也必须带上，
		// 否则浏览器直接把响应拦掉，表现为「载入中」卡住。
		val headers = mapOf(
			"Access-Control-Allow-Origin" to "*",
			"Access-Control-Allow-Methods" to "GET, OPTIONS",
			"Cache-Control" to "public, max-age=31536000",
		)
		return WebResourceResponse(mimeOf(path), null, 200, "OK", headers, FileInputStream(file))
	}

	/* ------------------------------------------------------------------ *
	 * 下载
	 * ------------------------------------------------------------------ */

	private fun download(url: String, target: File): Boolean {
		target.parentFile?.mkdirs()
		for (candidate in candidates(url)) {
			val tmp = File(target.parentFile, target.name + ".tmp")
			try {
				tmp.delete()
				client.newCall(Request.Builder().url(candidate).get().build()).execute().use { resp ->
					if (!resp.isSuccessful) {
						Log.d(TAG, "失败 ${resp.code} $candidate")
						return@use
					}
					val body = resp.body ?: return@use
					tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
					if (tmp.length() <= 0) return@use
					if (tmp.renameTo(target)) {
						Log.i(TAG, "已缓存 ${target.name}（${target.length()} B）← $candidate")
						return true
					}
					// 同一目标跨线程重命名可能失败，退一步用复制
					target.outputStream().use { out -> tmp.inputStream().copyTo(out) }
					tmp.delete()
					if (target.length() > 0) return true
				}
			} catch (t: Throwable) {
				Log.d(TAG, "异常 ${t.javaClass.simpleName} $candidate")
			} finally {
				if (tmp.exists() && target.isFile) tmp.delete()
			}
		}
		return false
	}

	/**
	 * 把 `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>`
	 * 展开成一串候选地址，官方排第一（最全、最及时），后面是镜像。
	 */
	private fun candidates(url: String): List<String> {
		val out = mutableListOf(url)
		val u = Uri.parse(url)
		val host = u.host ?: return out
		val segs = u.pathSegments
		if (segs.size < 4) return out

		val owner = segs[0]
		val repo = segs[1]
		val ref = segs[2]
		val rest = segs.drop(3).joinToString("/")

		if (host == "raw.githubusercontent.com") {
			// jsDelivr：国内相对好连，分支名里带点也能用
			out += "https://cdn.jsdelivr.net/gh/$owner/$repo@$ref/$rest"
			out += "https://fastly.jsdelivr.net/gh/$owner/$repo@$ref/$rest"
			out += "https://gcore.jsdelivr.net/gh/$owner/$repo@$ref/$rest"
			// jsDelivr 的国内社区镜像
			out += "https://jsd.onmicrosoft.cn/gh/$owner/$repo@$ref/$rest"
			out += "https://jsd.cdn.zzko.cn/gh/$owner/$repo@$ref/$rest"
			// staticdn：和 raw 同形
			out += "https://raw.staticdn.net/$owner/$repo/$ref/$rest"
			// 通用 gh 代理
			out += "https://gh.llkk.cc/$url"
			out += "https://mirror.ghproxy.com/$url"
			out += "https://gh-proxy.com/$url"
		}
		return out
	}

	/* ------------------------------------------------------------------ *
	 * 缓存布局
	 * ------------------------------------------------------------------ */

	private fun fileFor(host: String, path: String): File {
		val safe = path.trim('/').split('/').joinToString("/") { seg ->
			// 只保留文件名安全字符，避免奇怪路径写到目录外
			seg.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
		}
		return File(dir(), "$host/$safe")
	}

	private fun mimeOf(path: String): String = when {
		path.endsWith(".json") -> "application/json"
		path.endsWith(".png") -> "image/png"
		path.endsWith(".ogg") -> "audio/ogg"
		path.endsWith(".mcdoc") -> "text/plain"
		else -> "application/octet-stream"
	}

	/* ------------------------------------------------------------------ *
	 * 管理
	 * ------------------------------------------------------------------ */

	/** 已缓存文件数与总字节数。 */
	fun stats(): Pair<Int, Long> {
		val r = root ?: return 0 to 0L
		var n = 0
		var size = 0L
		r.walkTopDown().filter { it.isFile }.forEach { n++; size += it.length() }
		return n to size
	}

	fun clear() {
		root?.deleteRecursively()
		root?.mkdirs()
	}

	/**
	 * 预取某个版本的 ID 表。返回成功的文件数。
	 * 调用方负责放到后台线程。
	 */
	fun prefetchVersion(ref: String): Int {
		var ok = 0
		for (part in PREFETCH_PARTS) {
			val url = "https://raw.githubusercontent.com/misode/mcmeta/$ref-summary/$part"
			val f = fileFor("raw.githubusercontent.com", "/misode/mcmeta/$ref-summary/$part")
			if (f.isFile && f.length() > 0) { ok++; continue }
			if (download(url, f)) ok++
		}
		return ok
	}

	/** 生成器结构表（版本无关，一次约 2.6 MB，压后传输约 200 KB）。 */
	fun prefetchSchema(): Boolean {
		val url = "https://raw.githubusercontent.com/SpyglassMC/vanilla-mcdoc/generated/symbols.json"
		val f = fileFor("raw.githubusercontent.com", "/SpyglassMC/vanilla-mcdoc/generated/symbols.json")
		return (f.isFile && f.length() > 0) || download(url, f)
	}

	fun formatSize(bytes: Long): String = when {
		bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)
		bytes >= 1024 -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
		else -> "$bytes B"
	}
}
