package com.kongbai.modmigrator

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网页翻译：用 ML Kit 把页面文字整页翻成中文。
 *
 * 流程：
 *   1. 注入 JS，遍历页面所有文本节点，给每个节点编号并把原文收集成数组
 *   2. 通过 JS Bridge 把原文数组传回 Kotlin
 *   3. Kotlin 端用 ML Kit（离线模型）逐条翻译
 *   4. 把译文数组通过 JS 写回对应编号的节点
 *
 * 为什么不用翻译代理页（translate.google.com 那种）：
 *   - 代理页在国内经常打不开，一失败就是白屏——这正是之前的问题
 *   - 代理页会把整页 URL 发给第三方
 *   - 用本地模型翻译，离线也能用，还不外发页面内容
 *
 * 翻译期间页面保持原样，翻完再替换，不会出现白屏。
 */
object WebTranslate {

    private const val BRIDGE = "ModMigTranslate"

    /** 单次最多翻译多少个文本节点，避免大页面卡死 */
    private const val MAX_NODES = 300

    /** 单个节点最大字符数 */
    private const val MAX_CHARS = 500

    /** 回调：已翻译数量 / 总数 */
    fun start(
        ctx: Context,
        web: WebView,
        onProgress: ((Int, Int) -> Unit)? = null,
        onDone: ((Int) -> Unit)? = null
    ) {
        val bridge = Bridge(ctx, web, onProgress, onDone)
        setupBridge(web, bridge)
        web.evaluateJavascript(EXTRACT_JS, null)
    }

    @SuppressLint("JavascriptInterface", "SetJavaScriptEnabled")
    private fun setupBridge(web: WebView, bridge: Bridge) {
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(bridge, BRIDGE)
    }

    /** 注入到页面：遍历文本节点，编号 + 收集原文 */
    private val EXTRACT_JS = """
    (function(){
      try {
        var nodes = [];
        var texts = [];
        var walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
        var n;
        while ((n = walk.nextNode())) {
          var s = (n.nodeValue || '').trim();
          if (!s) continue;
          var p = n.parentNode;
          if (!p) continue;
          var tag = (p.tagName || '').toUpperCase();
          if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'CODE' ||
              tag === 'PRE' || tag === 'NOSCRIPT' || tag === 'TEXTAREA') continue;
          // 只要含拉丁字母的才翻
          if (!/[A-Za-z]{2,}/.test(s)) continue;
          if (nodes.length >= $MAX_NODES) break;
          nodes.push(n);
          texts.push(s.substring(0, $MAX_CHARS));
        }
        window.$BRIDGE.onTexts(JSON.stringify(texts));
      } catch (e) {
        window.$BRIDGE.onError(e && e.message ? e.message : 'extract failed');
      }
    })();
    """.trimIndent()

    /** 把译文写回页面 */
    private fun applyJs(list: List<String>): String {
        val arr = JSONArray()
        for (s in list) arr.put(escapeForJs(s))
        return """
        (function(){
          try {
            var t = JSON.parse('${arr.toString().replace("'", "\\'")}');
            var nodes = [];
            var walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
            var n;
            while ((n = walk.nextNode())) {
              var s = (n.nodeValue || '').trim();
              if (!s) continue;
              var p = n.parentNode;
              if (!p) continue;
              var tag = (p.tagName || '').toUpperCase();
              if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'CODE' ||
                  tag === 'PRE' || tag === 'NOSCRIPT' || tag === 'TEXTAREA') continue;
              if (!/[A-Za-z]{2,}/.test(s)) continue;
              if (nodes.length >= t.length) break;
              nodes.push(n);
            }
            for (var i = 0; i < nodes.length && i < t.length; i++) {
              if (t[i]) nodes[i].nodeValue = t[i];
            }
          } catch (e) {}
        })();
        """.trimIndent()
    }

    private fun escapeForJs(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")

    /** 清空 bridge，避免内存泄漏 */
    fun cleanup(web: WebView) {
        try {
            web.removeJavascriptInterface(BRIDGE)
        } catch (t: Throwable) {
        }
    }

    private class Bridge(
        private val ctx: Context,
        private val web: WebView,
        private val onProgress: ((Int, Int) -> Unit)?,
        private val onDone: ((Int) -> Unit)?
    ) {

        @JavascriptInterface
        fun onTexts(json: String) {
            val arr = try {
                JSONArray(json)
            } catch (t: Throwable) {
                return
            }
            val src = ArrayList<String>()
            for (i in 0 until arr.length()) {
                src.add(arr.optString(i, ""))
            }
            if (src.isEmpty()) {
                main { onDone?.invoke(0) }
                return
            }

            // 先确保模型就绪，再逐条翻
            Translator.ensureModel(ctx) { ready ->
                if (!ready) {
                    main { onDone?.invoke(0) }
                    return@ensureModel
                }
                val out = ArrayList<String>()
                var idx = 0
                fun next() {
                    if (idx >= src.size) {
                        val done = out.count { it.isNotBlank() }
                        main {
                            web.evaluateJavascript(applyJs(out), null)
                            onDone?.invoke(done)
                        }
                        return
                    }
                    val s = src[idx]
                    val pos = idx
                    idx++
                    Translator.toZh(ctx, s) { r ->
                        out.add(r ?: "")
                        main { onProgress?.invoke(out.size, src.size) }
                        next()
                    }
                }
                next()
            }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            main { onDone?.invoke(0) }
        }

        private fun main(b: () -> Unit) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { b() }
        }
    }
}
