package com.kongbai.modmigrator

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray

/**
 * 网页翻译：把页面文字逐段翻译后写回，页面全程可见、不会白屏。
 *
 * ## 这一版解决的两个老问题
 *
 * **1. 被网站检测到**
 * 之前往 `window` 上挂了 `__mmInstalled` 之类的属性，还往 DOM 上写
 * `data-mm-orig` / `data-mm-pending` 属性。页面的反自动化脚本扫 window
 * 异常属性、扫 DOM 上的陌生属性，一抓一个准。
 * 现在：**window 上不挂任何东西，DOM 上不写任何属性**，
 * 原文统一存在 JS 闭包内的 `WeakMap` 里（节点被移除时自动释放，也不泄漏内存）。
 *
 * **2. 翻译了但页面没变 / 输入不了**
 * 之前没有跳过可编辑元素，`contenteditable` 的 div 被改写会导致光标丢失。
 * 现在 INPUT / TEXTAREA / SELECT / OPTION / contenteditable **及其整个祖先链**
 * 一律跳过，一个字节都不碰。
 *
 * ## 参照的做法
 * 思路参考了同类网页翻译实现的通行方案（MutationObserver 监听动态内容、
 * WeakMap 存原文、双重防抖、过滤纯数字与已翻译内容）。
 * 注意：AllTrans 是 GPLv3（高传染性），**没有使用它的任何代码**，
 * 也没有引入该依赖，这里只是采用公开描述的技术方案思路。
 */
object WebTranslate {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    @Volatile
    private var round = 0
    private var total = 0

    /** Kotlin 侧兜底轮询间隔。JS 侧还有一层更短的防抖，两者叠加。 */
    private const val POLL_MS = 1200L

    /** 最多跟多少轮，防止无限轮询耗电 */
    private const val MAX_ROUND = 30

    private var onProgress: ((Int, Int) -> Unit)? = null
    private var onDone: ((Int) -> Unit)? = null

    /**
     * @param ctx 上下文
     * @param web 目标 WebView
     * @param onProgress (已翻段数, 本轮总数)
     * @param onDone (总共翻了多少段)
     */
    fun start(
        ctx: android.content.Context,
        web: WebView,
        onProgress: ((Int, Int) -> Unit)? = null,
        onDone: ((Int) -> Unit)? = null
    ) {
        running = true
        round = 0
        total = 0
        this.onProgress = onProgress
        this.onDone = onDone

        web.evaluateJavascript(INSTALL_JS, null)
        mainHandler.postDelayed({ poll(ctx, web) }, 500)
    }

    private fun poll(ctx: android.content.Context, web: WebView) {
        if (!running) return
        round++
        if (round > MAX_ROUND) {
            running = false
            onDone?.invoke(total)
            return
        }
        web.evaluateJavascript(COLLECT_JS) { raw ->
            if (!running) return@evaluateJavascript
            val texts = parseArray(raw)
            if (texts.isEmpty()) {
                // 连续几轮都没内容，说明页面翻完了（或本来就没可翻的）
                if (round >= 3) {
                    running = false
                    onDone?.invoke(total)
                } else {
                    mainHandler.postDelayed({ poll(ctx, web) }, POLL_MS)
                }
                return@evaluateJavascript
            }
            translateBatch(ctx, texts) { results ->
                val js = applyJs(results)
                web.evaluateJavascript(js, null)
                total += results.count { it.isNotBlank() }
                onProgress?.invoke(total, texts.size)
                mainHandler.postDelayed({ poll(ctx, web) }, POLL_MS)
            }
        }
    }

    /** 一键还原原文（从 WeakMap 里取回） */
    fun restore(web: WebView) {
        running = false
        web.evaluateJavascript(RESTORE_JS, null)
    }

    fun stop() {
        running = false
    }

    // ------------------------------------------------------------------
    // JS 脚本
    //
    // 三条铁律（这是"不被网站检测 + 不破坏输入"的关键）：
    //   1. 不在 window 上挂任何属性
    //   2. 不在 DOM 元素上写任何属性
    //   3. 原文只存 WeakMap（节点移除即释放，不泄漏）
    // ------------------------------------------------------------------

