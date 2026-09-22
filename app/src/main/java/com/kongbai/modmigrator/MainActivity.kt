package com.kongbai.modmigrator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        if (savedInstanceState == null) switchTo(R.id.nav_migration)
        nav.setOnItemSelectedListener { item ->
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

        SyncManager.schedule(this)
    }

    private fun switchTo(id: Int) {
        // 设置走独立的二级菜单页面，不再塞进底部导航
        if (id == R.id.nav_settings) {
            startActivity(android.content.Intent(this, SettingsHostActivity::class.java))
            return
        }
        val f: Fragment = when (id) {
            R.id.nav_server -> ServerFragment()
            R.id.nav_market -> MarketFragment()
            R.id.nav_sync -> SyncFragment()
            else -> MigrationFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, f)
            .commit()
    }
}
