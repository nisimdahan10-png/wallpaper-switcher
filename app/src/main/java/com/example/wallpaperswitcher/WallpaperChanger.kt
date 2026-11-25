package com.example.wallpaperswitcher

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import kotlin.random.Random

/** בוחר את התמונה הבאה ומגדיר אותה כרקע */
object WallpaperChanger {
    private const val TAG = "WallpaperChanger"
    const val ACTION_CHANGE_NOW = "com.example.wallpaperswitcher.CHANGE_NOW"
    private val executor = Executors.newSingleThreadExecutor()

    fun changeAsync(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())

        if (Prefs.isLive(app)) {
            // מצב אפקטים – הרקע החי מחליף בעצמו עם אנימציה
            app.sendBroadcast(Intent(ACTION_CHANGE_NOW).setPackage(app.packageName))
            onDone?.let { main.postDelayed(it, 500) }
            return
        }

        executor.execute {
            try {
                change(app)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change wallpaper", e)
            }
            onDone?.let { main.post(it) }
        }
    }

    /** בוחר את האינדקס הבא, שומר אותו ומחזיר את כתובת התמונה */
    fun pickNext(c: Context): String? {
        val uris = Prefs.getUris(c)
        if (uris.isEmpty()) return null
        val current = Prefs.getIndex(c)
        val next = if (Prefs.isRandom(c) && uris.size > 1) {
            var r: Int
            do { r = Random.nextInt(uris.size) } while (r == current)
            r
        } else {
            (current + 1).mod(uris.size)
        }
        Prefs.setIndex(c, next)
        return uris[next]
    }

    /** התמונה שמוצגת עכשיו (או הראשונה) */
    fun currentUri(c: Context): String? {
        val uris = Prefs.getUris(c)
        return uris.getOrNull(Prefs.getIndex(c)) ?: uris.firstOrNull()
    }

    private fun change(c: Context) {
        val wm = WallpaperManager.getInstance(c)
        val prevUri = if (Prefs.getIndex(c) >= 0) currentUri(c) else null
        val uri = pickNext(c) ?: return
        val bitmap = decodeScaled(c, Uri.parse(uri)) ?: run {
            Log.w(TAG, "Could not read image $uri")
            return
        }
        when (Prefs.getTarget(c)) {
            Prefs.TARGET_HOME -> {
                // אם למסך הנעילה אין רקע משלו הוא "נגרר" אחרי מסך הבית –
                // לכן קודם מקבעים לו את התמונה הקודמת
                if (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0 && prevUri != null) {
                    decodeScaled(c, Uri.parse(prevUri))?.let {
                        wm.setBitmap(it, null, true, WallpaperManager.FLAG_LOCK)
                        it.recycle()
                    }
                }
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            }
            Prefs.TARGET_LOCK ->
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            else -> {
                // שתי קריאות נפרדות – בחלק מהמכשירים (סמסונג) קריאה משולבת מעדכנת רק מסך אחד
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            }
        }
        bitmap.recycle()
    }

    /** מגדיר רק את מסך הנעילה (בשימוש ברקע חי) */
    fun setLock(c: Context, bitmap: Bitmap) {
        try {
            WallpaperManager.getInstance(c).setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
        } catch (e: Exception) {
            Log.e(TAG, "lock set failed", e)
        }
    }

    /**
     * בכניסה למצב אפקטים: "שניהם" – מסך הנעילה מקבל את התמונה הנוכחית;
     * "רק מסך בית" – מקבעים למסך הנעילה תמונה משלו כדי שלא יתחלף.
     */
    fun prepareLockForLive(context: Context) {
        val c = context.applicationContext
        executor.execute {
            val target = Prefs.getTarget(c)
            val wm = WallpaperManager.getInstance(c)
            val needs = target == Prefs.TARGET_BOTH ||
                (target == Prefs.TARGET_HOME && wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0)
            if (!needs) return@execute
            val uri = currentUri(c) ?: return@execute
            decodeScaled(c, Uri.parse(uri))?.let { setLock(c, it); it.recycle() }
        }
    }

    /** טוען תמונה מוקטנת לגודל המסך כדי לא לגמור את הזיכרון */
    fun decodeScaled(c: Context, uri: Uri): Bitmap? = try {
        val dm = c.resources.displayMetrics
        val reqW = minOf(dm.widthPixels, dm.heightPixels)
        val reqH = maxOf(dm.widthPixels, dm.heightPixels)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        Log.e(TAG, "decode failed", e)
        null
    }
}