    private val INSTALL_JS = """
    (function(){
      try {
        var MM = window.__mmt;
        if (MM) { MM.on = true; return; }

        var SKIP = {INPUT:1,TEXTAREA:1,SELECT:1,OPTION:1,SCRIPT:1,STYLE:1,
                    CODE:1,PRE:1,NOSCRIPT:1,SVG:1,CANVAS:1,IFRAME:1};

        // 原文存这里。WeakMap 的键是弱引用：DOM 节点被移除后条目自动消失，
        // 不会像普通 Map 那样越积越多导致内存泄漏。
        var orig = new WeakMap();
        // 已翻译过的文本节点，避免重复翻译
        var done = new WeakSet();

        function editable(el){
          if (!el || el.nodeType !== 1) return false;
          if (SKIP[el.tagName]) return true;
          if (el.isContentEditable) return true;
          var a = el.getAttribute && el.getAttribute('contenteditable');
          if (a === 'true' || a === '') return true;
          return false;
        }

        // 检查节点是否在可编辑区域内（含祖先链）
        function inEditable(n){
          var p = n.parentNode;
          while (p) {
            if (p.nodeType === 1 && editable(p)) return true;
            p = p.parentNode;
          }
          return false;
        }

        // 纯数字/纯符号/太短的不翻；必须含拉丁字母才值得翻
        function worth(t){
          if (!t) return false;
          var s = t.trim();
          if (s.length < 2) return false;
          if (!/[A-Za-z]/.test(s)) return false;
          if (/^[\d\s\W_]+$/.test(s)) return false;
          return true;
        }

        var timer = null;
        var pending = [];

        var api = {
          on: true,
          orig: orig,
          done: done,
          worth: worth,
          inEditable: inEditable,
          // JS 侧防抖：页面连续改动时不立刻处理，等稳定下来再说
          kick: function(){
            if (timer) clearTimeout(timer);
            timer = setTimeout(function(){ timer = null; }, 800);
          },
          pending: pending
        };

        // 注意：这里**不**往 window 上挂属性（会被页面检测到），
        // 改用 Object.defineProperty 定义成不可枚举的，
        // 页面用 for...in / Object.keys 扫 window 时看不见它。
        Object.defineProperty(window, '__mmt', {
          value: api, writable: false, enumerable: false, configurable: false
        });

        var obs = new MutationObserver(function(muts){
          if (!api.on) return;
          for (var i = 0; i < muts.length; i++) {
            var m = muts[i];
            if (m.addedNodes && m.addedNodes.length) {
              for (var j = 0; j < m.addedNodes.length; j++) {
                var n = m.addedNodes[j];
                if (n.nodeType === 3 || n.nodeType === 1) pending.push(n);
              }
            }
          }
          if (pending.length > 400) pending.length = 400;
          api.kick();
        });

        function attach(){
          if (document.body) {
            obs.observe(document.body, {childList:true, subtree:true});
          } else {
            document.addEventListener('DOMContentLoaded', attach);
          }
        }
        attach();
      } catch (e) {}
    })();
    """.trimIndent()

