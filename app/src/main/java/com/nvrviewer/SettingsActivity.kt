package com.nvrviewer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.MediaPlayer

class SettingsActivity : AppCompatActivity() {
    private lateinit var spBrand: Spinner
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etChannels: EditText
    private lateinit var spGrid: Spinner
    private lateinit var etTemplate: EditText
    private lateinit var tplLabel: TextView
    private lateinit var result: TextView

    private val grids = listOf(1, 4, 9)
    private val handler = Handler(Looper.getMainLooper())
    private var probe: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        spBrand = findViewById(R.id.spBrand)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etChannels = findViewById(R.id.etChannels)
        spGrid = findViewById(R.id.spGrid)
        etTemplate = findViewById(R.id.etTemplate)
        tplLabel = findViewById(R.id.tplLabel)
        result = findViewById(R.id.result)

        spBrand.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Brand.values().map { it.label })
        spGrid.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("מצלמה אחת במסך", "4 מצלמות (2×2)", "9 מצלמות (3×3)"))

        val c = Prefs.load(this)
        spBrand.setSelection(c.brand.ordinal)
        etHost.setText(c.host)
        etPort.setText(c.port.toString())
        etUser.setText(c.user)
        etPass.setText(c.pass)
        etChannels.setText(c.channels.toString())
        spGrid.setSelection(grids.indexOf(c.gridSize).coerceAtLeast(0))
        etTemplate.setText(c.customTemplate)

        spBrand.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val custom = Brand.values()[pos] == Brand.CUSTOM
                etTemplate.visibility = if (custom) View.VISIBLE else View.GONE
                tplLabel.visibility = etTemplate.visibility
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnDetect).setOnClickListener { detect() }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (etHost.text.isBlank()) { etHost.error = "חובה"; return@setOnClickListener }
            Prefs.save(this, read())
            Toast.makeText(this, "נשמר", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() { super.onDestroy(); stopProbe() }

    private fun read() = NvrConfig(
        brand = Brand.values()[spBrand.selectedItemPosition],
        host = etHost.text.toString().trim()
            .removePrefix("rtsp://").removePrefix("http://").removePrefix("https://").trimEnd('/'),
        port = etPort.text.toString().toIntOrNull() ?: 554,
        user = etUser.text.toString().trim(),
        pass = etPass.text.toString(),
        channels = (etChannels.text.toString().toIntOrNull() ?: 4).coerceIn(1, 64),
        gridSize = grids[spGrid.selectedItemPosition],
        customTemplate = etTemplate.text.toString().trim(),
        caching = 600
    )

    /** Tries each known brand's URL on channel 1 and picks the first one that answers. */
    private fun detect() {
        val base = read()
        if (base.host.isBlank()) { etHost.error = "חובה"; return }
        val candidates = listOf(Brand.HIKVISION, Brand.DAHUA, Brand.UNIVIEW, Brand.REOLINK)
        fun tryAt(i: Int) {
            if (i >= candidates.size) {
                result.text = "לא נמצא חיבור. בדוק: כתובת IP, פורט RTSP (בדרך כלל 554), שם משתמש וסיסמה, ושה-RTSP מופעל ב-NVR."
                return
            }
            val b = candidates[i]
            result.text = "בודק ${b.label}…"
            probe(base.withBrand(b)) { ok ->
                if (ok) {
                    spBrand.setSelection(b.ordinal)
                    result.text = "✓ נמצא! ה-NVR מתאים ל-${b.label}. לחץ שמירה."
                } else tryAt(i + 1)
            }
        }
        tryAt(0)
    }

    private fun probe(cfg: NvrConfig, done: (Boolean) -> Unit) {
        stopProbe()
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            handler.post { stopProbe(); done(ok) }
        }
        val mp = MediaPlayer(Vlc.get(this))
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.Vout -> finish(true)
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> finish(false)
            }
        }
        val m = Vlc.media(this, cfg.liveUrl(1, main = false), cfg, listOf(":no-audio"))
        mp.media = m
        m.release()
        mp.play()
        probe = mp
        handler.postDelayed({ finish(false) }, 8000)
    }

    private fun stopProbe() {
        probe?.let { it.stop(); it.release() }
        probe = null
    }
}
