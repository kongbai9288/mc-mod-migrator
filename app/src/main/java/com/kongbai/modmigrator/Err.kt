package com.kongbai.modmigrator

/**
 * 统一记录被吞掉的异常。
 *
 * ## 为什么要有这个
 *
 * 这是 AI 生成代码最典型的缺陷：**异常黑洞**。
 * 到处写 `catch (t: Throwable) { }`，语法完全正确、能编译、能跑通主流程，
 * 但一旦出错——没有日志、没有提示、没有上报，用户只看到"没反应"，
 * 而排查的人连发生了什么都不知道。
 *
 * 全项目曾有 87 处空 catch，这是"为什么改不动、为什么总是猜"的直接原因：
 * 失败被静默吞掉，等于没有任何现场信息。
 *
 * ## 用法
 *
 * ```kotlin
 * try { risky() } catch (t: Throwable) { Err.ignore(t, "读取配置") }
 * ```
 *
 * 这样即使不影响流程，也会进日志缓冲，用户分享崩溃日志时能看到
 * "崩之前到底发生了什么"。
 */
object Err {

    /**
     * 静默但留痕：不影响流程，但记录到日志。
     *
     * ⚠️ 这里**必须用 Android 原生 Log，不能调 LogCenter**。
     *
     * 原因是一个会直接撑爆栈的循环依赖：
     *   LogCenter.persist() 写盘失败 → catch 里调 Err.ignore()
     *   → Err.ignore() 调 LogCenter.w() → push() → persist()
     *   → 磁盘依然是满的，再次失败 → 再调 Err.ignore() → ……
     * 几毫秒内栈就溢出，抛 StackOverflowError。
     * 而崩在后台线程时用户只看到"闪退"，连崩溃日志都来不及写。
     *
     * 更隐蔽的是：这个循环只在"磁盘写不进去"时才触发，
     * 平时完全正常，所以极难复现，一旦出现就是必崩。
     *
     * Err 是最底层的错误处理设施，它只能依赖**同样不会失败**的输出方式。
     * android.util.Log 写的是内核 log 缓冲区，不碰文件系统，不会失败。
     */
    fun ignore(t: Throwable, what: String = "") {
        val msg = if (what.isBlank()) "已忽略异常" else "已忽略异常：$what"
        try {
            android.util.Log.w(
                "Err", "$msg -> ${t.javaClass.simpleName}: ${t.message?.take(120) ?: ""}", t
            )
        } catch (_: Throwable) {
        }
        try {
            timber.log.Timber.w(t, msg)
        } catch (_: Throwable) {
        }
    }

    /** 值得注意的失败：记 warn */
    fun warn(t: Throwable, what: String) {
        try {
            android.util.Log.w(
                "Err", "$what -> ${t.javaClass.simpleName}: ${t.message?.take(120) ?: ""}", t
            )
        } catch (_: Throwable) {
        }
        try {
            timber.log.Timber.w(t, what)
        } catch (_: Throwable) {
        }
    }

    /** 功能性失败：记 error，界面可据此提示用户 */
    fun fail(t: Throwable, what: String) {
        try {
            android.util.Log.e(
                "Err", "$what -> ${t.javaClass.simpleName}: ${t.message?.take(200) ?: ""}", t
            )
        } catch (_: Throwable) {
        }
        try {
            timber.log.Timber.e(t, what)
        } catch (_: Throwable) {
        }
    }

    /**
     * 包一层，吞掉异常但留痕。
     * 用于"失败也无所谓"的清理/兜底场景，替代裸 try-catch。
     */
    /**
     * 把异常信息清洗成能给人看的一句话。
     *
     * 为什么要洗：Android 抛的异常里经常带**内部路径与包名**，例如
     *   `Failed to find configured root that contains
     *    /data/data/com.kongbai.modmigrator/cache/...`
     * 直接弹给用户，他既看不懂，也无从下手 ——
     * 他根本不是从那个路径打开应用的，那串东西对他毫无意义。
     *
     * 这里去掉私有路径与包名，只留结论。
     */
    fun humanMessage(t: Throwable): String {
        var m = t.message ?: return t.javaClass.simpleName
        // 去掉 /data/data/... 、 /storage/...Android/data/... 这类内部路径
        m = m.replace(Regex("/(data|storage|sdcard)/[^\\s,;)\"]*(/[A-Za-z0-9._\\-]+)*"), "内部路径")
        // 去掉包名
        m = m.replace(Regex("\b(?:com|net|org)\\.[A-Za-z0-9_.]+\\b"), "")
        m = m.replace(Regex("\\s{2,}"), " ").trim()
        return m.ifBlank { t.javaClass.simpleName }
    }

    inline fun swallow(what: String = "", block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            ignore(t, what)
        }
    }
}
