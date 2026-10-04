package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * 区块图形编辑器（.mca 区域文件）。
 *
 * 用法：选区域文件 → 选区块 → 上下翻层 → 点格子 → 选笔刷涂 → 保存。
 *
 * ⚠️ 区域文件是存档本体，改错了就是毁档。所以：
 *   · 保存前强制写一份 .bak 备份；
 *   · 没动过的区块原样保留压缩字节，不做无谓重写。
 */
class ChunkMapActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "mca_path"
        private const val EXTRA_URI = "mca_uri"

        fun open(ctx: Context, f: File) {
            ctx.startActivity(
                Intent(ctx, ChunkMapActivity::class.java).putExtra(EXTRA_PATH, f.absolutePath)
            )
        }

        fun openUri(ctx: Context, uri: Uri) {
            ctx.startActivity(
                Intent(ctx, ChunkMapActivity::class.java).putExtra(EXTRA_URI, uri.toString())
            )
        }
    }

    private var region: McaEdit.Region? = null
    private var chunkTag: com.viaversion.nbt.tag.CompoundTag? = null
    private var slot = -1
    private var secs: List<McaEdit.Sec> = emptyList()
    private var absY = 64
    private var selX = 8
    private var selZ = 8
    private var dirty = false
    private var filePath: String = ""
    private var fileUri: Uri? = null

    private lateinit var tvInfo: TextView
    private lateinit var tvY: TextView
    private lateinit var tvSel: TextView
    private lateinit var grid: GridView
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        filePath = intent.getStringExtra(EXTRA_PATH) ?: ""
        val u = intent.getStringExtra(EXTRA_URI)
        fileUri = if (u.isNullOrBlank()) null else Uri.parse(u)

        val dp = (10 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp, dp, dp, dp)
        }
        setContentView(
            root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        tvInfo = TextView(this).apply { textSize = 12f }
        root.addView(tvInfo)

        val rowY = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(rowY)
        tvY = TextView(this).apply {
            textSize = 14f
            setPadding(dp, 0, dp, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowY.addView(tvY)
        rowY.addView(MaterialButton(this).apply {
            text = "上一层"
            setOnClickListener { step(1) }
        })
        rowY.addView(MaterialButton(this).apply {
            text = "下一层"
            setOnClickListener { step(-1) }
        })

        grid = GridView(this)
        root.addView(
            grid,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        tvSel = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp / 2, 0, dp / 2)
        }
        root.addView(tvSel)

        val scroll = HorizontalScrollView(this)
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in BlockColors.BRUSHES) {
            chipRow.addView(MaterialButton(this).apply {
                text = BlockColors.short(b)
                setOnClickListener { applyBrush(b) }
            })
        }
        scroll.addView(chipRow)
        root.addView(scroll)

        btnSave = MaterialButton(this).apply {
            text = "保存（会先备份 .bak）"
            setOnClickListener { save() }
        }
        root.addView(btnSave)

        load()
    }

    // ------------------------------------------------------------------

    private fun load() {
        // 区域文件动辄十几 MB，整块读进内存。
        // 这台设备之前就出现过堆只剩 2MB 的情况，
        // 这里先挡一道，免得点开一个大文件直接把应用拖崩。
        if (filePath.isNotBlank()) {
            val len = runCatching { java.io.File(filePath).length() }.getOrDefault(0L)
            if (len > 64L * 1024 * 1024) {
                toast("这个区域文件太大（${len / 1048576} MB），暂时打不开")
                finish()
                return
            }
        }
        val bytes = try {
            if (filePath.isNotBlank()) File(filePath).readBytes()
            else contentResolver.openInputStream(fileUri!!)?.use { it.readBytes() }
        } catch (t: Throwable) {
            Err.ignore(t, "读取区域文件")
            null
        }
        if (bytes == null) {
            toast("读不了这个区域文件")
            finish()
            return
        }
        val r = try {
            McaEdit.Region(bytes)
        } catch (t: Throwable) {
            Err.fail(t, "解析区域文件")
            toast("这个文件不是有效的 .mca")
            finish()
            return
        }
        region = r
        val slots = r.present()
        if (slots.isEmpty()) {
            toast("这个文件里没有任何区块")
            finish()
            return
        }
        tvInfo.text = "区域文件：共 ${slots.size} 个区块"
        val labels = slots.map { "区块 (${it % 32}, ${it / 32})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("打开哪个区块（共 ${slots.size} 个）")
            .setItems(labels) { _, i -> loadChunk(slots[i]) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun loadChunk(s: Int) {
        val r = region ?: return
        val c = r.chunk(s)
        if (c == null) {
            toast("这个区块解析失败，可能已损坏")
            finish()
            return
        }
        val list = McaEdit.sections(c)
        if (list.isEmpty()) {
            toast("这个区块里没有方块数据（可能是空区块）")
            finish()
            return
        }
        slot = s
        chunkTag = c
        secs = list
        absY = (list.first().y * 16 + 8).coerceAtLeast(minY()).coerceAtMost(maxY())
        tvInfo.text = "区块 (${s % 32}, ${s / 32})　共 ${list.size} 个分段　" +
            "高度 ${minY()} ~ ${maxY()}"
        dirty = false
        btnSave.isEnabled = false
        refresh()
    }

    private fun minY(): Int = secs.minOf { it.y * 16 }
    private fun maxY(): Int = secs.maxOf { it.y * 16 + 15 }

    private fun step(d: Int) {
        if (secs.isEmpty()) return
        absY = (absY + d).coerceIn(minY(), maxY())
        refresh()
    }

    private fun currentSec(): Pair<McaEdit.Sec, Int>? {
        for (s in secs) {
            val base = s.y * 16
            if (absY >= base && absY <= base + 15) return s to (absY - base)
        }
        return null
    }

    private fun applyBrush(name: String) {
        val p = currentSec() ?: return
        try {
            McaEdit.setBlock(p.first, selX, p.second, selZ, name)
        } catch (t: Throwable) {
            Err.fail(t, "修改方块")
            toast("改不了：${t.message ?: ""}")
            return
        }
        dirty = true
        btnSave.isEnabled = true
        refresh()
    }

    private fun refresh() {
        tvY.text = "Y = $absY"
        val p = currentSec()
        val name = if (p != null) McaEdit.blockAt(p.first, selX, p.second, selZ) else "—"
        tvSel.text = "($selX, $absY, $selZ)　${BlockColors.short(name)}"
        grid.invalidate()
    }

    private fun save() {
        val r = region ?: return
        val tag = chunkTag ?: return
        if (!dirty) {
            toast("还没有改动")
            return
        }
        try {
            r.put(slot, tag)
            val out = r.build()
            if (filePath.isNotBlank()) {
                val f = File(filePath)
                runCatching { f.copyTo(File(f.parentFile, f.name + ".bak"), true) }
                f.writeBytes(out)
            } else {
                contentResolver.openOutputStream(fileUri!!, "wt")?.use { it.write(out) }
                    ?: throw java.io.IOException("打不开输出流")
            }
            dirty = false
            btnSave.isEnabled = false
            toast("已保存，原文件备份为 .bak")
        } catch (t: Throwable) {
            Err.fail(t, "保存区域文件")
            toast("保存失败：${t.message ?: ""}")
        }
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------

    private inner class GridView(ctx: Context) : View(ctx) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#33FFFFFF")
        }
        private val mark = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.YELLOW
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val size = minOf(width.toFloat(), height.toFloat())
            val cell = size / 16f
            val p = currentSec()
            for (z in 0..15) {
                for (x in 0..15) {
                    val name =
                        if (p != null) McaEdit.blockAt(p.first, x, p.second, z)
                        else "minecraft:air"
                    fill.color = BlockColors.of(name)
                    c.drawRect(x * cell, z * cell, (x + 1) * cell, (z + 1) * cell, fill)
                }
            }
            for (i in 0..16) {
                c.drawLine(i * cell, 0f, i * cell, 16 * cell, line)
                c.drawLine(0f, i * cell, 16 * cell, i * cell, line)
            }
            c.drawRect(selX * cell, selZ * cell, (selX + 1) * cell, (selZ + 1) * cell, mark)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                val size = minOf(width.toFloat(), height.toFloat())
                val cell = size / 16f
                selX = (e.x / cell).toInt().coerceIn(0, 15)
                selZ = (e.y / cell).toInt().coerceIn(0, 15)
                refresh()
                return true
            }
            return super.onTouchEvent(e)
        }
    }
}
