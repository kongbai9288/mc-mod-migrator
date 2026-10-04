package com.kongbai.modmigrator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 断点续做记录。
 *
 * 解决的问题：迁移、批量下载这类操作动辄几十个文件、跑十几分钟。
 * 中途被用户划掉、系统回收、手机关机，进程直接消失，
 * **没有任何回调**——下次打开时界面一片干净，
 * 用户不知道做到哪儿了，只能凭记忆重来一遍，
 * 而已经下好的那部分会被重复下载一遍。
 *
 * 做法：跑的过程中把「总共哪些项、已经完成哪些」逐步落盘，
 * 正常结束（无论成功失败）就清掉。
 * 于是**磁盘上还留着记录 == 上次是异常中断的**。
 *
 * 判定"异常中断"不需要额外标记：
 * `active` 是进程内变量，进程被杀后重启一定是 false，
 * 所以启动时只要看到 `!active && 有记录`，就必然是上次没跑完。
 */
object Resume {

    private const val KEY = "resume_journal"

    /** 进程内标记：此刻是不是真的在跑。重启后必然为 false。 */
    @Volatile
    private var active = false

    /** 超过这个时长就不再提示（避免几个月前的一次中断一直弹） */
    private const val MAX_AGE = 7L * 24 * 60 * 60 * 1000

    const val KIND_MIGRATE = "migrate"
    const val KIND_DOWNLOAD = "download"

    data class Journal(
        val kind: String,
        val title: String,
        val ts: Long,
        /** 每项的唯一标识（迁移里就是下载地址） */
        val all: List<String>,
        val done: List<String>,
        /** key → 显示名。续做时不依赖内存里的列表，靠它把名字还原出来 */
        val names: Map<String, String>,
    ) {
        val total: Int get() = all.size
        val finished: Int get() = done.size
        /** 还没做的（按原有顺序） */
        val remaining: List<String> get() = all.filter { it !in done }
        fun nameOf(key: String): String = names[key] ?: key.substringAfterLast('/')
    }

    /**
     * 开始一项可续做的任务。
     * @param items 「唯一标识 → 显示名」，顺序即执行顺序。
     *   标识要能独立还原出这一项（迁移里直接用下载地址），
     *   这样续做时不必依赖内存里的列表——进程被杀后那些早就没了。
     */
    @Synchronized
    fun begin(ctx: Context, kind: String, title: String, items: List<Pair<String, String>>) {
        if (items.isEmpty()) {
            clear(ctx)
            return
        }
        active = true
        val o = JSONObject()
        o.put("kind", kind)
        o.put("title", title)
        o.put("ts", System.currentTimeMillis())
        o.put("all", JSONArray(items.map { it.first }))
        val nm = JSONObject()
        for ((k, v) in items) nm.put(k, v)
        o.put("names", nm)
        o.put("done", JSONArray())
        save(ctx, o)
    }

    /** 标记一项已完成。频繁调用，但只是小 JSON 写入。 */
    @Synchronized
    fun mark(ctx: Context, name: String) {
        if (!active) return
        val o = raw(ctx) ?: return
        val done = o.optJSONArray("done") ?: JSONArray()
        // 去重：并发下同一个名字可能被标记两次
        for (i in 0 until done.length()) {
            if (done.optString(i) == name) return
        }
        done.put(name)
        o.put("done", done)
        save(ctx, o)
    }

    /** 正常结束（成功或失败都算）——清掉记录，下次不再提示。 */
    @Synchronized
    fun finish(ctx: Context) {
        active = false
        clear(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        active = false
        runCatching { Prefs.get(ctx).edit().remove(KEY).apply() }
    }

    /**
     * 取出上次没跑完的任务。
     * 只有在**当前进程里没有任务在跑**时才返回——
     * 正在前台跑的任务不该再弹一次"要继续吗"。
     */
    @Synchronized
    fun peek(ctx: Context): Journal? {
        if (active) return null
        val o = raw(ctx) ?: return null
        val ts = o.optLong("ts", 0L)
        if (ts <= 0 || System.currentTimeMillis() - ts > MAX_AGE) {
            clear(ctx)
            return null
        }
        val all = toList(o.optJSONArray("all"))
        val done = toList(o.optJSONArray("done"))
        if (all.isEmpty()) return null
        // 全做完了却还留着记录：说明是 done 之后、finish 之前被杀的，
        // 这种没有可续做的东西，直接清掉。
        if (done.size >= all.size) {
            clear(ctx)
            return null
        }
        val nm = o.optJSONObject("names")
        val names = LinkedHashMap<String, String>()
        if (nm != null) {
            for (k in nm.keys()) names[k] = nm.optString(k, "")
        }
        return Journal(
            o.optString("kind", ""),
            o.optString("title", "上次的操作"),
            ts,
            all,
            done,
            names,
        )
    }

    /** 续做时要跳过的项 */
    fun skipSet(j: Journal): Set<String> = j.done.toSet()

    private fun raw(ctx: Context): JSONObject? {
        val s = runCatching { Prefs.get(ctx).getString(KEY, null) }.getOrNull()
        if (s.isNullOrBlank()) return null
        return runCatching { JSONObject(s) }.getOrNull()
    }

    private fun save(ctx: Context, o: JSONObject) {
        runCatching { Prefs.get(ctx).edit().putString(KEY, o.toString()).apply() }
    }

    private fun toList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val v = a.optString(i, "")
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }
}
