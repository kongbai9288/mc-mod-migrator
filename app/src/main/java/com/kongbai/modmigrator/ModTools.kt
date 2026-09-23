package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

/**
 * 模组体检与配置对比。
 *
 * 这两个是 PC 端启动器（MultiMC / Prism / HMCL）里常见的实用工具，
 * 迁移前先跑一遍能提前发现：同一个模组装了两份、文件名带中文空格、
 * 目标实例缺了某些配置文件等问题。
 *
 * 结果用纯文本返回，调用方直接放进对话框展示，避免新增布局带来的风险。
 */
object ModTools {

    data class Report(val title: String, val text: String)

    /** 文件名里不该出现的字符：中文、空格、括号等，MC 加载时容易出问题 */
    private val BAD_NAME = Regex("[^A-Za-z0-9._-]")

    /**
     * 从模组文件名里提取「项目名」，用于判断重复。
     * 例：jei-fabric-1.21.1-15.1.0.1.jar → jei
     *     sodium-fabric-1.20.1-0.5.3.jar  → sodium
     */
    fun projectOf(fileName: String): String {
        var n = fileName.substringBeforeLast(".jar", "")
        if (n.isBlank()) n = fileName
        n = n.lowercase(Locale.ROOT)
        // 去掉常见的版本段：纯数字与点组成的段、mc 版本号
        val parts = n.split("-", "_", " ").filter { it.isNotBlank() }
        val kept = parts.filterNot { seg ->
            seg.matches(Regex("^[0-9]+(\\.[0-9]+)*[a-z]?$")) ||
                seg.matches(Regex("^mc[0-9.]+$")) ||
                seg in setOf("fabric", "forge", "neoforge", "quilt", "mc", "mod", "release", "beta", "alpha")
        }
        return kept.firstOrNull() ?: n.takeWhile { it.isLetter() }.ifBlank { n }
    }

    /** 模组体检：重复、可疑文件名、超大文件 */
    fun doctor(ctx: Context, srcUri: String): Report {
        val root = Fs.tree(ctx, srcUri)
        if (root == null) {
            return Report("模组体检", "源目录不可访问，请先在迁移页选择「迁移前」的版本目录。")
        }
        val mods = Fs.find(root, "mods", 0)
        if (mods == null) {
            return Report("模组体检", "源目录里没有找到 mods 文件夹。")
        }
        val jars = Fs.children(mods).filter { it.isFile && (it.name ?: "").endsWith(".jar", true) }
        if (jars.isEmpty()) {
            return Report("模组体检", "mods 里没有 jar 文件。")
        }

        val sb = StringBuilder()
        var issues = 0

        // 1. 重复：同项目名出现多份
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (j in jars) {
            val n = j.name ?: continue
            groups.getOrPut(projectOf(n)) { ArrayList() }.add(n)
        }
        val dups = groups.filter { it.value.size > 1 }
        if (dups.isNotEmpty()) {
            issues += dups.size
            sb.append("【重复/多份】共 ${dups.size} 组\n")
            for ((k, v) in dups) {
                sb.append("  · $k：\n")
                for (f in v) sb.append("      - $f\n")
            }
            sb.append("  同一模组装多份会导致加载失败，保留一份即可。\n\n")
        }

        // 2. 可疑文件名
        val bad = jars.filter { BAD_NAME.containsMatchIn(it.name ?: "") }
        if (bad.isNotEmpty()) {
            issues += bad.size
            sb.append("【文件名可疑】共 ${bad.size} 个\n")
            for (j in bad) sb.append("  · ${j.name}\n")
            sb.append("  含中文/空格/括号的文件名在部分加载器下读不出来，建议改成纯英文。\n\n")
        }

        // 3. 超大文件
        val big = jars.filter { (it.length()) > 50L * 1024 * 1024 }
        if (big.isNotEmpty()) {
            issues += big.size
            sb.append("【文件偏大】共 ${big.size} 个（>50MB）\n")
            for (j in big) sb.append("  · ${j.name}（${fmt(j.length())}）\n")
            sb.append("  移动端加载大整合包容易内存不足。\n\n")
        }

        if (issues == 0) {
            sb.append("没有发现问题 ✓\n\n")
        }
        sb.append("共扫描 ${jars.size} 个 jar，发现 $issues 类问题。")
        return Report("模组体检（${jars.size} 个模组）", sb.toString())
    }

