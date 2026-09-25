package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/**
 * 创建实例 + 安装加载器。
 *
 * 迁移之后常常面临一个空目录：有 mods 有 config，但启动器不认它是个实例。
 * 这里补上启动器认的那些"标志"，让它变成一个能直接被识别的实例。
 *
 * **必须说清的边界**：
 *  - 本应用**不能替你下载安装 Forge/Fabric 的安装器本体**——
 *    那需要运行 Java 安装器、写入游戏版本目录、改启动参数，
 *    在手机上做不到，也不是模组迁移工具该干的事。
 *  - 我们做的是：①补齐实例目录结构与标志文件
 *    ②给出**官方下载地址**（走镜像加速），由你下载安装。
 *    不假装能一键装好。
 */
object InstanceCreator {

    data class Loader(
        val key: String,       // fabric / forge / quilt / neoforge
        val label: String,
        val page: String,      // 官方下载页
        val desc: String
    )

    val LOADERS = listOf(
        Loader("fabric", "Fabric", "https://fabricmc.net/use/installer/",
            "轻量、启动快，模组生态最大；适合 1.14+ 的大多数模组"),
        Loader("forge", "Forge", "https://files.minecraftforge.net/",
            "老牌加载器，兼容性最广；老版本模组基本只能用它"),
        Loader("neoforge", "NeoForge", "https://neoforged.net/",
            "Forge 的分支，1.20.2+ 的新选择"),
        Loader("quilt", "Quilt", "https://quiltmc.org/en/install/",
            "Fabric 的分支，兼容多数 Fabric 模组")
    )

    /**
     * 在目标目录下补齐一个标准实例结构。
     * 已存在的目录不会覆盖，只创建缺失的。
     */
    fun scaffold(ctx: Context, dst: DocumentFile): List<String> {
        val made = ArrayList<String>()
        val dirs = listOf("mods", "config", "saves", "resourcepacks", "shaderpacks")
        for (d in dirs) {
            try {
                val exist = dst.findFile(d)
                if (exist == null) {
                    dst.createDirectory(d)
                    made.add(d)
                }
            } catch (t: Throwable) { Err.ignore(t, "made.add(d)") }
        }
        return made
    }

    /**
     * 写入 MultiMC / Prism 风格的实例描述文件。
     * 有了这个，Prism/MultiMC 能直接把这个目录当实例导入。
     */
    fun writeInstanceCfg(ctx: Context, dst: DocumentFile, name: String, mc: String): Boolean {
        return try {
            val content = buildString {
                appendLine("[General]")
                appendLine("ConfigVersion=1.2.0")
                appendLine("InstanceType=OneSix")
                appendLine("iconKey=default")
                appendLine("name=$name")
                appendLine()
                appendLine("[OneSix]")
                if (mc.isNotBlank()) appendLine("IntendedVersion=$mc")
            }
            val old = dst.findFile("instance.cfg")
            if (old != null) old.delete()
            val f = dst.createFile("text/plain", "instance.cfg") ?: return false
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 写入 mmc-pack.json（Prism/MultiMC 的包描述）。
     * 这样这个目录在 Prism 里能直接作为"可导入的整合包"出现。
     */
    fun writeMmcPack(ctx: Context, dst: DocumentFile, name: String, mc: String, loader: String): Boolean {
        return try {
            val comps = ArrayList<String>()
            when (loader.lowercase()) {
                "fabric" -> comps.add(comp("net.fabricmc.intermediary", mc))
                "quilt" -> comps.add(comp("org.quiltmc.intermediary", mc))
            }
            // ── 必须用 org.json 序列化，不能手写字符串拼 ──────────
            // 之前用自带的 esc()，只转义 \ 和 "，漏掉换行等控制字符。
            // 实例名来自**目录名**，目录名里出现引号、换行是允许的，
            // 拼出来的 mmc-pack.json 就成了非法 JSON ——
            // Prism / MultiMC 导入时直接失败，而且看不出原因。
            val arr = org.json.JSONArray()
            arr.put(
                org.json.JSONObject()
                    .put("uid", "net.minecraft")
                    .put("version", mc)
            )
            for (c in comps) arr.put(c)
            val obj = org.json.JSONObject()
                .put("formatVersion", 1)
                .put("name", name)
                .put("components", arr)
                .put("packType", "modpack")
            val json = obj.toString(2)

            val old = dst.findFile("mmc-pack.json")
            if (old != null) old.delete()
            val f = dst.createFile("application/json", "mmc-pack.json") ?: return false
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (t: Throwable) {
            Err.ignore(t, "写 mmc-pack.json")
            false
        }
    }

    private fun comp(uid: String, version: String): org.json.JSONObject =
        org.json.JSONObject().put("uid", uid).put("version", version)

    /**
     * 加载器的官方下载页。
     *
     * 之前是 `https://ghfast.top/${l.page}`——
     * ghfast.top 这类是 **GitHub 文件加速**，不是通用网页代理，
     * 拿它去套 fabricmc.net / files.minecraftforge.net 基本打不开。
     * 表现就是：点「打开官方下载页」看到一个错误页，
     * 用户以为功能坏了。这里直接给官方地址。
     */
    fun loaderPage(loader: String): String {
        val l = LOADERS.firstOrNull { it.key == loader.lowercase() } ?: return ""
        return l.page
    }

    /**
     * 给用户的安装说明。
     * 不做"一键安装"，而是把步骤说清楚——
     * 假装能一键装好，结果装不上，比直接说明更糟。
     */
    fun installGuide(loader: String, mc: String): String {
        val l = LOADERS.firstOrNull { it.key == loader.lowercase() } ?: return ""
        return buildString {
            appendLine("安装 ${l.label}（MC ${mc.ifBlank { "未指定" }}）")
            appendLine()
            appendLine(l.desc)
            appendLine()
            appendLine("步骤：")
            appendLine("1. 点击下方「打开官方下载页」")
            appendLine("2. 下载对应 MC 版本的安装器")
            appendLine("3. 在你的启动器里运行安装器（FCL / Zalith / Pojav 都有入口）")
            appendLine()
            appendLine("说明：安装器需要运行 Java 并写入游戏版本目录，")
            appendLine("手机上无法代替启动器完成这一步，所以本应用只负责带你到官方页面。")
        }
    }

}
