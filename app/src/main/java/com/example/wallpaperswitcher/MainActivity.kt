package com.example.wallpaperswitcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var countText: TextView
    private lateinit var clearAll: TextView
    private lateinit var gridHint: TextView
    private lateinit var grid: GridLayout
    private lateinit var gridScroll: ScrollView
    private lateinit var emptyText: TextView

    private val loader = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var tileSize = 0
    private var updatingSwitch = false
    private var pendingRandom = false
    private var pendingTrigger = 0
    private var pendingTarget = 0
    private lateinit var applyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTitle = findViewById(R.id.statusTitle)
        statusSub = findViewById(R.id.statusSub)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        countText = findViewById(R.id.countText)
        clearAll = findViewById(R.id.clearAll)
        gridHint = findViewById(R.id.gridHint)
        grid = findViewById(R.id.imagesGrid)
        gridScroll = findViewById(R.id.gridScroll)
        emptyText = findViewById(R.id.emptyText)

        // 4 עמודות: רוחב מסך פחות ריווחים (16×4 חוץ וכרטיס, 4×2 ריפוד המלבן, 3×8 שוליים)
        tileSize = (resources.displayMetrics.widthPixels - dp(16 * 4 + 4 * 2 + 3 * 8)) / 4

        // גלילה בתוך המלבן בלי שהמסך כולו יזוז
        gridScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        findViewById<View>(R.id.addButton).setOnClickListener { pickImages() }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked && Prefs.getUris(this).isEmpty()) {
                toast("קודם הוסף תמונות")
                setSwitch(false)
                return@setOnCheckedChangeListener
            }
            setEnabled(checked)
        }

        clearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("להסיר את כל התמונות מהרשימה?")
                .setPositiveButton("נקה הכל") { _, _ ->
                    Prefs.getUris(this).forEach { releasePermission(it) }
                    Prefs.setUris(this, emptyList())
                    Prefs.setIndex(this, -1)
                    setEnabled(false)
                    refresh()
                }
                .setNegativeButton("ביטול", null)
                .show()
        }

        findViewById<View>(R.id.nowButton).setOnClickListener {
            if (Prefs.getUris(this).isEmpty()) {
                toast("קודם הוסף תמונות")
            } else {
                WallpaperChanger.changeAsync(this) {
                    toast("הרקע הוחלף")
                    refreshGrid()
                }
            }
        }

        findViewById<View>(R.id.batteryLink).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // הגדרות ממתינות – נשמרות רק בלחיצה על "החל"
        pendingRandom = Prefs.isRandom(this)
        pendingTrigger = Prefs.getTrigger(this)
        pendingTarget = Prefs.getTarget(this)
        applyButton = findViewById(R.id.applyButton)

        val randomSwitch = findViewById<Switch>(R.id.randomSwitch)
        randomSwitch.isChecked = pendingRandom
        randomSwitch.setOnCheckedChangeListener { _, checked ->
            pendingRandom = checked
            updateApplyButton()
        }

        val triggerGroup = findViewById<RadioGroup>(R.id.triggerGroup)
        triggerGroup.check(
            when (pendingTrigger) {
                Prefs.TRIGGER_SCREEN_ON -> R.id.triggerOn
                Prefs.TRIGGER_BOTH -> R.id.triggerBoth
                else -> R.id.triggerOff
            }
        )
        triggerGroup.setOnCheckedChangeListener { _, id ->
            pendingTrigger = when (id) {
                R.id.triggerOn -> Prefs.TRIGGER_SCREEN_ON
                R.id.triggerBoth -> Prefs.TRIGGER_BOTH
                else -> Prefs.TRIGGER_SCREEN_OFF
            }
            updateApplyButton()
        }

        val targetGroup = findViewById<RadioGroup>(R.id.targetGroup)
        targetGroup.check(
            when (pendingTarget) {
                Prefs.TARGET_HOME -> R.id.targetHome
                Prefs.TARGET_LOCK -> R.id.targetLock
                else -> R.id.targetBoth
            }
        )
        targetGroup.setOnCheckedChangeListener { _, id ->
            pendingTarget = when (id) {
                R.id.targetHome -> Prefs.TARGET_HOME
                R.id.targetLock -> Prefs.TARGET_LOCK
                else -> Prefs.TARGET_BOTH
            }
            updateApplyButton()
        }

        applyButton.setOnClickListener {
            Prefs.setRandom(this, pendingRandom)
            Prefs.setTrigger(this, pendingTrigger)
            Prefs.setTarget(this, pendingTarget)
            refreshStatus()
            updateApplyButton()
            toast("ההגדרות נשמרו")
        }
        updateApplyButton()

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refreshGrid() // הרקע אולי התחלף בזמן שהמסך היה כבוי
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    // ---------- מצב הפעלה ----------

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
        setSwitch(on)
        refreshStatus()
    }

    private fun setSwitch(on: Boolean) {
        updatingSwitch = true
        enabledSwitch.isChecked = on
        updatingSwitch = false
    }

    private fun refresh() {
        setSwitch(Prefs.isEnabled(this))
        refreshStatus()
        refreshGrid()
    }

    private fun hasPendingChanges() =
        pendingRandom != Prefs.isRandom(this) ||
            pendingTrigger != Prefs.getTrigger(this) ||
            pendingTarget != Prefs.getTarget(this)

    private fun updateApplyButton() {
        val dirty = hasPendingChanges()
        applyButton.isEnabled = dirty
        applyButton.alpha = if (dirty) 1f else 0.4f
        applyButton.text = if (dirty) "החל" else "ההגדרות שמורות"
    }

    private fun refreshStatus() {
        val on = Prefs.isEnabled(this)
        statusTitle.text = if (on) "פעיל" else "כבוי"
        statusTitle.setTextColor(getColor(if (on) R.color.on_green else R.color.text_secondary))

        val whenText = when (Prefs.getTrigger(this)) {
            Prefs.TRIGGER_SCREEN_ON -> "בכל הדלקת מסך"
            Prefs.TRIGGER_BOTH -> "בכל כיבוי והדלקה"
            else -> "בכל כיבוי מסך"
        }
        val whereText = when (Prefs.getTarget(this)) {
            Prefs.TARGET_HOME -> "מסך הבית"
            Prefs.TARGET_LOCK -> "מסך הנעילה"
            else -> "בית + נעילה"
        }
        statusSub.text = if (on) "מתחלף $whenText · $whereText"
        else "הפעל כדי שהרקע יתחלף אוטומטית"
    }

    // ---------- רשת התמונות ----------

    private fun refreshGrid() {
        val uris = Prefs.getUris(this)
        val current = Prefs.getIndex(this)

        grid.removeAllViews()
        uris.forEachIndexed { i, s -> grid.addView(makeThumb(i, s, i == current)) }

        // גובה המלבן: עד 2.5 שורות, ואם יש פחות תמונות – בדיוק כמה שצריך
        val rows = (uris.size + 3) / 4
        val rowH = tileSize + dp(6)
        val boxH = (minOf(rows.toFloat(), 2.5f) * rowH).toInt() + dp(8)
        gridScroll.layoutParams = gridScroll.layoutParams.apply { height = boxH }

        val empty = uris.isEmpty()
        gridScroll.visibility = if (empty) View.GONE else View.VISIBLE
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        countText.text = if (empty) "התמונות שלי" else "התמונות שלי (${uris.size})"
        clearAll.visibility = if (empty) View.GONE else View.VISIBLE
        gridHint.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun tileParams() = GridLayout.LayoutParams().apply {
        width = tileSize
        height = tileSize
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun makeThumb(index: Int, uri: String, isCurrent: Boolean): View {
        val iv = ImageView(this)
        iv.layoutParams = tileParams()
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = getDrawable(R.drawable.thumb_bg)
        iv.clipToOutline = true
        if (isCurrent) iv.foreground = getDrawable(R.drawable.thumb_current)
        iv.setOnClickListener { confirmRemove(index) }
        loadThumb(uri, iv)
        return iv
    }

    private fun loadThumb(uri: String, iv: ImageView) {
        thumbCache.get(uri)?.let { iv.setImageBitmap(it); return }
        iv.tag = uri
        loader.execute {
            val bmp = decodeThumb(Uri.parse(uri))
            if (bmp != null) thumbCache.put(uri, bmp)
            mainHandler.post { if (iv.tag == uri && bmp != null) iv.setImageBitmap(bmp) }
        }
    }

    private fun decodeThumb(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tileSize && bounds.outHeight / (sample * 2) >= tileSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    private fun confirmRemove(index: Int) {
        AlertDialog.Builder(this)
            .setMessage("להסיר את התמונה מהרשימה?")
            .setPositiveButton("הסר") { _, _ -> removeAt(index) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun removeAt(index: Int) {
        val list = Prefs.getUris(this).toMutableList()
        if (index !in list.indices) return
        releasePermission(list.removeAt(index))
        Prefs.setUris(this, list)

        val current = Prefs.getIndex(this)
        if (index <= current) Prefs.setIndex(this, current - 1)

        if (list.isEmpty() && Prefs.isEnabled(this)) setEnabled(false)
        refreshGrid()
    }

    private fun releasePermission(uri: String) {
        try {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
    }

    // ---------- בחירת תמונות ----------

    /** בוחר התמונות החדש של אנדרואיד – עם תיבת סימון על כל תמונה */
    private fun photoPickerAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 33 ||
            (Build.VERSION.SDK_INT >= 30 &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2)

    private fun documentPickerIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    private fun pickImages() {
        val intent = if (photoPickerAvailable()) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
            }
        } else {
            toast("לבחירת כמה תמונות: לחיצה ארוכה על הראשונה, ואז נגיעה בשאר")
            documentPickerIntent()
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            startActivityForResult(documentPickerIntent(), REQ_PICK)
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
        var added = 0
        for (uri in picked) {
            try {
                // הרשאת קריאה קבועה – כדי שהתמונה תישאר זמינה גם אחרי הפעלה מחדש
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
            val s = uri.toString()
            if (s !in list) { list.add(s); added++ }
        }
        Prefs.setUris(this, list)
        if (added > 0) toast("נוספו $added תמונות")
        refreshGrid()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PICK = 100
        private const val REQ_NOTIF = 101
    }
}
