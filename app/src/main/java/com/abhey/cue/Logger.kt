package com.abhey.cue

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object Logger {

    private val entries = CopyOnWriteArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        entries.add(line)
        if (entries.size > 200) entries.removeAt(0)
        android.util.Log.d("CueApp", line)
    }

    fun all(): String = entries.joinToString("\n")

    fun clear() = entries.clear()
}
