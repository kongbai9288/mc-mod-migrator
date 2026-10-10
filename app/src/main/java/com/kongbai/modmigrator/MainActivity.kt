package com.kongbai.modmigrator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    /** 「更多」是固定项，占底部 5 个名额里的最后一个 */
    private val ID_MORE = 1900
    private var nav: BottomNavigationView? = null

    /** 主线程 handler，用于切换后的兜底校验 */
    private val handler = Bg.ui

    /** 语言拓展包在这层注入，之后所有 getString 都会走译文 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LangPack.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemePrefs.styleRes(this))
        super.onCreate(savedInstanceState)
        // 冷启动净化：Progress 是全局单例，上次运行若因异常/提前 return
        // 没走到 done()，running 会永久卡在 true，
        // 于是本次一进界面就提示"扫描运行中"——用户根本没点扫描。
        // 这里在渲染任何界面之前强制复位，作为最后一道兜底。
        runCatching { Progress.done() }
        // 每次冷启动重置「首次进商店」标记：
        // 这样推荐只在本次打开应用后的第一次进商店时自动跑
        runCatching {
            Prefs.get(this).edit().putBoolean(K.FIRST_MARKET_VISIT, false).apply()
        }
        setContentView(R.layout.activity_main)

        nav = findViewById(R.id.bottom_nav)
        buildNav()

        if (savedInstanceState == null) {
            val first = NavConfig.navPages(this).firstOrNull()
            if (first != null && first.key != "settings") switchTo(NavConfig.idOf(first.key))
        }

        setupNavListener()

        // 公告只在启动时检查一次。
        // 之前这句写在了 tab 选择监听器里，导致每切一次 tab 就弹一次公告。
        checkAnnouncement()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                42
            )
        }

        try {
            SyncManager.schedule(this)
        } catch (t: Throwable) {
            // WorkManager 初始化失败也不能让主界面打不开
                 Err.ignore(t, "WorkManager 初始化失败也不能让主界面打不开")
             }

        CrashReport.showIfAny(this)

        // ── 开场动画：从哪个启动器进来，就放哪家的图标 ──────────
        // 启动器通过 Intent extra 自报名字与版本（见 LauncherBrand 的说明），
        // 不做包名校验——fork 版改了包名也照样能认。
        // 动画占满 3 秒有点久，所以设置里给了开关，点击任意处也能跳过。
        runCatching {
            val h = LauncherBrand.handoff(this)
            if (h != null && Prefs.get(this).getBoolean(K.SPLASH_ANIM, true)) {
                LauncherSplash.play(this, h)
            }
        }
    }

    /**
     * 每次回到主界面都重建导航：
     * 用户可能在设置/更多里改了导航栏配置，不刷新就会显示旧的。
     */
    override fun onResume() {
        super.onResume()
        try {
            buildNav()
        } catch (t: Throwable) { Err.ignore(t, "buildNav()") }
        // 从后台回来时容器可能是空的（进程被回收后恢复失败），补一次校验
        handler.post { ensureContainerFilled() }
        // 补捡"下载完了但没能自动弹出安装"的更新包。
        // 之前只靠动态注册的广播接收下载完成：用户切后台后进程被回收，
        // Receiver 就没了，APK 下完也没人来调起安装。
        // 回到前台时主动查一次 DownloadManager，把漏掉的那次补上。
        try {
            UpdateInstaller.checkPendingInstallations(this)
        } catch (t: Throwable) { Err.ignore(t, "补捡待安装更新") }
        //
        // 兜底"页面一片空白"。
        // 内存紧张时系统会回收 Fragment（或 commit 因状态保存被丢弃），
        // 容器里就什么都不剩 —— 界面全白、点了没反应，
        // 而用户只能杀进程重开。这里检测到容器为空就重建当前页。
        //
        try { ensureContentVisible() } catch (t: Throwable) { Err.ignore(t, "检查页面内容") }
        //
        // 断点续做：上次没跑完的长任务。
        // 迁移、批量下载这类操作中途被划掉或手机关机，
        // 进程直接消失、没有任何回调，下次打开界面一片干净，
        // 用户不知道做到哪儿了，只能凭记忆重来（已下好的还会重复下一遍）。
        // 只在这个入口问一次，避免每次回前台都弹。
        //
        try { checkResume() } catch (t: Throwable) { Err.ignore(t, "检查未完成任务") }
    }

    /** 上次中断的任务，只问一次 */
    private var resumeAsked = false

    private fun checkResume() {
        if (resumeAsked) return
        resumeAsked = true
        val j = Resume.peek(this) ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("上次没做完")
            .setMessage(
                buildString {
                    append(j.title)
                    append("\n\n已完成 ${j.finished}/${j.total}，还剩 ${j.remaining.size} 项。\n\n")
                    append("要接着做吗？已经完成的那部分不会重复。")
                }
            )
            .setPositiveButton("继续") { _, _ -> runResume(j) }
            .setNegativeButton("不用了") { _, _ -> Resume.clear(this) }
            .setOnCancelListener { Resume.clear(this) }
            .show()
    }

    private fun runResume(j: Resume.Journal) {
        val ctx = this
        val dstUri = Prefs.get(ctx).getString(K.DST_URI, null)
        val d = ProgressDialog.show(ctx, "继续：${j.title}")
        //
        // 续做只依赖两样东西：记录里的下载地址 + 已授权的目标目录。
        // 不依赖内存中的模组列表 —— 进程被杀后那些早就没了，
        // 这也是当初把"下载地址"而不是"模组名"作为记录主键的原因。
        //
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            var ok = 0
            val still = ArrayList<String>()
            val rem = j.remaining
            val dir = if (dstUri.isNullOrBlank()) null
            else runCatching {
                val t = Fs.tree(ctx, dstUri)
                if (t == null) null else Fs.ensureDir(t, "mods")
            }.getOrNull()

            if (dir == null) {
                still.add("目标 mods 目录不可用，请先在迁移页授权")
            } else {
                for ((i, key) in rem.withIndex()) {
                    runOnUiThread { d.update(i.toLong(), rem.size.toLong()) }
                    val name = j.nameOf(key)
                    val f = runCatching { Downloader.download(ctx, key, dir, name) }.getOrNull()
                    if (f != null) ok++ else still.add(name)
                }
            }
            runOnUiThread {
                d.dismiss()
                Resume.clear(ctx)
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("续做完成")
                    .setMessage(
                        buildString {
                            append("成功 $ok / ${rem.size}。")
                            if (still.isNotEmpty()) {
                                append("\n\n没下成的：\n")
                                still.take(8).forEach { append("  · $it\n") }
                                if (still.size > 8) append("  …等 ${still.size} 个\n")
                            }
                        }
                    )
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    /**
     * 容器里没有 Fragment 就重建当前页。
     *
     * 注意：**不能无条件重建**，否则每次 onResume 都会把页面重置一次，
     * 滚动位置、已加载的列表全丢，比空白还难受。
     */
    private fun ensureContentVisible() {
        val fm = supportFragmentManager
        val has = fm.findFragmentById(R.id.fragment_container)
        if (has != null && has.view != null) return
        val page = currentPage
        if (page.isBlank()) return
        LogCenter.w("Main", "页面内容为空，重建「$page」")
        showTab(page)
    }

    /** 当前一级页 key，空白重建时用 */
    private val currentPage: String
        get() = if (currentTabId == ID_MORE) "more" else (NavConfig.keyOfId(currentTabId) ?: "")

    /** 供设置页改动导航栏后调用重建 */
    fun rebuildNav() {
        try {
            buildNav()
        } catch (t: Throwable) { Err.ignore(t, "buildNav()") }
    }

    private fun buildNav() {
        val n = nav ?: return
        val menu = n.menu
        menu.clear()
        val pages = NavConfig.navPages(this)
        // Material 硬性上限 5 项，这里自定义最多 4 项 + 固定的「更多」
        for ((i, p) in pages.withIndex()) {
            menu.add(0, NavConfig.idOf(p.key), i, getString(p.title))
                .setIcon(p.icon)
        }
        menu.add(0, ID_MORE, pages.size, getString(R.string.tab_more))
            .setIcon(R.drawable.ic_filter_list)

        // ── 重建后必须把高亮补回去 ────────────────────────────────
        // menu.clear() 会连同"当前选中项"一起清掉。
        // 而 buildNav() 在 onResume 里每次都调——从内置浏览器、
        // 登录页、文件选择器等任何 Activity 返回主界面都会走这里。
        // 结果就是：**回到主界面后底部导航没有任何一项是亮的**，
        // 用户看着内容在"设置"，底下却像没选中任何页。
        // 这也是"点了设置，返回后界面不对"的一部分成因。
        if (currentTabId > 0) syncNavSelection(currentTabId)
    }

    /**
     * tab 切换历史。
     *
     * 之前底部导航切换**不进返回栈**，而设置子页**进栈**，两者混在一起导致：
     * 从设置子页一路返回后，会直接跳过一级页面退出 Activity，
     * 或者返回后底部高亮还停在别的 tab 上——用户看到的"返回到别的页面""卡住了"。
     *
     * 现在按官方 BottomNavigationView 的做法：
     *   - 一级 tab 切换不进 FragmentManager 栈，而是记进这里的历史
     *   - 返回时先弹子页栈（栈里有东西的话）
     *   - 子页栈空了，再按历史退回上一个 tab
     *   - 都没有了才真的退出
     * 并且每次切换都同步底部导航的选中项，视觉和状态始终一致。
     */
    private val tabHistory = ArrayList<Int>()
    private var currentTabId: Int = -1

    // ── 底部导航防抖 ──────────────────────────────────────────
    // 快速连点底部导航栏时，FragmentManager 会把多个事务排队执行。
    // 而每个事务都带淡入淡出动画：
    // 前一个还没结束、后一个已经开始替换 container 里的 Fragment，
    // 于是出现"两个 Fragment 同时挂载"的瞬间 ——
    // 上层那个被移除时留下空白，背景色透出来，看起来就是**白屏**。
    // 更糟的是旧 Fragment 的视图还没销毁就又被引用，泄漏随之而来。
    // 这里做双重保护：时间窗 350ms + 正在切换中的标志位。
    private var navLock = false
    private var lastNavAt = 0L

    private fun switchTo(id: Int) {
        // 同一个 tab 重复点也没意义，直接忽略
        if (id == currentTabId) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (navLock || now - lastNavAt < 350L) return
        navLock = true
        lastNavAt = now
        try {
            switchToInternal(id)
        } finally {
            navLock = false
        }
    }

    private fun switchToInternal(id: Int) {
        // 记录切换历史（去重：连续点同一个不算）
        if (id != currentTabId) {
            tabHistory.remove(id)   // 已存在就先移除，再放到末尾，保持最近优先
            tabHistory.add(id)
            if (tabHistory.size > 12) tabHistory.removeAt(0)
        }
        currentTabId = id
        // 切 tab 时把子页栈清掉：从"迁移"切到"市场"，
        // 之前在"迁移"里打开的二级页面不应该还留在栈里
        clearChildStack()
        syncNavSelection(id)

        val key = if (id == ID_MORE) "more" else (NavConfig.keyOfId(id) ?: return)
        showTab(key)
    }

    /** 当前显示的一级页 key */
    private var currentTabKey: String = ""

    /**
     * 显示一级页 —— **缓存复用，不再每次重建**。
     *
     * 之前每次切 tab 都 new 一个 Fragment 再 replace：
     * 已加载的列表、滚动位置、展开状态全丢，
     * 回到「迁移」页就是从头加载一遍，慢的时候看着像卡住。
     *
     * 现在每个一级页只创建一次，之后靠 hide/show 切换，
     * Fragment 实例和它的 View 都活着，切回来还是原来那样。
     *
     * ⚠️ 子页（设置二级页）必须 add 上来而不是 replace ——
     * replace 会把底下的 tab 一起 remove，
     * 子页一开一关 tab 又被重建，缓存就白做了。
     */
    private fun showTab(key: String) {
        val fm = supportFragmentManager
        val tag = "tab:$key"
        var f = fm.findFragmentByTag(tag)
        if (f == null) {
            f = try {
                if (key == "more") MoreFragment() else NavConfig.find(key)?.make()
            } catch (t: Throwable) {
                Err.ignore(t, "创建页面 $key")
                null
            }
            if (f == null) return
            fm.beginTransaction()
                .add(R.id.fragment_container, f, tag)
                .commitAllowingStateLoss()
            runCatching { fm.executePendingTransactions() }
        }
        currentTabKey = key
        showOnly(f)
        handler.post { ensureContainerFilled() }
    }

    /**
     * 只让目标 Fragment 可见，其余全部 hide。
     *
     * 容器是 FrameLayout，多个 Fragment add 进去会**叠着一起显示**，
     * 所以任何时候都必须保证只有一个是 show 的。
     * 这里每次都遍历一遍而不是记"上一个是谁"，
     * 免得某条路径漏了 hide 就出现两层内容重叠。
     */
    private fun showOnly(target: Fragment?) {
        runCatching {
            val tx = supportFragmentManager.beginTransaction()
            for (f in supportFragmentManager.fragments) {
                if (f == null) continue
                if (f === target) {
                    if (f.isHidden) tx.show(f)
                } else if (!f.isHidden) {
                    tx.hide(f)
                }
            }
            tx.commitAllowingStateLoss()
        }
    }

    /**
     * 打开设置子页。
     * 跟一级设置页共用同一个容器并压入返回栈，
     * 返回键路径：子页 → 设置入口 → 上一个 tab。
     */
    fun openSettingsPage(page: String) {
        val f: Fragment = try {
            when (page) {
                "backend" -> SettingsBackendFragment()
                "search" -> SettingsSearchFragment()
                "migrate" -> SettingsMigrateFragment()
                "storage" -> SettingsStorageFragment()
                "nav" -> SettingsNavFragment()
                "theme" -> SettingsThemeFragment()
                "lang" -> SettingsLangFragment()
                "anim" -> SettingsAnimFragment()
                "translate" -> SettingsTranslateFragment()
                "plugin" -> PluginFragment()
                "devs" -> DevsFragment()
                "log" -> SettingsLogFragment()
                "patch" -> SettingsPatchFragment()
                "about" -> SettingsAboutFragment()
                "lab" -> SettingsLabFragment()
                "feed" -> McFeedFragment()
                else -> SettingsMainFragment()
            }
        } catch (t: Throwable) {
            SettingsMainFragment()
        }
        try {
            //
            // 用 add 叠在当前页之上，不用 replace。
            // replace 会把底下的一级页一起 remove，
            // 返回后它就得整个重建 —— 从设置返回到「迁移」又要重新加载，
            // 正是这次要修的那个"每次进来都重新加载"。
            //
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, f, "page:$page")
                .addToBackStack("settings:$page")
                .commitAllowingStateLoss()
            handler.post { showOnly(f) }
        } catch (t: Throwable) { Err.ignore(t, ".commit()") }
    }

    /**
     * 公告：从仓库拉 announcement.json，多镜像源依次尝试。
     * 弹窗展示，可关闭 / 清除 / 在设置里再弹一次。
     */
    private fun checkAnnouncement() {
        Thread {
            val ctx = this@MainActivity
            val n = try {
                Announcement.fetch(ctx)
            } catch (t: Throwable) {
                null
            } ?: return@Thread
            runOnUiThread {
                try {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val msg = StringBuilder(n.body)
                    if (n.url.isNotBlank()) {
                        msg.append("\n\n详情：").append(n.url)
                    }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle(n.title)
                        .setMessage(msg.toString())
                        .setPositiveButton("知道了") { _, _ ->
                            Announcement.dismiss(ctx, n.id)
                        }
                        .setNeutralButton("清除") { _, _ ->
                            Announcement.clear(ctx, n.id)
                            android.widget.Toast.makeText(ctx, "已清除这条公告", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("打开链接") { _, _ ->
                            Announcement.dismiss(ctx, n.id)
                            if (n.url.isNotBlank()) WebActivity.open(ctx, n.url, n.title)
                        }
                        .setCancelable(true)
                        .show()
                } catch (t: Throwable) { Err.ignore(t, ".show()") }
            }
        }.start()
    }

    /**
     * 底部导航的监听器**只设一次**。
     *
     * ⚠️ 之前的写法是每次切换都 `setOnItemSelectedListener(null)` 再重设一个新监听器。
     * 高频切换时，BottomNavigationView 内部的事件分发链会在极短时间内被反复拆掉又接上，
     * 它的选中状态机直接崩掉 —— 表现为**底部栏整个消失**，而且再也点不动。
     * 这是"点几下下面啥也不显示"的直接原因。
     *
     * 反复解绑本来是为了防止"程序改选中态 → 反过来触发 switchTo"的循环，
     * 但用标志位就能解决，没必要动监听器本身。
     */
    private fun setupNavListener() {
        val n = nav ?: return
        n.setOnItemSelectedListener { item ->
            // 程序同步选中态时不回调，避免 switchTo → syncNavSelection → switchTo 死循环
            if (!suppressNavCallback) switchTo(item.itemId)
            true
        }
    }

    /** 正在由程序（而非用户点击）同步选中态 */
    private var suppressNavCallback = false

    /** 同步底部导航的选中项，避免"内容变了但高亮还在别处" */
    private fun syncNavSelection(id: Int) {
        val n = nav ?: return
        runCatching {
            suppressNavCallback = true
            try {
                val item = n.menu.findItem(id)
                if (item != null) item.isChecked = true
            } finally {
                suppressNavCallback = false
            }
        }
    }

    /** 清空子页栈（切 tab 时用） */
    private fun clearChildStack() {
        runCatching {
            val fm = supportFragmentManager
            while (fm.backStackEntryCount > 0) {
                fm.popBackStackImmediate()
            }
        }
    }

    /**
     * 返回键处理。
     *
     * 顺序：子页栈 → tab 历史 → 真的退出。
     * 每一步都同步底部高亮，保证"看到的就是所在的"。
     */
    override fun onBackPressed() {
        // 1. 有子页（设置二级页等）先退子页
        val fm = supportFragmentManager
        if (fm.backStackEntryCount > 0) {
            // 同步弹出：异步的话下面拿到的还是"子页仍在"的状态，
            // 会把刚要显示的一级页又 hide 回去，界面就空了。
            runCatching { fm.popBackStackImmediate() }
            val back = if (currentTabKey.isBlank()) null
            else fm.findFragmentByTag("tab:$currentTabKey")
            if (back != null) showOnly(back) else handler.post { ensureContainerFilled() }
            return
        }
        // 2. 子页没了，看还有没有上一个 tab
        if (tabHistory.size > 1) {
            tabHistory.removeAt(tabHistory.size - 1)   // 移除当前
            val prev = tabHistory.removeAt(tabHistory.size - 1)
            switchTo(prev)
            return
        }
        // 3. 都没有，真的退出
        super.onBackPressed()
    }

    private fun replace(f: Fragment) {
        try {
            val tx = supportFragmentManager.beginTransaction()
            //
            // ⚠️ 之前进出都用淡入淡出，新旧两个页面会**同时**处在半透明状态，
            // 中间那一瞬能看见两层内容重叠在一起——就是反馈里说的"叠化"。
            // 现在旧页面用空动画（立刻消失），只有新页面淡入，
            // 不再有两层共存的一瞬。淡入时长也缩短到 120ms。
            //
            if (AnimPrefs.enabled(this)) {
                tx.setCustomAnimations(R.anim.fade_in_fast, R.anim.no_anim)
            }
            tx.replace(R.id.fragment_container, f)
            //
            // commit() 在 onSaveInstanceState 之后调用会抛
            // IllegalStateException（"Can not perform this action after..."）。
            // 之前这个异常被 catch 吞掉，于是 currentTabId 已经改了、
            // 但界面没切换成功 —— 表现就是"页面空白"。
            // 改用 AllowingStateLoss 保证切得过去；切完再校验一次，
            // 真没切成功就退回默认页，不再留一个空容器。
            //
            tx.commitAllowingStateLoss()
            handler.post { ensureContainerFilled() }
        } catch (t: Throwable) {
            Err.ignore(t, "切换页面")
            handler.post { ensureContainerFilled() }
        }
    }

    /**
     * 兜底：容器里如果一个 Fragment 都没有，界面就是一片空白。
     * 这种情况出现过多次（commit 失败、状态恢复异常、内存不足被回收），
     * 这里发现空就直接重建当前 tab 对应的页面。
     */
    private fun ensureContainerFilled() {
        if (isFinishing || isDestroyed) return
        if (supportFragmentManager.isStateSaved) return
        val has = supportFragmentManager.findFragmentById(R.id.fragment_container) != null
        if (has) return
        val key = NavConfig.keyOfId(currentTabId) ?: return
        val p = NavConfig.find(key) ?: return
        try {
            val f = try {
                p.make()
            } catch (t: Throwable) {
                MigrationFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, f)
                .commitAllowingStateLoss()
        } catch (t: Throwable) {
            Err.ignore(t, "恢复空白页面")
        }
    }
}
