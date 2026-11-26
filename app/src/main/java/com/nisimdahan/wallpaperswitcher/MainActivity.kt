package com.nisimdahan.wallpaperswitcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.view.WindowInsets
import android.view.WindowInsetsController
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
        setupEdgeToEdge()

        statusTitle = findViewById(R.id.statusTitle)
        statusSub = findViewById(R.id.statusSub)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        countText = findViewById(R.id.countText)
        clearAll = findViewById(R.id.clearAll)
        gridHint = findViewById(R.id.gridHint)
        grid = findViewById(R.id.imagesGrid)
        gridScroll = findViewById(R.id.gridScroll)
        emptyText = findViewById(R.id.emptyText)

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        findViewById<TextView>(R.id.versionText).text = "v$version"

        // 4 עמודות: רוחב מסך פחות ריווחים (16×4 חוץ וכרטיס, 4×2 ריפוד המלבן, 3×8 שוליים)
        tileSize = (resources.displayMetrics.widthPixels - dp(16 * 4 + 4 * 2 + 3 * 8)) / 4

        // גלילה בתוך המלבן בלי שהמסך כולו יזוז
        gridScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        findViewById<View>(R.id.addButton).setOnClickListener { pickImages() }
        findViewById<View>(R.id.addFolderButton).setOnClickListener { pickFolder() }
        findViewById<View>(R.id.languageButton).setOnClickListener { chooseLanguage() }
        findViewById<View>(R.id.batteryFix).setOnClickListener { requestBatteryExemption() }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked && !Library.hasAny(this)) {
                toast(getString(R.string.need_images))
                setSwitch(false)
                return@setOnCheckedChangeListener
            }
            setEnabled(checked)
        }

        clearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_all_confirm)
                .setPositiveButton(R.string.clear_all) { _, _ ->
                    Prefs.getUris(this).forEach { releasePermission(it) }
                    Prefs.getFolders(this).forEach { releasePermission(it) }
                    Prefs.setUris(this, emptyList())
                    Prefs.setFolders(this, emptyList())
                    Prefs.setIndex(this, -1)
                    setEnabled(false)
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        findViewById<View>(R.id.nowButton).setOnClickListener { btn ->
            btn.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!Library.hasAny(this)) {
                toast(getString(R.string.need_images))
            } else {
                btn.isEnabled = false
                btn.alpha = 0.6f
                WallpaperChanger.changeAsync(this) {
                    btn.isEnabled = true
                    btn.alpha = 1f
                    toast(getString(R.string.changed))
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
            toast(getString(R.string.settings_saved_toast))
        }
        updateApplyButton()

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refreshGrid() // הרקע אולי התחלף בזמן שהמסך היה כבוי
        refreshBattery()
    }

    // ---------- שפה ----------

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Lang.wrap(newBase))

    private fun chooseLanguage() {
        val names = Lang.OPTIONS.map { (code, native) -> if (code.isEmpty()) getString(R.string.lang_system) else native }
        val checked = Lang.OPTIONS.indexOfFirst { it.first == Lang.current(this) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(names.toTypedArray(), checked) { d, which ->
                d.dismiss()
                Lang.set(this, Lang.OPTIONS[which].first)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- תצוגה מקצה לקצה (חובה מאנדרואיד 15) ----------

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun setupEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            // סמלי שורת הסטטוס לבנים (על הכותרת הצבעונית); סרגל הניווט לפי מצב בהיר/כהה
            window.insetsController?.setSystemBarsAppearance(
                if (isNight()) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 26 && !isNight()) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }

        val header = findViewById<View>(R.id.header)
        val now = findViewById<View>(R.id.nowButton)
        val headerH = header.layoutParams.height
        val headerTop = header.paddingTop
        val nowBottom = (now.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = systemBars(insets)
            header.layoutParams = header.layoutParams.apply { height = headerH + top }
            header.setPadding(header.paddingLeft, headerTop + top, header.paddingRight, header.paddingBottom)
            now.layoutParams = (now.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = nowBottom + bottom
            }
            insets
        }
    }

    private fun systemBars(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            b.top to b.bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
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
            .setTitle(R.string.effect_title)
            .setSingleChoiceItems(choices.map { getString(it.second) }.toTypedArray(), checked) { d, which ->
                pendingEffect = choices[which].first
                updateEffectSection()
                updateApplyButton()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateEffectSection() {
        findViewById<TextView>(R.id.effectPicker).text = "${Prefs.effectName(this, pendingEffect)}   ▾"
        val effect = pendingEffect != Prefs.EFFECT_NONE
        val hint = findViewById<TextView>(R.id.effectHint)
        hint.visibility = if (effect) View.VISIBLE else View.GONE
        hint.text = when (pendingTarget) {
            Prefs.TARGET_LOCK -> getString(R.string.hint_lock)
            Prefs.TARGET_BOTH -> getString(R.string.hint_both)
            else -> getString(R.string.hint_home)
        }
    }

    private fun isLiveWallpaperActive(): Boolean = try {
        WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName
    } catch (e: Exception) {
        false
    }

    private fun openLiveWallpaperPicker() {
        toast(getString(R.string.press_set_wallpaper))
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
                toast(getString(R.string.no_live))
            }
        }
    }

    private fun updateApplyButton() {
        val dirty = hasPendingChanges()
        applyButton.isEnabled = dirty
        applyButton.alpha = if (dirty) 1f else 0.4f
        applyButton.text = getString(if (dirty) R.string.apply else R.string.settings_saved_btn)
    }

    private fun refreshStatus() {
        val on = Prefs.isEnabled(this)
        statusTitle.text = getString(if (on) R.string.status_on else R.string.status_off)
        statusTitle.setTextColor(getColor(if (on) R.color.on_green else R.color.text_secondary))
        findViewById<ImageView>(R.id.statusIcon).apply {
            setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_pause)
            setBackgroundResource(if (on) R.drawable.status_on_bg else R.drawable.status_off_bg)
        }

        val whenText = when (Prefs.getTrigger(this)) {
            Prefs.TRIGGER_SCREEN_ON -> getString(R.string.when_on)
            Prefs.TRIGGER_BOTH -> getString(R.string.when_both)
            else -> getString(R.string.when_off)
        }
        val whereText = when (Prefs.getTarget(this)) {
            Prefs.TARGET_HOME -> getString(R.string.where_home)
            Prefs.TARGET_LOCK -> getString(R.string.where_lock)
            else -> getString(R.string.where_both)
        }
        val effectName = when (Prefs.getEffect(this)) {
            Prefs.EFFECT_NONE -> null
            Prefs.EFFECT_RANDOM -> getString(R.string.effect_random_short)
            else -> Prefs.effectName(this, Prefs.getEffect(this))
        }
        val extra = if (effectName != null && Prefs.isLive(this)) "$whereText · $effectName" else whereText
        statusSub.text = if (on) getString(R.string.status_detail, whenText, extra)
        else getString(R.string.status_hint_off)
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
        emptyText.text = getString(if (hasAny) R.string.folder_empty else R.string.no_images)
        countText.text = if (empty) getString(R.string.my_images) else getString(R.string.my_images_count, all.size)
        clearAll.visibility = if (hasAny) View.VISIBLE else View.GONE
        gridHint.visibility = if (empty) View.GONE else View.VISIBLE
        findViewById<View>(R.id.startCard).visibility = if (hasAny) View.GONE else View.VISIBLE

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
                text = getString(R.string.folder_row, Library.folderName(this@MainActivity, f), count)
                setTextColor(getColor(R.color.text))
                gravity = Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_folder, 0, 0, 0)
                compoundDrawablePadding = dp(10)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = getString(R.string.remove)
                setTextColor(getColor(R.color.danger))
                textSize = 14f
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(getString(R.string.remove_folder_confirm, Library.folderName(this@MainActivity, f)))
                        .setPositiveButton(R.string.remove) { _, _ ->
                            mutateKeepingCurrent {
                                Prefs.setFolders(this@MainActivity, Prefs.getFolders(this@MainActivity) - f)
                                releasePermission(f)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
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
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0x99000000.toInt())
        }
        bar.addView(barButton(getString(R.string.set_as_wallpaper), R.drawable.btn_primary) {
            dialog.dismiss()
            WallpaperChanger.changeAsync(this, index) {
                toast(getString(R.string.changed))
                refreshGrid()
            }
        })
        if (img.folder == null) {
            bar.addView(barButton(getString(R.string.remove), R.drawable.btn_danger) {
                dialog.dismiss()
                mutateKeepingCurrent {
                    Library.removeManual(this, img.uri)
                    releasePermission(img.uri)
                }
            })
        }
        bar.addView(barButton(getString(R.string.close), R.drawable.btn_dark) { dialog.dismiss() })
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        if (img.folder != null) {
            root.addView(TextView(this).apply {
                text = getString(R.string.from_folder, Library.folderName(this@MainActivity, img.folder))
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(0x99000000.toInt())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            ))
        }

        val barBottom = bar.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (_, bottom) = systemBars(insets)
            bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, barBottom + bottom)
            insets
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
            toast(getString(R.string.multi_hint))
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
            toast(getString(R.string.folder_exists))
            return
        }
        mutateKeepingCurrent { Prefs.setFolders(this, folders + s) }
        loader.execute {
            val n = Library.scan(this, s).size
            mainHandler.post { toast(getString(R.string.folder_added, Library.folderName(this, s), n)) }
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
        if (added > 0) toast(getString(R.string.images_added, added))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PICK = 100
        private const val REQ_NOTIF = 101
        private const val REQ_FOLDER = 102
    }
}
