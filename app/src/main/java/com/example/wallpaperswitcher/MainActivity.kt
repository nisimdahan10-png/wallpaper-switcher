package com.example.wallpaperswitcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var countText: TextView
    private lateinit var enabledSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        countText = findViewById(R.id.countText)
        enabledSwitch = findViewById(R.id.enabledSwitch)

        findViewById<Button>(R.id.pickButton).setOnClickListener { pickImages() }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            Prefs.setUris(this, emptyList())
            Prefs.setIndex(this, -1)
            setEnabled(false)
            refresh()
        }

        findViewById<Button>(R.id.nowButton).setOnClickListener {
            if (Prefs.getUris(this).isEmpty()) toast("קודם בחר תמונות")
            else { WallpaperChanger.changeAsync(this); toast("מחליף רקע…") }
        }

        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        val randomBox = findViewById<CheckBox>(R.id.randomBox)
        randomBox.isChecked = Prefs.isRandom(this)
        randomBox.setOnCheckedChangeListener { _, checked -> Prefs.setRandom(this, checked) }

        val triggerGroup = findViewById<RadioGroup>(R.id.triggerGroup)
        triggerGroup.check(
            when (Prefs.getTrigger(this)) {
                Prefs.TRIGGER_SCREEN_ON -> R.id.triggerOn
                Prefs.TRIGGER_BOTH -> R.id.triggerBoth
                else -> R.id.triggerOff
            }
        )
        triggerGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setTrigger(
                this, when (id) {
                    R.id.triggerOn -> Prefs.TRIGGER_SCREEN_ON
                    R.id.triggerBoth -> Prefs.TRIGGER_BOTH
                    else -> Prefs.TRIGGER_SCREEN_OFF
                }
            )
        }

        val targetGroup = findViewById<RadioGroup>(R.id.targetGroup)
        targetGroup.check(
            when (Prefs.getTarget(this)) {
                Prefs.TARGET_HOME -> R.id.targetHome
                Prefs.TARGET_LOCK -> R.id.targetLock
                else -> R.id.targetBoth
            }
        )
        targetGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setTarget(
                this, when (id) {
                    R.id.targetHome -> Prefs.TARGET_HOME
                    R.id.targetLock -> Prefs.TARGET_LOCK
                    else -> Prefs.TARGET_BOTH
                }
            )
        }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && Prefs.getUris(this).isEmpty()) {
                toast("קודם בחר תמונות")
                enabledSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            setEnabled(checked)
        }

        refresh()
    }

    private fun setEnabled(on: Boolean) {
        Prefs.setEnabled(this, on)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
            SwitcherService.start(this)
        } else {
            SwitcherService.stop(this)
        }
        if (enabledSwitch.isChecked != on) enabledSwitch.isChecked = on
    }

    /** בוחר התמונות החדש של אנדרואיד – עם תיבת סימון על כל תמונה */
    private fun photoPickerAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 33 ||
            (Build.VERSION.SDK_INT >= 30 &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2)

    private fun pickImages() {
        val intent = if (photoPickerAvailable()) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
            }
        } else {
            toast("לבחירת כמה תמונות: לחיצה ארוכה על הראשונה, ואז נגיעה בשאר")
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            // גיבוי אם בוחר התמונות החדש לא זמין במכשיר
            val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(fallback, REQ_PICK)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return

        val picked = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) picked.add(clip.getItemAt(i).uri)
        } ?: data.data?.let { picked.add(it) }

        val list = Prefs.getUris(this).toMutableList()
        for (uri in picked) {
            try {
                // שומר הרשאת קריאה קבועה כדי שהתמונה תהיה זמינה גם אחרי הפעלה מחדש
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
            val s = uri.toString()
            if (s !in list) list.add(s)
        }
        Prefs.setUris(this, list)
        toast("נוספו ${picked.size} תמונות")
        refresh()
    }

    private fun refresh() {
        val n = Prefs.getUris(this).size
        countText.text = if (n == 0) "לא נבחרו תמונות" else "נבחרו $n תמונות"
        enabledSwitch.isChecked = Prefs.isEnabled(this)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PICK = 100
        private const val REQ_NOTIF = 101
    }
}
