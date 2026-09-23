package com.kongbai.modmigrator

/**
 * 进度中心：进度条 + 当前正在做什么。
 * 页面注册监听，后台任务汇报「阶段 / 当前 / 总数」。
 */
object Progress {

    data class State(
        val running: Boolean = false,
        val current: Int = 0,
        val total: Int = 0,
        val label: String = ""
    ) {
        val percent: Int
            get() = if (total <= 0) 0 else (current * 100 / total).coerceIn(0, 100)
        val text: String
            get() = if (label.isBlank()) "" else {
                if (total > 0) "$label（$current/$total）" else label
            }
    }

    private var state = State()
    private val hooks = ArrayList<(State) -> Unit>()

    fun addHook(h: (State) -> Unit) { hooks.add(h); h(state) }
    fun removeHook(h: (State) -> Unit) { hooks.remove(h) }

    @Synchronized
    fun update(label: String, current: Int = 0, total: Int = 0) {
        state = State(true, current, total, label)
        fire()
    }

    @Synchronized
    fun done(label: String = "") {
        state = State(false, 0, 0, label)
        fire()
    }

    @Synchronized
    fun get(): State = state

    private fun fire() {
        for (h in hooks) {
            try {
                h(state)
            } catch (t: Throwable) {
            }
        }
    }
}
