package com.kongbai.modmigrator

import android.util.Base64

/**
 * 正版玩家皮肤查询（Mojang 公开 API，不需要登录、不需要 API Key）。
 *
 * 两条链路，端点都是联网核过的：
 *
 *   1) 名字 → UUID
 *      GET https://api.minecraftservices.com/minecraft/profile/lookup/name/{name}
 *      返回 {"id":"<无横线 UUID>","name":"<真实大小写名字>"}
 *      404 = 没有这个玩家。
 *
 *      这里刻意不用 api.mojang.com/users/profiles/minecraft/{name}：
 *      那个端点会**随机**返回 403（Mojang 自己的配置错误，编号 WEB-7591），
 *      表现就是"同一个名字有时能查、有时查不到"。
 *      api.minecraftservices.com 这个形式返回完全相同，且不受该问题影响。
 *
 *   2) UUID → 皮肤 / 披风
 *      GET https://sessionserver.mojang.com/session/minecraft/profile/{uuid}
 *      返回 properties[0].value，是一段 base64，解出来是：
 *        textures.SKIN.url            皮肤图片地址
 *        textures.SKIN.metadata.model 只在 Alex 模型时出现，值为 slim
 *        textures.CAPE.url            披风地址
 *      204 = 该 UUID 没有关联玩家；400 = UUID 格式不对。
 *
 *      注意：**没有 SKIN 字段不代表失败**，只说明这个账号在用默认皮肤。
 *      早期把这个当成错误会误报"查询失败"。
 *
 * 限速：见 [MojangRate]。这里**不自己实现限流**，统一走那一层，
 * 这样"设置里重置配额""被 429 后冷却"只在一处生效。
 *
 * 附带一个内存缓存：同一个 UUID 短时间内重复查询直接走缓存，不打网络、
 * **也不消耗配额**。缓存带有效期，否则玩家换了皮肤会一直看到旧的。
 */
object SkinApi {

    /** 玩家皮肤信息 */
    data class Skin(
        val uuid: String,
        val name: String,
        val skinUrl: String?,
        val capeUrl: String?,
        /** true = Alex 模型（细手臂）；false = Steve 模型（宽手臂） */
        val slim: Boolean,
        /** 查到它的时刻，用于缓存有效期判断 */
        val at: Long = System.currentTimeMillis()
    ) {
        val hasSkin: Boolean get() = !skinUrl.isNullOrBlank()
        val modelText: String get() = if (slim) "Alex（细手臂）" else "Steve（宽手臂）"
    }

    private const val NAME_URL =
        "https://api.minecraftservices.com/minecraft/profile/lookup/name/"
    private const val PROFILE_URL =
        "https://sessionserver.mojang.com/session/minecraft/profile/"

    /** 缓存上限：按 UUID 记，超了丢最久没用的 */
    private const val MAX_CACHE = 64

    /** 缓存有效期。过期会重新查一次（并消耗配额） */
    private const val CACHE_TTL = 30 * 60_000L

