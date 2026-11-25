package com.example.wallpaperswitcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.util.concurrent.Executors
import kotlin.random.Random

/** בוחר את התמונה הבאה ומגדיר אותה כרקע */
object WallpaperChanger {
    private const val TAG = "WallpaperChanger"
    private val executor = Executors.newSingleThreadExecutor()

    fun changeAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            try {
                change(app)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change wallpaper", e)
            }
        }
    }

    private fun change(c: Context) {
        val uris = Prefs.getUris(c)
        if (uris.isEmpty()) return

        val current = Prefs.getIndex(c)
        val next = if (Prefs.isRandom(c) && uris.size > 1) {
            var r: Int
            do { r = Random.nextInt(uris.size) } while (r == current)
            r
        } else {
            (current + 1).mod(uris.size)
        }
        Prefs.setIndex(c, next)

        val bitmap = decodeScaled(c, Uri.parse(uris[next])) ?: run {
            Log.w(TAG, "Could not read image ${uris[next]}")
            return
        }

        val flags = when (Prefs.getTarget(c)) {
            Prefs.TARGET_HOME -> WallpaperManager.FLAG_SYSTEM
            Prefs.TARGET_LOCK -> WallpaperManager.FLAG_LOCK
            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        WallpaperManager.getInstance(c).setBitmap(bitmap, null, true, flags)
        bitmap.recycle()
    }

    /** טוען תמונה מוקטנת לגודל המסך כדי לא לגמור את הזיכרון */
    private fun decodeScaled(c: Context, uri: Uri): Bitmap? {
        val dm = c.resources.displayMetrics
        val reqW = minOf(dm.widthPixels, dm.heightPixels)
        val reqH = maxOf(dm.widthPixels, dm.heightPixels)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
