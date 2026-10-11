package com.kongbai.modmigrator


/**
 * MC 资讯分区。
 *
 * 四个分区：**官方最新版本 / 最新模组 / 最新整合包 / 最新光影**。
 *
 * ⚠️ 之前这一块是**抓网页**（Jsoup 解析 HTML），四个分区全靠它，
 * 结果就是"完全没法用"：
 *  1. Modrinth 是前端渲染的 SPA，抓下来的静态 HTML 里根本没有列表；
 *  2. minecraft-schematics.com、curseforge.com 在国内基本连不上；
 *  3. 站点一改版选择器就失效，而失效的表现是**空白**，看不出原因。
 *
 * 现在**全部改成接口取数**，一个网页都不抓：
 *  · 官方版本 → [McVer] 的清单（本身已带镜像与 7 天缓存，抓不到还有内置兜底）
 *  · 其余三块 → Modrinth 搜索接口，按发布时间倒序
 * 这样任何一块拿不到都能明确降级，而不是整页空白。
 *
 * 只取标题与链接这类摘要信息，不整页搬运正文。
 */
object McFeed {

    /** 分区标识 */
    const val OFFICIAL = "official"
    const val MODS = "mods"
    const val MODPACK = "modpack"
    const val SHADER = "shader"

    data class Item(
        val title: String,
        val url: String,
        val source: String,
        val extra: String = ""
    )

    /** 分区定义：名字、站点名、原站地址、Modrinth 项目类型（官方版本那块为空） */
    data class Zone(
        val key: String,
        val label: String,
        val site: String,
        val url: String,
        val selector: String,
        val desc: String
    )

    private val ZONES = listOf(
        Zone(
            OFFICIAL, "官方最新版本", "Minecraft 版本清单",
            "https://zh.minecraft.wiki/w/Java%E7%89%88",
            "",
            "最新的 Java 版正式版"
        ),
        Zone(
            MODS, "最新模组", "Modrinth",
            "https://modrinth.com/mods",
            "mod",
            "刚发布的模组"
        ),
        Zone(
            MODPACK, "最新整合包", "Modrinth",
            "https://modrinth.com/modpacks",
            "modpack",
            "刚发布的整合包"
        ),
        Zone(
            SHADER, "最新光影", "Modrinth",
            "https://modrinth.com/shaders",
            "shader",
            "刚发布的光影 / 着色器"
        )
    )

    // ── 缓存 ────────────────────────────────────────────────────
    // 之前是**一个** `cacheAt` 管四个分区的时间戳：
    //   先抓"官方" → cacheAt = T1
    //   再抓"模组" → 写入 cache[MODS]，并**把 cacheAt 刷新成 T2**
    // 但 cached(key) 判断的是 `now - cacheAt < TTL`，
    // 于是"官方"那一份明明只存了 2 秒，也因为 cacheAt 被刷新
    // 而一直被当成"新鲜"，长期不更新。
    // 反过来更糟：某一个分区反复被刷新时，其他分区的缓存
    // 有效期被无意义地延长，用户看到的是过期很久的内容。
    // 改成**每个分区各自记自己的时间戳**。
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Item>>()
    private val stampAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val TTL = 10 * 60 * 1000L
    private const val PER_ZONE = 8

    fun labels(): List<Zone> = ZONES

    fun cached(key: String): List<Item> {
        val at = stampAt[key] ?: return emptyList()
        if (System.currentTimeMillis() - at >= TTL) return emptyList()
        return cache[key] ?: emptyList()
    }

    /** 抓单个分区。后台线程调用。 */
    fun fetch(ctx: android.content.Context, key: String): List<Item> {
        val hit = cached(key)
        if (hit.isNotEmpty()) return hit

        // 资讯联网可单独关闭。注意放在缓存之后：
        // 断网时仍然返回已缓存的内容，不至于一片空白。
        if (!NetGate.allow(ctx, NetGate.Area.FEED)) return emptyList()

        val zone = ZONES.firstOrNull { it.key == key } ?: return emptyList()

        val list = try {
            when {
                key == OFFICIAL -> officialVersions()
                else -> latestProjects(ctx, zone)
            }
        } catch (t: Throwable) {
            Err.ignore(t, "资讯：$key")
            emptyList()
        }
        if (list.isNotEmpty()) put(key, list)
        return list
    }

    /** 写入缓存并给**这个分区**记时间戳 */
    private fun put(key: String, list: List<Item>) {
        cache[key] = list
        stampAt[key] = System.currentTimeMillis()
    }

    /**
     * 官方最新版本：直接读 [McVer] 的清单。
     *
     * 之前是去 Modrinth 现抓一个写死的 v3 地址，**没走镜像开关**，
     * 国内连不上就整块空白。而 [McVer] 那份清单本来就已经带镜像、
     * 带 7 天缓存、抓不到还有内置兜底，复用它就够了——
     * 同一份版本数据没必要在两处各拉一次。
     */
    private fun officialVersions(): List<Item> {
        val out = ArrayList<Item>()
        for (v in McVer.known().take(PER_ZONE)) {
            out.add(
                Item(
                    title = "Minecraft $v",
                    url = "https://zh.minecraft.wiki/w/Java%E7%89%88$v",
                    source = "Minecraft 版本清单",
                    extra = "Java 版正式版"
                )
            )
        }
        return out
    }

    /**
     * 最新项目：走 Modrinth 搜索接口，按**发布时间**倒序。
     *
     * 用设置里的默认 MC 版本过滤；没填版本就不过滤，
     * 至少能看到东西，而不是空白。
     *
     * @param zone 里的 selector 字段这里复用为 Modrinth 的项目类型
     *             （mod / modpack / shader），不再当 CSS 选择器用。
     */
    private fun latestProjects(ctx: android.content.Context, zone: Zone): List<Item> {
        val p = Prefs.get(ctx)
        val mc = p.getString(K.DEF_VERSION, "") ?: ""
        val ld = p.getString(K.DEF_LOADER, "auto") ?: "auto"
        val ptype = zone.selector.ifBlank { "mod" }
        val hits = ModrinthApi.search(
            "", mc, ld, PER_ZONE, 0, null, "newest", ptype
        )
        val out = ArrayList<Item>()
        for (h in hits) {
            val page = h.pageUrl.ifBlank { "https://modrinth.com/$ptype/${h.slug}" }
            out.add(
                Item(
                    title = h.name.take(60),
                    url = page,
                    source = "Modrinth",
                    extra = h.summary.take(30)
                )
            )
        }
        return out
    }

    fun clearCache() {
        cache.clear()
        stampAt.clear()
    }
}
