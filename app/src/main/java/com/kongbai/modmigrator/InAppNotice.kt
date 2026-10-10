package com.kongbai.modmigrator

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用内活动弹窗。
 *
 * # 为什么不再用底部小弹窗
 *
 * 底部那条小黑条有两个硬伤，攒了几十个版本一直在：
 *
 *   1. **看不见**。多按几下按钮它就沉到最底下被内容挡住，
 *      而它又偏偏用来报「完成 / 失败」—— 最关键的信息最容易被漏掉。
 *   2. **一闪而过**。内容长一点就截断，想看全得一直盯着。
 *
 * 所以改成从顶部落下的卡片：位置固定在内容之上，能展开，
 * 能带按钮和进度，多条叠放也不互相盖。
 */
object InAppNotice {

    enum class Kind { INFO, OK, WARN, ERR, TASK }

    private class Slot(
        val id: Int,
        val kind: Kind,
        val title: String,
        var text: String,
        var view: View?,
        var tvText: TextView?,
        var bar: ProgressBar?,
        var handler: Runnable?
    )

    /** 每个 Activity 一套浮层 */
    private class Host(
        val act: Activity,
        val overlay: FrameLayout,
        val stack: LinearLayout
    )

    private val hosts = java.util.LinkedHashMap<Activity, Host>()
    private val slots = java.util.ArrayList<Slot>()
    private val seq = AtomicInteger(3000)
    private val ui = Handler(Looper.getMainLooper())
    private var current: WeakReference<Activity>? = null
    private var installed = false

    private const val MAX_VISIBLE = 3

    // ------------------------------------------------------------ 安装

