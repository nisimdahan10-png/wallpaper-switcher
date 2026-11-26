package com.nisimdahan.wallpaperswitcher

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * בחירת שפה בתוך האפליקציה.
 * אנדרואיד 13+: משתמש בשפה-לכל-אפליקציה של המערכת (מופיע גם בהגדרות הטלפון).
 * גרסאות ישנות: שומר את הבחירה ומחיל אותה בעצמו.
 */
object Lang {
    /** קוד שפה ("" = לפי המכשיר) ושם השפה בשפה עצמה */
    val OPTIONS = listOf(
        "" to "",
        "he" to "עברית",
        "en" to "English",
        "ar" to "العربية",
        "ru" to "Русский",
    )

    fun current(c: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            val list = c.getSystemService(LocaleManager::class.java).applicationLocales
            if (list.isEmpty) "" else normalize(list[0].language)
        } else {
            Prefs.getLang(c)
        }

    fun set(activity: Activity, code: String) {
        Prefs.setLang(activity, code)
        if (Build.VERSION.SDK_INT >= 33) {
            // המערכת מחליפה שפה ובונה את המסך מחדש בעצמה
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        } else {
            activity.recreate()
        }
    }

    /** לגרסאות לפני אנדרואיד 13 – מחיל את השפה שנבחרה על ה-Context */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val code = Prefs.getLang(base)
        if (code.isEmpty()) return base
        val locale = Locale(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    // ג'אווה ישנה מחזירה "iw" לעברית
    private fun normalize(lang: String) = if (lang == "iw") "he" else lang
}
