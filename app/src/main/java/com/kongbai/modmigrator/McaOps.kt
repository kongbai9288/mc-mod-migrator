package com.kongbai.modmigrator

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * 区块地图上的两类"成块操作"：替换与导出。
 *
 * 这两件事都绕不开一个重要前提：**改的是哪一份文件**。
 * 之前有个坑：SAF 打开时文件被复制到缓存目录，保存只写缓存，
 * 原存档一个字节没动却提示"已删除 N 个" —— 所以这里凡是写盘的
 * 都返回真实的成功/失败，绝不假报。
 */
object McaOps {

    /**
     * 用源区域的区块覆盖目标区域。
     *
     * 按**槽位一一对应**：源的第 N 个槽位覆盖目标的第 N 个。
     * 这样两个不同区域文件也能对齐 —— 常见用法就是拿备份的
     * r.0.0.mca 去覆盖当前的 r.0.0.mca。
     *
     * 源里不存在的槽位跳过（不会把目标清空），返回真正覆盖的数量。
     */
    fun replaceSlots(dst: McaEdit.Region, src: McaEdit.Region, slots: IntArray): Int {
        var n = 0
        val present = src.present().toSet()
        for (slot in slots) {
            if (slot !in present) continue
            val tag = try {
                src.chunk(slot)
            } catch (_: Throwable) {
                null
            } ?: continue
            try {
                dst.put(slot, tag)
                n++
            } catch (_: Throwable) {
                // 单个失败不影响其余
            }
        }
        return n
    }

    /**
     * 把若干区域按坐标拼成一张 PNG 存进相册。
     *
     * 拼图尺寸 = 区域网格 × 512。区域多的时候会很大 ——
     * 先算出来，超过 [MAX_SIDE] 就按整比例缩小，
     * 免得直接 OOM 或者存出一个打不开的文件。
     */
    fun exportPng(ctx: Context, title: String, tiles: List<Pair<McaWorld.Ref, Bitmap>>): String? {
        if (tiles.isEmpty()) return null
        val xs = tiles.map { it.first.rx }
        val zs = tiles.map { it.first.rz }
        val x0 = xs.minOrNull() ?: return null
        val z0 = zs.minOrNull() ?: return null
        val cols = (xs.maxOrNull()!! - x0 + 1)
        val rows = (zs.maxOrNull()!! - z0 + 1)

        val side = McaTiles.IMG
        val fullW = cols * side
        val fullH = rows * side
        val scale = if (fullW > MAX_SIDE || fullH > MAX_SIDE) {
            (MAX_SIDE.toFloat() / maxOf(fullW, fullH)).coerceAtMost(1f)
        } else 1f
        val w = (fullW * scale).toInt().coerceAtLeast(1)
        val h = (fullH * scale).toInt().coerceAtLeast(1)

        val out = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            Err.ignore(oom, "导出图内存不足")
            return null
        }
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        for ((ref, bmp) in tiles) {
            val dx = (ref.rx - x0) * side * scale
            val dz = (ref.rz - z0) * side * scale
            val dst = android.graphics.RectF(dx, dz, dx + side * scale, dz + side * scale)
            runCatching { canvas.drawBitmap(bmp, null, dst, paint) }
        }
        return savePng(ctx, title, out)
    }

    /** 单个区域直接存，不用拼 */
    fun exportOne(ctx: Context, title: String, bmp: Bitmap): String? =
        savePng(ctx, title, bmp)

    private const val MAX_SIDE = 8192

    /**
     * 写进相册（Pictures 目录）。
     *
     * Android 10 以下没有相对路径这套，退回应用私有目录 ——
     * 那里卸载会被清掉，所以提示语里要说明落在哪儿。
     */
    private fun savePng(ctx: Context, title: String, bmp: Bitmap): String? {
        val name = sanitize(title) + "_" +
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + ".png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ModMigrator")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v
                ) ?: return fallback(ctx, name, bmp)
                ctx.contentResolver.openOutputStream(uri)?.use {
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) return null
                } ?: return null
                v.clear()
                v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, v, null, null)
                "相册/Pictures/ModMigrator/$name"
            } else {
                fallback(ctx, name, bmp)
            }
        } catch (t: Throwable) {
            Err.ignore(t, "导出 PNG 失败")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun fallback(ctx: Context, name: String, bmp: Bitmap): String? {
        return try {
            val dir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "ModMigrator")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f.absolutePath
        } catch (t: Throwable) {
            Err.ignore(t, "导出 PNG 回退也失败")
            null
        }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_\\-一-龥]"), "_").take(40).ifBlank { "chunkmap" }
}
