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
    var status: String = "待处理"
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
    var source: String = "modrinth"
)

data class ModFile(
    var name: String = "",
    var version: String = "",
    var url: String = "",
    var fileName: String = ""
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
