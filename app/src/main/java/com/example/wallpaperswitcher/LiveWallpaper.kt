package com.example.wallpaperswitcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
        private val matrix3d = Matrix()
        private val camera = Camera().apply { setLocation(0f, 0f, -24f) }
        private val clip = Path()
        private var blurred: Bitmap? = null // גרסה מטושטשת של התמונה החדשה (לאפקט טשטוש)

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
                if (!Prefs.isLive(c)) return
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
                if (go) loadNext(i.getIntExtra(WallpaperChanger.EXTRA_INDEX, -1).takeIf { it >= 0 })
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

        private fun loadNext(force: Int? = null) {
            val ctx = applicationContext
            loader.execute {
                val bmp = WallpaperChanger.nextBitmap(ctx, force) ?: return@execute
                // "שניהם" – מסך הנעילה מקבל את אותה תמונה (בלי אנימציה)
                if (Prefs.getTarget(ctx) == Prefs.TARGET_BOTH) WallpaperChanger.setLock(ctx, bmp)
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
                Prefs.EFFECT_RANDOM -> Prefs.EFFECTS[Random.nextInt(Prefs.EFFECTS.size)].first
                Prefs.EFFECT_NONE -> Prefs.EFFECT_FADE
                else -> e
            }
            blurred = if (effect == Prefs.EFFECT_BLUR) makeBlurred(next) else null
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
                ((SystemClock.uptimeMillis() - animStart).toFloat() / durationFor(effect)).coerceIn(0f, 1f)
            else 1f
            val e = 1f - (1f - t) * (1f - t) * (1f - t) // האטה בסוף – נראה חלק

            try {
                canvas.drawColor(Color.BLACK)
                if (animating && prev != null && cur != null) {
                    drawTransition(canvas, prev, cur, t, e)
                } else if (cur != null) {
                    drawBitmap(canvas, cur, 1f, 0f, 255)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }

            if (animating && t >= 1f) {
                animating = false
                previous = null
                blurred = null
            }
        }

        private fun ease(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return 1f - (1f - c) * (1f - c) * (1f - c)
        }

        /** t = התקדמות לינארית 0..1, e = התקדמות עם האטה */
        private fun drawTransition(canvas: Canvas, prev: Bitmap, cur: Bitmap, t: Float, e: Float) {
            val w = surfaceW.toFloat()
            val h = surfaceH.toFloat()
            when (effect) {
                Prefs.EFFECT_ZOOM -> {
                    drawBitmap(canvas, prev, 1f + 0.08f * e, 0f, 255)
                    drawBitmap(canvas, cur, 1.25f - 0.25f * e, 0f, (255 * e).toInt())
                }
                Prefs.EFFECT_SLIDE -> {
                    drawBitmap(canvas, prev, 1f, -w * e, 255)
                    drawBitmap(canvas, cur, 1f, w * (1f - e), 255)
                }
                Prefs.EFFECT_ROTATE -> {
                    drawBitmap(canvas, prev, 1f, 0f, 255)
                    canvas.save()
                    canvas.rotate(-15f * (1f - e), w / 2f, h / 2f)
                    drawBitmap(canvas, cur, 1.35f - 0.35f * e, 0f, (255 * e).toInt())
                    canvas.restore()
                }
                Prefs.EFFECT_CIRCLE -> {
                    drawBitmap(canvas, prev, 1f, 0f, 255)
                    val r = Math.hypot(w.toDouble(), h.toDouble()).toFloat() / 2f * e
                    clip.reset()
                    clip.addCircle(w / 2f, h / 2f, r, Path.Direction.CW)
                    canvas.save()
                    canvas.clipPath(clip)
                    drawBitmap(canvas, cur, 1.1f - 0.1f * e, 0f, 255)
                    canvas.restore()
                }
                Prefs.EFFECT_WIPE -> {
                    drawBitmap(canvas, prev, 1f, 0f, 255)
                    canvas.save()
                    canvas.clipRect(0f, 0f, w, h * e)
                    drawBitmap(canvas, cur, 1f, 0f, 255)
                    canvas.restore()
                }
                Prefs.EFFECT_BLUR -> {
                    // קודם התמונה החדשה מופיעה מטושטשת, ואז מתחדדת
                    drawBitmap(canvas, prev, 1f, 0f, 255)
                    blurred?.let { drawBitmap(canvas, it, 1.05f, 0f, (255 * ease(t / 0.4f)).toInt()) }
                    drawBitmap(canvas, cur, 1f, 0f, (255 * ease((t - 0.35f) / 0.65f)).toInt())
                }
                Prefs.EFFECT_FLIP -> {
                    // חצי ראשון: הישנה מסתובבת הצידה, חצי שני: החדשה מסתובבת פנימה
                    if (t < 0.5f) drawFlipped(canvas, prev, 180f * t)
                    else drawFlipped(canvas, cur, 180f * t - 180f)
                }
                Prefs.EFFECT_BLINDS -> {
                    drawBitmap(canvas, prev, 1f, 0f, 255)
                    val n = 8
                    val strip = h / n
                    clip.reset()
                    for (i in 0 until n) {
                        val local = ease((t - i * 0.04f) / 0.72f)
                        val top = i * strip
                        clip.addRect(0f, top, w, top + strip * local + 1f, Path.Direction.CW)
                    }
                    canvas.save()
                    canvas.clipPath(clip)
                    drawBitmap(canvas, cur, 1f, 0f, 255)
                    canvas.restore()
                }
                else -> { // דהייה
                    drawBitmap(canvas, prev, 1f, 0f, 255)
                    drawBitmap(canvas, cur, 1f, 0f, (255 * e).toInt())
                }
            }
        }

        private fun drawFlipped(canvas: Canvas, b: Bitmap, angle: Float) {
            camera.save()
            camera.rotateY(angle)
            camera.getMatrix(matrix3d)
            camera.restore()
            matrix3d.preTranslate(-surfaceW / 2f, -surfaceH / 2f)
            matrix3d.postTranslate(surfaceW / 2f, surfaceH / 2f)
            canvas.save()
            canvas.concat(matrix3d)
            drawBitmap(canvas, b, 1f, 0f, 255)
            canvas.restore()
        }

        /** טשטוש זול: מקטינים מאוד ומגדילים חזרה עם החלקה */
        private fun makeBlurred(b: Bitmap): Bitmap? = try {
            val small = Bitmap.createScaledBitmap(b, maxOf(1, b.width / 12), maxOf(1, b.height / 12), true)
            Bitmap.createScaledBitmap(small, maxOf(1, b.width / 40), maxOf(1, b.height / 40), true)
        } catch (e: Exception) {
            null
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
        private fun durationFor(effect: Int) = when (effect) {
            Prefs.EFFECT_FLIP, Prefs.EFFECT_BLINDS, Prefs.EFFECT_BLUR -> 900f
            else -> 700f
        }
    }
}
