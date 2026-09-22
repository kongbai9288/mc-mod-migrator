package com.kongbai.modmigrator

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** 二级设置：入口页 + 各分组详情页 */
class SettingsHostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings2)
        val page = intent.getStringExtra("page") ?: "main"
        val f = when (page) {
            "backend" -> SettingsBackendFragment()
            "search" -> SettingsSearchFragment()
            "migrate" -> SettingsMigrateFragment()
            "storage" -> SettingsStorageFragment()
            "about" -> SettingsAboutFragment()
            else -> SettingsMainFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commit()
        title = when (page) {
            "backend" -> getString(R.string.menu_backend)
            "search" -> getString(R.string.menu_search)
            "migrate" -> getString(R.string.menu_migrate)
            "storage" -> getString(R.string.menu_storage)
            "about" -> getString(R.string.menu_about)
            else -> getString(R.string.tab_settings)
        }
    }
}
