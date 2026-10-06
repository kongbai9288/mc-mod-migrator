package io.misode.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * A drop-in Android view that runs the misode Minecraft data pack generators
 * completely offline inside a WebView.
 *
 * Typical use inside a Fragment / Activity:
 *
 * ```kotlin
 * val misode = MisodeView(context)
 * container.addView(misode, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
 * misode.addListener { if (it.type == MisodeEvent.READY) misode.openGenerator("loot_table") }
 * ```
 *
 * The web bundle is expected at `src/main/assets/misode/index.html`; CI copies
 * the output of the `misode-web` build there before assembling the AAR.
 */
class MisodeView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

	companion object {
		/** Fake origin the offline bundle is served from. */
		const val DOMAIN: String = "misode.local"
		/**
		 * ⚠️ 之前是 `https://$DOMAIN/assets/misode/index.html`。
		 *
		 * misode 是个单页应用，它自己解析 URL 路径做路由。
		 * 把 `/assets/misode/index.html` 当根路径加载，
		 * 它的路由就会把这一段当成生成器名字去匹配，
		 * 于是首页直接报 `Cannot find generator "/assets/misode/index.html"`。
		 *
		 * 根必须是 `/`：SPA 路由才会落在首页。
		 * 真实资源在 assets 的 `misode/` 子目录下，由下面的
		 * MisodePathHandler 做映射，两者解耦。
		 */
		private const val ROOT = "https://$DOMAIN/"
		private const val ASSET_ROOT = "misode"
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val listeners = mutableListOf<(MisodeEventData) -> Unit>()
	private lateinit var webView: WebView

	/**
	 * 外部资源拦截器。
	 *
	 * 网页包里只有代码，Minecraft 数据（ID 表、生成器结构）要另外取。
	 * 宿主可以在这里接上自己的缓存层（见 [MisodeDataCache]），
	 * 命中缓存时直接返回，页面就不需要联网。
	 *
	 * 返回 null 表示不接管，请求照常发给 WebView。
	 */
	var resourceInterceptor: ((Uri) -> WebResourceResponse?)? = null

	/**
	 * 是否把原生侧的生成器标题换成中文。
	 *
	 * bridge.getGenerators() 内部写死了英文词条，网页里的列表会跟随语言，
	 * 但原生拿到的 title 永远是英文。置 true 后用 [GeneratorZh] 补译。
	 */
	var chineseTitles: Boolean = true

	/**
	 * 把 `/<任意路径>` 映射到 assets 的 `misode/<任意路径>`。
	 *
	 * 为什么不直接用 AssetsPathHandler：
	 * 它只能把某个 URL 前缀映射到 assets 的**根**，
	 * 而打包时资源在 `assets/misode/` 子目录下，对不上。
	 *
	 * 另外这是 SPA：路由产生的路径（如某个生成器页）在 assets 里
	 * 并不存在，这时必须回退到 index.html，
	 * 否则一点进去就是 404 白屏。
	 */
	private class MisodePathHandler(private val ctx: Context) : WebViewAssetLoader.PathHandler {
		override fun handle(path: String): WebResourceResponse? {
			val rel = if (path.isEmpty() || path == "/") "$ASSET_ROOT/index.html"
			else "$ASSET_ROOT/" + path.trimStart('/')
			return try {
				WebResourceResponse(mimeOf(rel), null, ctx.assets.open(rel))
			} catch (_: Throwable) {
				// SPA 回退：路由路径在 assets 里没有实体文件
				try {
					WebResourceResponse("text/html", null, ctx.assets.open("$ASSET_ROOT/index.html"))
				} catch (_: Throwable) {
					null
				}
			}
		}

		/**
		 * Android 自带的 guessContentTypeFromName 对 .js / .mjs 常返回 null，
		 * 返回 null 的 mime 会让 WebView 拒绝执行脚本（表现为页面空白）。
		 * 这里显式补上打包里会出现的几种。
		 */
		private fun mimeOf(name: String): String = when {
			name.endsWith(".js") || name.endsWith(".mjs") -> "application/javascript"
			name.endsWith(".css") -> "text/css"
			name.endsWith(".json") -> "application/json"
			name.endsWith(".html") -> "text/html"
			name.endsWith(".svg") -> "image/svg+xml"
			name.endsWith(".png") -> "image/png"
			name.endsWith(".woff2") -> "font/woff2"
			name.endsWith(".woff") -> "font/woff"
			name.endsWith(".wasm") -> "application/wasm"
			else -> "application/octet-stream"
		}
	}

	private val assetLoader = WebViewAssetLoader.Builder()
		.setDomain(DOMAIN)
		.addPathHandler("/", MisodePathHandler(context))
		.build()

	init {
		createWebView()
	}

	/* ------------------------------------------------------------------ *
	 * WebView setup
	 * ------------------------------------------------------------------ */

	@SuppressLint("SetJavaScriptEnabled")
	private fun createWebView() {
		webView = WebView(context).apply {
			layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			)
			settings.apply {
				javaScriptEnabled = true
				domStorageEnabled = true
				databaseEnabled = true
				allowFileAccess = false
				allowContentAccess = false
				loadWithOverviewMode = false
				useWideViewPort = true
				builtInZoomControls = false
				displayZoomControls = false
				setSupportZoom(false)
				mediaPlaybackRequiresUserGesture = true
				// The bundle is self contained; never hit the network.
				cacheMode = WebSettings.LOAD_NO_CACHE
				// deepslate / spyglass use BigInt64Array + WASM free JS only.
				mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
			}
			webViewClient = MisodeWebViewClient()
			webChromeClient = WebChromeClient()
			addJavascriptInterface(NativeHost(), "MisodeNative")
		}
		addView(webView)
		webView.loadUrl(ROOT)
	}

	private inner class MisodeWebViewClient : WebViewClient() {
		override fun shouldInterceptRequest(
			view: WebView,
			request: WebResourceRequest,
		): WebResourceResponse? {
			val url = request.url
			if (url.host != DOMAIN) {
				// 外部数据（ID 表、生成器结构）交给宿主的缓存层；
				// 它不接管就放行给 WebView。
				return resourceInterceptor?.invoke(url)
			}
			// 交由 MisodePathHandler 处理：命中就返回实体，
			// 不命中回退 index.html（SPA 路由）。
			return assetLoader.shouldInterceptRequest(url)
		}

		override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
			// Keep in-app navigation; the page handles routing itself.
			return request.url.host != DOMAIN
		}
	}


	/* ------------------------------------------------------------------ *
	 * Native -> JS plumbing
	 * ------------------------------------------------------------------ */

	private inner class NativeHost {
		@JavascriptInterface
		fun onEvent(type: String, payload: String) {
			val event = MisodeEvent.fromJs(type) ?: return
			dispatch(MisodeEventData(event, json = payload))
		}

		@JavascriptInterface
		fun onReady() = dispatch(MisodeEventData(MisodeEvent.READY))

		@JavascriptInterface
		fun onRoute(path: String) = dispatch(MisodeEventData(MisodeEvent.ROUTE, path))

		@JavascriptInterface
		fun onOutput(output: String) = dispatch(MisodeEventData(MisodeEvent.OUTPUT, output))

		@JavascriptInterface
		fun onTitle(title: String) = dispatch(MisodeEventData(MisodeEvent.TITLE, title))
	}

	private fun dispatch(data: MisodeEventData) {
		mainHandler.post { listeners.toList().forEach { it(data) } }
	}

	/** Register a listener for events pushed by the page. */
	fun addListener(listener: (MisodeEventData) -> Unit) {
		mainHandler.post { listeners.add(listener) }
	}

	fun removeListener(listener: (MisodeEventData) -> Unit) {
		mainHandler.post { listeners.remove(listener) }
	}

	private fun eval(js: String, callback: (Any?) -> Unit) {
		mainHandler.post {
			webView.evaluateJavascript(js) { raw -> callback(unwrap(raw)) }
		}
	}

	private fun unwrap(raw: String?): Any? {
		if (raw == null || raw == "null") return null
		return try {
			val value = JSONTokener(raw).nextValue()
			if (value === JSONObject.NULL) null else value
		} catch (_: Exception) {
			raw
		}
	}

	private inline fun <reified T> evalTyped(js: String, crossinline block: (T?) -> Unit) {
		eval(js) {
			@Suppress("UNCHECKED_CAST")
			block(it as? T)
		}
	}

	private fun call(method: String, vararg args: String): String {
		val callArgs = args.joinToString(",")
		return "window.MisodeBridge ? window.MisodeBridge.$method($callArgs) : null"
	}

	/* ------------------------------------------------------------------ *
	 * Lifecycle / navigation
	 * ------------------------------------------------------------------ */

	/** True once the bridge has been installed by the page. */
	fun isReady(callback: (Boolean) -> Unit) =
		evalTyped<Boolean>(call("isReady")) { callback(it == true) }

	/** Reload the whole bundle. */
	fun reload() = mainHandler.post { webView.loadUrl(ROOT) }

	/**
	 * 跟随系统语言。
	 *
	 * 网页里 `Store.getLanguage()` 取不到设置时一律回退 `'en'`，
	 * 中文系统下也不会自己切过来 —— 这是「页面全是英文」的直接原因。
	 * 这里在页面就绪后按系统 Locale 显式切一次。
	 */
	fun applySystemLanguage(onApplied: ((String) -> Unit)? = null) {
		val sys = Locale.getDefault()
		val target = when (sys.language) {
			Locale.CHINESE.language -> when (sys.country.uppercase()) {
				"TW", "HK", "MO" -> "zh-tw"
				else -> "zh-cn"
			}
			Locale.JAPANESE.language -> "ja"
			Locale.KOREAN.language -> "ko"
			else -> "en"
		}
		chineseTitles = target == "zh-cn" || target == "zh-tw"
		getLanguage { cur ->
			if (cur != target) setLanguage(target)
			// 词条是异步 import 的，切完稍后复核一次，没生效再切一遍
			mainHandler.postDelayed({
				getLanguage { now -> if (now != target) setLanguage(target) }
			}, 800)
			onApplied?.invoke(target)
		}
	}

	/** Navigate to any in-app route, e.g. `/loot-table`. */
	fun navigate(path: String) = eval(call("navigate", quote(path))) {}

	/** Open a generator by id (`loot_table`) or url (`loot-table`). */
	fun openGenerator(idOrUrl: String) {
		eval(call("getGenerator", quote(idOrUrl))) { value ->
			val path = (value as? JSONObject)?.optString("path")
			if (path.isNullOrEmpty()) navigate("/${idOrUrl.replace('_', '-')}") else navigate(path)
		}
	}

	/** Let the WebView handle the back key when it has history. */
	fun goBack(): Boolean {
		if (webView.canGoBack()) {
			mainHandler.post { webView.goBack() }
			return true
		}
		return false
	}

	fun getCurrentPath(callback: (String) -> Unit) =
		evalTyped<String>(call("getPath")) { callback(it ?: "/") }

	fun getTitle(callback: (String) -> Unit) =
		evalTyped<String>(call("getTitle")) { callback(it ?: "") }

	/** Release the WebView. Call from `onDestroyView`. */
	fun destroy() {
		mainHandler.post {
			webView.removeJavascriptInterface("MisodeNative")
			webView.webViewClient = WebViewClient()
			webView.destroy()
			removeView(webView)
		}
	}

	/* ------------------------------------------------------------------ *
	 * Catalogue queries
	 * ------------------------------------------------------------------ */

	fun getGenerators(callback: (List<GeneratorInfo>) -> Unit) {
		eval(call("getGenerators")) { value ->
			val array = value as? JSONArray ?: return@eval callback(emptyList())
			val list = (0 until array.length()).mapNotNull { i ->
				val o = array.optJSONObject(i) ?: return@mapNotNull null
				GeneratorInfo(
					id = o.optString("id"),
					url = o.optString("url"),
					title = o.optString("title", o.optString("id")),
					path = o.optString("path", "/${o.optString("url")}"),
					category = o.optStringOrNull("category"),
					minVersion = o.optStringOrNull("minVersion"),
					maxVersion = o.optStringOrNull("maxVersion"),
					wiki = o.optStringOrNull("wiki"),
				)
			}
			// bridge 里写死了英文词条，这里按同一份中文档案补译
			callback(if (chineseTitles) GeneratorZh.apply(list) else list)
		}
	}

	fun getVersions(callback: (List<String>) -> Unit) =
		eval(call("getVersions")) { callback(jsonToStringList(it)) }

	fun getLanguages(callback: (List<LanguageInfo>) -> Unit) {
		eval(call("getLanguages")) { value ->
			val array = value as? JSONArray ?: return@eval callback(emptyList())
			callback((0 until array.length()).mapNotNull { i ->
				val o = array.optJSONObject(i) ?: return@mapNotNull null
				LanguageInfo(o.optString("code"), o.optString("name"))
			})
		}
	}

	fun getGuides(callback: (List<Pair<String, String>>) -> Unit) {
		eval(call("getGuides")) { value ->
			val array = value as? JSONArray ?: return@eval callback(emptyList())
			callback((0 until array.length()).mapNotNull { i ->
				val o = array.optJSONObject(i) ?: return@mapNotNull null
				o.optString("id") to o.optString("title")
			})
		}
	}

	fun getGeneratorHistory(callback: (List<String>) -> Unit) =
		eval(call("getGeneratorHistory")) { callback(jsonToStringList(it)) }

	fun clearHistory() = eval(call("clearHistory")) {}

	/* ------------------------------------------------------------------ *
	 * Settings
	 * ------------------------------------------------------------------ */

	fun setVersion(version: String) = eval(call("setVersion", quote(version))) {}
	fun getVersion(callback: (String?) -> Unit) = evalTyped<String>(call("getVersion")) { callback(it) }

	fun setTheme(theme: String) = eval(call("setTheme", quote(theme))) {}
	fun getTheme(callback: (String?) -> Unit) = evalTyped<String>(call("getTheme")) { callback(it) }

	fun setLanguage(language: String) = eval(call("setLanguage", quote(language))) {}
	fun getLanguage(callback: (String?) -> Unit) = evalTyped<String>(call("getLanguage")) { callback(it) }

	fun setFormat(format: String) = eval(call("setFormat", quote(format))) {}
	fun getFormat(callback: (String?) -> Unit) = evalTyped<String>(call("getFormat")) { callback(it) }

	fun setIndent(indent: String) = eval(call("setIndent", quote(indent))) {}
	fun getIndent(callback: (String?) -> Unit) = evalTyped<String>(call("getIndent")) { callback(it) }

	fun setHighlighting(enabled: Boolean) = eval(call("setHighlighting", enabled.toString())) {}
	fun getHighlighting(callback: (Boolean?) -> Unit) = evalTyped<Boolean>(call("getHighlighting")) { callback(it) }

	fun setSoundsVersion(version: String) = eval(call("setSoundsVersion", quote(version))) {}
	fun getSoundsVersion(callback: (String?) -> Unit) = evalTyped<String>(call("getSoundsVersion")) { callback(it) }

	fun setTreeViewMode(mode: String) = eval(call("setTreeViewMode", quote(mode))) {}
	fun getTreeViewMode(callback: (String?) -> Unit) = evalTyped<String>(call("getTreeViewMode")) { callback(it) }

	fun setColormap(colormap: String) = eval(call("setColormap", quote(colormap))) {}
	fun getColormap(callback: (String?) -> Unit) = evalTyped<String>(call("getColormap")) { callback(it) }

	/** One round trip instead of nine. */
	fun getSettings(callback: (MisodeSettings) -> Unit) {
		val js = """
			(function(){var b=window.MisodeBridge;if(!b)return null;return {
				version:b.getVersion(),theme:b.getTheme(),language:b.getLanguage(),
				format:b.getFormat(),indent:b.getIndent(),highlighting:b.getHighlighting(),
				soundsVersion:b.getSoundsVersion(),treeViewMode:b.getTreeViewMode(),
				openProject:b.getOpenProject()};})()
		""".trimIndent()
		eval(js) { value ->
			val o = value as? JSONObject ?: return@eval callback(
				MisodeSettings("", "dark", "en", "json", "2_spaces", true, "latest", "resources", "")
			)
			callback(
				MisodeSettings(
					version = o.optString("version"),
					theme = o.optString("theme", "dark"),
					language = o.optString("language", "en"),
					format = o.optString("format", "json"),
					indent = o.optString("indent", "2_spaces"),
					highlighting = o.optBoolean("highlighting", true),
					soundsVersion = o.optString("soundsVersion", "latest"),
					treeViewMode = o.optString("treeViewMode", "resources"),
					openProject = o.optString("openProject"),
				)
			)
		}
	}

	/* ------------------------------------------------------------------ *
	 * Projects & output
	 * ------------------------------------------------------------------ */

	fun getProjects(callback: (List<String>) -> Unit) = eval(call("getProjects")) { callback(jsonToStringList(it)) }

	fun getOpenProject(callback: (String?) -> Unit) = evalTyped<String>(call("getOpenProject")) { callback(it) }
	fun setOpenProject(name: String) = eval(call("setOpenProject", quote(name))) {}

	/** The last output produced by the open generator (JSON or SNBT). */
	fun getOutput(callback: (String?) -> Unit) = evalTyped<String>(call("getOutput")) { callback(it) }

	/** Fallback that reads the editor DOM when no output event fired yet. */
	fun scrapeOutput(callback: (String?) -> Unit) = evalTyped<String>(call("scrapeOutput")) { callback(it) }

	/** Coroutine friendly variant of [getOutput]. */
	suspend fun awaitOutput(): String? = suspendCancellableCoroutine { cont ->
		getOutput { cont.resume(it) }
	}

	/** Coroutine friendly variant of [getGenerators]. */
	suspend fun awaitGenerators(): List<GeneratorInfo> = suspendCancellableCoroutine { cont ->
		getGenerators { cont.resume(it) }
	}

	/** Escape hatch: run arbitrary JS inside the page and get the JSON result. */
	fun evaluate(js: String, callback: (Any?) -> Unit) = eval(js) { callback(it) }

	/** Names of every method exposed on `window.MisodeBridge`. */
	fun listBridgeMethods(callback: (List<String>) -> Unit) =
		eval(call("apiNames")) { callback(jsonToStringList(it)) }

	/* ------------------------------------------------------------------ *
	 * Helpers
	 * ------------------------------------------------------------------ */

	private fun quote(value: String): String =
		JSONObject.quote(value)

	private fun jsonToStringList(value: Any?): List<String> {
		val array = value as? JSONArray ?: return emptyList()
		return (0 until array.length()).mapNotNull { array.optString(it) }
	}

	private fun JSONObject.optStringOrNull(key: String): String? =
		if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
