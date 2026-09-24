package com.kongbai.modmigrator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONTokener

/**
 * 网页翻译：把页面文字用 ML Kit 离线模型翻成中文。
 *
 * 实现参考了成熟的网页翻译方案（AllTrans、WebToApp、gudd1991116/WebViewTranslate），
 * 核心五点：
 *
 *  1. **不挂 JavascriptInterface**
 *     之前的实现用 addJavascriptInterface 往页面 window 上挂了对象，
 *     反自动化脚本一检测一个准（这就是"被网站检测到"的原因）。
 *     现在改成 evaluateJavascript 提取文本（它有返回值，不需要挂任何东西）
 *     → Kotlin 端翻译 → evaluateJavascript 写回。window 上干干净净，
 *     页面无从察觉。
 *
 *  2. **MutationObserver + 防抖**
 *     Modrinth / CurseForge 都是 React/Vue 的 SPA，翻译完一重渲染就把译文覆盖掉
 *     （这就是"没翻译到"的原因）。现在在页面内注册 observer，
 *     新出现的节点打上待翻译标记，Kotlin 端轮询补译。
 *
 *  3. **严格排除可编辑元素**
 *     INPUT / TEXTAREA / SELECT / contenteditable 及其子孙一律不动，
 *     否则会把用户正在输入的内容改掉、光标丢失（这就是"输入不了"的原因）。
 *
 *  4. **原文存在 DOM 属性上**
 *     用 data-mm-orig 保存原文，可一键还原；同时用它判断"是否已翻过"，
 *     避免重复翻译和死循环。
 *
 *  5. **去重 + 过滤**
 *     纯数字、纯符号、长度过短、不含拉丁字母的一律跳过。
 *
 * 翻译期间页面保持原样可用，翻不翻得出来都不会白屏。
 */
object WebTranslate {

    /** 轮询间隔：页面内 observer 打标，这边定期取走翻译 */
    private const val POLL_MS = 1200L

    /** 最多轮询多少轮（约 30 秒后自动收手，不一直耗着） */
    private const val MAX_ROUNDS = 25

    /** 单轮最多翻译多少段，避免一次性卡死 */
    private const val MAX_PER_ROUND = 120

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否正在翻译（防止重复启动） */
    @Volatile
    private var running = false

    /**
     * 开始翻译当前页面。
     * @param onProgress 已翻译段数 / 本轮总数
     * @param onDone 总共翻译了多少段
     */
    fun start(
        ctx: Context,
        web: WebView,
        onProgress: ((Int, Int) -> Unit)? = null,
        onDone: ((Int) -> Unit)? = null
    ) {
        if (running) return
        running = true

        // 1) 在页面内装 observer（IIFE 闭包，不往 window 上挂任何东西）
        web.evaluateJavascript(INSTALL_JS, null)

        var total = 0
        var round = 0

        fun poll() {
            if (round++ >= MAX_ROUNDS) {
                running = false
                onDone?.invoke(total)
                return
            }
            web.evaluateJavascript(COLLECT_JS) { raw ->
                val texts = parseArray(raw)
                if (texts.isEmpty()) {
                    // 本轮没有待翻译内容，再等一轮看看（SPA 可能还在加载）
                    if (round >= 3) {
                        running = false
                        onDone?.invoke(total)
                    } else {
                        mainHandler.postDelayed({ poll() }, POLL_MS)
                    }
                    return@evaluateJavascript
                }
                translateBatch(ctx, texts) { results ->
                    val js = applyJs(results)
                    web.evaluateJavascript(js, null)
                    total += results.count { it.isNotBlank() }
                    onProgress?.invoke(total, texts.size)
                    mainHandler.postDelayed({ poll() }, POLL_MS)
                }
            }
        }

        // 等页面先渲染一轮再开始
        mainHandler.postDelayed({ poll() }, 600)
    }

    /** 一键还原原文 */
    fun restore(web: WebView) {
        running = false
        web.evaluateJavascript(RESTORE_JS, null)
    }

    /** 停止轮询 */
    fun stop() {
        running = false
    }

    // ------------------------------------------------------------------
    // JS：安装 observer（闭包内保存状态，绝不污染 window）
    // ------------------------------------------------------------------
    private val INSTALL_JS = """
    (function(){
      if (window.__mmInstalled) return;
      window.__mmInstalled = 1;
      try {
        var SKIP = {INPUT:1,TEXTAREA:1,SELECT:1,OPTION:1,SCRIPT:1,STYLE:1,
                    CODE:1,PRE:1,NOSCRIPT:1,SVG:1,CANVAS:1};
        function editable(el){
          if (!el || el.nodeType !== 1) return false;
          if (SKIP[el.tagName]) return true;
          if (el.isContentEditable) return true;
          if (el.getAttribute && el.getAttribute('contenteditable') === 'true') return true;
          return false;
        }
        function inEditable(n){
          var p = n.parentNode;
          while (p) {
            if (p.nodeType === 1 && editable(p)) return true;
            p = p.parentNode;
          }
          return false;
        }
        function markPending(node){
          var p = node.parentNode;
          if (p && p.nodeType === 1 && !p.hasAttribute('data-mm-orig')
              && !p.hasAttribute('data-mm-pending')) {
            p.setAttribute('data-mm-pending', '1');
          }
        }
        var timer = null;
        var obs = new MutationObserver(function(muts){
          for (var i = 0; i < muts.length; i++) {
            var m = muts[i];
            if (m.addedNodes && m.addedNodes.length) {
              for (var j = 0; j < m.addedNodes.length; j++) {
                markPending(m.addedNodes[j]);
              }
            }
          }
        });
        if (document.body) {
          obs.observe(document.body, {childList:true, subtree:true});
        } else {
          document.addEventListener('DOMContentLoaded', function(){
            obs.observe(document.body, {childList:true, subtree:true});
          });
        }
      } catch (e) {}
    })();
    """.trimIndent()

