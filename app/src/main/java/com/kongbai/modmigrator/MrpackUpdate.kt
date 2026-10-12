package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/**
 * 整合包更新检查。
 *
 * ## 只查「用户主动导入」的包
 *
 * 这里只接受 [MrpackInstall.Pack] —— 也就是用户亲手导入并登记过的整合包。
 * 不去扫某个目录里的 jar 猜它属于哪个包，理由有两个：
 *  1. 没有基线就无法判断新旧：目录里只有一堆文件，
 *     不知道"当前应该是哪一版"，也就无从谈更新。
 *  2. 这是隐私边界：检查更新会把文件的哈希发往 Modrinth。
 *     只有用户明确导入过的包才做这件事，其余一律不碰。
 *
 * 需要"看看某个目录里的模组有没有新版"，那是模组更新
 * （见整合包页里的另一个入口），不是整合包更新。
 */
object MrpackUpdate {

    data class Item(
        val path: String,
        val currentName: String,
        val latestName: String,
        val url: String,
        val sha1: String
    ) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    /**
     * @return null 表示没法查（离线、清单读不出来），空列表表示都已是最新
     */
    fun check(ctx: Context, pack: MrpackInstall.Pack, log: (String) -> Unit): List<Item>? {
        val index = Mrpack.parse(pack.indexJson)
        if (index == null) {
            log("登记的清单读不出来，请重新导入")
            return null
        }
        if (index.files.isEmpty()) {
            log("这个包的所有文件都随包带走，没有可远程更新的条目")
            return emptyList()
        }
        if (Prefs.get(ctx).getBoolean(K.OFFLINE, false)) {
            log("离线模式：无法联网检查更新")
            return null
        }
        val mc = index.mcVersion
        val loader = index.loader
        val hashes = index.files.map { it.sha1 }.filter { it.isNotBlank() }.map { it.lowercase() }
        if (hashes.isEmpty()) {
            log("清单里没有可用的哈希，无法比对")
            return emptyList()
        }
        log("正在比对 ${hashes.size} 个文件…")
        val latest = runCatching {
            ModrinthApi.latestForHashes(hashes, mc, loader)
        }.getOrDefault(emptyMap())

        val out = ArrayList<Item>()
        for (f in index.files) {
            if (f.sha1.isBlank()) continue
            val hit = latest[f.sha1.lowercase()] ?: continue
            val newName = hit.fileName
            if (newName.isBlank() || hit.url.isBlank()) continue
            // 文件名一致就说明已经是这一版。Modrinth 的文件名带版本号，
            // 比比对 version_number 字符串可靠（后者格式五花八门）。
            if (newName.equals(f.path.substringAfterLast('/'), true)) continue
            out.add(
                Item(
                    path = f.path,
                    currentName = f.path.substringAfterLast('/'),
                    latestName = newName,
                    url = hit.url,
                    sha1 = f.sha1
                )
            )
        }
        return out
    }

    /**
     * 把更新装到所有目标实例上。
     *
     * 同一个包可能装在多个实例/多个启动器里，
     * 所以更新也是一次装全部，不会出现"这台更新了那台没更新"。
     *
     * 旧文件先改名成 .old 再删 —— 直接删的话下载中途失败就两边都没有了。
     */
    fun apply(
        ctx: Context, items: List<Item>, targets: List<DocumentFile>, log: (String) -> Unit
    ): Int {
        if (items.isEmpty() || targets.isEmpty()) return 0
        var done = 0
        for (t in targets) {
            val label = t.name ?: "目标"
            log("「$label」开始更新…")
            for (it in items) {
                val slash = it.path.lastIndexOf('/')
                val dirRel = if (slash > 0) it.path.substring(0, slash) else ""
                val dir = if (dirRel.isBlank()) t else subDir(t, dirRel)
                if (dir == null) {
                    log("  ${it.path}：目录建不出来")
                    continue
                }
                val old = dir.findFile(it.currentName)
                val out = runCatching {
                    Downloader.download(ctx, it.url, dir, it.latestName, emptyMap())
                }.getOrNull()
                if (out == null) {
                    log("  ${it.latestName}：下载失败")
                    continue
                }
                if (old != null && old.isFile) {
                    runCatching {
                        val bak = dir.createFile("application/octet-stream", "${it.currentName}.old")
                        if (bak != null) {
                            ctx.contentResolver.openInputStream(old.uri)?.use { i ->
                                ctx.contentResolver.openOutputStream(bak.uri, "w")?.use { o ->
                                    i.copyTo(o)
                                }
                            }
                        }
                        old.delete()
                    }
                }
                done++
            }
            log("「$label」更新完成")
        }
        return done
    }

    private fun subDir(root: DocumentFile, rel: String): DocumentFile? {
        var cur = root
        for (part in rel.split('/')) {
            if (part.isBlank()) continue
            val next = cur.findFile(part) ?: cur.createDirectory(part) ?: return null
            cur = next
        }
        return cur
    }
}
