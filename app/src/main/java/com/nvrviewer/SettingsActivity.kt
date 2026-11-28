package com.nvrviewer

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.InetSocketAddress
import java.net.Socket

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
    private lateinit var probeView: VLCVideoLayout

    private val grids = listOf(1, 4, 9)
    private val handler = Handler(Looper.getMainLooper())
    private var probe: MediaPlayer? = null

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        data.getStringExtra("host")?.let { etHost.setText(it) }
        etPort.setText("554")
        val brand = data.getStringExtra("brand")?.let { runCatching { Brand.valueOf(it) }.getOrNull() }
        if (brand != null) {
            spBrand.setSelection(brand.ordinal)
            result.text = "✓ נבחר ${etHost.text} (${brand.label}). הכנס סיסמה ולחץ שמירה."
        } else {
            result.text = "נבחר ${etHost.text}. הכנס סיסמה ולחץ \"זהה יצרן אוטומטית\"."
        }
        etPass.requestFocus()
    }

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
        probeView = findViewById(R.id.probeView)

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
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scanLauncher.launch(Intent(this, ScanActivity::class.java))
        }
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

    private fun reachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 3000) }; true
    } catch (e: Exception) { false }

    /** Checks the NVR answers, then tries each known brand's URL on channel 1. */
    private fun detect() {
        val base = read()
        if (base.host.isBlank()) { etHost.error = "חובה"; return }
        result.text = "בודק אם ה-NVR עונה בכתובת ${base.host}…"
        Thread {
            val rtsp = reachable(base.host, base.port)
            val web = if (rtsp) true else reachable(base.host, 80)
            runOnUiThread {
                when {
                    !rtsp && !web -> result.text =
                        "✗ אין שום מכשיר שעונה בכתובת ${base.host}.\n• ודא שהטלפון מחובר ל-WiFi של הבית\n• בדוק שוב את כתובת ה-IP (ב-NVR: תפריט ← רשת)"
                    !rtsp -> result.text =
                        "✗ ה-NVR נמצא, אבל פורט ${base.port} (RTSP) סגור.\nהפעל RTSP בהגדרות ה-NVR (רשת ← מתקדם / פורט RTSP) ובדוק את מספר הפורט."
                    else -> tryBrands(base)
                }
            }
        }.start()
    }

    private fun tryBrands(base: NvrConfig) {
        val candidates = listOf(Brand.HIKVISION, Brand.DAHUA, Brand.UNIVIEW, Brand.REOLINK)
        fun tryAt(i: Int) {
            if (i >= candidates.size) {
                result.text = "✗ ה-NVR עונה בפורט ${base.port}, אבל אף פורמט לא עבד.\nכנראה שם המשתמש או הסיסמה שגויים (שים לב לאותיות גדולות/קטנות), או שהמשתמש חסום אחרי ניסיונות כושלים – חכה 30 דקות."
                return
            }
            val b = candidates[i]
            result.text = "✓ ה-NVR עונה. בודק ${b.label}… (${i + 1}/${candidates.size})"
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
        mp.attachViews(probeView, null, false, false)
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
        probe?.let { it.setEventListener(null); it.stop(); it.detachViews(); it.release() }
        probe = null
    }
}