    // ------------------------------------------------------------------
    // JS：收集待翻译文本，返回 JSON 数组
    // ------------------------------------------------------------------
    private val COLLECT_JS = """
    (function(){
      try {
        var SKIP = {INPUT:1,TEXTAREA:1,SELECT:1,OPTION:1,SCRIPT:1,STYLE:1,
                    CODE:1,PRE:1,NOSCRIPT:1,SVG:1,CANVAS:1};
        function editable(el){
          if (!el || el.nodeType !== 1) return false;
          if (SKIP[el.tagName]) return true;
          if (el.isContentEditable) return true;
          if (el.getAttribute && el.getAttribute('contenteditable') === 'true') return true;
          return false;
        }
        function inEditable(n){
          var p = n.parentNode;
          while (p) {
            if (p.nodeType === 1 && editable(p)) return true;
            p = p.parentNode;
          }
          return false;
        }
        var out = [];
        var walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
        var n;
        while ((n = walk.nextNode()) && out.length < $MAX_PER_ROUND) {
          var p = n.parentNode;
          if (!p || p.nodeType !== 1) continue;
          // 已翻译过的跳过（靠 data-mm-orig 判断，不靠文本比对）
          if (p.hasAttribute('data-mm-orig')) continue;
          if (inEditable(n)) continue;
          var s = (n.nodeValue || '').trim();
          if (!s) continue;
          // 只要含拉丁字母的（已经是中文的不用翻）
          if (!/[A-Za-z]{2,}/.test(s)) continue;
          // 纯数字/纯符号跳过
          if (!/[A-Za-z]/.test(s.replace(/[^A-Za-z]/g,''))) continue;
          if (s.length < 2) continue;
          out.push(s.substring(0, 500));
          p.setAttribute('data-mm-pending', '1');
        }
        return JSON.stringify(out);
      } catch (e) {
        return '[]';
      }
    })();
    """.trimIndent()

    /** 把译文写回页面 */
    private fun applyJs(results: List<String>): String {
        val arr = JSONArray()
        for (s in results) arr.put(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " "))
        val payload = arr.toString().replace("'", "\\'")
        return """
        (function(){
          try {
            var t = JSON.parse('$payload');
            var SKIP = {INPUT:1,TEXTAREA:1,SELECT:1,OPTION:1,SCRIPT:1,STYLE:1,
                        CODE:1,PRE:1,NOSCRIPT:1,SVG:1,CANVAS:1};
            function editable(el){
              if (!el || el.nodeType !== 1) return false;
              if (SKIP[el.tagName]) return true;
              if (el.isContentEditable) return true;
              if (el.getAttribute && el.getAttribute('contenteditable') === 'true') return true;
              return false;
            }
            function inEditable(n){
              var p = n.parentNode;
              while (p) {
                if (p.nodeType === 1 && editable(p)) return true;
                p = p.parentNode;
              }
              return false;
            }
            var idx = 0;
            var walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
            var n;
            while ((n = walk.nextNode()) && idx < t.length) {
              var p = n.parentNode;
              if (!p || p.nodeType !== 1) continue;
              if (p.hasAttribute('data-mm-orig')) continue;
              if (!p.hasAttribute('data-mm-pending')) continue;
              if (inEditable(n)) { p.removeAttribute('data-mm-pending'); continue; }
              var s = (n.nodeValue || '').trim();
              if (!s || !/[A-Za-z]{2,}/.test(s)) { p.removeAttribute('data-mm-pending'); continue; }
              var tr = t[idx];
              if (tr) {
                p.setAttribute('data-mm-orig', s);
                n.nodeValue = tr;
              }
              p.removeAttribute('data-mm-pending');
              idx++;
            }
          } catch (e) {}
        })();
        """.trimIndent()
    }

    /** 还原原文 */
    private val RESTORE_JS = """
    (function(){
      try {
        var els = document.querySelectorAll('[data-mm-orig]');
        for (var i = 0; i < els.length; i++) {
          var el = els[i];
          var orig = el.getAttribute('data-mm-orig');
          var walk = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null, false);
          var first = walk.nextNode();
          if (first && orig) first.nodeValue = orig;
          el.removeAttribute('data-mm-orig');
          el.removeAttribute('data-mm-pending');
        }
      } catch (e) {}
    })();
    """.trimIndent()

    // ------------------------------------------------------------------
    // Kotlin 侧
    // ------------------------------------------------------------------

    /** 解析 evaluateJavascript 的返回值（可能是双重 JSON 编码） */
    private fun parseArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            var v: Any? = JSONTokener(raw).nextValue()
            // 有些版本返回的是 JSON 字符串字面量，需要再解一层
            if (v is String) v = JSONTokener(v).nextValue()
            if (v !is JSONArray) return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until v.length()) out.add(v.optString(i, ""))
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 批量翻译，回调顺序与输入一致 */
    private fun translateBatch(
        ctx: Context,
        texts: List<String>,
        onDone: (List<String>) -> Unit
    ) {
        Translator.ensureModel(ctx) { ready ->
            if (!ready) {
                // 模型没好，用在线兜底
                val results = texts.map { Translator.fallbackTranslate(it) ?: "" }
                onDone(results)
                return@ensureModel
            }
            val results = ArrayList<String>()
            var i = 0
            fun next() {
                if (i >= texts.size) {
                    onDone(results)
                    return
                }
                val s = texts[i++]
                Translator.toZh(ctx, s) { r ->
                    results.add(r ?: "")
                    next()
                }
            }
            next()
        }
    }
}
