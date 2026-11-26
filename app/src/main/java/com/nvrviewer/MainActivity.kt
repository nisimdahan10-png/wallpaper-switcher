package com.nvrviewer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.ceil
import kotlin.math.sqrt

/** Multi-camera live grid, using the low-quality sub-stream to save bandwidth. */
class MainActivity : AppCompatActivity() {
    private lateinit var grid: GridLayout
    private lateinit var pageLabel: TextView
    private val players = mutableListOf<MediaPlayer>()
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        pageLabel = findViewById(R.id.pageLabel)
        page = savedInstanceState?.getInt("page") ?: 0

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnPlayback).setOnClickListener {
            startActivity(Intent(this, PlaybackActivity::class.java))
        }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { changePage(-1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { changePage(+1) }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("page", page)
    }

    override fun onStart() {
        super.onStart()
        if (!Prefs.isConfigured(this)) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        grid.post { showPage() }
    }

    override fun onStop() {
        super.onStop()
        releasePlayers()
    }

    private fun pages(): Int {
        val c = Prefs.load(this)
        return ceil(c.channels / c.gridSize.toDouble()).toInt().coerceAtLeast(1)
    }

    private fun changePage(d: Int) {
        val n = pages()
        page = (page + d + n) % n
        showPage()
    }

    private fun showPage() {
        releasePlayers()
        val cfg = Prefs.load(this)
        val cols = sqrt(cfg.gridSize.toDouble()).toInt().coerceAtLeast(1)
        val n = pages()
        if (page >= n) page = 0
        pageLabel.text = "עמוד ${page + 1} / $n"

        grid.removeAllViews()
        grid.columnCount = cols
        grid.rowCount = cols

        val first = page * cfg.gridSize + 1
        for (i in 0 until cfg.gridSize) {
            val ch = first + i
            val cell = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#111111"))
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply { width = 0; height = 0; setMargins(2, 2, 2, 2) }
            }
            grid.addView(cell)
            if (ch > cfg.channels) continue

            val video = VLCVideoLayout(this)
            cell.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val label = TextView(this).apply {
                text = "מצלמה $ch"
                setTextColor(Color.WHITE)
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
                setPadding(12, 6, 12, 6)
            }
            cell.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

            // Transparent overlay to catch taps (SurfaceView swallows them)
            val touch = FrameLayout(this).apply {
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, PlayerActivity::class.java).putExtra("ch", ch))
                }
            }
            cell.addView(touch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val mp = MediaPlayer(Vlc.get(this))
            mp.attachViews(video, null, false, false)
            mp.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            mp.setEventListener { e ->
                if (e.type == MediaPlayer.Event.EncounteredError) label.text = "מצלמה $ch – אין חיבור"
            }
            val m = Vlc.media(this, cfg.liveUrl(ch, main = cfg.gridSize == 1), cfg, listOf(":no-audio"))
            mp.media = m
            m.release()
            mp.play()
            players += mp
        }
    }

    private fun releasePlayers() {
        players.forEach { it.stop(); it.detachViews(); it.release() }
        players.clear()
    }
}
