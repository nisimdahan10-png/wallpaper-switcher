package com.example.wallpaperswitcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    private var pendingEffect = 0
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
        findViewById<View>(R.id.addFolderButton).setOnClickListener { pickFolder() }
        findViewById<View>(R.id.batteryFix).setOnClickListener { requestBatteryExemption() }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked && !Library.hasAny(this)) {
                toast("קודם הוסף תמונות")
                setSwitch(false)
                return@setOnCheckedChangeListener
            }
            setEnabled(checked)
        }

        clearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("להסיר את כל התמונות והתיקיות מהרשימה?")
                .setPositiveButton("נקה הכל") { _, _ ->
                    Prefs.getUris(this).forEach { releasePermission(it) }
                    Prefs.getFolders(this).forEach { releasePermission(it) }
                    Prefs.setUris(this, emptyList())
                    Prefs.setFolders(this, emptyList())
                    Prefs.setIndex(this, -1)
                    setEnabled(false)
                    refresh()
                }
                .setNegativeButton("ביטול", null)
                .show()
        }

        findViewById<View>(R.id.nowButton).setOnClickListener {
            if (!Library.hasAny(this)) {
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
        pendingEffect = Prefs.getEffect(this)
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
            updateEffectSection()
            updateApplyButton()
        }

        findViewById<View>(R.id.effectPicker).setOnClickListener { chooseEffect() }
        updateEffectSection()

        applyButton.setOnClickListener {
            val wasLive = Prefs.isLive(this)
            Prefs.setEffect(this, pendingEffect)
            Prefs.setRandom(this, pendingRandom)
            Prefs.setTrigger(this, pendingTrigger)
            Prefs.setTarget(this, pendingTarget)
            val nowLive = Prefs.isLive(this)

            if (nowLive) {
                WallpaperChanger.prepareLockForLive(this)
                if (!isLiveWallpaperActive()) openLiveWallpaperPicker()
            } else if (wasLive && Library.hasAny(this)) {
                WallpaperChanger.changeAsync(this) // חזרה לרקע רגיל
            }
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
        refreshBattery()
    }

    // ---------- סוללה ----------

    private fun refreshBattery() {
        val pm = getSystemService(PowerManager::class.java)
        val restricted = !pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<View>(R.id.batteryCard).visibility =
            if (restricted && Prefs.isEnabled(this)) View.VISIBLE else View.GONE
    }

    @Suppress("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
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
            if (Prefs.isLive(this)) {
                WallpaperChanger.prepareLockForLive(this)
                if (!isLiveWallpaperActive()) openLiveWallpaperPicker()
            }
        } else {
            SwitcherService.stop(this)
        }
        setSwitch(on)
        refreshStatus()
        refreshBattery()
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
            pendingTarget != Prefs.getTarget(this) ||
            pendingEffect != Prefs.getEffect(this)

    private fun chooseEffect() {
        val choices = Prefs.EFFECT_CHOICES
        val checked = choices.indexOfFirst { it.first == pendingEffect }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("אפקט מעבר")
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), checked) { d, which ->
                pendingEffect = choices[which].first
                updateEffectSection()
                updateApplyButton()
                d.dismiss()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun updateEffectSection() {
        findViewById<TextView>(R.id.effectPicker).text = "${Prefs.effectName(pendingEffect)}   ▾"
        val effect = pendingEffect != Prefs.EFFECT_NONE
        val hint = findViewById<TextView>(R.id.effectHint)
        hint.visibility = if (effect) View.VISIBLE else View.GONE
        hint.text = when (pendingTarget) {
            Prefs.TARGET_LOCK -> "אפקטים פועלים רק במסך הבית – במצב \"רק מסך נעילה\" ההחלפה תהיה בלי אנימציה."
            Prefs.TARGET_BOTH -> "מסך הבית מתחלף עם אנימציה, ומסך הנעילה מתעדכן לאותה תמונה (בלי אנימציה)."
            else -> "מסך הבית מתחלף עם אנימציה. מסך הנעילה לא משתנה."
        }
    }

    private fun isLiveWallpaperActive(): Boolean = try {
        WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName
    } catch (e: Exception) {
        false
    }

    private fun openLiveWallpaperPicker() {
        toast("לחץ \"הגדר רקע\" כדי להפעיל את האפקטים")
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, LiveWallpaper::class.java)
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                toast("המכשיר לא תומך ברקע חי")
            }
        }
    }

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
        val effectName = if (Prefs.getEffect(this) == Prefs.EFFECT_NONE) null
        else Prefs.effectName(Prefs.getEffect(this)).substringBefore(" (")
        val extra = if (effectName != null && Prefs.isLive(this)) "$whereText · $effectName" else whereText
        statusSub.text = if (on) "מתחלף $whenText · $extra"
        else "הפעל כדי שהרקע יתחלף אוטומטית"
    }

    // ---------- רשת התמונות ----------

    private fun refreshGrid() {
        loader.execute {
            val all = Library.all(this) // סריקת תיקיות – ברקע
            mainHandler.post { if (!isDestroyed) renderGrid(all) }
        }
    }

    private fun renderGrid(all: List<Img>) {
        val current = Prefs.getIndex(this)

        grid.removeAllViews()
        all.forEachIndexed { i, img -> grid.addView(makeThumb(i, img, i == current)) }

        // גובה המלבן: עד 2.5 שורות, ואם יש פחות תמונות – בדיוק כמה שצריך
        val rows = (all.size + 3) / 4
        val rowH = tileSize + dp(6)
        val boxH = (minOf(rows.toFloat(), 2.5f) * rowH).toInt() + dp(8)
        gridScroll.layoutParams = gridScroll.layoutParams.apply { height = boxH }

        val empty = all.isEmpty()
        val hasAny = Library.hasAny(this)
        gridScroll.visibility = if (empty) View.GONE else View.VISIBLE
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        emptyText.text = if (hasAny) "התיקייה ריקה – אין בה תמונות" else "עדיין לא נבחרו תמונות"
        countText.text = if (empty) "התמונות שלי" else "התמונות שלי (${all.size})"
        clearAll.visibility = if (hasAny) View.VISIBLE else View.GONE
        gridHint.visibility = if (empty) View.GONE else View.VISIBLE

        renderFolders(all)
    }

    private fun renderFolders(all: List<Img>) {
        val box = findViewById<LinearLayout>(R.id.foldersList)
        box.removeAllViews()
        for (f in Prefs.getFolders(this)) {
            val count = all.count { it.folder == f }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            row.addView(TextView(this).apply {
                text = "📁  ${Library.folderName(f)}  ·  $count תמונות"
                setTextColor(getColor(R.color.text))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "הסר"
                setTextColor(getColor(R.color.danger))
                textSize = 14f
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("להסיר את התיקייה \"${Library.folderName(f)}\" מהסבב?\n(התמונות עצמן לא יימחקו)")
                        .setPositiveButton("הסר") { _, _ ->
                            mutateKeepingCurrent {
                                Prefs.setFolders(this@MainActivity, Prefs.getFolders(this@MainActivity) - f)
                                releasePermission(f)
                            }
                        }
                        .setNegativeButton("ביטול", null)
                        .show()
                }
            })
            box.addView(row)
        }
    }

    private fun tileParams() = GridLayout.LayoutParams().apply {
        width = tileSize
        height = tileSize
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun makeThumb(index: Int, img: Img, isCurrent: Boolean): View {
        val iv = ImageView(this)
        iv.layoutParams = tileParams()
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = getDrawable(R.drawable.thumb_bg)
        iv.clipToOutline = true
        if (isCurrent) iv.foreground = getDrawable(R.drawable.thumb_current)
        iv.setOnClickListener { showPreview(index, img) }
        loadThumb(img.uri, iv)
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

    // ---------- תצוגה מלאה ----------

    private fun showPreview(index: Int, img: Img) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // מציג את התמונה כמו שהיא תיראה כרקע (ממלאת את המסך)
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbCache.get(img.uri)?.let { iv.setImageBitmap(it) }
        root.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            setBackgroundColor(0x99000000.toInt())
        }
        bar.addView(barButton("הגדר כרקע", R.drawable.btn_primary) {
            dialog.dismiss()
            WallpaperChanger.changeAsync(this, index) {
                toast("הרקע הוחלף")
                refreshGrid()
            }
        })
        if (img.folder == null) {
            bar.addView(barButton("הסר", R.drawable.btn_danger) {
                dialog.dismiss()
                mutateKeepingCurrent {
                    Library.removeManual(this, img.uri)
                    releasePermission(img.uri)
                }
            })
        }
        bar.addView(barButton("סגור", R.drawable.btn_dark) { dialog.dismiss() })
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        if (img.folder != null) {
            root.addView(TextView(this).apply {
                text = "📁 ${Library.folderName(img.folder)} – להסרה, הוצא אותה מהתיקייה או הסר את התיקייה"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(0x99000000.toInt())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            ))
        }

        dialog.setContentView(root)
        dialog.show()

        // טוען את התמונה באיכות מלאה
        loader.execute {
            val full = WallpaperChanger.decodeScaled(this, Uri.parse(img.uri))
            mainHandler.post { if (dialog.isShowing && full != null) iv.setImageBitmap(full) }
        }
    }

    private fun barButton(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        background = getDrawable(bg)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener { onClick() }
    }

    /** שינוי ברשימה בלי לאבד את סימון הרקע הנוכחי */
    private fun mutateKeepingCurrent(block: () -> Unit) {
        loader.execute {
            val idx = Prefs.getIndex(this)
            val curUri = if (idx >= 0) Library.all(this).getOrNull(idx)?.uri else null
            mainHandler.post {
                block()
                loader.execute {
                    val all = Library.all(this)
                    Prefs.setIndex(this, curUri?.let { u -> all.indexOfFirst { it.uri == u } } ?: -1)
                    mainHandler.post {
                        if (!Library.hasAny(this) && Prefs.isEnabled(this)) setEnabled(false)
                        renderGrid(all)
                    }
                }
            }
        }
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

    private fun pickFolder() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
    }

    private fun onFolderPicked(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { }
        val s = tree.toString()
        val folders = Prefs.getFolders(this)
        if (s in folders) {
            toast("התיקייה כבר ברשימה")
            return
        }
        mutateKeepingCurrent { Prefs.setFolders(this, folders + s) }
        loader.execute {
            val n = Library.scan(this, s).size
            mainHandler.post { toast("נוספה התיקייה ${Library.folderName(s)} ($n תמונות)") }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            data?.data?.let { onFolderPicked(it) }
            return
        }
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
        mutateKeepingCurrent { Prefs.setUris(this, list) }
        if (added > 0) toast("נוספו $added תמונות")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PICK = 100
        private const val REQ_NOTIF = 101
        private const val REQ_FOLDER = 102
    }
}
