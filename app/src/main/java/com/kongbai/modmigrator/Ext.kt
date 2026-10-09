package com.kongbai.modmigrator

import android.widget.Toast

import android.widget.AdapterView
import android.widget.Spinner

fun Spinner.setOnItemSelectedListenerSafe(block: () -> Unit) {
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = block()
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
}

fun android.widget.EditText.addTextWatcherSafe(block: () -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = block()
    })
}

/**
 * 安全地重建 Activity。
 *
 * ⚠️ 直接调 `activity.recreate()` 是**会崩的**，真实崩溃日志为证：
 * ```
 * java.lang.IllegalStateException: The specified message queue synchronization
 *   barrier token has not been posted or has already been removed.
 *     at android.os.MessageQueue.removeSyncBarrier(MessageQueue.java:600)
 *     at android.view.ViewRootImpl.doTraversal(ViewRootImpl.java:3140)
 *     at android.view.ViewRootImpl$TraversalRunnable.run(...)
 *     at android.view.Choreographer$CallbackRecord.run(...)
 * ```
 *
 * 成因：ViewRootImpl 每次布局前会 `postSyncBarrier()` 插一道同步屏障，
 * 保证自己那一帧的遍历消息优先执行，遍历结束后再 `removeSyncBarrier(token)` 撤掉。
 * 如果 Activity 在这个窗口内被 recreate，旧的 ViewRootImpl 连同它的
 * traversal 状态一起作废；等 Choreographer 下一帧回调 `doTraversal()` 时，
 * 屏障早没了，于是 `removeSyncBarrier` 找不到 token 直接抛异常。
 *
 * 触发场景高度一致：**对话框 dismiss 后紧接着 recreate**
 * （dismiss 本身就要重排布局、开启动画，屏障正活跃）。
 *
 * 解法：不要立刻 recreate，也不要用 `handler.postDelayed` 猜一个延时
 * （300ms 也可能刚好撞上下一帧的遍历）。
 * 改成把重建动作 `post` 到 **decorView** 上：
 * 同步屏障会挡住普通消息、只放行异步的遍历消息，
 * 所以这条 post 必然排在"当前这次遍历结束、屏障撤掉"之后才执行 ——
 * 由系统保证时序，而不是靠我们自己猜延时。
 */
fun android.app.Activity.recreateSafely() {
    val act = this
    // 显式写成 Runnable：直接把 `{ ... }` 传给 post 会触发
    // "unit conversions on arbitrary expressions" 实验特性告警（此处按错误处理）
    val go = Runnable {
        try {
            act.recreate()
        } catch (t: Throwable) {
            // 兜底：极端情况下重建失败也不能把界面卡死
            android.util.Log.w("recreateSafely", "重建 Activity 失败", t)
        }
    }
    try {
        val decor = act.window?.decorView
        if (decor != null) decor.post(go) else go.run()
    } catch (t: Throwable) {
        go.run()
    }
}

/**
 * 取当前主题的正文色。
 *
 * 为什么需要它：代码里 `TextView(ctx)` 这种**手动 new 出来的控件**
 * 不会套用主题的 textColorPrimary，用的是系统默认色 ——
 * 深色主题下就是黑底黑字，看起来像"字没了"。
 * 布局文件里的控件没这个问题（系统会按主题注入），只有代码建的才需要。
 */
fun textColorPrimary(ctx: android.content.Context): Int {
    return try {
        val tv = android.util.TypedValue()
        val ok = ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        if (ok && tv.resourceId != 0) {
            if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(tv.resourceId)
            else @Suppress("DEPRECATION") ctx.resources.getColor(tv.resourceId)
        } else android.graphics.Color.GRAY
    } catch (t: Throwable) {
        android.graphics.Color.GRAY
    }
}

/** 取当前主题的次要文字色 */
fun textColorSecondary(ctx: android.content.Context): Int {
    return try {
        val tv = android.util.TypedValue()
        val ok = ctx.theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        if (ok && tv.resourceId != 0) {
            if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(tv.resourceId)
            else @Suppress("DEPRECATION") ctx.resources.getColor(tv.resourceId)
        } else android.graphics.Color.GRAY
    } catch (t: Throwable) {
        android.graphics.Color.GRAY
    }
}

