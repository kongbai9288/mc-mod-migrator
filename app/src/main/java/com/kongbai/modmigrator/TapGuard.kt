package com.kongbai.modmigrator

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * 防连点。
 *
 * ## 为什么单独做一层
 *
 * 手快连点两下、或者界面卡住时反复戳，同一个动作会被排队执行好几遍。
 * 迁移扫描、批量下载、打开地图这类动作每跑一次都要解 NBT、建位图、起线程，
 * 连点几次就把堆顶满 —— 用户看到的就是"点了没反应→白屏→闪退"。
 *
 * 之前每个按钮各写各的判断，写法还不一样（有的记时间戳、有的加 boolean 标志、
 * 有的什么都不做），漏一个就出事。现在收成一处。
 *
 * ## 用在哪
 *
 * 1. [View.safeClick] —— 直接替换 setOnClickListener
 * 2. [UiCards] 里的按钮工厂已经内置，通过那两个函数造出来的按钮自动生效
 * 3. 列表项点击（Adapter）—— 复用同样的时间窗，避免一条被点出多个下载
 *
 * ## 关于内存
 *
 * 记录表用 WeakHashMap 且以 View 为键：View 被回收时记录跟着走，
 * 不会因为"点了什么"把整页拽住。这是这类工具最常见的泄漏点。
 */
object TapGuard {

    /** 默认时间窗。700ms 够挡住手抖，又不至于让正常连击两个不同按钮变迟钝 */
    const val DEFAULT_WINDOW = 700L

    /** 列表项点击用短一点，否则快速连点两条会觉得"第二条没反应" */
    const val LIST_WINDOW = 400L

    private val stamps: MutableMap<View, Long> =
        Collections.synchronizedMap(WeakHashMap<View, Long>())

    /**
     * 这次点击放行吗。放行会顺手记下时间。
     *
     * @param key 以哪个 View 为界。同一个 View 连续点才会被挡，
     *            不同 View 之间互不影响。
     */
    fun allowed(key: View, window: Long = DEFAULT_WINDOW): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = stamps[key] ?: 0L
        if (now - prev < window) return false
        stamps[key] = now
        return true
    }

    /** 手动放行下一次（比如加载失败后允许用户立刻重试） */
    fun reset(key: View) {
        stamps.remove(key)
    }

    /** 页面销毁之类的时候清空。WeakHashMap 本身不漏，这里只是省一点 */
    fun clear() {
        stamps.clear()
    }
}

/**
 * 带防连点的点击监听。
 *
 * 用法与 setOnClickListener 完全一样，只是多了时间窗。
 */
fun View.safeClick(window: Long = TapGuard.DEFAULT_WINDOW, fn: (View) -> Unit) {
    setOnClickListener { v -> if (TapGuard.allowed(v, window)) fn(v) }
}

/**
 * 无参版本，省掉 lambda 里那个用不上的 View。
 */
fun View.safeClickUnit(window: Long = TapGuard.DEFAULT_WINDOW, fn: () -> Unit) {
    setOnClickListener { v -> if (TapGuard.allowed(v, window)) fn() }
}
