package com.kongbai.modmigrator

import java.io.File

/**
 * 世界 → 维度 → 区域文件的索引。
 *
 * 只做"找到文件、算出坐标"这一件事，不读文件内容 ——
 * 内容留给 [McaTiles] 按需读，避免一进页面就把整个世界搬进内存。
 *
 * 存档的目录结构前后改过三次，三种都得认：
 *
 *   · 1.21 起（含 26.x）：<世界>/dimensions/minecraft/<维度>/region
 *   · 1.16 及更早      ：<世界>/region、<世界>/DIM-1/region、<世界>/DIM1/region
 *   · 模组自定义维度    ：<世界>/dimensions/<命名空间>/<维度>/region
 *
 * 只认 region 目录。同级的 poi / entities 不存方块数据，
 * 混进来会让用户点开一片空白 —— 那是之前"点进去全提示无数据"的来源。
 */
object McaWorld {

    /**
     * SAF 世界 → 本地镜像目录的文件映射。
     *
     * SAF 的 content:// 拿不到真实 File，而区块地图整块逻辑都基于 File。
     * 所以打开 SAF 世界时先把 region 下的 .mca 复制到本地，
     * 保存时再按这张表逐个写回原处。
     */
    object Mirror {
        private val map = java.util.concurrent.ConcurrentHashMap<String, android.net.Uri>()

        fun put(f: java.io.File, u: android.net.Uri) { map[f.absolutePath] = u }

        fun of(f: java.io.File): android.net.Uri? = map[f.absolutePath]

        fun clear() { map.clear() }
    }

    /** 一个区域文件的引用。坐标从文件名 r.X.Z.mca 来。 */
    class Ref(val rx: Int, val rz: Int, val file: File) {
        /** 该区域左下角对应的世界区块坐标 */
        val baseChunkX get() = rx * 32
        val baseChunkZ get() = rz * 32
        /** 该区域左下角对应的方块坐标 */
        val baseBlockX get() = rx * 512
        val baseBlockZ get() = rz * 512
        val name get() = file.name
    }

    /** 一个维度 */
    class Dim(val key: String, val label: String, val regionDir: File) {

        /** 该维度下所有区域文件，按坐标排好序，方便按可见范围取 */
        fun regions(): List<Ref> {
            val out = ArrayList<Ref>()
            val files = regionDir.listFiles() ?: return out
            for (f in files) {
                if (!f.isFile) continue
                val n = f.name
                // .mcr 是 Beta 1.3 及更早的旧 Region 格式，区块数据布局不同，
                // 但扇区表和 .mca 一致，所以同样要列出来（解析层已分版本处理）。
                // .mcc 是早已废弃的分离式区块文件，游戏现在也不会读。
                if (!n.endsWith(".mca") && !n.endsWith(".mcr")) continue
                val c = parseName(n) ?: continue
                out.add(Ref(c.first, c.second, f))
            }
            out.sortWith(compareBy<Ref> { it.rx }.thenBy { it.rz })
            return out
        }

        fun count(): Int = regions().size

        /** 该维度所有区域文件占用的磁盘空间 */
        fun sizeBytes(): Long = regions().sumOf { it.file.length() }
    }

    /** r.-1.0.mca / r.-1.0.mcr → (-1, 0)。两种扩展名都要认，旧存档是 .mcr。 */
    private fun parseName(n: String): Pair<Int, Int>? {
        val p = n.split('.')
        if (p.size < 4) return null
        if (p[0] != "r") return null
        val x = p[1].toIntOrNull() ?: return null
        val z = p[2].toIntOrNull() ?: return null
        return x to z
    }

    private fun labelOf(key: String): String = when (key) {
        "overworld" -> "主世界"
        "the_nether" -> "下界"
        "nether" -> "下界"
        "the_end" -> "末地"
        "end" -> "末地"
        "DIM-1" -> "下界"
        "DIM1" -> "末地"
        else -> key
    }

    private fun dir(base: File, vararg seg: String): File? {
        var f = base
        for (s in seg) f = File(f, s)
        return if (f.isDirectory) f else null
    }

    /**
     * 列出一个世界下的所有维度。
     *
     * 主世界优先排在前面 —— 绝大多数人只想看主世界，
     * 每次都要在三个维度里翻一遍很烦。
     */
    fun discover(worldDir: File): List<Dim> {
        if (!worldDir.isDirectory) return emptyList()
        val out = LinkedHashMap<String, Dim>()

        fun put(key: String, d: File?) {
            if (d == null) return
            if (out.containsKey(key)) return
            out[key] = Dim(key, labelOf(key), d)
        }

        // 1) 新版：dimensions/<命名空间>/<维度>/region
        val dimsRoot = dir(worldDir, "dimensions")
        if (dimsRoot != null) {
            val nsDirs = dimsRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
            // minecraft 命名空间优先，自定义维度排在后面
            val ordered = nsDirs.sortedBy { if (it.name == "minecraft") 0 else 1 }
            for (ns in ordered) {
                val ds = ns.listFiles()?.filter { it.isDirectory } ?: continue
                for (d in ds) {
                    val region = dir(d, "region") ?: continue
                    val key = if (ns.name == "minecraft") d.name else "${ns.name}:${d.name}"
                    put(key, region)
                }
            }
        }

        // 2) 老版：顶层 region（主世界）、DIM-1（下界）、DIM1（末地）
        put("overworld", dir(worldDir, "region"))
        put("DIM-1", dir(worldDir, "DIM-1", "region"))
        put("DIM1", dir(worldDir, "DIM1", "region"))

        // 主世界排最前，其次下界、末地，自定义维度最后
        val rank: (String) -> Int = {
            when (it) {
                "overworld" -> 0
                "the_nether", "DIM-1" -> 1
                "the_end", "DIM1" -> 2
                else -> 3
            }
        }
        return out.values.sortedBy { rank(it.key) }
    }

    /**
     * 兼容入口：直接给一个 .mca 文件也能看。
     * 坐标只能当作单个区域处理，没有世界上下文。
     */
    fun fromSingleFile(f: File): Dim? {
        if (!f.isFile) return null
        if (!f.name.endsWith(".mca") && !f.name.endsWith(".mcr")) return null
        val c = parseName(f.name) ?: return null
        val d = f.parentFile ?: return null
        val dim = Dim("_single", "单个区域文件", d)
        return dim
    }
}
