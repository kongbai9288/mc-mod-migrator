package com.kongbai.modmigrator

/**
 * 进度中心：进度条 + 当前在做什么 + 已用/预计剩余时间 + 速度。
 *
 * 时间估算用「已完成量的平均速度」外推：
 *   剩余时间 = 剩余量 / (已完成量 / 已用时间)
 * 刚起步（完成量太少）时不给估算，避免数字乱跳。
 */
object Progress {

    data class State(
        val running: Boolean = false,
        val current: Int = 0,
        val total: Int = 0,
        val label: String = "",
        /** 已处理字节数，用来算速度 */
        val bytes: Long = 0,
        /** 任务开始时刻 */
        val startedAt: Long = 0
    ) {
        val percent: Int
            get() = if (total <= 0) 0 else (current * 100 / total).coerceIn(0, 100)

        /** 已用毫秒 */
        val elapsedMs: Long
            get() = if (startedAt <= 0) 0 else System.currentTimeMillis() - startedAt

        /** 剩余毫秒；无法估算时返回 -1 */
        val remainMs: Long
            get() {
                if (total <= 0 || current <= 0 || elapsedMs <= 0) return -1
                // 完成量太少时样本不足，不给估算
                if (current < 1 || percent < 3) return -1
                val perItem = elapsedMs.toDouble() / current
                return (perItem * (total - current)).toLong().coerceAtLeast(0)
            }

        /** 速度：字节/秒；没有字节信息时返回 -1 */
        val bytesPerSec: Double
            get() {
                if (bytes <= 0 || elapsedMs <= 0) return -1.0
                return bytes * 1000.0 / elapsedMs
            }

        /** 主标题：正在做什么 */
        val text: String
            get() {
                if (label.isBlank()) return ""
                return if (total > 0) "$label（$current/$total）" else label
            }

        /** 副标题：百分比 + 已用/剩余时间 + 速度，给进度条下面那行用 */
        val detail: String
            get() {
                if (!running) return ""
                val sb = StringBuilder()
                if (total > 0) sb.append(percent).append('%')
                val el = elapsedMs
                if (el > 0) sb.append(" · 已用 ").append(fmtDur(el))
                val rm = remainMs
                if (rm >= 0) sb.append(" · 预计还剩 ").append(fmtDur(rm))
                val sp = bytesPerSec
                if (sp > 0) sb.append(" · ").append(fmtSpeed(sp))
                return sb.toString()
            }

        private fun fmtDur(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "${s}秒"
                s < 3600 -> "${s / 60}分${s % 60}秒"
                else -> "${s / 3600}小时${(s % 3600) / 60}分"
            }
        }

        private fun fmtSpeed(bps: Double): String {
            return when {
                bps >= 1024 * 1024 -> String.format("%.1f MB/s", bps / 1024 / 1024)
                bps >= 1024 -> String.format("%.0f KB/s", bps / 1024)
                else -> String.format("%.0f B/s", bps)
            }
        }
    }

    private var state = State()
    private val hooks = ArrayList<(State) -> Unit>()

    fun addHook(h: (State) -> Unit) {
        hooks.add(h)
        h(state)
    }

    fun removeHook(h: (State) -> Unit) {
        hooks.remove(h)
    }

    /** 开始一个新任务 */
    @Synchronized
    fun start(label: String, total: Int = 0) {
        state = State(true, 0, total, label, 0, System.currentTimeMillis())
        fire()
    }

    @Synchronized
    fun update(label: String, current: Int = 0, total: Int = 0) {
        val keepStart = if (state.startedAt > 0) state.startedAt else System.currentTimeMillis()
        val tot = if (total > 0) total else state.total
        state = State(true, current, tot, label, state.bytes, keepStart)
        fire()
    }

    /** 只更新字节数（用于下载过程） */
    @Synchronized
    fun bytes(n: Long) {
        state = state.copy(bytes = n)
        fire()
    }

    /** 累加字节数 */
    @Synchronized
    fun addBytes(n: Long) {
        state = state.copy(bytes = state.bytes + n)
        fire()
    }

    @Synchronized
    fun done(label: String = "") {
        state = State(false, 0, 0, label, state.bytes, 0)
        fire()
    }

    @Synchronized
    fun get(): State = state

    private fun fire() {
        for (h in hooks) {
            try {
                h(state)
            } catch (t: Throwable) { Err.ignore(t, "h(state)") }
        }
    }
}
