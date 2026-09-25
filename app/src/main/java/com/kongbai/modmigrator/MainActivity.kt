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

    /** 语言拓展包在这层注入，之后所有 getString 都会走译文 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LangPack.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemePrefs.styleRes(this))
        super.onCreate(savedInstanceState)
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

        nav?.setOnItemSelectedListener { item ->
            switchTo(item.itemId)
            true
        }

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
    }

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

    private fun switchTo(id: Int) {
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

        if (id == ID_MORE) {
            replace(MoreFragment())
            return
        }
        val key = NavConfig.keyOfId(id) ?: return
        // 设置现在就在主界面里显示，不再跳到独立 Activity。
        // 之前那样做会导致：从设置返回后底部导航高亮还停在别的 tab 上，
        // 看起来像"返回到了别的页面"。现在设置就是一个普通 tab，
        // 子页在同一容器内打开，返回路径连贯。
        val p = NavConfig.find(key) ?: return
        val f: Fragment = try {
            p.make()
        } catch (t: Throwable) {
            MigrationFragment()
        }
        replace(f)
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
                "about" -> SettingsAboutFragment()
                "lab" -> SettingsLabFragment()
                "feed" -> McFeedFragment()
                else -> SettingsMainFragment()
            }
        } catch (t: Throwable) {
            SettingsMainFragment()
        }
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, f)
                .addToBackStack("settings:$page")
                .commit()
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

    /** 同步底部导航的选中项，避免"内容变了但高亮还在别处" */
    private fun syncNavSelection(id: Int) {
        val n = nav ?: return
        runCatching {
            // 先取消监听再设置，防止 setSelectedItemId 反过来触发 switchTo 造成循环
            n.setOnItemSelectedListener(null)
            val item = n.menu.findItem(id)
            if (item != null) item.isChecked = true
            n.setOnItemSelectedListener { it2 ->
                switchTo(it2.itemId)
                true
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
            runCatching { fm.popBackStack() }
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
            // 开启动画时用系统过渡，关闭时直接替换
            if (AnimPrefs.enabled(this)) {
                tx.setCustomAnimations(
                    android.R.anim.fade_in, android.R.anim.fade_out
                )
            }
            tx.replace(R.id.fragment_container, f)
            tx.commit()
        } catch (t: Throwable) { Err.ignore(t, "tx.commit()") }
    }
}
