package com.nvrviewer

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Brand(val label: String, val supportsPlayback: Boolean) {
    HIKVISION("Hikvision / HiLook / HiWatch", true),
    DAHUA("Dahua / Imou / רוב הסיניים", true),
    UNIVIEW("Uniview (UNV)", true),
    REOLINK("Reolink", false),
    CUSTOM("אחר – כתובת RTSP ידנית", false)
}

data class NvrConfig(
    val brand: Brand,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val channels: Int,
    val gridSize: Int,          // 1, 4 or 9 cameras per page
    val customTemplate: String, // for CUSTOM: rtsp://{host}:{port}/...{ch}...{stream}
    val caching: Int            // ms network buffer
) {
    private fun creds(): String {
        if (user.isEmpty()) return ""
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "${enc(user)}:${enc(pass)}@"
    }

    private val base get() = "rtsp://${creds()}$host:$port"

    /** main = true for high quality, false for sub-stream (low quality). */
    fun liveUrl(ch: Int, main: Boolean): String = when (brand) {
        Brand.HIKVISION -> "$base/Streaming/Channels/${ch}0${if (main) 1 else 2}"
        Brand.DAHUA -> "$base/cam/realmonitor?channel=$ch&subtype=${if (main) 0 else 1}"
        Brand.UNIVIEW -> "$base/unicast/c$ch/s${if (main) 0 else 1}/live"
        Brand.REOLINK -> "$base/h264Preview_${"%02d".format(ch)}_${if (main) "main" else "sub"}"
        Brand.CUSTOM -> customTemplate
            .replace("{host}", "${creds()}$host").replace("{port}", port.toString())
            .replace("{ch}", ch.toString()).replace("{ch2}", "%02d".format(ch))
            .replace("{stream}", if (main) "0" else "1")
    }

    /** Recording playback URL from the NVR's disk, or null if not supported. */
    fun playbackUrl(ch: Int, startMs: Long, endMs: Long): String? = when (brand) {
        Brand.HIKVISION -> {
            // Hikvision expects NVR local time with a literal 'Z'
            val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            "$base/Streaming/tracks/${ch}01?starttime=${f.format(Date(startMs))}&endtime=${f.format(Date(endMs))}"
        }
        Brand.DAHUA -> {
            val f = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
            "$base/cam/playback?channel=$ch&subtype=0&starttime=${f.format(Date(startMs))}&endtime=${f.format(Date(endMs))}"
        }
        Brand.UNIVIEW -> "$base/c$ch/b${startMs / 1000}/e${endMs / 1000}/replay/"
        else -> null
    }

    fun withBrand(b: Brand) = copy(brand = b)

    /** URL with the password hidden, for showing on screen. */
    fun masked(url: String): String = url.replace(Regex("//[^@/]*@"), "//${user}:****@")
}

object Prefs {
    private const val NAME = "nvr"
    fun load(ctx: Context): NvrConfig {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return NvrConfig(
            brand = runCatching { Brand.valueOf(p.getString("brand", "HIKVISION")!!) }.getOrDefault(Brand.HIKVISION),
            host = p.getString("host", "")!!,
            port = p.getInt("port", 554),
            user = p.getString("user", "admin")!!,
            pass = p.getString("pass", "")!!,
            channels = p.getInt("channels", 4),
            gridSize = p.getInt("grid", 4),
            customTemplate = p.getString("tpl", "rtsp://{host}:{port}/ch{ch}/{stream}")!!,
            caching = p.getInt("caching", 600)
        )
    }

    fun save(ctx: Context, c: NvrConfig) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("brand", c.brand.name).putString("host", c.host).putInt("port", c.port)
            .putString("user", c.user).putString("pass", c.pass).putInt("channels", c.channels)
            .putInt("grid", c.gridSize).putString("tpl", c.customTemplate).putInt("caching", c.caching)
            .apply()
    }

    fun isConfigured(ctx: Context) = load(ctx).host.isNotBlank()
}

object Vlc {
    @Volatile private var instance: LibVLC? = null

    fun get(ctx: Context): LibVLC = instance ?: synchronized(this) {
        instance ?: LibVLC(ctx.applicationContext, arrayListOf(
            "--rtsp-tcp",            // TCP is far more reliable over the internet / 4G
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--no-audio-time-stretch"
        )).also { instance = it }
    }

    fun media(ctx: Context, url: String, cfg: NvrConfig, extra: List<String> = emptyList()): Media =
        Media(get(ctx), Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=${cfg.caching}")
            addOption(":rtsp-tcp")
            extra.forEach { addOption(it) }
        }
}
