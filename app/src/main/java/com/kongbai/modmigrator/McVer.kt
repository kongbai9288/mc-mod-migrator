package com.kongbai.modmigrator

/**
 * MC 版本号的解析、排序与候选清单。
 *
 * 为什么要有这个文件：
 * 版本号以前是**按字面对待**的——下拉里写死一串 arrayOf("1.7.10", …, "26.3")，
 * 比较时又是 split(".") 取三段转成整数比大小。
 * 这两件事都会随 Mojang 改规则而失效：
 *   - 列表写死：出新版本后下拉里没有，用户只能手动输入；
 *   - 按三段解析：26.3 这种两段写法（2026 年起的新编号）会解析失败或排错位置，
 *     1.21.9-rc1 / 25w14a 这类带后缀的也一样。
 *
 * 这里把"怎么读一个版本号"收成一处：
 *   - [key] 把任意版本串映射成一个可比较的长整数，认不出来返回 -1；
 *   - [known] 返回候选清单，优先用联网抓到的，抓不到用内置兜底；
 *   - 其它地方一律调这里，不要再就地 split(".") 或写死一串版本。
 */
object McVer {

    /**
     * 内置兜底清单。
     *
     * 只在**没抓到远端列表**时用（首次启动、断网）。
     * 不需要随新版本频繁改：只要联网过一次，清单就以远端为准，
     * 内置这份只保证"最坏情况下下拉不是空的"。
     */
    private val BUILTIN = listOf(
        "1.7.10", "1.8.9", "1.12.2", "1.16.5", "1.17.1", "1.18.2",
        "1.19.2", "1.19.4", "1.20.1", "1.20.4", "1.20.6",
        "1.21.1", "1.21.4", "1.21.8", "26.2", "26.3"
    )

    /** 远端清单缓存多久（7 天）。过期只是后台重抓，不影响使用 */
    private const val TTL = 7L * 24 * 60 * 60 * 1000

    /** 候选清单最多保留多少个。太多会让下拉难翻，太少会漏掉中间版本 */
    private const val KEEP = 80

    private const val OFFICIAL = "https://api.modrinth.com/v3/tag/game_version"
    private const val MIRROR = "https://mod.mcimirror.top/modrinth/v2/tag/game_version"

    /**
     * 把版本串映射成可比较的数值。认不出来返回 -1。
     *
     * 量纲：主版本 × 1_000_000 + 次版本 × 1_000 + 修订号。
     * 这样 1.21.8（1_021_008）和 26.3（26_003_000）能直接比大小，
     * 不需要事先知道它是两段还是三段。
     *
     * 只接受纯数字点分（1.20.5 / 26.3 / 1.7.10）。
     * 快照（25w14a）、预发布（1.21.9-rc1）返回 -1 ——
     * 它们不是发布版本号，不该混进"迁移目标"的下拉里。
     */
    fun key(v: String?): Long {
        val s = v?.trim()?.removePrefix("v")?.removePrefix("V") ?: return -1
        if (s.isBlank()) return -1
        val p = s.split('.')
        if (p.size !in 1..3) return -1
        val a = p[0].toLongOrNull() ?: return -1
        val b = if (p.size >= 2) (p[1].toLongOrNull() ?: return -1) else 0L
        val c = if (p.size >= 3) (p[2].toLongOrNull() ?: return -1) else 0L
        if (a < 0 || b < 0 || c < 0) return -1
        // 超出量纲说明不是版本号（比如一串很大的数字）
        if (a > 999_999L || b > 999L || c > 999L) return -1
        return a * 1_000_000L + b * 1_000L + c
    }

    /** 是不是一个正式版本号（能被 [key] 解析） */
    fun isRelease(v: String?): Boolean = key(v) >= 0

    /** 比较。认不出来的排最后 */
    fun compare(a: String?, b: String?): Int {
        val x = key(a)
        val y = key(b)
        return when {
            x < 0 && y < 0 -> 0
            x < 0 -> 1
            y < 0 -> -1
            else -> x.compareTo(y)
        }
    }

    /** 从新到旧排 */
    fun sortDesc(vs: Iterable<String>): List<String> =
        vs.distinct().sortedWith { a, b -> compare(b, a) }

    /** 清单里最新的正式版；清单为空时返回空串 */
    fun latest(vs: Iterable<String>): String =
        vs.filter { isRelease(it) }.maxWithOrNull { a, b -> compare(a, b) } ?: ""

    /**
     * 候选版本清单（新→旧）。
     *
     * 联网抓到过就用抓到的结果并上内置清单，没抓到就只有内置清单。
     * 抓到的结果存在 Prefs 里，下次启动直接读。
     */
    fun known(): List<String> {
        val ctx = Prefs.appCtx() ?: return sortDesc(BUILTIN)
        val raw = Prefs.get(ctx).getString(Prefs.K.MCVER_LIST, null)
        if (raw.isNullOrBlank()) return sortDesc(BUILTIN)
        val remote = raw.split(',').map { it.trim() }.filter { isRelease(it) }
        if (remote.isEmpty()) return sortDesc(BUILTIN)
        return sortDesc(BUILTIN + remote).take(KEEP)
    }

    /**
     * 当前应该填在版本输入框里的默认值。
     *
     * 顺序：设置里保存过的迁移目标 → 清单里最新的 → 空串。
     * 不再写死某个具体版本号。
     */
    fun defaultMc(ctx: android.content.Context?): String {
        if (ctx != null) {
            val saved = Prefs.get(ctx).getString(Prefs.K.TARGET_MC, null)
            if (isRelease(saved)) return saved!!.trim()
        }
        return latest(known())
    }

    /**
     * 后台刷新清单。失败不影响既有缓存。
     *
     * 只在缓存过期或为空时才真正发请求，
     * 免得每次进页面都拉一次几百条的版本表。
     */
    fun refresh(ctx: android.content.Context?) {
        val c = ctx ?: Prefs.appCtx() ?: return
        val sp = Prefs.get(c)
        val at = sp.getLong(Prefs.K.MCVER_AT, 0L)
        val stale = System.currentTimeMillis() - at > TTL
        val empty = sp.getString(Prefs.K.MCVER_LIST, null).isNullOrBlank()
        if (!stale && !empty) return
        Thread {
            val list = try {
                fetch()
            } catch (t: Throwable) {
                emptyList<String>()
            }
            if (list.isEmpty()) return@Thread
            try {
                Prefs.get(c).edit()
                    .putString(Prefs.K.MCVER_LIST, list.joinToString(","))
                    .putLong(Prefs.K.MCVER_AT, System.currentTimeMillis())
                    .apply()
            } catch (t: Throwable) {
                // 写不进去就算了，下次启动再试
            }
        }.apply { isDaemon = true }.start()
    }

    /** 抓远端版本表。先镜像后官方，任一成功即可 */
    private fun fetch(): List<String> {
        val urls = if (Prefs.mirror()) listOf(MIRROR, OFFICIAL) else listOf(OFFICIAL, MIRROR)
        for (u in urls) {
            val txt = try {
                Http.get(u, timeout = Http.SHORT)
            } catch (t: Throwable) {
                continue
            }
            val arr = Json.arr(txt) ?: continue
            val out = ArrayList<String>()
            for (e in arr) {
                val v = Json.s(e, "version")
                if (!isRelease(v)) continue
                val type = Json.s(e, "type")
                // type 为空时无法判断，保守收下（由调用方再过滤）
                if (type.isNotBlank() && !type.equals("release", true)) continue
                out.add(v)
            }
            if (out.isNotEmpty()) return sortDesc(out).take(KEEP)
        }
        return emptyList()
    }
}
