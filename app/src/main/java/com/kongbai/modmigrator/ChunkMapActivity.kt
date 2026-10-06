package com.kongbai.modmigrator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 世界区块地图。
 *
 * 交互参照 MCA Selector：打开世界 → 整个世界摊在地图上 → 放大到能看见
 * 区块网格时逐格选 → 批量删。不是"选一个 .mca 文件再一个个点"，
 * 那样用户根本不知道自己点的是世界的哪个位置。
 *
 * 三级坐标（方块 / 区块 / 区域）始终显示在状态栏，
 * 这是判断"我到底在看哪儿"的唯一依据。
 *
 * ⚠️ 删除区块是改存档本体，不可逆：
 *   · 删除前给原文件留 .bak；
 *   · 二次确认，并且写清楚"游戏会重新生成地形"。
 */
class ChunkMapActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_WORLD = "world_dir"
        private const val EXTRA_DIM = "dim_key"
        private const val EXTRA_FILE = "mca_file"
        private const val EXTRA_URI = "mca_uri"

        /** 从世界进入：给世界目录，维度可选（不传就让用户挑） */
        fun open(ctx: Context, worldDir: File, dimKey: String? = null) {
            ctx.startActivity(
                Intent(ctx, ChunkMapActivity::class.java)
                    .putExtra(EXTRA_WORLD, worldDir.absolutePath)
                    .putExtra(EXTRA_DIM, dimKey)
            )
        }

        /** 兼容入口：直接给一个 .mca 文件 */
        fun openFile(ctx: Context, f: File) {
            ctx.startActivity(
                Intent(ctx, ChunkMapActivity::class.java)
                    .putExtra(EXTRA_FILE, f.absolutePath)
            )
        }

        /**
         * 兼容入口：SAF 选中的单个 .mca（content://）。
         *
         * 这类 Uri 拿不到真实路径，先在 Activity 里落到自己的缓存目录，
         * 之后按普通文件处理。保存时再写回原处。
         */
        fun openUri(ctx: Context, uri: Uri) {
            ctx.startActivity(
                Intent(ctx, ChunkMapActivity::class.java)
                    .putExtra(EXTRA_URI, uri.toString())
            )
        }

        /** 一个区域 32×32 个区块，一个区块 16×16 格 */
        private const val CHUNKS_PER_REGION = 32
        private const val BLOCKS_PER_CHUNK = 16
        private const val BLOCKS_PER_REGION = CHUNKS_PER_REGION * BLOCKS_PER_CHUNK

        /** 一个区块在屏幕上大于这个像素数时，切到"逐区块选择" */
        private const val CHUNK_MODE_PX = 10f
    }

    // ------------------------------------------------------------------ 状态

    private var dims: List<McaWorld.Dim> = emptyList()
    private var dim: McaWorld.Dim? = null
    private var refs: List<McaWorld.Ref> = emptyList()

    /** 区域坐标 → 缩略图。只放已经生成好的，没生成的画占位 */
    private val tiles = HashMap<Long, Bitmap>()

    private var selMode = false          // false=浏览（拖平移） true=选择（拖框选）
    private val selReg = HashSet<Long>()
    private val selChunk = HashSet<Long>()

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var map: MapView
    private lateinit var btnMode: MaterialButton
    private lateinit var btnDim: MaterialButton

    private val loader = Executors.newSingleThreadExecutor()
    private val loading = AtomicBoolean(false)
    private var lastTapBlock: Pair<Int, Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "区块地图"

        val worldPath = intent.getStringExtra(EXTRA_WORLD)
        val dimKey = intent.getStringExtra(EXTRA_DIM)
        val file = intent.getStringExtra(EXTRA_FILE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        setContentView(
            root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 顶部：维度 + 模式
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(bar)
        btnDim = MaterialButton(this).apply {
            text = "维度"
            setOnClickListener { pickDim() }
        }
        bar.addView(btnDim)
        btnMode = MaterialButton(this).apply {
            text = "选择"
            setOnClickListener { toggleMode() }
        }
        bar.addView(btnMode)

        tvHint = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            text = "双指缩放，单指拖动"
        }
        root.addView(tvHint)

        map = MapView(this)
        root.addView(
            FrameLayout(this).apply { addView(map) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        tvStatus = TextView(this).apply {
            textSize = 11f
            text = "载入中…"
        }
        root.addView(tvStatus)

        // 底部操作
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(row)
        row.addView(MaterialButton(this).apply {
            text = "全选可见"
            setOnClickListener { selectVisible() }
        })
        row.addView(MaterialButton(this).apply {
            text = "反选"
            setOnClickListener { invertSelection() }
        })
        row.addView(MaterialButton(this).apply {
            text = "清空"
            setOnClickListener { clearSelection() }
        })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row2)
        row2.addView(MaterialButton(this).apply {
            text = "删除选中"
            setOnClickListener { askDelete() }
        })
        row2.addView(MaterialButton(this).apply {
            text = "回到原点"
            setOnClickListener { map.resetView(); map.invalidate() }
        })

        // 载入
        val uriStr = intent.getStringExtra(EXTRA_URI)
        when {
            !worldPath.isNullOrBlank() -> {
                val w = File(worldPath)
                dims = McaWorld.discover(w)
                if (dims.isEmpty()) {
                    tvStatus.text = "这个世界里没找到任何 region 目录"
                    return
                }
                val pick = dims.firstOrNull { it.key == dimKey } ?: dims.first()
                useDim(pick)
            }
            !uriStr.isNullOrBlank() -> {
                // SAF 的 Uri 拿不到真实路径，先落到缓存再按文件处理
                tvStatus.text = "正在读取…"
                loader.execute {
                    val f = cacheSingle(Uri.parse(uriStr))
                    runOnUiThread {
                        if (f == null) tvStatus.text = "读不了这个区域文件"
                        else bootSingle(f)
                    }
                }
            }
            !file.isNullOrBlank() -> bootSingle(File(file))
            else -> tvStatus.text = "没有指定世界"
        }
    }

    /** SAF 单文件 → 缓存目录里的普通文件 */
    private fun cacheSingle(uri: Uri): File? {
        // 写成块体而不是 = try {…}：表达式体里不允许裸 return
        return try {
            val dir = File(filesDir, "mca-single").apply { mkdirs() }
            val f = File(dir, "r.0.0.mca")
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { src -> f.outputStream().use { src.copyTo(it) } }
            f
        } catch (t: Throwable) {
            Err.fail(t, "缓存区域文件")
            null
        }
    }

    private fun bootSingle(f: File) {
        val d = McaWorld.fromSingleFile(f)
        if (d == null) {
            tvStatus.text = "这个文件不是有效的区域文件"
            return
        }
        dims = listOf(d)
        btnDim.visibility = View.GONE
        useDim(d)
    }

    private fun useDim(d: McaWorld.Dim) {
        dim = d
        refs = d.regions()
        tiles.clear()
        presentCache.clear()
        selReg.clear()
        selChunk.clear()
        btnDim.text = d.label
        if (refs.isEmpty()) {
            tvStatus.text = "「${d.label}」里没有区域文件"
            return
        }
        map.resetView()
        map.invalidate()
        updateStatus()
        kickLoad()
    }

    private fun pickDim() {
        if (dims.size <= 1) return
        val names = dims.map {
            "${it.label}（${it.count()} 个区域）"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("切换维度")
            .setItems(names) { _, i -> useDim(dims[i]) }
            .show()
    }

    private fun toggleMode() {
        selMode = !selMode
        btnMode.text = if (selMode) "浏览" else "选择"
        tvHint.text = if (selMode) {
            if (map.chunkMode()) "拖动框选区块"
            else "拖动框选区域（放大可逐区块选）"
        } else "双指缩放，单指拖动"
    }

    // ------------------------------------------------------------------ 加载

    /**
     * 按需补图。可见区域优先，离屏幕中心近的排前面。
     *
     * 不一次性把整个维度全渲染 —— 一个区域要解压上千个区块，
     * 几十个区域排队时用户只会看到界面卡死。
     */
    private fun kickLoad() {
        if (loading.get()) return
        val want = map.visibleRefs()
        if (want.isEmpty()) return
        loading.set(true)
        loader.execute {
            try {
                val cx = map.centerBlockX()
                val cz = map.centerBlockZ()
                val order = want.sortedBy {
                    val bx = it.baseBlockX + 256
                    val bz = it.baseBlockZ + 256
                    (bx - cx) * (bx - cx) + (bz - cz) * (bz - cz)
                }
                for (ref in order) {
                    val key = McaTiles.packRegion(ref.rx, ref.rz)
                    if (tiles.containsKey(key)) continue
                    if (Thread.currentThread().isInterrupted) break
                    val b = try {
                        McaTiles.get(this, ref) { msg ->
                            runOnUiThread { tvStatus.text = msg }
                        }
                    } catch (t: Throwable) {
                        Err.fail(t, "生成区域缩略图")
                        null
                    }
                    if (b != null) {
                        runOnUiThread {
                            tiles[key] = b
                            map.invalidate()
                            updateStatus()
                        }
                    }
                }
            } finally {
                loading.set(false)
                runOnUiThread { updateStatus() }
            }
        }
    }

    private fun updateStatus() {
        val d = dim ?: return
        val t = lastTapBlock
        val sb = StringBuilder()
        if (t != null) {
            val bx = t.first
            val bz = t.second
            val cx = Math.floorDiv(bx, BLOCKS_PER_CHUNK)
            val cz = Math.floorDiv(bz, BLOCKS_PER_CHUNK)
            val rx = Math.floorDiv(cx, CHUNKS_PER_REGION)
            val rz = Math.floorDiv(cz, CHUNKS_PER_REGION)
            sb.append("方块 $bx, $bz　区块 $cx, $cz　区域 $rx, $rz\n")
        }
        val n = if (map.chunkMode()) selChunk.size else selReg.size
        val unit = if (map.chunkMode()) "区块" else "区域"
        sb.append("已选 $n $unit　共 ${refs.size} 个区域" +
            if (map.chunkMode()) "（逐区块）" else "（整区域，放大可细分）")
        tvStatus.text = sb.toString()
    }

    // ------------------------------------------------------------------ 选择

    private fun selectVisible() {
        val v = map.visibleRefs()
        if (map.chunkMode()) {
            for (r in v) {
                // 只选这个区域里真实存在的区块，不存在的选了也没用
                for (slot in presentSlots(r)) {
                    val cx = slot and 31
                    val cz = (slot shr 5) and 31
                    selChunk.add(McaTiles.packChunk(r.rx, r.rz, cx, cz))
                }
            }
        } else {
            for (r in v) selReg.add(McaTiles.packRegion(r.rx, r.rz))
        }
        map.invalidate()
        updateStatus()
    }

    private fun invertSelection() {
        if (map.chunkMode()) {
            val v = map.visibleRefs()
            val next = HashSet<Long>()
            for (r in v) {
                for (slot in presentSlots(r)) {
                    val cx = slot and 31
                    val cz = (slot shr 5) and 31
                    val k = McaTiles.packChunk(r.rx, r.rz, cx, cz)
                    if (!selChunk.contains(k)) next.add(k)
                }
            }
            selChunk.clear()
            selChunk.addAll(next)
        } else {
            val v = map.visibleRefs()
            val next = HashSet<Long>()
            for (r in v) {
                val k = McaTiles.packRegion(r.rx, r.rz)
                if (!selReg.contains(k)) next.add(k)
            }
            selReg.clear()
            selReg.addAll(next)
        }
        map.invalidate()
        updateStatus()
    }

    private fun clearSelection() {
        selReg.clear()
        selChunk.clear()
        map.invalidate()
        updateStatus()
    }

    private fun toggleAt(rx: Int, rz: Int, cx: Int, cz: Int) {
        if (map.chunkMode()) {
            val k = McaTiles.packChunk(rx, rz, cx, cz)
            if (!selChunk.remove(k)) selChunk.add(k)
        } else {
            val k = McaTiles.packRegion(rx, rz)
            if (!selReg.remove(k)) selReg.add(k)
        }
    }

    /**
     * 屏幕矩形 → 世界坐标 → 命中的格子全选上。
     *
     * 只遍历与框相交的区域，不是整个世界 ——
     * 大世界有上百个区域，全扫一遍要几十秒。
     */
    private fun boxSelect(sx0: Int, sy0: Int, sx1: Int, sy1: Int) {
        val p0 = map.screenToBlock(sx0.toFloat(), sy0.toFloat())
        val p1 = map.screenToBlock(sx1.toFloat(), sy1.toFloat())
        val x0 = minOf(p0.first, p1.first)
        val x1 = maxOf(p0.first, p1.first)
        val z0 = minOf(p0.second, p1.second)
        val z1 = maxOf(p0.second, p1.second)
        val chunkMode = map.chunkMode()

        for (r in refs) {
            if (r.baseBlockX > x1 || r.baseBlockX + BLOCKS_PER_REGION < x0) continue
            if (r.baseBlockZ > z1 || r.baseBlockZ + BLOCKS_PER_REGION < z0) continue
            if (!chunkMode) {
                selReg.add(McaTiles.packRegion(r.rx, r.rz))
                continue
            }
            for (slot in presentSlots(r)) {
                val cx = slot and 31
                val cz = (slot shr 5) and 31
                val bx = r.baseBlockX + cx * BLOCKS_PER_CHUNK
                val bz = r.baseBlockZ + cz * BLOCKS_PER_CHUNK
                if (bx + BLOCKS_PER_CHUNK < x0 || bx > x1) continue
                if (bz + BLOCKS_PER_CHUNK < z0 || bz > z1) continue
                selChunk.add(McaTiles.packChunk(r.rx, r.rz, cx, cz))
            }
        }
    }

    /**
     * 某区域里真实存在的槽位。
     *
     * 读一次就记住 —— 框选、全选、反选都要反复用，
     * 每次重读一遍文件在同一个操作里能卡好几秒。
     */
    private val presentCache = HashMap<Long, IntArray>()

    private fun presentSlots(r: McaWorld.Ref): IntArray {
        val k = McaTiles.packRegion(r.rx, r.rz)
        presentCache[k]?.let { return it }
        val arr = try {
            McaEdit.Region(r.file.readBytes()).present().toIntArray()
        } catch (_: Throwable) {
            IntArray(0)
        }
        presentCache[k] = arr
        return arr
    }

    // ------------------------------------------------------------------ 删除

    private fun askDelete() {
        val n = if (map.chunkMode()) selChunk.size else selReg.size
        if (n == 0) { toast("还没选东西"); return }
        val unit = if (map.chunkMode()) "个区块" else "个区域"
        MaterialAlertDialogBuilder(this)
            .setTitle("删除 $n $unit？")
            .setMessage(
                "这些数据会被整个抹掉。\n\n" +
                "再次进入该区域时，游戏会按当前版本重新生成地形 —— " +
                "在这里建过的东西不会回来。\n\n" +
                "删除前会给每个区域文件留一份 .bak。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> doDelete() }
            .show()
    }

    private fun doDelete() {
        val chunkMode = map.chunkMode()
        val targets = LinkedHashMap<Long, ArrayList<Int>>()
        if (chunkMode) {
            for (k in selChunk) {
                val (rx, rz) = McaTiles.chunkRegionOf(k)
                val (cx, cz) = McaTiles.unpackChunk(k)
                val slot = cz * 32 + cx
                targets.getOrPut(McaTiles.packRegion(rx, rz)) { ArrayList() }.add(slot)
            }
        } else {
            for (k in selReg) {
                targets[k] = ArrayList()
            }
        }
        if (targets.isEmpty()) return

        val byRef = HashMap<Long, McaWorld.Ref>()
        for (r in refs) byRef[McaTiles.packRegion(r.rx, r.rz)] = r

        val prog = MaterialAlertDialogBuilder(this)
            .setTitle("正在删除…")
            .setMessage("0 / ${targets.size}")
            .setCancelable(false)
            .show()
        val tv = prog.findViewById<TextView>(android.R.id.message)

        loader.execute {
            var done = 0
            var removed = 0
            var failed = 0
            for ((rk, slots) in targets) {
                val ref = byRef[rk] ?: continue
                try {
                    val f = ref.file
                    val raw = f.readBytes()
                    val bak = File("${f.absolutePath}.bak")
                    if (!bak.exists()) runCatching { bak.writeBytes(raw) }
                    val reg = McaEdit.Region(raw)
                    if (slots.isEmpty()) {
                        // 整个区域删掉：所有槽位清空
                        for (s in reg.present()) if (reg.remove(s)) removed++
                    } else {
                        for (s in slots) if (reg.remove(s)) removed++
                    }
                    f.writeBytes(reg.build())
                    McaTiles.invalidate(this, ref)
                    done++
                } catch (t: Throwable) {
                    Err.fail(t, "删除区块")
                    failed++
                }
                runOnUiThread {
                    tv?.text = "${done + failed} / ${targets.size}"
                }
            }
            runOnUiThread {
                prog.dismiss()
                tiles.clear()
                presentCache.clear()
                selReg.clear()
                selChunk.clear()
                map.invalidate()
                updateStatus()
                toast("已删除 $removed 个区块" +
                    (if (failed > 0) "，失败 $failed 个区域" else ""))
                kickLoad()
            }
        }
    }

    private fun toast(m: String) =
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
        tiles.clear()
    }

    // ------------------------------------------------------------------ 地图

    /** 框选的世界坐标范围 */
    private data class Box(val x0: Int, val z0: Int, val x1: Int, val z1: Int)

    @SuppressLint("ViewConstructor")
    private inner class MapView(ctx: Context) : View(ctx) {

        /** 每方块占多少屏幕像素 */
        private var scale = 0.02f
        /** 视野中心的方块坐标 */
        private var camBX = 0.0
        private var camBZ = 0.0

        private val grid = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.argb(70, 255, 255, 255)
        }
        private val chunkGrid = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.argb(45, 255, 255, 255)
        }
        private val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(220, 66, 165, 245)
        }
        private val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(40, 66, 165, 245)
        }
        private val placeholder = Paint().apply {
            style = Paint.Style.FILL
            color = 0xFF1A1A1A.toInt()
        }
        private val textPaint = Paint().apply {
            color = Color.argb(150, 255, 255, 255)
            textSize = 22f
        }

        private val sc = ScaleGestureDetector(ctx, object :
            ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val f = d.scaleFactor
                val ns = (scale * f).coerceIn(0.002f, 4f)
                // 以手势中心为锚点缩放，否则一缩放画面就飘走
                val fx = d.focusX.toDouble()
                val fy = d.focusY.toDouble()
                val halfW = width.toDouble() / 2.0
                val halfH = height.toDouble() / 2.0
                val bxBefore = camBX + (fx - halfW) / scale.toDouble()
                val bzBefore = camBZ + (fy - halfH) / scale.toDouble()
                scale = ns
                camBX = bxBefore - (fx - halfW) / scale.toDouble()
                camBZ = bzBefore - (fy - halfH) / scale.toDouble()
                invalidate()
                return true
            }
        })

        private val ge = GestureDetector(ctx, object :
            GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val b = screenToBlock(e.x, e.y)
                val cx = Math.floorDiv(b.first, BLOCKS_PER_CHUNK)
                val cz = Math.floorDiv(b.second, BLOCKS_PER_CHUNK)
                val rx = Math.floorDiv(cx, CHUNKS_PER_REGION)
                val rz = Math.floorDiv(cz, CHUNKS_PER_REGION)
                if (!hasRegion(rx, rz)) {
                    toast("这里没有区块（还没生成过）")
                    return true
                }
                if (selMode) toggleAt(rx, rz, cx - rx * 32, cz - rz * 32)
                lastTapBlock = b
                invalidate()
                updateStatus()
                return true
            }
        })

        /** 拖动起点（方块坐标），选择模式用来画框 */
        private var dragFrom: Pair<Float, Float>? = null
        private var dragTo: Pair<Float, Float>? = null
        private var panFrom: Pair<Double, Double>? = null

        fun chunkMode(): Boolean =
            scale * BLOCKS_PER_CHUNK >= CHUNK_MODE_PX

        fun centerBlockX(): Int = camBX.toInt()
        fun centerBlockZ(): Int = camBZ.toInt()

        fun resetView() {
            if (refs.isEmpty()) return
            val xs = refs.map { it.baseBlockX }
            val zs = refs.map { it.baseBlockZ }
            val x0 = xs.minOrNull() ?: 0
            val x1 = (xs.maxOrNull() ?: 0) + BLOCKS_PER_REGION
            val z0 = zs.minOrNull() ?: 0
            val z1 = (zs.maxOrNull() ?: 0) + BLOCKS_PER_REGION
            camBX = (x0 + x1) / 2.0
            camBZ = (z0 + z1) / 2.0
            val w = width.toFloat().takeIf { it > 0 } ?: 800f
            val h = height.toFloat().takeIf { it > 0 } ?: 800f
            val fitW = if (x1 > x0) w / (x1 - x0).toFloat() else 1f
            val fitH = if (z1 > z0) h / (z1 - z0).toFloat() else 1f
            // 先按内容算铺满，再留 10% 边距，最后才夹到上下限
            scale = (minOf(fitW, fitH) * 0.9f).coerceIn(0.002f, 4f)
        }

        /**
         * 坐标换算全程用 Double。
         *
         * Kotlin 不做隐式数值提升，Double 和 Float 混着算直接编译不过 ——
         * 所以这里统一转成 Double 算完，最后一步才转回 Float 交给 Canvas。
         */
        fun screenToBlock(sx: Float, sy: Float): Pair<Int, Int> {
            val s = scale.toDouble()
            val bx = camBX + (sx.toDouble() - width.toDouble() / 2.0) / s
            val bz = camBZ + (sy.toDouble() - height.toDouble() / 2.0) / s
            return Math.floor(bx).toInt() to Math.floor(bz).toInt()
        }

        private fun blockToScreen(bx: Double, bz: Double): Pair<Float, Float> {
            val s = scale.toDouble()
            return ((bx - camBX) * s + width.toDouble() / 2.0).toFloat() to
                ((bz - camBZ) * s + height.toDouble() / 2.0).toFloat()
        }

        /** 当前可见区域内的所有区域文件 */
        fun visibleRefs(): List<McaWorld.Ref> {
            if (refs.isEmpty()) return emptyList()
            val s = scale.toDouble()
            val halfW = width.toDouble() / 2.0 / s
            val halfH = height.toDouble() / 2.0 / s
            val x0 = Math.floor(camBX - halfW).toInt()
            val z0 = Math.floor(camBZ - halfH).toInt()
            val x1 = Math.ceil(camBX + halfW).toInt()
            val z1 = Math.ceil(camBZ + halfH).toInt()
            return refs.filter {
                it.baseBlockX + BLOCKS_PER_REGION >= x0 && it.baseBlockX <= x1 &&
                    it.baseBlockZ + BLOCKS_PER_REGION >= z0 && it.baseBlockZ <= z1
            }
        }

        /** 给定屏幕矩形，换算成世界坐标框，并列出覆盖到的区域 */
        fun visibleRange(sx0: Int, sz0: Int, sx1: Int, sz1: Int): Box {
            val p0 = screenToBlock(sx0.toFloat(), sz0.toFloat())
            val p1 = screenToBlock(sx1.toFloat(), sz1.toFloat())
            return Box(
                minOf(p0.first, p1.first), minOf(p0.second, p1.second),
                maxOf(p0.first, p1.first), maxOf(p0.second, p1.second)
            )
        }

        private fun hasRegion(rx: Int, rz: Int): Boolean =
            refs.any { it.rx == rx && it.rz == rz }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val list = visibleRefs()
            for (r in list) {
                val (sx, sy) = blockToScreen(
                    r.baseBlockX.toDouble(), r.baseBlockZ.toDouble()
                )
                val size = BLOCKS_PER_REGION * scale
                if (sx + size < 0 || sy + size < 0 || sx > width || sy > height) continue

                val bmp = tiles[McaTiles.packRegion(r.rx, r.rz)]
                if (bmp != null) {
                    c.drawBitmap(bmp, null,
                        android.graphics.RectF(sx, sy, sx + size, sy + size), null)
                } else {
                    c.drawRect(sx, sy, sx + size, sy + size, placeholder)
                    c.drawText("${r.rx},${r.rz}", sx + 4, sy + 20, textPaint)
                }

                // 网格：区域边界始终画；放大后加画区块网格
                c.drawRect(sx, sy, sx + size, sy + size, grid)
                if (chunkMode()) {
                    val cell = size / CHUNKS_PER_REGION
                    for (i in 1 until CHUNKS_PER_REGION) {
                        c.drawLine(sx + i * cell, sy, sx + i * cell, sy + size, chunkGrid)
                        c.drawLine(sx, sy + i * cell, sx + size, sy + i * cell, chunkGrid)
                    }
                }
                McaTiles.drawSelection(
                    c, sx, sy, size, r.rx, r.rz, chunkMode(), selReg, selChunk
                )
            }

            // 选择模式的框
            val f = dragFrom
            val t = dragTo
            if (selMode && f != null && t != null) {
                val x0 = minOf(f.first, t.first)
                val y0 = minOf(f.second, t.second)
                val x1 = maxOf(f.first, t.first)
                val y1 = maxOf(f.second, t.second)
                c.drawRect(x0, y0, x1, y1, fillPaint)
                c.drawRect(x0, y0, x1, y1, boxPaint)
            }
            kickLoad()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            sc.onTouchEvent(ev)
            ge.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (selMode) {
                        dragFrom = ev.x to ev.y
                        dragTo = ev.x to ev.y
                    } else {
                        panFrom = ev.x.toDouble() to ev.y.toDouble()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (selMode) {
                        dragTo = ev.x to ev.y
                    } else {
                        val p = panFrom
                        if (p != null && ev.pointerCount == 1) {
                            val s = scale.toDouble()
                            camBX -= (ev.x.toDouble() - p.first) / s
                            camBZ -= (ev.y.toDouble() - p.second) / s
                            panFrom = ev.x.toDouble() to ev.y.toDouble()
                        }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (selMode) {
                        val a = dragFrom
                        val b = dragTo
                        if (a != null && b != null) {
                            val dx = kotlin.math.abs(a.first - b.first)
                            val dy = kotlin.math.abs(a.second - b.second)
                            if (dx > 4 || dy > 4) {
                                boxSelect(
                                    a.first.toInt(), a.second.toInt(),
                                    b.first.toInt(), b.second.toInt()
                                )
                            }
                        }
                        dragFrom = null
                        dragTo = null
                        invalidate()
                        updateStatus()
                    } else {
                        panFrom = null
                    }
                }
            }
            return true
        }
    }
}
