package com.kongbai.modmigrator

import android.widget.AdapterView
import android.widget.Spinner

fun Spinner.setOnItemSelectedListenerSafe(block: () -> Unit) {
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = block()
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
}

fun android.widget.EditText.addTextWatcherSafe(block: () -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = block()
    })
}
