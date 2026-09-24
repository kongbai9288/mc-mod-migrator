package com.kongbai.modmigrator

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * 硬链接优化（同盘迁移时省空间）。
 *
 * 同一个"实例"复制两份，模组动辄几个 GB，硬链接能做到**几乎不占额外空间**，
 * 因为两个路径指向同一份数据块。
 *
 * 重要限制（必须说清楚，不能假装万能）：
 *  1. 只有在**能用 File API** 的路径下才行（共享存储 / 应用私有目录）。
 *     经过 SAF 树授权的目录（DocumentFile）拿不到真实文件描述符，
 *     无法建硬链接，只能老老实实复制。
 *  2. 必须**同一分区**（同一个 st_dev）。跨 SD 卡、跨分区会失败。
 *  3. 部分文件系统（如 FAT32 / 某些 SD 卡）不支持硬链接。
 *  4. 硬链接后两个文件"是一份"，改一个另一个也变——
 *     所以**只对只读的模组文件用**，配置文件一定用复制。
 *
 * 策略：能链就链，链不了就复制，绝不因为追求省空间导致迁移失败。
 */
object HardLink {

    /**
     * 尝试建立硬链接，失败则回退为复制。
     * @return 实际使用的方式："hardlink" 或 "copy"
     */
    fun linkOrCopy(src: File, dst: File): String {
        return try {
            if (!src.exists() || !src.isFile) return "copy"
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()

            // 先判断同分区：不同分区硬链接必然失败，直接复制更干脆
            if (!sameDevice(src, dst)) return copy(src, dst)

            Files.createLink(dst.toPath(), src.toPath())
            // 校验：硬链接后两者大小必须一致
            if (dst.exists() && dst.length() == src.length()) "hardlink"
            else copy(src, dst)
        } catch (t: Throwable) {
            // 硬链接不支持 / 权限不足 / 文件系统不支持 → 复制
            copy(src, dst)
        }
    }

    /** 两个路径是否在同一分区 */
    private fun sameDevice(a: File, b: File): Boolean {
        return try {
            val pa = Files.readAttributes(a.toPath(), "unix:dev", LinkOption.NOFOLLOW_LINKS)
            val rootB = nearestExisting(b)
            val pb = Files.readAttributes(rootB.toPath(), "unix:dev", LinkOption.NOFOLLOW_LINKS)
            pa["dev"] == pb["dev"]
        } catch (t: Throwable) {
            // 拿不到 dev（非 unix 或不支持该属性）就保守认为可以试
            true
        }
    }

    private fun nearestExisting(f: File): File {
        var cur = f
        while (!cur.exists() && cur.parent != null) cur = cur.parentFile
        return cur
    }

    private fun copy(src: File, dst: File): String {
        return try {
            dst.parentFile?.mkdirs()
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            "copy"
        } catch (t: Throwable) {
            try {
                src.inputStream().use { i -> dst.outputStream().use { i.copyTo(it) } }
                "copy"
            } catch (t2: Throwable) {
                "copy"
            }
        }
    }

    /** 这个文件系统看起来支不支持硬链接（用于界面提示） */
    fun supported(dir: File): Boolean {
        return try {
            val test = File(dir, ".hardlink_test_${System.currentTimeMillis()}")
            if (!test.createNewFile()) return false
            val link = File(dir, ".hardlink_test_link_${System.currentTimeMillis()}")
            val ok = runCatching { Files.createLink(link.toPath(), test.toPath()); true }
                .getOrDefault(false)
            link.delete(); test.delete()
            ok
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 估算能省多少空间。
     * 返回可硬链接文件的总字节数（即理论上不用重复占用的空间）。
     */
    fun savingsOf(files: List<File>, dstDir: File): Long {
        if (!supported(dstDir)) return 0L
        var sum = 0L
        for (f in files) {
            if (!f.isFile) continue
            // 目标在别处（不同目录）才算真的省；同目录的覆盖场景不算
            if (sameDevice(f, dstDir)) sum += f.length()
        }
        return sum
    }
}
