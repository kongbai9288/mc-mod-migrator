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
        } catch (t: Throwable) {
        }
    }

    /** 供设置页改动导航栏后调用重建 */
    fun rebuildNav() {
        try {
            buildNav()
        } catch (t: Throwable) {
        }
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

    private fun switchTo(id: Int) {
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
        } catch (t: Throwable) {
        }
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
        } catch (t: Throwable) {
        }
    }
}
