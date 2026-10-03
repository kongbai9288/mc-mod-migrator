package com.kongbai.modmigrator

import com.viaversion.nbt.io.NBTIO
import com.viaversion.nbt.limiter.TagLimiter
import com.viaversion.nbt.stringified.SNBT
import com.viaversion.nbt.tag.CompoundTag
import com.viaversion.nbt.tag.Tag
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * NBT 文件读写。基于 ViaNBT（MIT，已做成零依赖 AAR）。
 *
 * ⚠️ 为什么必须处理 gzip：Minecraft 的 level.dat、playerdata、结构文件
 * 几乎都是 gzip 压缩的，而 ViaNBT 的 `read(InputStream)` **不会自动解压**，
 * 必须自己判断魔数（0x1F 0x8B）再包一层 GZIPInputStream。
 * 直接读会抛 "Not in GZIP format"，表现为"文件打不开"，
 * 而实际上是调用方式的问题。
 *
 * 写入同理：Minecraft 期望的是压缩格式，不包 GZIPOutputStream
 * 写出来的文件游戏根本认不出（且不报错，只是存档读不出来）。
 */
object NbtFile {

    /** 读一个 NBT 文件，自动识别 gzip。返回根 compound。 */
    fun read(file: File): CompoundTag {
        FileInputStream(file).use { raw ->
            return readStream(raw)
        }
    }

    fun readStream(input: InputStream): CompoundTag {
        //
        // 先偷看两个字节判断是不是 gzip。
        // 不能直接用 mark/reset 后再 GZIPInputStream —— GZIPInputStream
        // 构造时就会读头部，包装前必须自己判断。
        //
        val buf = ByteArray(2)
        val push = java.io.PushbackInputStream(java.io.BufferedInputStream(input), 2)
        val n = push.read(buf)
        if (n == 2) push.unread(buf, 0, n)
        val gz = n == 2 && (buf[0].toInt() and 0xFF) == 0x1F && (buf[1].toInt() and 0xFF) == 0x8B
        val src: InputStream = if (gz) GZIPInputStream(push) else push
        //
        // named()：标准 NBT 文件的根标签是带名字的（level.dat 的根叫 ""）。
        // 不加这个会读错一个字节，表现为整个文件解析失败或字段错位。
        //
        val tag = NBTIO.reader()
            .named()
            .tagLimiter(TagLimiter.noop())
            .read(DataInputStream(java.io.BufferedInputStream(src)))
        return tag as? CompoundTag
            ?: throw java.io.IOException("根标签不是 Compound（实际是 ${tag.javaClass.simpleName}）")
    }

    /** 读字节数组（用于 SAF 拿到的流） */
    fun readBytes(bytes: ByteArray): CompoundTag = readStream(ByteArrayInputStream(bytes))

    /** 写 NBT 文件。compressed=true 时写成 gzip（Minecraft 默认格式）。 */
    fun write(file: File, tag: CompoundTag, compressed: Boolean = true) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            val out: java.io.OutputStream =
                if (compressed) GZIPOutputStream(java.io.BufferedOutputStream(fos))
                else java.io.BufferedOutputStream(fos)
            NBTIO.writer().named().write(out, tag)
            out.flush()
            (out as? GZIPOutputStream)?.finish()
        }
        // 先写临时文件再原子替换：写一半失败不会把原存档毁掉。
        // 存档文件损坏是不可逆的（用户可能玩了几百小时）。
        if (file.exists() && !file.delete()) throw java.io.IOException("无法替换原文件：${file.name}")
        if (!tmp.renameTo(file)) throw java.io.IOException("写入失败：${file.name}")
    }

    /** 转成 SNBT 文本（人可读、可编辑） */
    fun toSnbt(tag: Tag): String = try {
        SNBT.serialize(tag)
    } catch (t: Throwable) {
        Err.ignore(t, "NBT 转 SNBT")
        ""
    }

    /** 从 SNBT 文本解析回 compound */
    fun fromSnbt(text: String): CompoundTag = SNBT.deserializeCompoundTag(text)

    /** 序列化成字节（给分享/导出用） */
    fun toBytes(tag: CompoundTag, compressed: Boolean = true): ByteArray {
        val bos = ByteArrayOutputStream()
        val out: java.io.OutputStream =
            if (compressed) GZIPOutputStream(bos) else bos
        NBTIO.writer().named().write(out, tag)
        out.flush()
        (out as? GZIPOutputStream)?.finish()
        return bos.toByteArray()
    }
}