    /** 收集目录下所有文件的相对路径 → 大小 */
    private fun walk(dir: DocumentFile, prefix: String, out: MutableMap<String, Long>, depth: Int) {
        if (depth > 4) return
        for (c in Fs.children(dir)) {
            val n = c.name ?: continue
            if (n.startsWith(".")) continue
            val rel = if (prefix.isEmpty()) n else "$prefix/$n"
            if (c.isDirectory) {
                walk(c, rel, out, depth + 1)
            } else {
                out[rel] = c.length()
            }
        }
    }

    /**
     * 配置差异对比：比较源与目标实例的配置目录。
     * 默认比 config，两边都没有时退回比根目录的顶层文件。
     */
    fun diffConfig(ctx: Context, srcUri: String, dstUri: String): Report {
        val srcRoot = Fs.tree(ctx, srcUri)
        val dstRoot = Fs.tree(ctx, dstUri)
        if (srcRoot == null || dstRoot == null) {
            return Report(
                "配置对比",
                "需要同时设置「迁移前」与「迁移后」的目录，请先在迁移页选好两处。"
            )
        }
        val srcDir = Fs.find(srcRoot, "config", 0) ?: srcRoot
        val dstDir = Fs.find(dstRoot, "config", 0) ?: dstRoot

        val a = LinkedHashMap<String, Long>()
        val b = LinkedHashMap<String, Long>()
        walk(srcDir, "", a, 0)
        walk(dstDir, "", b, 0)

        val onlyA = a.keys.filter { it !in b }.sorted()
        val onlyB = b.keys.filter { it !in a }.sorted()
        val changed = a.keys.filter { it in b && a[it] != b[it] }.sorted()
        val same = a.keys.count { it in b && a[it] == b[it] }

        val sb = StringBuilder()
        sb.append("「迁移前」${a.size} 个文件，「迁移后」${b.size} 个文件\n")
        sb.append("两边一致：$same 个\n\n")

        if (onlyA.isNotEmpty()) {
            sb.append("【仅迁移前有】${onlyA.size} 个（迁移后会缺失）\n")
            for (f in onlyA.take(30)) sb.append("  · $f\n")
            if (onlyA.size > 30) sb.append("  …还有 ${onlyA.size - 30} 个\n")
            sb.append("\n")
        }
        if (changed.isNotEmpty()) {
            sb.append("【两边都有但内容不同】${changed.size} 个\n")
            for (f in changed.take(30)) {
                sb.append("  · $f（${fmt(a[f] ?: 0)} → ${fmt(b[f] ?: 0)}）\n")
            }
            if (changed.size > 30) sb.append("  …还有 ${changed.size - 30} 个\n")
            sb.append("  迁移会覆盖这些文件，重要配置建议先备份。\n\n")
        }
        if (onlyB.isNotEmpty()) {
            sb.append("【仅迁移后有】${onlyB.size} 个（迁移前没有，不会被覆盖）\n")
            for (f in onlyB.take(20)) sb.append("  · $f\n")
            if (onlyB.size > 20) sb.append("  …还有 ${onlyB.size - 20} 个\n")
        }
        if (onlyA.isEmpty() && changed.isEmpty() && onlyB.isEmpty()) {
            sb.append("两边配置完全一致，无需迁移。")
        }
        return Report("配置对比", sb.toString())
    }

    private fun fmt(size: Long): String {
        if (size < 1024) return "${size}B"
        if (size < 1024 * 1024) return "${size / 1024}KB"
        return String.format(Locale.ROOT, "%.1fMB", size / 1024.0 / 1024.0)
    }
}
