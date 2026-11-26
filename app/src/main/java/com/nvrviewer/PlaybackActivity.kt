package com.nvrviewer

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Pick a camera + date/time and play the recording stored on the NVR. */
class PlaybackActivity : AppCompatActivity() {
    private val cal = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -1); set(Calendar.SECOND, 0) }
    private lateinit var btnDate: Button
    private lateinit var btnTime: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        val cfg = Prefs.load(this)

        val note = findViewById<TextView>(R.id.note)
        if (!cfg.brand.supportsPlayback) {
            note.text = "צפייה בהקלטות דרך RTSP נתמכת רק ב-Hikvision, Dahua ו-Uniview. ביצרן שבחרת – השתמש באפליקציה של היצרן לצפייה בהקלטות."
        }

        val chSpinner = findViewById<Spinner>(R.id.spChannel)
        chSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            (1..cfg.channels).map { "מצלמה $it" })

        val durations = listOf(15, 30, 60, 120)
        val durSpinner = findViewById<Spinner>(R.id.spDuration)
        durSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            durations.map { "$it דקות" })
        durSpinner.setSelection(2)

        btnDate = findViewById(R.id.btnDate)
        btnTime = findViewById(R.id.btnTime)
        refresh()

        btnDate.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d); refresh() },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        btnTime.setOnClickListener {
            TimePickerDialog(this, { _, h, min -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); refresh() },
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }

        findViewById<Button>(R.id.btnPlay).setOnClickListener {
            val start = cal.timeInMillis
            val end = start + durations[durSpinner.selectedItemPosition] * 60_000L
            startActivity(Intent(this, PlayerActivity::class.java)
                .putExtra("ch", chSpinner.selectedItemPosition + 1)
                .putExtra("start", start).putExtra("end", end))
        }
    }

    private fun refresh() {
        btnDate.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(cal.time)
        btnTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
    }
}
