package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

data class InstanceInfo(
    var name: String = "",
    var uri: String = "",
    var mcVersion: String = "",
    var loader: String = "auto",
    var modCount: Int = 0,
    var hasConfig: Boolean = false,
    var kind: String = ""
)

object InstanceScanner {

    val ROOT_HINTS = listOf(
        "games", "Games", "minecraft", "Minecraft",
        "HMCL", "PCL", "mcinaBox", "MCinaBox"
    )

    /** 扫描时直接跳过的目录名：这些目录体积大且不可能含实例 */
    private val SKIP = setOf(
        "android", "Android", "dcim", "DCIM", "pictures", "Pictures",
        "movies", "Movies", "download", "Download", "music", "Music",
        "documents", "Documents", "tencent", "Tencent", "qq", "QQ",
        "weixin", "WeChat", "alipay", "cache", ".cache", "thumbnails",
        "screenshots", "recordings", "podcasts", "alarms", "ringtones",
        "notifications", "audiobooks", "system", "lost.dir"
    )

    private val LAUNCHER_DIRS = listOf(
        "pojav", "PojavLauncher", "zalithlauncher", "ZalithLauncher",
        "instances", "versions", "hmcl", "HMCL"
    )

    fun looksLikeInstance(dir: DocumentFile): Boolean {
        if (!dir.isDirectory) return false
        val names = Fs.children(dir).map { (it.name ?: "").lowercase(Locale.ROOT) }.toSet()
        val hasMods = names.contains("mods")
        val hasConfig = names.contains("config")
        val hasVersion = names.contains("version.json")
        val hasPack = names.contains("mmc-pack.json") || names.contains("manifest.json")
        val hasJar = names.contains("versions") || names.contains("libraries")
        return hasMods || hasConfig || hasVersion || hasPack || hasJar
    }

    /**
     * 扫描实例。quick = true 时只进入「名字像启动器」的目录，
     * 跳过其余子树，速度能快一个量级；扫不到再用完整模式。
     */
    fun scan(
        ctx: Context,
        rootUri: String,
        log: (String) -> Unit,
        quick: Boolean = true
    ): List<InstanceInfo> {
        val root = Fs.tree(ctx, rootUri) ?: return emptyList()
        val out = mutableListOf<InstanceInfo>()
        if (quick) collectQuick(ctx, root, 0, out, log) else collect(ctx, root, 0, out, log)
        if (out.isEmpty() && quick) {
            log("快速扫描没找到，再完整扫一遍…")
            out.clear()
            collect(ctx, root, 0, out, log)
        }
        return out.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun collectQuick(
        ctx: Context,
        dir: DocumentFile,
        depth: Int,
        out: MutableList<InstanceInfo>,
        log: (String) -> Unit
    ) {
        if (depth > 4) return
        if (depth > 0 && looksLikeInstance(dir)) {
            val info = readInfo(ctx, dir)
            if (info != null) {
                out.add(info)
                log("发现实例：${info.name}")
                return
            }
        }
        for (c in Fs.children(dir)) {
            if (!c.isDirectory) continue
            val n = c.name ?: ""
            if (n in SKIP || n.startsWith(".")) continue
            // 快速模式：只进「像启动器」的目录，其余跳过
            if (depth == 0 && !LauncherHelper.isKnownDir(n) && n.lowercase(Locale.ROOT) != "games") continue
            collectQuick(ctx, c, depth + 1, out, log)
        }
    }

    private fun collect(
        ctx: Context,
        dir: DocumentFile,
        depth: Int,
        out: MutableList<InstanceInfo>,
        log: (String) -> Unit
    ) {
        if (depth > 3) return
        if (depth > 0 && looksLikeInstance(dir)) {
            val info = readInfo(ctx, dir)
            if (info != null) {
                out.add(info)
                log("发现实例：${info.name}（MC ${info.mcVersion.ifBlank { "?" }} / ${info.loader}）")
                return
            }
        }
        for (c in Fs.children(dir)) {
            if (!c.isDirectory) continue
            val n = c.name ?: ""
            if (n in SKIP || n.startsWith(".")) continue
            collect(ctx, c, depth + 1, out, log)
        }
    }

    fun readInfo(ctx: Context, dir: DocumentFile): InstanceInfo? {
        val name = dir.name ?: return null
        val info = InstanceInfo(name = name, uri = dir.uri.toString())

        val mmc = Fs.find(dir, "mmc-pack.json", 0)
        if (mmc != null) {
            val o = Json.obj(Fs.readText(ctx, mmc))
            val comps = Json.a(o, "components")
            if (comps != null) {
                for (c in comps) {
                    val uid = Json.s(c, "uid")
                    val ver = Json.s(c, "version")
                    when {
                        uid == "net.minecraft" -> info.mcVersion = ver
                        uid.startsWith("net.fabricmc") -> info.loader = "fabric"
                        uid.startsWith("org.quiltmc") -> info.loader = "quilt"
                        uid.startsWith("net.minecraftforge") -> info.loader = "forge"
                        uid.startsWith("net.neoforged") -> info.loader = "neoforge"
                    }
                }
            }
            info.kind = "MultiMC/Prism"
        }

        val man = Fs.find(dir, "manifest.json", 0)
        if (man != null && info.mcVersion.isBlank()) {
            val o = Json.obj(Fs.readText(ctx, man))
            val mcObj = o?.asJsonObject?.get("minecraft")
            info.mcVersion = Json.s(mcObj, "version")
            val ls = Json.a(mcObj, "modLoaders")
            if (ls != null && ls.size() > 0) {
                val idv = Json.s(ls[0], "id")
                info.loader = when {
                    idv.startsWith("forge") -> "forge"
                    idv.startsWith("neoforge") -> "neoforge"
                    idv.startsWith("fabric") -> "fabric"
                    idv.startsWith("quilt") -> "quilt"
                    else -> "auto"
                }
            }
            info.kind = "CurseForge"
        }

        val vj = Fs.find(dir, "version.json", 0)
        if (vj != null && info.mcVersion.isBlank()) {
            info.mcVersion = Json.s(Json.obj(Fs.readText(ctx, vj)), "id")
            info.kind = "原版/Forge"
        }

        val mods = Fs.find(dir, "mods", 0)
        if (mods != null) {
            info.modCount = Fs.children(mods).count {
                it.isFile && (it.name ?: "").endsWith(".jar", true)
            }
        }
        info.hasConfig = Fs.find(dir, "config", 0) != null
        if (info.kind.isBlank()) info.kind = "目录"
        return info
    }
}
