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
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 世界区块地图。
 *
 * 交互参照 MCA Selector / Blocktopograph / Nodecraft 这类成熟工具，
 * 而不是"选一个 .mca 文件再一个个点"：
 *
 *   · 打开的是整个世界的一个维度，地形直接摊在地图上，能缩放能拖；
 *   · 点一下选中一个格子，拖一下平移，切到框选就是拉一个矩形；
 *   · 可以按「玩家停留时长」筛选 —— 这才是"哪些区块可以放心删掉
 *     让新版本重新生成"的判断依据，光看地形看不出来；
 *   · 删除是先暂存、再显式保存，保存前随时能撤销；
 *   · 三级坐标（方块 / 区块 / 区域）常驻，随时知道自己在看哪儿。
 *
 * 加载参照 MCA Selector 的磁盘缓存：每个区域渲染成一张 PNG 存下来，
 * 第二次打开直接读图。同时按需加载 —— 只看得见的那几个先画，
 * 用户一平移就重新排队，不让他盯着一片黑等整个世界渲染完。
 *
 * ⚠️ 删除区块是改存档本体，不可逆：
 *   · 保存时给原文件留 .bak；
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

        /**
         * 缩放上下界。
         *
         * ⚠️ 之前上界写 4f —— 一格方块能放到 4 像素，
         * 于是整张 512×512 的位图被拉伸到几千像素，
         * 绘制开销和内存都失控（表现为平移时随机变黑、卡顿）。
         *
         * 上界取「进区块模式时用的那个值」：
         * `CHUNK_MODE_PX * 1.6 / BLOCKS_PER_CHUNK` = 1.0，
         * 也就是最大只能到区块级（一格方块约 1px）。
         * 方块级操作不走缩放，改用「按范围删」填坐标 ——
         * 手机上靠手指放到方块级既看不清也选不准。
         */
        private const val MIN_SCALE = 0.002f
        private const val MAX_SCALE = 1.0f

        /**
         * 同时最多几个线程在渲染区域。
         *
         * 按 CPU 核数算，夹在 [2,4]：单核/双核开多了确实互相抢，
         * 但这台设备是 8 核，固定 2 会让整张地图铺满要等很久，
         * 期间大片区域一直是空的。
         */
        private val RENDER_THREADS =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        /** 20 tick = 1 秒 */
        private const val TPS = 20L
    }

    // ------------------------------------------------------------------ 状态

    private var worldDir: File? = null
    /** SAF 打开时的原始 Uri。保存完要把缓存副本写回这里，否则改动全丢 */
    private var srcUri: Uri? = null
    private var dims: List<McaWorld.Dim> = emptyList()
    private var dim: McaWorld.Dim? = null
    private var refs: List<McaWorld.Ref> = emptyList()

    private var selMode = false          // false=浏览（拖平移） true=框选（拖拉框）
    private val selReg = HashSet<Long>()
    private val selChunk = HashSet<Long>()

    /** 待保存的删除。还没动磁盘，随时能撤销 */
    private val stagedReg = HashSet<Long>()
    private val stagedChunk = HashSet<Long>()

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var map: MapView

    /** 选源 .mca（替换区块用） */
    private lateinit var pickSrc: androidx.activity.result.ActivityResultLauncher<Array<String>>
    private lateinit var btnMode: MaterialButton
    private lateinit var btnDim: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnY: MaterialButton

    /**
     * 渲染线程池。
     *
     * ⚠️ 之前是 val 且 onDestroy 里 shutdownNow，但 pump() 靠 runOnUiThread
     * 循环投递，关闭后仍有任务在排队回调 —— 再 execute 就抛
     * RejectedExecutionException（Terminated / Shutting down），直接崩。
     * 现在：alive 标记 + 提交前自愈（已关闭就重建），并且所有回调先过 alive。
     */
    private var pool = Executors.newFixedThreadPool(RENDER_THREADS)
    @Volatile private var alive = true

    private fun submitPool(task: Runnable) {
        if (!alive) return
        try {
            pool.execute(task)
        } catch (t: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) {
                if (!alive) return
                pool = Executors.newFixedThreadPool(RENDER_THREADS)
                runCatching { pool.execute(task) }
            }
        }
    }
    private val busy = AtomicInteger(0)

    /** 待渲染队列 + 正在渲染的集合。平移时整队重排，近的先画 */
    private val queue = ArrayList<McaWorld.Ref>()
    private val inflightKeys = HashSet<Long>()
    private var lastKick = 0L

    /** 渲染进度：已完成 / 本次视野内总数 */
    private var loadDone = 0
    private var loadTotal = 0

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

        // 第一行：维度 / 模式 / 跳转
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(bar)
        // registerForActivityResult 必须在 CREATED 之前注册，
        // 放到点击时才注册会直接抛异常 —— 所以在这里先备好。
        ensurePickers()
        btnDim = MaterialButton(this).apply {
            text = "维度"
            setOnClickListener { pickDim() }
        }
        bar.addView(btnDim)
        btnMode = MaterialButton(this).apply {
            text = "框选"
            setOnClickListener { toggleMode() }
        }
        bar.addView(btnMode)
        bar.addView(MaterialButton(this).apply {
            text = "跳转"
            setOnClickListener { askGoto() }
        })
        btnY = MaterialButton(this).apply {
            text = "高度"
            setOnClickListener { askYRange() }
        }
        bar.addView(btnY)

        tvHint = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            text = "拖动平移，点一下选中，双指缩放"
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

        // 选择
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row)
        row.addView(MaterialButton(this).apply {
            text = "按条件选"
            setOnClickListener { askFilter() }
        })
        row.addView(MaterialButton(this).apply {
            text = "全选可见"
            setOnClickListener { selectVisible() }
        })
        row.addView(MaterialButton(this).apply {
            text = "反选"
            setOnClickListener { invertVisible() }
        })
        row.addView(MaterialButton(this).apply {
            text = "清空"
            setOnClickListener { clearSelection() }
        })
        row.addView(MaterialButton(this).apply {
            text = "找结构"
            setOnClickListener { askFindStructure() }
        })

        // 操作
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row2)
        row2.addView(MaterialButton(this).apply {
            text = "标记删除"
            setOnClickListener { stageSelection() }
        })
        row2.addView(MaterialButton(this).apply {
            text = "按范围删"
            setOnClickListener { askRangeDelete() }
        })
        row2.addView(MaterialButton(this).apply {
            text = "导出选中"
            setOnClickListener { askExport() }
        })
        row2.addView(MaterialButton(this).apply {
            text = "替换区块"
            setOnClickListener { askReplace() }
        })
        row2.addView(MaterialButton(this).apply {
            text = "导出图"
            setOnClickListener { askExportPng() }
        })

        // 保存 / 撤销 / 复位
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row3)
        btnSave = MaterialButton(this).apply {
            text = "保存"
            setOnClickListener { askSave() }
        }
        row3.addView(btnSave)
        row3.addView(MaterialButton(this).apply {
            text = "撤销标记"
            setOnClickListener { clearStaged() }
        })
        row3.addView(MaterialButton(this).apply {
            text = "回原点"
            setOnClickListener { map.resetView(); map.invalidate() }
        })
        row3.addView(MaterialButton(this).apply {
            text = "地图缓存"
            setOnClickListener { askTileCache() }
        })

        // 载入
        val uriStr = intent.getStringExtra(EXTRA_URI)
        when {
            !worldPath.isNullOrBlank() -> {
                val w = File(worldPath)
                worldDir = w
                dims = McaWorld.discover(w)
                if (dims.isEmpty()) {
                    tvStatus.text = "这个世界里没找到任何 region 目录"
                    return
                }
                val pick = dims.firstOrNull { it.key == dimKey } ?: dims.first()
                useDim(pick)
            }
            !uriStr.isNullOrBlank() -> {
                tvStatus.text = "正在读取…"
                submitPool {
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

    /**
     * SAF 单文件 → 缓存目录里的普通文件。
     *
     * ⚠️ 之前文件名写死成 `r.0.0.mca`，于是无论你选的是哪个区域文件，
     * 坐标一律按 (0,0) 算 —— 显示错、导出错、删除也可能删到别的格子。
     * 现在取 Uri 里的真实显示名，取不到才回退。
     */
    private fun ensurePickers() {
        if (::pickSrc.isInitialized) return
        pickSrc = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) runReplace(uri)
        }
    }

    private fun cacheSingle(uri: Uri): File? {
        // 写成块体而不是 = try {…}：表达式体里不允许裸 return
        return try {
            val dir = File(filesDir, "mca-single").apply { mkdirs() }
            val name = displayNameOf(uri)?.takeIf {
        it.endsWith(".mca") || it.endsWith(".mcr")
    } ?: "r.0.0.mca"
            // 同名会互相覆盖，先清掉旧的
            dir.listFiles()?.forEach { if (it.name != name) it.delete() }
            val f = File(dir, name)
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { src -> f.outputStream().use { src.copyTo(it) } }
            srcUri = uri
            f
        } catch (t: Throwable) {
            Err.fail(t, "缓存区域文件")
            null
        }
    }

    /** 从 SAF Uri 取真实文件名，取不到返回 null */
    private fun displayNameOf(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 把缓存副本写回原 Uri。
     *
     * ⚠️ 之前缺失这一步：保存只写到自己的缓存目录，
     * 于是「已删除 N 个区块」提示照弹，存档本体纹丝不动 ——
     * 这正是「删除功能有问题」的来源。
     */
    private fun writeBack(f: File, uri: Uri): Boolean {
        return try {
            val out = contentResolver.openOutputStream(uri, "wt") ?: return false
            out.use { f.inputStream().copyTo(it) }
            true
        } catch (t: Throwable) {
            Err.fail(t, "写回区域文件")
            false
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

    /** 当前维度，没选就是 null。新功能统一走这里，免得各自去碰 dims。 */
    private fun curDimOrNull(): McaWorld.Dim? = dim

    private fun useDim(d: McaWorld.Dim) {
        dim = d
        refs = d.regions()
        synchronized(this) {
            queue.clear()
            inflightKeys.clear()
        }
        selReg.clear()
        selChunk.clear()
        stagedReg.clear()
        stagedChunk.clear()
        loadDone = 0
        loadTotal = 0
        // 公有目录的瓦片子目录按「世界 / 维度」拼，用户打开相册就知道在看哪个世界
        McaTiles.setNamespace(worldDir?.name ?: d.label, d.label)
        btnDim.text = d.label
        syncYButton()
        if (refs.isEmpty()) {
            tvStatus.text = "「${d.label}」里没有区域文件"
            return
        }
        map.resetView()
        map.invalidate()
        updateStatus()
    }

    private fun pickDim() {
        if (dims.size <= 1) return
        val names = dims.map { "${it.label}（${it.count()} 个区域）" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("切换维度")
            .setItems(names) { _, i -> useDim(dims[i]) }
            .show()
    }

    private fun toggleMode() {
        selMode = !selMode
        btnMode.text = if (selMode) "平移" else "框选"
        tvHint.text = if (selMode) {
            if (map.chunkMode()) "拖一个框，框住的区块全选上"
            else "拖一个框，框住的区域全选上（放大可细分到区块）"
        } else "拖动平移，点一下选中，双指缩放"
    }

    // ------------------------------------------------------------------ 加载

    /**
     * 把视野内的区域排进队列，然后起渲染。
     *
     * 每次平移都会重排：离屏幕中心近的排前面。
     * 已经在渲染的最多两个，等它们跑完就会按新顺序继续 ——
     * 这样用户一平移，画面就朝他看的地方长出来，
     * 而不是让他等着一整批早已划走的区域渲染完。
     */
    fun kickLoad() {
        val now = System.currentTimeMillis()
        if (now - lastKick < 120) return   // onDraw 每帧都会调，节流一下
        lastKick = now

        val want = map.visibleRefs()
        if (want.isEmpty()) return

        val cx = map.centerBlockX()
        val cz = map.centerBlockZ()
        val ordered = want.sortedBy {
            val bx = it.baseBlockX + 256
            val bz = it.baseBlockZ + 256
            (bx - cx) * (bx - cx) + (bz - cz) * (bz - cz)
        }
        synchronized(this) {
            queue.clear()
            for (r in ordered) {
                val k = McaTiles.packRegion(r.rx, r.rz)
                if (inflightKeys.contains(k)) continue
                if (McaTiles.peek(r) != null) continue
                queue.add(r)
            }
            loadTotal = queue.size + loadDone
        }
        pump()
    }

    /** 有空位就往线程池里塞下一个区域 */
    private fun pump() {
        while (busy.get() < RENDER_THREADS) {
            val next = synchronized(this) {
                if (queue.isEmpty()) null else queue.removeAt(0)
            } ?: return
            val key = McaTiles.packRegion(next.rx, next.rz)
            synchronized(this) { inflightKeys.add(key) }
            busy.incrementAndGet()
            val app = this
            submitPool {
                try {
                    val b: Bitmap? = try {
                        McaTiles.get(app, next)
                    } catch (t: Throwable) {
                        Err.fail(t, "生成区域缩略图")
                        null
                    }
                    if (b != null) {
                        runOnUiThread {
                            loadDone++
                            map.invalidate()
                            updateStatus()
                        }
                    }
                } finally {
                    busy.decrementAndGet()
                    synchronized(app) { inflightKeys.remove(key) }
                    runOnUiThread {
                        pump()
                        map.invalidate()
                        updateStatus()
                    }
                }
            }
        }
    }

    private fun updateStatus() {
        val sb = StringBuilder()
        val t = lastTapBlock
        if (t != null) {
            val bx = t.first
            val bz = t.second
            val cx = Math.floorDiv(bx, BLOCKS_PER_CHUNK)
            val cz = Math.floorDiv(bz, BLOCKS_PER_CHUNK)
            sb.append("方块 $bx, $bz　区块 $cx, $cz　")
            sb.append("区域 ${Math.floorDiv(cx, CHUNKS_PER_REGION)}, ")
            sb.appendLine("${Math.floorDiv(cz, CHUNKS_PER_REGION)}")
        }
        val pending = synchronized(this) { queue.size }
        if (pending > 0) {
            sb.appendLine("渲染中 $loadDone / $loadTotal …")
        }
        val n = selCount()
        val unit = if (map.chunkMode()) "区块" else "区域"
        sb.append("已选 $n $unit")
        val st = stagedCount()
        if (st > 0) sb.append("　待删除 $st 项（未保存）")
        btnSave.text = if (st > 0) "保存 $st 项" else "保存"
        btnSave.isEnabled = st > 0
        tvStatus.text = sb.toString()
    }

    private fun selCount() = if (map.chunkMode()) selChunk.size else selReg.size

    private fun stagedCount(): Int {
        var n = stagedChunk.size
        for (k in stagedReg) {
            n += metaCached(k)?.slots?.size ?: 32 * 32
        }
        return n
    }

    // ------------------------------------------------------------------ 元数据

    /**
     * 取一个区域的元数据，但只取已经在内存里的。
     *
     * 选择、框选、反选这些操作都在 UI 线程上跑，
     * 这里绝不能去解析文件 —— 一个区域几秒，点一下卡几秒没人受得了。
     * 没缓存过就当作"不知道"，由需要精确结果的操作（筛选）在后台补。
     */
    private fun metaCached(r: McaWorld.Ref): McaTiles.Meta? =
        McaTiles.metaCached(r)

    private fun metaCached(regionKey: Long): McaTiles.Meta? {
        val r = refs.firstOrNull { McaTiles.packRegion(it.rx, it.rz) == regionKey }
            ?: return null
        return metaCached(r)
    }

    /** 某区域里真实存在的槽位（只在元数据已就绪时有效） */
    private fun slotsOf(r: McaWorld.Ref): IntArray {
        val m = metaCached(r) ?: return IntArray(0)
        return m.slots
    }

    // ------------------------------------------------------------------ 选择

    private fun selectVisible() {
        val v = map.visibleRefs()
        if (map.chunkMode()) {
            for (r in v) {
                for (slot in slotsOf(r)) {
                    selChunk.add(McaTiles.packChunk(r.rx, r.rz, slot and 31, (slot shr 5) and 31))
                }
            }
        } else {
            for (r in v) selReg.add(McaTiles.packRegion(r.rx, r.rz))
        }
        map.invalidate()
        updateStatus()
        if (map.chunkMode() && selChunk.isEmpty()) {
            toast("这些区域还没读过，先等地形画出来再选")
        }
    }

    private fun invertVisible() {
        val v = map.visibleRefs()
        if (map.chunkMode()) {
            val next = HashSet<Long>()
            for (r in v) {
                for (slot in slotsOf(r)) {
                    val k = McaTiles.packChunk(r.rx, r.rz, slot and 31, (slot shr 5) and 31)
                    if (!selChunk.contains(k)) next.add(k)
                }
            }
            selChunk.clear()
            selChunk.addAll(next)
        } else {
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
            for (slot in slotsOf(r)) {
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

    // ------------------------------------------------------------------ 筛选

    /**
     * 按条件选区块。
     *
     * 参照 MCA Selector 的 Chunk Filter。它的核心字段是 InhabitedTime ——
     * 玩家在这个区块累计待过多久。这个值小的区块基本是路过扫了一眼，
     * 删掉让新版本重新生成最划算；反过来，停留久的地方多半有家当。
     *
     * 光看地形图分不出这两者，所以必须提供这个筛选。
     */
    private fun askFilter() {
        val items = arrayOf(
            "完全没人待过（停留 0）",
            "待过不足 1 分钟",
            "待过不足 10 分钟",
            "待过不足 1 小时",
            "待过超过 1 小时（先看看这些）",
            "读不出来的损坏区块",
            "已经生成的所有区块"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("按条件选中（当前视野）")
            .setItems(items) { _, i -> runFilter(i) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runFilter(which: Int) {
        val chunkMode = map.chunkMode()
        val targets = map.visibleRefs()
        if (targets.isEmpty()) { toast("视野里没有区域"); return }

        // 阈值换算成 tick：20 tick = 1 秒
        val maxTicks = when (which) {
            0 -> 0L
            1 -> 60L * TPS
            2 -> 10 * 60L * TPS
            3 -> 60 * 60L * TPS
            else -> -1L
        }

        val prog = MaterialAlertDialogBuilder(this)
            .setTitle("正在筛选…")
            .setMessage("0 / ${targets.size}")
            .setCancelable(false)
            .show()
        val tv = prog.findViewById<TextView>(android.R.id.message)

        submitPool {
            var done = 0
            val hitReg = HashSet<Long>()
            val hitChunk = HashSet<Long>()
            for (r in targets) {
                val m = try {
                    McaTiles.meta(this, r)
                } catch (t: Throwable) {
                    Err.fail(t, "读取区块元数据")
                    McaTiles.Meta.EMPTY
                }
                for (slot in m.slots) {
                    val cx = slot and 31
                    val cz = (slot shr 5) and 31
                    val ok = when (which) {
                        0, 1, 2, 3 -> m.inhabited[slot] <= maxTicks
                        4 -> m.inhabited[slot] > 60 * 60L * TPS
                        5 -> false          // 损坏的不在 slots 里，单独处理
                        else -> true
                    }
                    if (!ok) continue
                    if (chunkMode) hitChunk.add(McaTiles.packChunk(r.rx, r.rz, cx, cz))
                    else { hitReg.add(McaTiles.packRegion(r.rx, r.rz)); break }
                }
                if (which == 5) {
                    for (slot in m.broken) {
                        val cx = slot and 31
                        val cz = (slot shr 5) and 31
                        if (chunkMode) hitChunk.add(McaTiles.packChunk(r.rx, r.rz, cx, cz))
                        else hitReg.add(McaTiles.packRegion(r.rx, r.rz))
                    }
                }
                done++
                runOnUiThread { tv?.text = "$done / ${targets.size}" }
            }
            runOnUiThread {
                prog.dismiss()
                if (chunkMode) { selChunk.clear(); selChunk.addAll(hitChunk) }
                else { selReg.clear(); selReg.addAll(hitReg) }
                map.invalidate()
                updateStatus()
                val n = if (chunkMode) hitChunk.size else hitReg.size
                val unit = if (chunkMode) "区块" else "区域"
                if (n == 0) toast("视野里没有符合条件的$unit")
                else toast("选中 $n $unit")
            }
        }
    }

    // ------------------------------------------------------------------ 跳转

    // ---------------------------------------------------------------- Y 范围

    /**
     * 设置高度区间。
     *
     * ⚠️ 上一版写成两个空输入框「下界 Y / 上界 Y」，配一句抽象说明 ——
     * 除了写它的人没人知道该填什么、填完会变成什么样。
     *
     * 实际语义是：在指定的这一段高度里，每格显示最高的那个方块。
     * 所以这里改成先给几个一眼就懂的档位，自定义放最后，
     * 并且按钮上直接写出"现在在看哪一段"。
     */
    private fun askYRange() {
        val opts = arrayOf(
            "全部高度（默认）",
            "只看地表以上（Y ≥ 60）",
            "只看地表以下（Y ≤ 59）",
            "只看深层矿洞（Y -64 ~ 0）",
            "只看下界那一层（Y 0 ~ 128）",
            "自定义…"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("看哪一段高度")
            .setMessage(
                "在指定的这一段高度里，每格显示最高的那个方块。\n" +
                    "想看矿洞就选「地表以下」，想看地形轮廓就选「地表以上」。"
            )
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> applyYRange(null, null)
                    1 -> applyYRange(60, null)
                    2 -> applyYRange(null, 59)
                    3 -> applyYRange(-64, 0)
                    4 -> applyYRange(0, 128)
                    5 -> askYRangeCustom()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 自定义档位。只有这里才需要填数字，并且写清楚填的是什么 */
    private fun askYRangeCustom() {
        val lo = EditText(this).apply {
            hint = "最低看第几层（留空=不限）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            if (McaTiles.yLo != Int.MIN_VALUE) setText(McaTiles.yLo.toString())
        }
        val hi = EditText(this).apply {
            hint = "最高看第几层（留空=不限）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            if (McaTiles.yHi != Int.MAX_VALUE) setText(McaTiles.yHi.toString())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pd = (16 * resources.displayMetrics.density).toInt()
            setPadding(pd, pd, pd, pd)
            addView(TextView(this@ChunkMapActivity).apply {
                text = "1.18 起主世界是 -64 ~ 319，更早的版本是 0 ~ 255。\n" +
                    "两个都留空就是恢复默认的全部高度。"
            })
            addView(lo); addView(hi)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("自定义高度区间")
            .setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认") { _, _ -> applyYRange(null, null) }
            .setPositiveButton("应用") { _, _ ->
                val a = lo.text.toString().trim().toIntOrNull()
                val b = hi.text.toString().trim().toIntOrNull()
                if (a != null && b != null && a > b) {
                    toast("最低不能大于最高"); return@setPositiveButton
                }
                applyYRange(a, b)
            }
            .show()
    }

    private fun applyYRange(lo: Int?, hi: Int?) {
        McaTiles.setYRange(lo, hi)
        McaTiles.evictAll()
        syncYButton()
        map.invalidate()
        kickLoad()
        toast(
            if (lo == null && hi == null) "已恢复全部高度"
            else when {
                lo == null -> "只看 Y $hi 以下"
                hi == null -> "只看 Y $lo 以上"
                else -> "只看 Y $lo ~ $hi"
            }
        )
    }

    /** 按钮上直接写出当前在看哪一段，不用再点进去猜 */
    private fun syncYButton() {
        if (!::btnY.isInitialized) return
        val lo = McaTiles.yLo
        val hi = McaTiles.yHi
        btnY.text = when {
            lo == Int.MIN_VALUE && hi == Int.MAX_VALUE -> "高度"
            lo == Int.MIN_VALUE -> "高度 ≤$hi"
            hi == Int.MAX_VALUE -> "高度 ≥$lo"
            else -> "高度 $lo~$hi"
        }
    }

    /**
     * 瓦片缓存放在公有目录，用户可以自己去看、自己去删。
     * 这里给出占用和一次清空的入口 —— 写到磁盘上的东西必须让用户看得见。
     */
    private fun askTileCache() {
        val (n, bytes) = McaTiles.cacheStats(this)
        val mb = bytes / 1024.0 / 1024.0
        MaterialAlertDialogBuilder(this)
            .setTitle("地图缓存")
            .setMessage(
                "当前世界已缓存 $n 张区域图，共 ${"%.1f".format(mb)} MB。\n\n" +
                    "位置：相册 / Pictures / ModMigrator / 地图缓存 /\n" +
                    "<世界> / <维度> / <高度档> / r.<x>.<z>.png\n\n" +
                    "清掉不会丢失存档，只是下次打开要重新画一遍。"
            )
            .setNegativeButton("关闭", null)
            .setPositiveButton("清空") { _, _ ->
                McaTiles.clearCache(this)
                map.invalidate()
                kickLoad()
                toast("已清空，正在重画")
            }
            .show()
    }

    // ---------------------------------------------------------------- 找结构

    /**
     * 在当前维度里找结构。
     *
     * 优先用区块里的结构索引，认不出来才按特征方块推断 ——
     * 推断的结果标注了"推断"，免得把玩家自己盖的房子当成遗迹。
     */
    private fun askFindStructure() {
        val dim = curDimOrNull() ?: return toast("先选一个维度")
        val refs = dim.regions()
        if (refs.isEmpty()) return toast("这个维度没有区域文件")
        val prog = android.app.ProgressDialog(this).apply {
            setMessage("扫描 ${refs.size} 个区域文件…")
            setCancelable(false)
            show()
        }
        submitPool {
            val hits = ArrayList<McaStructures.Hit>()
            for (r in refs) {
                // 走文件来源：找结构要把整个维度的区域全扫一遍，
                // 每个都整文件读进内存会一路把堆吃光
                val reg = runCatching { McaEdit.Region(r.file) }.getOrNull() ?: continue
                try {
                    runCatching { hits.addAll(McaStructures.scan(reg, r.rx, r.rz)) }
                } finally {
                    reg.close()
                }
            }
            val grouped = McaStructures.group(hits)
            runOnUiThread {
                prog.dismiss()
                if (grouped.isEmpty()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("没找到")
                        .setMessage(
                            "这个维度没扫到已知结构。\n\n" +
                                "1.13 以后的存档靠区块里的结构索引，比较准；" +
                                "更早的只能按方块特征推断，认不出的会漏。"
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                    return@runOnUiThread
                }
                val names = grouped.map { (id, list) ->
                    "${McaStructures.labelOf(id)}（${list.size} 处" +
                        "${if (list.any { !it.exact }) "·推断" else ""}）"
                }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("找到 ${hits.size} 处")
                    .setItems(names) { _, w ->
                        val list = grouped[w].second
                        val sub = list.map {
                            "区块 (${it.worldChunkX}, ${it.worldChunkZ})" +
                                if (it.exact) "" else " ·推断"
                        }.toTypedArray()
                        MaterialAlertDialogBuilder(this)
                            .setTitle(McaStructures.labelOf(grouped[w].first))
                            .setItems(sub) { _, k ->
                                val h = list[k]
                                map.gotoBlock(h.worldChunkX * 16, h.worldChunkZ * 16)
                                afterGoto()
                                toast("已跳转并选中")
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    // ---------------------------------------------------------------- 替换

    /**
     * 用另一个 .mca 覆盖已选区域。
     *
     * 按槽位一一对应 —— 常见的用法就是拿备份的 r.0.0.mca
     * 去覆盖当前的 r.0.0.mca。源里没有的槽位保持原样，不会清空。
     */
    private fun askReplace() {
        if (selReg.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("先选区域")
                .setMessage("替换是按区域文件整块覆盖，先在地图上选中要替换的区域。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("替换区块")
            .setMessage(
                "选一个源 .mca 文件，它的区块会覆盖当前选中区域的同名槽位。\n\n" +
                    "覆盖前会先留一份 .bak，可以手动还原。\n" +
                    "源里没有的区块不动。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("选源文件") { _, _ ->
                runCatching { pickSrc.launch(arrayOf("*/*")) }
                    .onFailure { toast("打不开文件选择器") }
            }
            .show()
    }

    private fun runReplace(srcUri: Uri) {
        val dim = curDimOrNull() ?: return toast("先选一个维度")
        val targets = dim.regions().filter { selReg.contains(McaTiles.packRegion(it.rx, it.rz)) }
        if (targets.isEmpty()) return toast("选中的区域不在这个维度里")
        val prog = android.app.ProgressDialog(this).apply {
            setMessage("替换中…"); setCancelable(false); show()
        }
        submitPool {
            val srcBytes = try {
                contentResolver.openInputStream(srcUri)?.use { it.readBytes() }
            } catch (t: Throwable) {
                Err.ignore(t, "读源文件失败"); null
            }
            if (srcBytes == null) {
                runOnUiThread { prog.dismiss(); toast("源 .mca 读不出来") }
                return@submitPool
            }
            val src = try {
                McaEdit.Region(srcBytes)
            } catch (t: Throwable) {
                Err.ignore(t, "源 .mca 解析失败")
                runOnUiThread { prog.dismiss(); toast("源 .mca 不是有效的区域文件") }
                return@submitPool
            }
            var done = 0
            var n = 0
            for (ref in targets) {
                try {
                    val dst = McaEdit.Region(ref.file)
                    val slots = dst.present().toIntArray()
                    val k = McaOps.replaceSlots(dst, src, slots)
                    if (k == 0) continue
                    val out = dst.build()
                    // 先备份，再写；写失败要如实说，不能假报成功
                    runCatching { ref.file.copyTo(File(ref.file.parentFile, ref.file.name + ".bak"), true) }
                    val ok = File(ref.file.parentFile, ref.file.name).let {
                        runCatching { it.writeBytes(out) }.isSuccess
                    }
                    if (ok) {
                        done++; n += k
                        McaTiles.invalidate(this, ref)
                        val u = McaWorld.Mirror.of(ref.file)
                        if (u != null) writeBack(ref.file, u)
                    }
                } catch (t: Throwable) {
                    Err.ignore(t, "替换区域失败")
                }
            }
            runOnUiThread {
                prog.dismiss()
                MaterialAlertDialogBuilder(this)
                    .setTitle(if (done > 0) "替换完成" else "没替换成功")
                    .setMessage(
                        if (done > 0) "覆盖了 $done 个区域文件、共 $n 个区块。\n原文件已留 .bak。"
                        else "一个都没覆盖成 —— 可能源文件和目标区域没有对应槽位。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                map.invalidate(); kickLoad()
            }
        }
    }

    // ---------------------------------------------------------------- 导出图

    /**
     * 把当前看到的区域拼成一张 PNG 存进相册。
     *
     * 没选区域就导出当前视野内的；区域多了会按比例缩小，
     * 不然直接 OOM 或者存出一个打不开的文件。
     */
    private fun askExportPng() {
        val dim = curDimOrNull() ?: return toast("先选一个维度")
        val vis = map.visibleRefs()
        val refs = if (selReg.isEmpty()) vis
        else dim.regions().filter { selReg.contains(McaTiles.packRegion(it.rx, it.rz)) }
        if (refs.isEmpty()) return toast("没有可导出的区域")
        val prog = android.app.ProgressDialog(this).apply {
            setMessage("导出 ${refs.size} 个区域…"); setCancelable(false); show()
        }
        submitPool {
            val tiles = ArrayList<Pair<McaWorld.Ref, Bitmap>>()
            for (r in refs) {
                val b = McaTiles.get(this@ChunkMapActivity, r) ?: continue
                tiles.add(r to b)
            }
            val path = if (tiles.size == 1) {
                McaOps.exportOne(this@ChunkMapActivity, "${dim.label}_${tiles[0].first.name}", tiles[0].second)
            } else {
                McaOps.exportPng(this@ChunkMapActivity, "${dim.label}_map", tiles)
            }
            runOnUiThread {
                prog.dismiss()
                MaterialAlertDialogBuilder(this)
                    .setTitle(if (path != null) "已导出" else "导出失败")
                    .setMessage(path ?: "写文件时出错了，看看存储空间是不是满了。")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun askGoto() {
        val spawn = readSpawn()
        val items = ArrayList<String>()
        items.add("输入方块坐标…")
        items.add("输入区块坐标…")
        if (spawn != null) items.add("出生点（${spawn.first}, ${spawn.second}）")
        items.add("世界原点 (0, 0)")

        MaterialAlertDialogBuilder(this)
            .setTitle("跳转到")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    items[i].startsWith("输入方块") -> askCoord(false)
                    items[i].startsWith("输入区块") -> askCoord(true)
                    items[i].startsWith("出生点") -> {
                        val s = spawn
                        if (s != null) { map.gotoBlock(s.first, s.second); afterGoto() }
                    }
                    else -> { map.gotoBlock(0, 0); afterGoto() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun afterGoto() {
        map.invalidate()
        updateStatus()
        kickLoad()
    }

    private fun askCoord(isChunk: Boolean) {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val ex = EditText(this).apply {
            hint = if (isChunk) "区块 X" else "方块 X"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val ez = EditText(this).apply {
            hint = if (isChunk) "区块 Z" else "方块 Z"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        lay.addView(ex)
        lay.addView(ez)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isChunk) "跳到区块" else "跳到方块坐标")
            .setView(lay)
            .setNegativeButton("取消", null)
            .setPositiveButton("跳转") { _, _ ->
                val x = ex.text.toString().toIntOrNull()
                val z = ez.text.toString().toIntOrNull()
                if (x == null || z == null) { toast("坐标填得不对"); return@setPositiveButton }
                if (isChunk) map.gotoBlock(x * 16 + 8, z * 16 + 8) else map.gotoBlock(x, z)
                // 跳过去就放大到能看见区块网格，方便直接勾
                map.zoomToChunk()
                afterGoto()
            }
            .show()
    }

    /** 出生点坐标。level.dat 里的 Data.SpawnX / SpawnZ */
    private fun readSpawn(): Pair<Int, Int>? {
        val w = worldDir ?: return null
        val f = File(w, "level.dat")
        if (!f.isFile) return null
        return try {
            val root = NbtFile.read(f)
            val data = root.get("Data")
            if (data is com.viaversion.nbt.tag.CompoundTag) {
                data.getInt("SpawnX", 0) to data.getInt("SpawnZ", 0)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ 暂存 / 保存

    private fun stageSelection() {
        if (map.chunkMode()) {
            if (selChunk.isEmpty()) { toast("还没选区块"); return }
            stagedChunk.addAll(selChunk)
            selChunk.clear()
        } else {
            if (selReg.isEmpty()) { toast("还没选区域"); return }
            stagedReg.addAll(selReg)
            selReg.clear()
        }
        map.invalidate()
        updateStatus()
        toast("已标记为待删除，还没动存档 —— 点「保存」才生效，点「撤销标记」可以反悔")
    }

    /**
     * 填范围删。
     *
     * 地图上最大只能放到区块级（见 [MIN_SCALE]），
     * 方块级靠手指点既看不清也选不准，所以用填坐标的方式。
     * 输入的是**方块坐标**，内部按区块边界对齐：
     * 完全落在范围内的区块才删，边上只擦到一半的不动 ——
     * 免得你填 0~100 结果把 0~127 整块端掉。
     */
    private fun askRangeDelete() {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        fun field(hint: String): EditText = EditText(ctx).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val e0 = field("x 起点"); val e1 = field("z 起点")
        val e2 = field("x 终点"); val e3 = field("z 终点")
        listOf(e0, e1, e2, e3).forEach { box.addView(it) }
        box.addView(TextView(ctx).apply {
            text = "填方块坐标。只删被范围完整包住的区块，" +
                "边上擦到一半的不动。"
            textSize = 12f
        })

        fun int(e: EditText): Int? = e.text.toString().trim().toIntOrNull()

        MaterialAlertDialogBuilder(ctx)
            .setTitle("按范围删除")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("标记") { _, _ ->
                val x0 = int(e0); val z0 = int(e1)
                val x1 = int(e2); val z1 = int(e3)
                if (x0 == null || z0 == null || x1 == null || z1 == null) {
                    toast("四个都要填"); return@setPositiveButton
                }
                val ax = minOf(x0, x1); val bx = maxOf(x0, x1)
                val az = minOf(z0, z1); val bz = maxOf(z0, z1)
                // 方块坐标 → 区块坐标（向下取整 / 向上取整，只取完整包含的）
                val cx0 = kotlin.math.floor(ax / 16.0).toInt() + if (ax % 16 != 0) 1 else 0
                val cx1 = kotlin.math.ceil((bx + 1) / 16.0).toInt() - 1
                val cz0 = kotlin.math.floor(az / 16.0).toInt() + if (az % 16 != 0) 1 else 0
                val cz1 = kotlin.math.ceil((bz + 1) / 16.0).toInt() - 1
                if (cx1 < cx0 || cz1 < cz0) {
                    toast("这个范围里没有完整包含的区块"); return@setPositiveButton
                }
                var n = 0
                for (cz in cz0..cz1) for (cx in cx0..cx1) {
                    val rx = kotlin.math.floor(cx / 32.0).toInt()
                    val rz = kotlin.math.floor(cz / 32.0).toInt()
                    val lcx = cx - rx * 32
                    val lcz = cz - rz * 32
                    stagedChunk.add(McaTiles.packChunk(rx, rz, lcx, lcz))
                    n++
                }
                map.invalidate()
                updateStatus()
                toast("已标记 $n 个区块待删除 —— 点「保存」才生效")
            }
            .show()
    }

    private fun clearStaged() {
        stagedReg.clear()
        stagedChunk.clear()
        map.invalidate()
        updateStatus()
        toast("已撤销，存档没被改动")
    }

    private fun askSave() {
        val n = stagedCount()
        if (n == 0) { toast("没有待保存的改动"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("保存 $n 项删除？")
            .setMessage(
                "这会真的改写存档文件。\n\n" +
                "再次进入这些区域时，游戏会按当前版本重新生成地形 —— " +
                "在这里建过的东西不会回来。\n\n" +
                "保存前会给每个区域文件留一份 .bak。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> doSave() }
            .show()
    }

    private fun doSave() {
        val chunkMode = map.chunkMode()
        val targets = LinkedHashMap<Long, ArrayList<Int>>()
        for (k in stagedReg) targets.getOrPut(k) { ArrayList() }
        for (k in stagedChunk) {
            val (rx, rz) = McaTiles.chunkRegionOf(k)
            val (cx, cz) = McaTiles.unpackChunk(k)
            targets.getOrPut(McaTiles.packRegion(rx, rz)) { ArrayList() }
                .add(cz * 32 + cx)
        }
        if (targets.isEmpty()) return

        val byRef = HashMap<Long, McaWorld.Ref>()
        for (r in refs) byRef[McaTiles.packRegion(r.rx, r.rz)] = r

        val prog = MaterialAlertDialogBuilder(this)
            .setTitle("正在写入…")
            .setMessage("0 / ${targets.size}")
            .setCancelable(false)
            .show()
        val tv = prog.findViewById<TextView>(android.R.id.message)

        submitPool {
            var done = 0
            var removed = 0
            var failed = 0
            for ((rk, slots) in targets) {
                val ref = byRef[rk] ?: continue
                try {
                    val f = ref.file
                    val bak = File("${f.absolutePath}.bak")
                    if (!bak.exists()) runCatching { f.copyTo(bak) }
                    val reg = McaEdit.Region(f)
                    if (slots.isEmpty()) {
                        for (s in reg.present()) if (reg.remove(s)) removed++
                    } else {
                        for (s in slots) if (reg.remove(s)) removed++
                    }
                    f.writeBytes(reg.build())
                    // SAF 来的：改的是本地副本（镜像或单文件缓存），
                    // 必须写回原处，否则一切白做
                    val back = McaWorld.Mirror.of(f) ?: srcUri
                    if (back != null && !writeBack(f, back)) {
                        failed++
                        runOnUiThread {
                            tv?.text = "${done + failed} / ${targets.size}"
                        }
                        continue
                    }
                    McaTiles.invalidate(this, ref)
                    done++
                } catch (t: Throwable) {
                    Err.fail(t, "删除区块")
                    failed++
                }
                runOnUiThread { tv?.text = "${done + failed} / ${targets.size}" }
            }
            runOnUiThread {
                prog.dismiss()
                stagedReg.clear()
                stagedChunk.clear()
                selReg.clear()
                selChunk.clear()
                loadDone = 0
                loadTotal = 0
                map.invalidate()
                updateStatus()
                kickLoad()
                toast("已删除 $removed 个区块" +
                    (if (failed > 0) "，失败 $failed 个区域" else ""))
            }
        }
    }

    // ------------------------------------------------------------------ 导出

    /**
     * 把选中的区块导出成新的 .mca。
     *
     * MCA Selector 的 Export selection —— 想把家当搬到别的世界、
     * 或者先留一份再删，靠的就是这个。
     */
    private fun askExport() {
        val chunkMode = map.chunkMode()
        val n = if (chunkMode) selChunk.size else selReg.size
        if (n == 0) { toast("还没选东西"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("导出 $n ${if (chunkMode) "个区块" else "个区域"}？")
            .setMessage("导出成独立的 .mca 文件，原存档不动。")
            .setNegativeButton("取消", null)
            .setPositiveButton("导出") { _, _ -> doExport() }
            .show()
    }

    private fun doExport() {
        val chunkMode = map.chunkMode()
        val targets = LinkedHashMap<Long, ArrayList<Int>>()
        if (chunkMode) {
            for (k in selChunk) {
                val (rx, rz) = McaTiles.chunkRegionOf(k)
                val (cx, cz) = McaTiles.unpackChunk(k)
                targets.getOrPut(McaTiles.packRegion(rx, rz)) { ArrayList() }
                    .add(cz * 32 + cx)
            }
        } else {
            for (k in selReg) targets.getOrPut(k) { ArrayList() }
        }
        val byRef = HashMap<Long, McaWorld.Ref>()
        for (r in refs) byRef[McaTiles.packRegion(r.rx, r.rz)] = r

        val outDir = File(filesDir, "mca-export").apply { mkdirs() }
        val prog = MaterialAlertDialogBuilder(this)
            .setTitle("正在导出…")
            .setMessage("0 / ${targets.size}")
            .setCancelable(false)
            .show()
        val tv = prog.findViewById<TextView>(android.R.id.message)

        submitPool {
            var done = 0
            var failed = 0
            val made = ArrayList<File>()
            for ((rk, slots) in targets) {
                val ref = byRef[rk] ?: continue
                try {
                    val src = McaEdit.Region(ref.file)
                    val want = if (slots.isEmpty()) src.present().toList() else slots.toList()
                    // 空头（8192 字节全 0）就是一个"没有任何区块"的区域
                    val out = McaEdit.Region(ByteArray(8192))
                    var put = 0
                    for (s in want) {
                        val tag = src.chunk(s) ?: continue
                        out.put(s, tag)
                        put++
                    }
                    if (put == 0) { done++; continue }
                    val f = File(outDir, "${ref.name}")
                    f.writeBytes(out.build())
                    made.add(f)
                    done++
                } catch (t: Throwable) {
                    Err.fail(t, "导出区块")
                    failed++
                }
                runOnUiThread { tv?.text = "${done + failed} / ${targets.size}" }
            }
            runOnUiThread {
                prog.dismiss()
                if (made.isEmpty()) toast("没有导出任何文件")
                else toast("导出 ${made.size} 个文件到 ${outDir.absolutePath}")
            }
        }
    }

    private fun toast(m: String) =
        Tips.long(this, m)

    override fun onDestroy() {
        alive = false
        super.onDestroy()
        runCatching { pool.shutdownNow() }
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
        private val stagedFill = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(110, 229, 83, 75)
        }
        private val stagedLine = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(230, 229, 83, 75)
        }
        /**
         * 还没加载出来的区域。
         *
         * ⚠️ 之前是纯黑，跟"这里地形本来就是黑的"完全分不清 ——
         * 用户只能猜到底是没加载还是没生成。
         * 现在用带蓝调的深灰，和地形的纯黑区分开，
         * 并且标了坐标，至少能知道是哪一块还在路上。
         */
        private val placeholder = Paint().apply {
            style = Paint.Style.FILL
            color = 0xFF2B3138.toInt()
        }
        private val textPaint = Paint().apply {
            color = Color.argb(190, 150, 170, 190)
            textSize = 22f
        }

        private val sc = ScaleGestureDetector(ctx, object :
            ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val f = d.scaleFactor
                val ns = (scale * f).coerceIn(MIN_SCALE, MAX_SCALE)
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
                lastTapBlock = b
                if (!hasRegion(rx, rz)) {
                    toast("这里没有区域文件（还没生成过）")
                    updateStatus()
                    return true
                }
                // 点按始终切换选中，不管当前是平移还是框选 ——
                // 参考 MCA Selector：左键点一下就是选，不需要先切模式
                toggleAt(rx, rz, cx - rx * 32, cz - rz * 32)
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

        /** 平移到某个方块坐标 */
        fun gotoBlock(bx: Int, bz: Int) {
            camBX = bx.toDouble()
            camBZ = bz.toDouble()
        }

        /** 放大到刚好能看见区块网格 */
        fun zoomToChunk() {
            scale = (CHUNK_MODE_PX * 1.6f / BLOCKS_PER_CHUNK).coerceIn(MIN_SCALE, MAX_SCALE)
        }

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
            scale = (minOf(fitW, fitH) * 0.9f).coerceIn(MIN_SCALE, MAX_SCALE)
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

        private fun hasRegion(rx: Int, rz: Int): Boolean =
            refs.any { it.rx == rx && it.rz == rz }

        /** 画待删除的标记，用红色和蓝色的"已选"区分开 */
        private fun drawStaged(c: Canvas, sx: Float, sy: Float, size: Float,
                               rx: Int, rz: Int) {
            if (stagedReg.contains(McaTiles.packRegion(rx, rz))) {
                c.drawRect(sx, sy, sx + size, sy + size, stagedFill)
                c.drawRect(sx, sy, sx + size, sy + size, stagedLine)
                return
            }
            if (!chunkMode() || stagedChunk.isEmpty()) return
            val cell = size / 32f
            val top = McaTiles.packChunk(rx, rz, 0, 0) and (0xFFFFFFFFL shl 32)
            for (key in stagedChunk) {
                if (key and (0xFFFFFFFFL shl 32) != top) continue
                val cx = ((key ushr 16) and 0xFFFFL).toInt()
                val cz = (key and 0xFFFFL).toInt()
                val x = sx + cx * cell
                val y = sy + cz * cell
                c.drawRect(x, y, x + cell, y + cell, stagedFill)
                c.drawRect(x, y, x + cell, y + cell, stagedLine)
            }
        }

        /** 每帧最多从磁盘解码几张。避免一整屏全 miss 时掉帧 */
        private var diskBudget = 3

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            diskBudget = 3
            val list = visibleRefs()
            for (r in list) {
                val (sx, sy) = blockToScreen(
                    r.baseBlockX.toDouble(), r.baseBlockZ.toDouble()
                )
                val size = BLOCKS_PER_REGION * scale
                if (sx + size < 0 || sy + size < 0 || sx > width || sy > height) continue

                // 两级取图：内存 → 磁盘 PNG。
                // ⚠️ 之前只查内存，LRU 一踢掉就画黑，可磁盘上明明有现成的图。
                // 解码 PNG 是毫秒级，解析 .mca 是秒级，所以只有磁盘也没有
                // 才允许空着，并交给后台去渲染。
                var bmp = McaTiles.peek(r)
                if (bmp == null && diskBudget > 0) {
                    bmp = McaTiles.fromDisk(this@ChunkMapActivity, r)
                    if (bmp != null) diskBudget--
                }
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
                drawStaged(c, sx, sy, size, r.rx, r.rz)
                McaTiles.drawSelection(
                    c, sx, sy, size, r.rx, r.rz, chunkMode(), selReg, selChunk
                )
            }

            // 框选的框
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
                        // 手一松就按新位置重排队列，让用户看的地方先长出来
                        lastKick = 0L
                        kickLoad()
                    }
                }
            }
            return true
        }
    }
}
