package com.kongbai.modmigrator

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/**
 * 二级设置容器：入口页 + 各分组详情页。
 *
 * 返回栈用自己维护的 pageStack，而不是 FragmentManager 的 back stack。
 * 之前混用了两套机制：show(page, true) 走 addToBackStack，
 * 但 onBackPressed 里却调 show("main", false)（不入栈），
 * 结果返回栈里越堆越多，用户按返回键会依次弹回到之前看过的子页面，
 * 表现就是「返回卡住、要按好几次才退出去」。
 * 现在统一由 pageStack 管理，返回一次退一层，栈空才真正退出。
 */
class SettingsHostActivity : AppCompatActivity() {

    /** 进入子页面前的页面历史，返回时逐层弹出 */
    private val pageStack = ArrayList<String>()

    private var current: String = "main"

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LangPack.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemePrefs.styleRes(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings2)
        val page = intent.getStringExtra("page") ?: "main"
        pageStack.clear()
        // 直接进子页面时，把入口页垫在栈底，返回才能回到入口
        if (page != "main") pageStack.add("main")
        show(page)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    /** 切到某个设置子页。addToStack=true 表示记入历史，返回键能退回上一步。 */
    fun show(page: String, addToStack: Boolean = true) {
        if (addToStack && current != page && current.isNotBlank()) {
            // 避免连续重复入栈
            if (pageStack.isEmpty() || pageStack.last() != current) {
                pageStack.add(current)
            }
        }
        current = page
        val f: Fragment = when (page) {
            "backend" -> SettingsBackendFragment()
            "search" -> SettingsSearchFragment()
            "migrate" -> SettingsMigrateFragment()
            "storage" -> SettingsStorageFragment()
            "favorites" -> FavoritesFragment()
            "weekly" -> WeeklyReportFragment()
            "sites" -> SitesFragment()
            "translate" -> SettingsTranslateFragment()
            "nav" -> SettingsNavFragment()
            "anim" -> SettingsAnimFragment()
            "theme" -> SettingsThemeFragment()
            "lang" -> SettingsLangFragment()
            "plugin" -> PluginFragment()
            "devs" -> DevsFragment()
            "log" -> SettingsLogFragment()
            "about" -> SettingsAboutFragment()
            "more" -> MoreFragment()
            else -> SettingsMainFragment()
        }
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, f)
                .commit()
        } catch (t: Throwable) {
            // 事务提交失败也不能崩
        }
        title = when (page) {
            "backend" -> getString(R.string.menu_backend)
            "search" -> getString(R.string.menu_search)
            "migrate" -> getString(R.string.menu_migrate)
            "storage" -> getString(R.string.menu_storage)
            "favorites" -> getString(R.string.tab_favorites)
            "weekly" -> getString(R.string.tab_weekly)
            "sites" -> getString(R.string.tab_sites)
            "translate" -> getString(R.string.menu_translate)
            "nav" -> getString(R.string.menu_nav)
            "anim" -> getString(R.string.anim_title)
            "theme" -> getString(R.string.theme_title)
            "lang" -> getString(R.string.lang_title)
            "plugin" -> getString(R.string.menu_plugin)
            "devs" -> getString(R.string.menu_devs)
            "log" -> getString(R.string.menu_log)
            "about" -> getString(R.string.menu_about)
            "more" -> getString(R.string.tab_more)
            else -> getString(R.string.tab_settings)
        }
    }

    /**
     * 返回键：历史非空就退一层回到上一个页面；历史为空才真正退出。
     */
    override fun onBackPressed() {
        if (pageStack.isNotEmpty()) {
            val prev = pageStack.removeAt(pageStack.size - 1)
            show(prev, false)
            return
        }
        super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
