package com.nvrviewer

import android.app.Application
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/** Saves any crash so it is shown on the next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            getSharedPreferences("crash", MODE_PRIVATE).edit()
                .putString("trace", "Android ${Build.VERSION.RELEASE} • ${Build.MANUFACTURER} ${Build.MODEL}\n\n$sw")
                .commit()
            prev?.uncaughtException(t, e)
        }
    }
}
