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


// ---------------------------------------------------------------- 带上限的读

/**
 * 从一个流里最多读 [cap] 字节，超过就返回 null 并立刻停手。
 *
 * ## 为什么要有这个函数
 *
 * `InputStream.readBytes()` 会一直读到 EOF：先在堆里攒下一个与数据等长的
 * ByteArray，每满一次就复制扩容（ByteArrayOutputStream 是 ×1.5 增长，
 * 峰值能到实际体积的约 2 倍）。对下面这些"长度由外部决定"的数据，
 * 等于把内存上限交给对方：
 *
 *  · 网络响应（对方想返回多少就返回多少）
 *  · zip / .mca 条目（用户从文件管理器里选的，大小不可控）
 *
 * 更隐蔽的坑是"先读后判"：
 * ```kotlin
 * val bytes = ins.readBytes()      // 已经进堆了
 * if (bytes.size > MAX) return     // 这时才判，晚了
 * ```
 * 那个上限拦不住任何东西，数据已经把堆吃掉了。本项目里原来有三处这种写法。
 *
 * 这里用固定 64KB 缓冲循环，累计超过 [cap] 就返回 null，
 * 峰值内存恒定为 cap + 64KB。
 *
 * @return 读到的字节；超过 [cap] 或读取出错返回 null
 */
fun readCappedBytes(ins: java.io.InputStream, cap: Int): ByteArray? {
    val buf = ByteArray(1 shl 16)
    val out = java.io.ByteArrayOutputStream()
    var total = 0
    // ⚠️ 这里刻意 **不** 关流：`use { }` 会把调用方的流一起关掉，
    // 而 ZipInputStream 场景下调用方还要接着读下一个条目
    // （关掉之后 nextEntry 直接返回 null，整包被当成空包）。
    // 流的生命周期交给调用方，本函数只管读多少。
    return try {
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } catch (t: Throwable) {
        null
    }
}

/**
 * [readCappedBytes] 的字符串版本。
 *
 * 整段读完再统一解码，而不是边读边转 —— 否则一个多字节字符（中文、emoji）
 * 被切在缓冲区边界上就会变成乱码。
 */
fun readCappedText(ins: java.io.InputStream, cap: Int): String? =
    readCappedBytes(ins, cap)?.let { String(it, Charsets.UTF_8) }
