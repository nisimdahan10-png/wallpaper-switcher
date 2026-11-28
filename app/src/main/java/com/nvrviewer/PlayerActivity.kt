package com.nvrviewer

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single camera, full screen. Live mode (extra "ch") or recording playback
 * (extras "ch", "start", "end" in epoch millis).
 */
class PlayerActivity : AppCompatActivity() {
    private lateinit var cfg: NvrConfig
    private lateinit var video: VLCVideoLayout
    private lateinit var status: TextView
    private lateinit var btnQuality: Button
    private lateinit var btnRecord: Button
    private var player: MediaPlayer? = null

    private var ch = 1
    private var main = true
    private var startMs = 0L
    private var endMs = 0L
    private val isPlayback get() = startMs > 0
    private var recordFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        cfg = Prefs.load(this)
        ch = intent.getIntExtra("ch", 1)
        startMs = intent.getLongExtra("start", 0L)
        endMs = intent.getLongExtra("end", 0L)

        video = findViewById(R.id.video)
        status = findViewById(R.id.status)
        btnQuality = findViewById(R.id.btnQuality)
        btnRecord = findViewById(R.id.btnRecord)

        btnQuality.setOnClickListener { main = !main; start() }
        findViewById<Button>(R.id.btnSnapshot).setOnClickListener { snapshot() }
        btnRecord.setOnClickListener { if (recordFile == null) startRecording() else stopRecording() }
        findViewById<Button>(R.id.btnPrevCh).setOnClickListener { switchChannel(-1) }
        findViewById<Button>(R.id.btnNextCh).setOnClickListener { switchChannel(+1) }

        val back = findViewById<Button>(R.id.btnBack10)
        val fwd = findViewById<Button>(R.id.btnFwd10)
        if (isPlayback) {
            btnQuality.visibility = View.GONE
            back.setOnClickListener { shift(-10) }
            fwd.setOnClickListener { shift(+10) }
        } else {
            back.visibility = View.GONE
            fwd.visibility = View.GONE
        }
    }

    override fun onStart() { super.onStart(); start() }

    override fun onStop() {
        super.onStop()
        if (recordFile != null) stopRecording(restart = false) else releasePlayer()
    }

    private fun title(): String {
        val base = "מצלמה $ch"
        if (!isPlayback) return "$base • שידור חי • ${if (main) "איכות גבוהה" else "איכות נמוכה"}"
        val f = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        return "$base • הקלטה מ-${f.format(Date(startMs))}"
    }

    private fun currentUrl(): String? =
        if (isPlayback) cfg.playbackUrl(ch, startMs, endMs) else cfg.liveUrl(ch, main)

    private fun start(extraOptions: List<String> = emptyList()) {
        releasePlayer()
        val url = currentUrl() ?: run { status.text = "היצרן הזה לא תומך בצפייה בהקלטות"; return }
        btnQuality.text = if (main) "HD" else "SD"
        status.text = "${title()} • מתחבר…"

        val mp = MediaPlayer(Vlc.get(this))
        // TextureView is required so we can grab a bitmap for snapshots
        mp.attachViews(video, null, false, true)
        mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> status.text = title() + if (recordFile != null) " • ● מקליט" else ""
                MediaPlayer.Event.Buffering -> if (e.buffering < 100f) status.text = "${title()} • טוען ${e.buffering.toInt()}%"
                MediaPlayer.Event.EncounteredError -> status.text = "שגיאה בחיבור – בדוק כתובת, סיסמה ופורט\n${cfg.masked(url)}"
                MediaPlayer.Event.EndReached -> if (isPlayback) status.text = "סוף ההקלטה"
            }
        }
        val m = Vlc.media(this, url, cfg, extraOptions)
        mp.media = m
        m.release()
        mp.play()
        player = mp
    }

    private fun releasePlayer() {
        player?.let { it.setEventListener(null); it.stop(); it.detachViews(); it.release() }
        player = null
    }

    private fun switchChannel(d: Int) {
        if (recordFile != null) stopRecording(restart = false)
        ch = ((ch - 1 + d + cfg.channels) % cfg.channels) + 1
        start()
    }

    private fun shift(minutes: Int) {
        val delta = minutes * 60_000L
        startMs += delta; endMs += delta
        start()
    }

    // ---------- Snapshot ----------
    private fun findTexture(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTexture(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun snapshot() {
        val bmp = findTexture(video)?.bitmap ?: run { toast("אין תמונה לצילום"); return }
        val name = "cam${ch}_${stamp()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NVR")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("שמירה נכשלה"); return }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        toast("נשמר בגלריה: Pictures/NVR")
    }

    // ---------- Recording to phone ----------
    private fun startRecording() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val f = File(dir, "cam${ch}_${stamp()}.ts")
        recordFile = f
        btnRecord.text = "■ עצור"
        // Duplicate the stream: show it on screen AND write it to a file, without re-encoding
        start(listOf(
            ":sout=#duplicate{dst=display,dst=std{access=file,mux=ts,dst='${f.absolutePath}'}}",
            ":sout-keep"
        ))
    }

    private fun stopRecording(restart: Boolean = true) {
        val f = recordFile ?: return
        recordFile = null
        btnRecord.text = "● הקלט"
        releasePlayer() // closes the file
        Thread {
            val ok = f.exists() && f.length() > 0 && copyToGallery(f)
            f.delete()
            runOnUiThread {
                toast(if (ok) "ההקלטה נשמרה: Movies/NVR" else "ההקלטה נכשלה")
                if (restart && !isFinishing) start()
            }
        }.start()
    }

    private fun copyToGallery(f: File): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NVR")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: return false
        return true
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
