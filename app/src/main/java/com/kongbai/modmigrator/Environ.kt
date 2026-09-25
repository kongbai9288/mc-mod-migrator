package com.kongbai.modmigrator

import com.google.gson.JsonElement

/**
 * Modrinth 运行环境（environment）。
 *
 * ## 为什么要单独抽出来
 *
 * 老代码用的是 `client_side` / `server_side`，而 Modrinth 官方文档
 * 已把这两个字段标为 **deprecated（废弃）**，改为单一的 `environment`：
 *
 * > client_side (deprecated - use environment instead)
 * > server_side (deprecated - use environment instead)
 *
 * 继续读废弃字段会拿到 `unknown` 或缺失，判断"这个模组能不能装到服务端"
 * 就没有依据了。
 *
 * ## 官方取值（来自 Modrinth 官方博客对这套新体系的说明）
 *
 * | environment | 含义 | 例子 |
 * |---|---|---|
 * | `client_and_server` | 两端都必需 | Cobblemon |
 * | `client_only` | 仅客户端 | Mod Menu |
 * | `client_only_server_optional` | 主要客户端，服务端可选 | AppleSkin |
 * | `singleplayer_only` | 仅单人 | — |
 * | `server_only` | 仅服务端，单人也能用 | YUNG's Bridges |
 * | `server_only_client_optional` | 主要服务端，客户端可选 | Polymer |
 * | `dedicated_server_only` | 仅独立服务端 | Better Fabric Console |
 * | `client_or_server` | 两端任一即可 | — |
 * | `client_or_server_prefers_both` | 两端任一，优先都装 | — |
 * | `unknown` | 未标注 | — |
 */
object Environ {

    enum class Side { CLIENT, SERVER }

    /** 纯客户端：装到服务端上没用（甚至可能报错） */
    private val CLIENT_ONLY = setOf("client_only")

    /** 服务端可用的（含"服务端可选/必需"与两端皆可） */
    private val SERVER_OK = setOf(
        "client_and_server",
        "server_only",
        "server_only_client_optional",
        "dedicated_server_only",
        "client_or_server",
        "client_or_server_prefers_both",
        "client_only_server_optional"
    )

    /** 客户端可用的 */
    private val CLIENT_OK = setOf(
        "client_and_server",
        "client_only",
        "client_only_server_optional",
        "singleplayer_only",
        "client_or_server",
        "client_or_server_prefers_both",
        "server_only_client_optional",
        "server_only" // 单人世界也算客户端侧可用
    )

    /**
     * 读取一个 JSON 对象里的 environment。
     *
     * 兼容处理：老数据可能只有 client_side / server_side，
     * 这里做一次兜底换算，避免拿到空值导致判断失效。
     */
    fun read(e: JsonElement?): String {
        val v = Json.s(e, "environment")
        if (v.isNotBlank()) return v.lowercase()
        // 兜底：老字段（已废弃）
        val cs = Json.s(e, "client_side").lowercase()
        val ss = Json.s(e, "server_side").lowercase()
        if (cs.isBlank() && ss.isBlank()) return "unknown"
        return when {
            cs == "required" && ss == "required" -> "client_and_server"
            cs == "required" -> "client_only"
            ss == "required" -> "server_only"
            cs == "optional" || ss == "optional" -> "client_or_server"
            else -> "unknown"
        }
    }

    /** 该环境在指定端是否可用。未标注（unknown）一律放行——不能因为没标注就误杀 */
    fun okFor(env: String, side: Side): Boolean {
        val e = env.lowercase()
        if (e.isBlank() || e == "unknown") return true
        return if (side == Side.SERVER) e in SERVER_OK else e in CLIENT_OK
    }

    /** 是否明确是"纯客户端"（用于给用户提示，而不是直接拦掉） */
    fun isClientOnly(env: String): Boolean = env.lowercase() in CLIENT_ONLY

    /**
     * 生成 v3 的 `new_filters` 片段。
     *
     * 官方 search 文档把 `environment` 列为 facet/filter 类型之一，
     * 取值与上表一致。这里只在能明确表达时才加过滤：
     * 纯客户端的取值只有一个（client_only），可以用 `!=` 排除；
     * 服务端可用取值有七个，用 OR 表达过于冗长，
     * 交给 [okFor] 在拿到结果后筛更稳妥。
     */
    fun filterFor(side: Side): String? = when (side) {
        Side.SERVER -> """environment!="client_only""""
        Side.CLIENT -> null // 客户端场景基本都能装，不过滤
    }

    /** 给用户看的一句话说明 */
    fun label(env: String): String {
        val e = env.lowercase()
        return when (e) {
            "client_and_server" -> "两端都需要"
            "client_only" -> "仅客户端"
            "client_only_server_optional" -> "客户端为主，服务端可选"
            "singleplayer_only" -> "仅单人"
            "server_only" -> "仅服务端"
            "server_only_client_optional" -> "服务端为主，客户端可选"
            "dedicated_server_only" -> "仅独立服务端"
            "client_or_server" -> "两端任一"
            "client_or_server_prefers_both" -> "两端任一（推荐都装）"
            else -> "未标注"
        }
    }
}