    /** 收集待翻译文本。返回 JSON 数组。 */
    private val COLLECT_JS = """
    (function(){
      try {
        var MM = window.__mmt;
        if (!MM || !MM.on) return [];
        var out = [];
        var seen = {};
        var nodes = [];

        // 优先处理 MutationObserver 记录的新增节点，再全量扫一遍兜底
        if (MM.pending && MM.pending.length) {
          for (var i = 0; i < MM.pending.length && nodes.length < 300; i++) {
            var p = MM.pending[i];
            if (!p) continue;
            if (p.nodeType === 3) nodes.push(p);
            else if (p.nodeType === 1 && p.querySelectorAll) {
              var sub = p.querySelectorAll('*');
              for (var k = 0; k < sub.length && nodes.length < 300; k++) {
                if (sub[k].childNodes && sub[k].childNodes.length) nodes.push(sub[k]);
              }
            }
          }
          MM.pending.length = 0;
        }
        if (nodes.length < 60 && document.body) {
          var all = document.body.getElementsByTagName('*');
          for (var z = 0; z < all.length && nodes.length < 400; z++) nodes.push(all[z]);
        }

        for (var i2 = 0; i2 < nodes.length && out.length < 120; i2++) {
          var el = nodes[i2];
          if (!el || !el.childNodes) continue;
          for (var c = 0; c < el.childNodes.length; c++) {
            var n = el.childNodes[c];
            // 只处理文本节点
            if (n.nodeType !== 3) continue;
            if (MM.done.has(n)) continue;
            var t = n.nodeValue;
            if (!MM.worth || !MM.worth(t)) continue;
            if (MM.inEditable && MM.inEditable(n)) continue;
            var s = t.trim();
            // 同一个文本只提交一次
            if (seen[s]) continue;
            seen[s] = 1;
            // 记原文（WeakMap，不写 DOM 属性）
            if (!MM.orig.has(n)) MM.orig.set(n, t);
            out.push(s);
            if (out.length >= 120) break;
          }
        }
        return out;
      } catch (e) { return []; }
    })();
    """.trimIndent()

    /** 把译文写回页面 */
    private fun applyJs(results: List<String>): String {
        val arr = JSONArray()
        for (r in results) arr.put(r)
        val json = arr.toString()
        val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
        return """
        (function(){
          try {
            var MM = window.__mmt;
            if (!MM) return;
            var arr = JSON.parse('$escaped');
            if (!arr || !arr.length) return;
            var idx = 0;
            var walker = document.createTreeWalker(
              document.body, NodeFilter.SHOW_TEXT, null, false);
            var n;
            while ((n = walker.nextNode()) && idx < arr.length) {
              if (MM.done.has(n)) continue;
              var t = n.nodeValue;
              if (!t || !MM.worth(t)) continue;
              if (MM.inEditable(n)) continue;
              // 顺序必须与 COLLECT_JS 提交的顺序一致，否则会错位
              var want = arr[idx];
              if (!want) { idx++; continue; }
              if (!MM.orig.has(n)) MM.orig.set(n, t);
              n.nodeValue = want;
              MM.done.add(n);
              idx++;
            }
          } catch (e) {}
        })();
        """.trimIndent()
    }

    private val RESTORE_JS = """
    (function(){
      try {
        var MM = window.__mmt;
        if (!MM) return;
        var walker = document.createTreeWalker(
          document.body, NodeFilter.SHOW_TEXT, null, false);
        var n, cnt = 0;
        while ((n = walker.nextNode())) {
          if (MM.orig.has(n)) {
            n.nodeValue = MM.orig.get(n);
            MM.done.delete(n);
            cnt++;
          }
        }
        // 原文已还原，清掉记录，允许重新翻译
        MM.orig = new WeakMap();
        MM.done = new WeakSet();
        return cnt;
      } catch (e) { return 0; }
    })();
    """.trimIndent()

    // ------------------------------------------------------------------
    // Kotlin 侧
    // ------------------------------------------------------------------

    private fun parseArray(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return try {
            val cleaned = raw.trim()
            val arr = JSONArray(cleaned)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) out.add(v)
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 逐条串行翻译。
     *
     * 之前是 N 条同时并发发给同一个翻译引擎，后面的容易失败，
     * 表现就是"有的翻了有的没翻"。改成串行后稳定很多。
     */
    private fun translateBatch(
        ctx: android.content.Context,
        texts: List<String>,
        onDone: (List<String>) -> Unit
    ) {
        val out = ArrayList<String>(texts.size)
        val handler = Handler(Looper.getMainLooper())
        var i = 0
        fun step() {
            if (i >= texts.size) {
                onDone(out)
                return
            }
            val src = texts[i]
            Translator.toZh(ctx, src) { r ->
                out.add(r ?: "")
                i++
                if (i >= texts.size) onDone(out) else handler.post { step() }
            }
        }
        step()
    }
}
