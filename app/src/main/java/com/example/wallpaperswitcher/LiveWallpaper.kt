package com.example.wallpaperswitcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * רקע חי: התמונה הבאה נטענת מראש לזיכרון,
 * וכשהמסך נדלק היא נכנסת עם אנימציה (דהייה / זום / החלקה).
 */
class LiveWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = SwitchEngine()

    inner class SwitchEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val loader = Executors.newSingleThreadExecutor()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val matrix = Matrix()

        private var current: Bitmap? = null
        private var previous: Bitmap? = null
        private var pendingNext: Bitmap? = null

        private var animating = false
        private var animStart = 0L
        private var effect = Prefs.EFFECT_FADE
        private var visible = false
        private var surfaceW = 0
        private var surfaceH = 0

        private val frame = object : Runnable {
            override fun run() {
                draw()
                if (animating) handler.postDelayed(this, 16)
            }
        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val t = Prefs.getTrigger(c)
                val enabled = Prefs.isEnabled(c)
                val go = when (i.action) {
                    Intent.ACTION_SCREEN_OFF ->
                        enabled && (t == Prefs.TRIGGER_SCREEN_OFF || t == Prefs.TRIGGER_BOTH)
                    Intent.ACTION_SCREEN_ON ->
                        enabled && (t == Prefs.TRIGGER_SCREEN_ON || t == Prefs.TRIGGER_BOTH)
                    WallpaperChanger.ACTION_CHANGE_NOW -> true
                    else -> false
                }
                if (go) loadNext()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(WallpaperChanger.ACTION_CHANGE_NOW)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            loadCurrent()
        }

        override fun onDestroy() {
            try { unregisterReceiver(receiver) } catch (_: Exception) { }
            handler.removeCallbacksAndMessages(null)
            loader.shutdownNow()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceW = width
            surfaceH = height
            draw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            draw()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                if (pendingNext != null) startTransition() else draw()
            }
        }

        // ---------- טעינת תמונות ----------

        private fun loadCurrent() {
            val ctx = applicationContext
            loader.execute {
                val uri = WallpaperChanger.currentUri(ctx) ?: return@execute
                val bmp = WallpaperChanger.decodeScaled(ctx, Uri.parse(uri)) ?: return@execute
                handler.post {
                    current = bmp
                    draw()
                }
            }
        }

        private fun loadNext() {
            val ctx = applicationContext
            loader.execute {
                val uri = WallpaperChanger.pickNext(ctx) ?: return@execute
                val bmp = WallpaperChanger.decodeScaled(ctx, Uri.parse(uri)) ?: return@execute
                handler.post {
                    pendingNext = bmp
                    // אם המסך כבוי – האנימציה תרוץ כשהוא יידלק
                    if (visible) startTransition()
                }
            }
        }

        private fun startTransition() {
            val next = pendingNext ?: return
            pendingNext = null
            if (current == null) {
                current = next
                draw()
                return
            }
            previous = current
            current = next
            effect = when (val e = Prefs.getEffect(applicationContext)) {
                Prefs.EFFECT_RANDOM -> Random.nextInt(Prefs.EFFECT_FADE, Prefs.EFFECT_SLIDE + 1)
                Prefs.EFFECT_NONE -> Prefs.EFFECT_FADE
                else -> e
            }
            animStart = SystemClock.uptimeMillis()
            animating = true
            handler.removeCallbacks(frame)
            handler.post(frame)
        }

        // ---------- ציור ----------

        private fun draw() {
            val holder = surfaceHolder
            if (surfaceW == 0 || surfaceH == 0) return
            val canvas: Canvas = try {
                if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas()
            } catch (e: Exception) {
                null
            } ?: return

            val cur = current
            val prev = previous
            val t = if (animating)
                ((SystemClock.uptimeMillis() - animStart).toFloat() / DURATION_MS).coerceIn(0f, 1f)
            else 1f
            val e = 1f - (1f - t) * (1f - t) * (1f - t) // האטה בסוף – נראה חלק

            try {
                canvas.drawColor(Color.BLACK)
                if (animating && prev != null && cur != null) {
                    when (effect) {
                        Prefs.EFFECT_ZOOM -> {
                            drawBitmap(canvas, prev, 1f + 0.08f * e, 0f, 255)
                            drawBitmap(canvas, cur, 1.25f - 0.25f * e, 0f, (255 * e).toInt())
                        }
                        Prefs.EFFECT_SLIDE -> {
                            drawBitmap(canvas, prev, 1f, -surfaceW * e, 255)
                            drawBitmap(canvas, cur, 1f, surfaceW * (1f - e), 255)
                        }
                        else -> { // דהייה
                            drawBitmap(canvas, prev, 1f, 0f, 255)
                            drawBitmap(canvas, cur, 1f, 0f, (255 * e).toInt())
                        }
                    }
                } else if (cur != null) {
                    drawBitmap(canvas, cur, 1f, 0f, 255)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }

            if (animating && t >= 1f) {
                animating = false
                previous = null
            }
        }

        /** מצייר תמונה ממורכזת שממלאת את המסך (כמו center-crop) */
        private fun drawBitmap(canvas: Canvas, b: Bitmap, scale: Float, dx: Float, alpha: Int) {
            val s = maxOf(surfaceW.toFloat() / b.width, surfaceH.toFloat() / b.height) * scale
            matrix.reset()
            matrix.postScale(s, s)
            matrix.postTranslate((surfaceW - b.width * s) / 2f + dx, (surfaceH - b.height * s) / 2f)
            paint.alpha = alpha.coerceIn(0, 255)
            canvas.drawBitmap(b, matrix, paint)
        }
    }

    companion object {
        private const val DURATION_MS = 700f
    }
}
