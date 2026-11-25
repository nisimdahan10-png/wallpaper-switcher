package com.example.wallpaperswitcher

import android.content.Context
import android.content.SharedPreferences

/** הגדרות האפליקציה */
object Prefs {
    // מתי להחליף
    const val TRIGGER_SCREEN_OFF = 0
    const val TRIGGER_SCREEN_ON = 1
    const val TRIGGER_BOTH = 2

    // איפה להחליף
    const val TARGET_BOTH = 0
    const val TARGET_HOME = 1
    const val TARGET_LOCK = 2

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getUris(c: Context): List<String> =
        (sp(c).getString("uris", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun setUris(c: Context, list: List<String>) =
        sp(c).edit().putString("uris", list.joinToString("\n")).apply()

    fun getIndex(c: Context) = sp(c).getInt("index", -1)
    fun setIndex(c: Context, v: Int) = sp(c).edit().putInt("index", v).apply()

    fun isRandom(c: Context) = sp(c).getBoolean("random", false)
    fun setRandom(c: Context, v: Boolean) = sp(c).edit().putBoolean("random", v).apply()

    fun getTrigger(c: Context) = sp(c).getInt("trigger", TRIGGER_SCREEN_OFF)
    fun setTrigger(c: Context, v: Int) = sp(c).edit().putInt("trigger", v).apply()

    fun getTarget(c: Context) = sp(c).getInt("target", TARGET_BOTH)
    fun setTarget(c: Context, v: Int) = sp(c).edit().putInt("target", v).apply()

    fun isEnabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()
}
