package com.nvrviewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tests RTSP connections with libVLC. Needs a (tiny, hidden) VLCVideoLayout
 * so the player has somewhere to render — without it libVLC can crash.
 */
class Prober(private val ctx: Context, private val view: VLCVideoLayout) {
    private val handler = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    @Volatile var cancelled = false

    fun cancel() { cancelled = true; stop() }

    private fun stop() {
        handler.removeCallbacksAndMessages(null)
        mp?.let { it.setEventListener(null); it.stop(); it.detachViews(); it.release() }
        mp = null
    }

    /** Does this URL deliver a stream? */
    fun test(cfg: NvrConfig, url: String, timeoutMs: Long = 7000, done: (Boolean) -> Unit) {
        stop()
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            handler.post { stop(); if (!cancelled) done(ok) }
        }
        val p = MediaPlayer(Vlc.get(ctx))
        p.attachViews(view, null, false, false)
        p.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.Vout -> finish(true)
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> finish(false)
            }
        }
        val m = Vlc.media(ctx, url, cfg, listOf(":no-audio"))
        p.media = m
        m.release()
        p.play()
        mp = p
        handler.postDelayed({ finish(false) }, timeoutMs)
    }

    sealed class Result {
        data class Ok(val brand: Brand, val channels: Int) : Result()
        object NoDevice : Result()
        object PortClosed : Result()
        object BadLogin : Result()
    }

    /**
     * Full auto-connect: checks the host answers, finds the brand (trying the
     * scanner's guess first), then counts working channels.
     */
    fun autoConnect(base: NvrConfig, hint: Brand?, progress: (String) -> Unit, done: (Result) -> Unit) {
        cancelled = false
        progress("בודק שה-NVR עונה…")
        Thread {
            val rtsp = reachable(base.host, base.port)
            val any = rtsp || reachable(base.host, 80)
            handler.post {
                if (cancelled) return@post
                when {
                    !any -> done(Result.NoDevice)
                    !rtsp -> done(Result.PortClosed)
                    else -> findBrand(base, hint, progress, done)
                }
            }
        }.start()
    }

    private fun findBrand(base: NvrConfig, hint: Brand?, progress: (String) -> Unit, done: (Result) -> Unit) {
        val order = listOfNotNull(hint) +
            listOf(Brand.HIKVISION, Brand.DAHUA, Brand.UNIVIEW, Brand.REOLINK).filter { it != hint }
        fun at(i: Int) {
            if (i >= order.size) { done(Result.BadLogin); return }
            val b = order[i]
            val cfg = base.withBrand(b)
            progress("מתחבר… בודק ${b.label} (${i + 1}/${order.size})")
            test(cfg, cfg.liveUrl(1, main = false)) { ok ->
                if (ok) countChannels(cfg, progress) { n -> done(Result.Ok(b, n)) }
                else at(i + 1)
            }
        }
        at(0)
    }

    /** Tries channel 2, 3, … until one fails (max 32). */
    private fun countChannels(cfg: NvrConfig, progress: (String) -> Unit, done: (Int) -> Unit) {
        fun at(ch: Int) {
            if (ch > 32) { done(32); return }
            progress("✓ מחובר! סופר מצלמות… נמצאו ${ch - 1}")
            test(cfg, cfg.liveUrl(ch, main = false), timeoutMs = 5000) { ok ->
                if (ok) at(ch + 1) else done(ch - 1)
            }
        }
        at(2)
    }

    companion object {
        fun reachable(host: String, port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(host, port), 3000) }; true
        } catch (e: Exception) { false }

        fun message(r: Result, host: String, port: Int): String = when (r) {
            is Result.Ok -> "✓ מחובר ל-${r.brand.label} • ${r.channels} מצלמות"
            Result.NoDevice -> "✗ אין מכשיר שעונה בכתובת $host.\n• ודא שהטלפון מחובר ל-WiFi של הבית\n• בדוק את כתובת ה-IP"
            Result.PortClosed -> "✗ ה-NVR נמצא, אבל פורט $port (RTSP) סגור.\nהפעל RTSP בהגדרות ה-NVR (רשת ← מתקדם) ובדוק את מספר הפורט."
            Result.BadLogin -> "✗ ה-NVR עונה, אבל ההתחברות נכשלה.\nכנראה שם המשתמש או הסיסמה שגויים (שים לב לאותיות גדולות/קטנות). אחרי כמה ניסיונות כושלים ה-NVR עלול לחסום ל-30 דקות."
        }
    }
}
