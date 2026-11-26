package com.example.wallpaperswitcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * שירות שרץ ברקע ומאזין לכיבוי/הדלקת מסך.
 * (אנדרואיד לא מאפשר להאזין לאירועים האלה בלי שירות פעיל.)
 */
class SwitcherService : Service() {

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Prefs.isLive(context)) return // ברקע חי ההחלפה נעשית שם, עם אנימציה
            val trigger = Prefs.getTrigger(context)
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF ->
                    if (trigger == Prefs.TRIGGER_SCREEN_OFF || trigger == Prefs.TRIGGER_BOTH)
                        WallpaperChanger.changeAsync(context)
                Intent.ACTION_SCREEN_ON ->
                    if (trigger == Prefs.TRIGGER_SCREEN_ON || trigger == Prefs.TRIGGER_BOTH)
                        WallpaperChanger.changeAsync(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "החלפת רקעים", NotificationManager.IMPORTANCE_MIN)
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("מחליף רקעים פעיל")
            .setContentText("הרקע יתחלף בכל כיבוי/הדלקה של המסך")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "switcher"
        private const val NOTIF_ID = 1

        fun start(c: Context) {
            val i = Intent(c, SwitcherService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, SwitcherService::class.java))
        }
    }
}