    private val cache = object : LinkedHashMap<String, Skin>(
        MAX_CACHE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Skin>?): Boolean =
            size > MAX_CACHE
    }

    /**
     * 发一个请求。
     *
     * 这里刻意**不**再过一遍 [MojangRate.check]：入口 [query] 已经一次性
     * 预留过整次查询的额度。若在这里逐请求判间隔，一次查询内部的两个请求
     * 会被 [MojangRate] 的最小间隔互相拦死，表现就是"永远查不出来"。
     *
     * 所以这里只负责两件事：
     *   1. 真发出去了才记一笔配额（缓存命中根本不会走到这，也就零消耗）
     *   2. 收到 429 → 进冷却，并且**不重试**
     */
    private fun fetch(url: String): String {
        val body = try {
            Http.get(url, timeout = Http.SHORT)
        } catch (t: Throwable) {
            if ((t.message ?: "").contains("HTTP 429")) {
                MojangRate.onTooMany()
                throw RuntimeException(
                    "Mojang 已限流（429），已自动冷却 " +
                        (MojangRate.coolingMs() / 1000).coerceAtLeast(1) + " 秒"
                )
            }
            throw t
        }
        MojangRate.record()
        MojangRate.onSuccess()
        return body
    }

    /**
     * 去掉 UUID 里的横线。
     * sessionserver 只认无横线形式，而用户手输的往往带横线。
     */
    fun undash(s: String): String = s.trim().replace("-", "")

    private fun looksLikeUuid(s: String): Boolean {
        val u = undash(s)
        return u.length == 32 && u.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * 名字 → UUID（无横线）。
     * @throws RuntimeException 查不到或网络出错，message 直接是可显示的话
     */
    fun uuidByName(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) throw RuntimeException("请先输入玩家名")
        val body = try {
            fetch(NAME_URL + Http.enc(n))
        } catch (t: Throwable) {
            val m = t.message ?: ""
            if (m.contains("HTTP 404")) throw RuntimeException("没有叫「$n」的正版玩家")
            throw RuntimeException("查名字失败：" + Http.describeError(t))
        }
        val o = Json.obj(body) ?: throw RuntimeException("返回内容不是 JSON")
        val id = Json.s(o, "id")
        if (id.isBlank()) throw RuntimeException("没有叫「$n」的正版玩家")
        return undash(id)
    }

    /**
     * UUID → 皮肤。UUID 带不带横线都行。
     */
    fun skinByUuid(uuidRaw: String): Skin {
        val uuid = undash(uuidRaw)
        if (!looksLikeUuid(uuid)) throw RuntimeException("UUID 格式不对：$uuidRaw")

        //
        // 缓存命中直接返回，不打网络、不消耗配额。
        // 但要看有效期 —— 玩家换了皮肤的话，永远返回旧图等于"查不了新皮肤"。
        //
        val hit = synchronized(cache) { cache[uuid] }
        if (hit != null) {
            if (System.currentTimeMillis() - hit.at < CACHE_TTL) return hit
            synchronized(cache) { cache.remove(uuid) }
        }

        val body = try {
            fetch(PROFILE_URL + uuid)
        } catch (t: Throwable) {
            val m = t.message ?: ""
            when {
                m.contains("HTTP 404") -> throw RuntimeException("这个 UUID 没有对应的玩家")
                m.contains("HTTP 400") -> throw RuntimeException("UUID 格式不对：$uuidRaw")
                else -> throw RuntimeException("查皮肤失败：" + Http.describeError(t))
            }
        }
        if (body.isBlank()) throw RuntimeException("这个 UUID 没有对应的玩家")

        val o = Json.obj(body) ?: throw RuntimeException("返回内容不是 JSON")
        val name = Json.s(o, "name").ifBlank { uuid }

        var value = ""
        val props = Json.a(o, "properties")
        if (props != null) {
            for (i in 0 until props.size()) {
                val e = props.get(i)
                if (Json.s(e, "name") == "textures") {
                    value = Json.s(e, "value")
                    break
                }
            }
        }
        if (value.isBlank()) throw RuntimeException("这个账号没有皮肤数据")

        // base64 解出来才是真正的 textures
        val decoded = try {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        } catch (t: Throwable) {
            throw RuntimeException("皮肤数据解不开：" + (t.message ?: ""))
        }
        //
        // Json.obj() 只吃 String（它的签名是 obj(text: String)），没有「从已有
        // 元素里取子对象」的重载 —— 第一版写成 Json.obj(tex, "SKIN") 直接编译不过。
        // 嵌套对象这里直接用 Gson 的 getAsJsonObject，成员不存在时它返回 null。
        //
        val root = Json.obj(decoded) ?: throw RuntimeException("皮肤数据解不开")
        val tex = root.getAsJsonObject("textures")
            ?: throw RuntimeException("皮肤数据里没有 textures")

        val skinObj = tex.getAsJsonObject("SKIN")
        val capeObj = tex.getAsJsonObject("CAPE")

        // SKIN 缺失 = 默认皮肤，不是失败
        val skinUrl = skinObj?.let { Json.s(it, "url").ifBlank { null } }?.let { https(it) }
        val capeUrl = capeObj?.let { Json.s(it, "url").ifBlank { null } }?.let { https(it) }
        val slim = skinObj?.getAsJsonObject("metadata")?.let { Json.s(it, "model") } == "slim"

        val s = Skin(uuid, name, skinUrl, capeUrl, slim)
        synchronized(cache) { cache[uuid] = s }
        return s
    }

    /**
     * 输入可能是名字也可能是 UUID，都接受。
     *
     * **唯一的对外入口**：限速检查只在这里做一次，别绕过它直接调
     * [uuidByName] / [skinByUuid]，否则就没有限流了。
     */
    fun query(input: String): Skin {
        val s = input.trim()
        if (s.isEmpty()) throw RuntimeException("请输入玩家名或 UUID")

        // 直接给 UUID 的话只发 1 个请求；给名字要发 2 个（先换 UUID 再取皮肤）。
        // 按多的那个预留，宁可保守一点。
        val byUuid = looksLikeUuid(s)
        val v = MojangRate.check(cost = if (byUuid) 1 else MojangRate.PER_QUERY)
        if (!v.ok) throw RuntimeException(v.reason + "，请 ${v.waitSec} 秒后再试")

        return if (byUuid) skinByUuid(s) else skinByUuid(uuidByName(s))
    }

    /**
     * 换成 https。
     *
     * textures.minecraft.net 返回的是 http:// 地址，而 Android 9（API 28）起
     * **默认禁止明文 http**，Coil 加载会直接失败（Cleartext traffic not permitted），
     * 表现就是"皮肤查到了但图片一片空白"。统一换成 https 再加载。
     */
    fun https(u: String): String =
        if (u.startsWith("http://", ignoreCase = true)) "https://" + u.substring(7) else u

    fun clearCache() = synchronized(cache) { cache.clear() }
}
