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
		private const val ROOT = "https://$DOMAIN/assets/misode/index.html"
		private const val ASSET_ROOT = "misode"
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val listeners = mutableListOf<(MisodeEventData) -> Unit>()
	private lateinit var webView: WebView

	private val assetLoader = WebViewAssetLoader.Builder()
		.setDomain(DOMAIN)
		.addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
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
			if (url.host != DOMAIN) return null
			val path = (url.path ?: "").trimStart('/')
			val target = if (path.isEmpty() || !assetExists(path)) Uri.parse(ROOT) else url
			return assetLoader.shouldInterceptRequest(target)
		}

		override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
			// Keep in-app navigation; the page handles routing itself.
			return request.url.host != DOMAIN
		}
	}

	/** True when the requested path exists inside the bundled assets. */
	private fun assetExists(path: String): Boolean {
		val assetPath = "$ASSET_ROOT/$path"
		return try {
			context.assets.open(assetPath).use { true }
		} catch (_: IOException) {
			false
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
			callback((0 until array.length()).mapNotNull { i ->
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
			})
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
