package com.kongbai.modmigrator

data class ModEntry(
    var fileName: String = "",
    var uri: String = "",
    var sha1: String = "",
    var projectId: String = "",
    var slug: String = "",
    var name: String = "",
    var currentVersion: String = "",
    var targetVersion: String = "",
    var targetUrl: String = "",
    var targetFileName: String = "",
    var pageUrl: String = "",
    var status: String = "待处理",
    /** 是否是"网络问题导致没查成"（区别于确实没收录）。用于重试。 */
    var netError: Boolean = false
)

data class MarketMod(
    var id: String = "",
    var slug: String = "",
    var name: String = "",
    var summary: String = "",
    var iconUrl: String = "",
    var pageUrl: String = "",
    var downloads: Long = 0,
    var fileId: String = "",
    var fileName: String = "",
    var summaryZh: String = "",
    var source: String = "modrinth",
    var updated: String = "",  // 最近更新时间（ISO），用于按时间排序
    /** Modrinth 的 environment；client_side/server_side 已废弃，见 [Environ] */
    var environment: String = ""
)

data class ModFile(
    var name: String = "",
    var version: String = "",
    var url: String = "",
    var fileName: String = "",
    /**
     * 运行环境（Modrinth 的 `environment` 字段）。
     *
     * 官方取值（client_side / server_side **已废弃**，改用这个）：
     *  - client_and_server              两端都需要（如 Cobblemon）
     *  - client_only                    仅客户端（如 Mod Menu）
     *  - client_only_server_optional    主要客户端，服务端可选（如 AppleSkin）
     *  - singleplayer_only              仅单人
     *  - server_only                    仅服务端，单人也能用（如 YUNG's Bridges）
     *  - server_only_client_optional    主要服务端，客户端可选（如 Polymer）
     *  - dedicated_server_only          仅独立服务端（如 Better Fabric Console）
     *  - client_or_server / client_or_server_prefers_both / unknown
     */
    var environment: String = ""
)

data class MarkedLink(
    var title: String = "",
    var url: String = ""
)

data class RemoteDevice(
    var id: String = "",
    var label: String = "",
    var time: String = "",
    var mcVersion: String = "",
    var loader: String = "",
    var modCount: Int = 0,
    var manifestPath: String = ""
)