    /**
     * 给所有 Activity 自动挂浮层。
     *
     * 用生命周期回调而不是每个页面手写一遍：项目里有几十个
     * Activity/Fragment，逐个改必然漏，而漏掉的那个就会退回底部小弹窗。
     */
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivityDestroyed(a: Activity) {
                hosts.remove(a)
                if (current?.get() === a) current = null
            }
            override fun onActivityPaused(a: Activity) {
                if (current?.get() === a) current = null
            }
            override fun onActivityResumed(a: Activity) {
                current = WeakReference(a)
                // setContentView 可能还没走完，post 一次确保能拿到 content
                ui.post { runCatching { attach(a) } }
            }
        })
    }

    private fun attach(a: Activity) {
        if (a.isFinishing || a.isDestroyed) return
        if (hosts.containsKey(a)) return
        val content = a.findViewById<FrameLayout>(android.R.id.content) ?: return

        val dm = a.resources.displayMetrics
        val pad = (10 * dm.density).toInt()

        val stack = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val overlay = FrameLayout(a).apply {
            // 关键点：浮层本身不消费触摸，否则底下的列表就划不动了。
            // 只有卡片自己是可点的。
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            addView(
                stack,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        content.addView(overlay)
        hosts[a] = Host(a, overlay, stack)

        // 挂完把还没消失的消息补画出来，切页面时不会丢
        for (s in slots) {
            if (s.view == null) continue
            runCatching {
                (s.view?.parent as? ViewGroup)?.removeView(s.view)
                stack.addView(s.view)
            }
        }
    }

    private fun host(): Host? {
        val a = current?.get()
        if (a != null && !a.isFinishing && !a.isDestroyed) {
            val h = hosts[a]
            if (h != null) return h
            runCatching { attach(a) }
            return hosts[a]
        }
        return hosts.values.lastOrNull()
    }

    // ------------------------------------------------------------ 对外

    /** 一条普通提示。duration<=0 表示常驻，需要手动 dismiss */
    fun show(
        title: String, text: String = "", kind: Kind = Kind.INFO, durationMs: Long = 3200
    ): Int {
        val id = seq.incrementAndGet()
        ui.post {
            val s = Slot(id, kind, title, text, null, null, null, null)
            slots.add(s)
            trim()
            runCatching { build(s) }
            scheduleDismiss(s, durationMs)
        }
        return id
    }

    fun ok(title: String, text: String = "") = show(title, text, Kind.OK, 2600)
    fun warn(title: String, text: String = "") = show(title, text, Kind.WARN, 4200)
    fun err(title: String, text: String = "") = show(title, text, Kind.ERR, 6000)
    fun info(title: String, text: String = "") = show(title, text, Kind.INFO, 3200)

    /** 起一个带进度的任务卡片，返回 id */
    fun task(title: String, text: String = ""): Int {
        val id = seq.incrementAndGet()
        ui.post {
            val s = Slot(id, Kind.TASK, title, text, null, null, null, null)
            slots.add(s)
            trim()
            runCatching { build(s) }
        }
        return id
    }

    /** 更新任务进度。current<0 表示不确定进度（转圈） */
    fun progress(id: Int, current: Int, total: Int, text: String = "") {
        ui.post {
            val s = find(id) ?: return@post
            if (text.isNotEmpty()) {
                s.text = text
                s.tvText?.text = text
                s.tvText?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            val b = s.bar ?: return@post
            if (total > 0) {
                b.isIndeterminate = false
                b.max = total
                b.progress = current.coerceIn(0, total)
            } else {
                b.isIndeterminate = true
            }
        }
    }

    /** 任务结束：转成结果卡片后按普通提示消失 */
    fun done(id: Int, title: String, text: String = "", success: Boolean = true) {
        ui.post {
            val s = find(id) ?: return@post
            val idx = slots.indexOf(s)
            slots.remove(s)
            val s2 = Slot(s.id, if (success) Kind.OK else Kind.ERR, title, text, null, null, null, null)
            if (idx >= 0) slots.add(idx.coerceAtMost(slots.size), s2) else slots.add(s2)
            runCatching { (s.view?.parent as? ViewGroup)?.removeView(s.view) }
            runCatching { build(s2) }
            scheduleDismiss(s2, if (success) 2600 else 6000)
        }
    }

    fun dismiss(id: Int) {
        ui.post {
            val s = find(id) ?: return@post
            slots.remove(s)
            runCatching { (s.view?.parent as? ViewGroup)?.removeView(s.view) }
        }
    }

    /** 有别的弹窗在显示时（比如确认框），用它把卡片暂时收起来 */
    fun clear() {
        ui.post {
            for (s in slots) {
                runCatching { (s.view?.parent as? ViewGroup)?.removeView(s.view) }
            }
            slots.clear()
        }
    }

    private fun find(id: Int): Slot? = slots.firstOrNull { it.id == id }

    private fun trim() {
        while (slots.size > MAX_VISIBLE) {
            val old = slots.removeAt(0)
            runCatching { (old.view?.parent as? ViewGroup)?.removeView(old.view) }
        }
    }

    private fun scheduleDismiss(s: Slot, ms: Long) {
        if (ms <= 0) return
        val r = Runnable {
            slots.remove(s)
            runCatching { (s.view?.parent as? ViewGroup)?.removeView(s.view) }
        }
        s.handler = r
        ui.postDelayed(r, ms)
    }

    // ------------------------------------------------------------ 画

    private fun build(s: Slot) {
        val h = host() ?: return
        val ctx: Context = h.act
        val dm = ctx.resources.displayMetrics
        val p = (12 * dm.density).toInt()

        val accent = when (s.kind) {
            Kind.OK -> 0xFF2E9E5B.toInt()
            Kind.WARN -> 0xFFD08A1E.toInt()
            Kind.ERR -> 0xFFC8443C.toInt()
            Kind.TASK -> 0xFF2F6FD0.toInt()
            Kind.INFO -> 0xFF4A6B8A.toInt()
        }

        val bg = GradientDrawable().apply {
            setColor(cardColor(ctx))
            cornerRadius = 12 * dm.density
            setStroke((1 * dm.density).toInt(), 0x22000000.toInt())
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
            background = bg
            elevation = 6 * dm.density
        }

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(row)

        val dot = View(ctx).apply {
            val d = GradientDrawable()
            d.setColor(accent)
            d.cornerRadius = 8 * dm.density
            background = d
        }
        row.addView(
            dot,
            LinearLayout.LayoutParams((6 * dm.density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
        )

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dm.density).toInt(), 0, 0, 0)
        }
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val tvTitle = TextView(ctx).apply {
            text = s.title
            textSize = 14f
            setTextColor(titleColor(ctx))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        texts.addView(tvTitle)

        val tvText = TextView(ctx).apply {
            text = s.text
            textSize = 12f
            setTextColor(subColor(ctx))
            visibility = if (s.text.isBlank()) View.GONE else View.VISIBLE
        }
        texts.addView(tvText)
        s.tvText = tvText

        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            setTextColor(subColor(ctx))
            setPadding(p / 2, 0, 0, 0)
            setOnClickListener { dismiss(s.id) }
        }
        row.addView(close)

        if (s.kind == Kind.TASK) {
            val b = ProgressBar(
                ctx, null, android.R.attr.progressBarStyleHorizontal
            ).apply {
                isIndeterminate = true
                max = 100
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (4 * dm.density).toInt()
            )
            lp.topMargin = (8 * dm.density).toInt()
            card.addView(b, lp)
            s.bar = b
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = (8 * dm.density).toInt()
        s.view = card

        h.stack.addView(card, lp)
        card.alpha = 0f
        card.translationY = -20 * dm.density
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun cardColor(ctx: Context): Int {
        val night = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) 0xFF2A2D33.toInt() else 0xFFF7F8FA.toInt()
    }

    private fun titleColor(ctx: Context): Int =
        if (cardColor(ctx) == 0xFF2A2D33.toInt()) 0xFFF0F0F0.toInt() else 0xFF1A1A1A.toInt()

    private fun subColor(ctx: Context): Int =
        if (cardColor(ctx) == 0xFF2A2D33.toInt()) 0xFFB0B4BB.toInt() else 0xFF5A5F66.toInt()
}

/**

    fun err(ctx: android.content.Context?, msg: String) {
        if (!notice(ctx, "出错了", msg, 6000)) toast(ctx, msg)
    }

    fun warn(ctx: android.content.Context?, msg: String) {
        if (!notice(ctx, "注意", msg, 4200)) toast(ctx, msg)
    }

    fun short(ctx: android.content.Context?, msg: String) {
        if (msg.length <= 16 && !msg.contains("\n")) {
            if (!notice(ctx, msg, "", 2600)) toast(ctx, msg)
            return
        }
        val head = msg.substringBefore('\n').trim()
        val rest = msg.substringAfter('\n', "").trim()
        if (!notice(ctx, head.ifBlank { "提示" }, rest.ifBlank { msg }, 3200)) toast(ctx, msg)
    }

    fun long(ctx: android.content.Context?, msg: String) {
        val head = msg.substringBefore('\n').trim()
        val rest = msg.substringAfter('\n', "").trim()
        if (!notice(ctx, head.ifBlank { "提示" }, rest.ifBlank { msg }, 5000)) toast(ctx, msg)
    }

    fun show(ctx: android.content.Context?, title: String, msg: String, ms: Long = 3200) {
        if (!notice(ctx, title, msg, ms)) toast(ctx, "$title：$msg")
    }

    /** @return true 表示已走应用内卡片，false 表示没有可用界面、需兜底 */
    private fun notice(ctx: android.content.Context?, title: String, msg: String, ms: Long): Boolean {
        val a = ctx as? android.app.Activity ?: return false
        if (a.isFinishing || a.isDestroyed) return false
        InAppNotice.show(title, msg, InAppNotice.Kind.INFO, ms)
        return true
    }

    private fun toast(ctx: android.content.Context?, msg: String) {
        val c = ctx ?: return
        runCatching { Toast.makeText(c.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
