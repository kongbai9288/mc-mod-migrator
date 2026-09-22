package com.kongbai.modmigrator

import android.widget.AdapterView
import android.widget.Spinner

fun Spinner.setOnItemSelectedListenerSafe(block: () -> Unit) {
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = block()
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
}
