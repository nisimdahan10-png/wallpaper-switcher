package com.nvrviewer

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Finds DVR / NVR / IP cameras on the local network. */
class ScanActivity : AppCompatActivity() {

    data class Found(
        val ip: String,
        val ports: MutableSet<Int> = java.util.concurrent.ConcurrentSkipListSet(),
        var name: String? = null,
        var brand: Brand? = null
    ) {
        fun guessBrand(): Brand? = brand ?: when {
            8000 in ports -> Brand.HIKVISION
            37777 in ports -> Brand.DAHUA
            9000 in ports -> Brand.REOLINK
            else -> null
        }

        fun score() = (if (554 in ports) 10 else 0) + (if (name != null) 5 else 0) + (if (guessBrand() != null) 3 else 0)

        fun describe(): String {
            val b = guessBrand()?.label ?: "יצרן לא ידוע"
            val title = name ?: if (554 in ports) "מכשיר וידאו (RTSP)" else "מכשיר"
            val rtsp = if (554 in ports) "✓ RTSP פתוח" else "✗ RTSP סגור"
            return "$title\n$ip  •  $b\n$rtsp  •  פורטים: ${ports.joinToString(", ")}"
        }
    }

    // Ports that video recorders typically open
    private val ports = listOf(554, 8000, 37777, 9000, 80, 8554)

    private val found = ConcurrentHashMap<String, Found>()
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var btnScan: Button
    private var shown = listOf<Found>()
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        btnScan = findViewById(R.id.btnScan)
        btnScan.setOnClickListener { if (!running) scan() }
        findViewById<Button>(R.id.btnManual).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)); finish()
        }
        prober = Prober(this, findViewById(R.id.probeView))
        list.setOnItemClickListener { _, _, pos, _ -> askLogin(shown[pos]) }
        scan()
    }

    override fun onDestroy() { super.onDestroy(); running = false; prober.cancel() }

    private lateinit var prober: Prober

    /** Ask for username/password, then connect automatically. */
    private fun askLogin(f: Found) {
        val old = Prefs.load(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val user = android.widget.EditText(this).apply {
            hint = "שם משתמש"; setText(old.user.ifBlank { "admin" })
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val pass = android.widget.EditText(this).apply {
            hint = "סיסמה"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (old.host == f.ip) setText(old.pass)
        }
        box.addView(user); box.addView(pass)
        android.app.AlertDialog.Builder(this)
            .setTitle("התחברות ל-${f.ip}")
            .setMessage("הכנס את שם המשתמש והסיסמה שבהם נכנסים ל-NVR")
            .setView(box)
            .setPositiveButton("התחבר") { _, _ -> connect(f, user.text.toString().trim(), pass.text.toString()) }
            .setNegativeButton("ביטול", null)
            .show()
        pass.requestFocus()
    }

    private fun connect(f: Found, user: String, pass: String) {
        running = false
        val base = Prefs.load(this).copy(host = f.ip, port = if (554 in f.ports) 554 else 8554, user = user, pass = pass)
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("מתחבר ל-${f.ip}")
            .setMessage("…")
            .setCancelable(false)
            .setNegativeButton("ביטול") { _, _ -> prober.cancel() }
            .show()
        prober.autoConnect(base, f.guessBrand(), progress = { dlg.setMessage(it) }) { r ->
            dlg.dismiss()
            if (r is Prober.Result.Ok) {
                Prefs.save(this, base.copy(brand = r.brand, channels = r.channels.coerceAtLeast(1),
                    gridSize = if (r.channels <= 1) 1 else if (r.channels <= 4) 4 else 9))
                android.widget.Toast.makeText(this, Prober.message(r, base.host, base.port), android.widget.Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("ההתחברות נכשלה")
                    .setMessage(Prober.message(r, base.host, base.port))
                    .setPositiveButton("נסה שוב") { _, _ -> askLogin(f) }
                    .setNegativeButton("סגור", null)
                    .show()
            }
        }
    }

    private fun localIpv4(): Pair<String, Int>? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return la.address.hostAddress!! to la.prefixLength
    }

    private fun scan() {
        val local = localIpv4()
        if (local == null) {
            status.text = "הטלפון לא מחובר לרשת. התחבר ל-WiFi של הבית ונסה שוב."
            return
        }
        running = true
        found.clear()
        refresh()
        btnScan.isEnabled = false
        val (myIp, _) = local
        val prefix = myIp.substringBeforeLast('.')
        status.text = "סורק את הרשת $prefix.x …"

        Thread { onvifDiscovery() }.start()

        val pool = Executors.newFixedThreadPool(64)
        val done = AtomicInteger()
        val total = 254
        for (i in 1..254) {
            val ip = "$prefix.$i"
            if (ip == myIp) { done.incrementAndGet(); continue }
            pool.execute {
                if (!running) return@execute
                for (p in ports) {
                    if (open(ip, p)) {
                        found.getOrPut(ip) { Found(ip) }.ports.add(p)
                        runOnUiThread { refresh() }
                    }
                }
                val d = done.incrementAndGet()
                if (d % 10 == 0) runOnUiThread { status.text = "סורק $prefix.x … ${d * 100 / total}%" }
            }
        }
        Thread {
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.MINUTES)
            running = false
            runOnUiThread {
                btnScan.isEnabled = true
                refresh()
                val video = found.values.count { 554 in it.ports || it.name != null }
                status.text = if (video == 0)
                    "לא נמצא NVR ברשת. ודא שה-NVR מחובר בכבל לראוטר ושהטלפון על אותה רשת WiFi."
                else "נמצאו $video מכשירי וידאו. לחץ על המכשיר כדי לבחור אותו."
            }
        }.start()
    }

    private fun open(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 350) }; true
    } catch (e: Exception) { false }

    /** ONVIF WS-Discovery: most NVRs/cameras answer with their name and model. */
    private fun onvifDiscovery() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("onvif").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 1500
                val msg = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>""".toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 3702)) }
                val buf = ByteArray(65535)
                val end = System.currentTimeMillis() + 6000
                while (running && System.currentTimeMillis() < end) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (e: SocketTimeoutException) { continue }
                    val ip = pkt.address.hostAddress ?: continue
                    val xml = String(pkt.data, 0, pkt.length)
                    val f = found.getOrPut(ip) { Found(ip) }
                    val name = scope(xml, "name")
                    val hw = scope(xml, "hardware")
                    f.name = listOfNotNull(name, hw).distinct().joinToString(" ").ifBlank { "מכשיר ONVIF" }
                    f.brand = brandFrom("$name $hw $xml")
                    runOnUiThread { refresh() }
                }
            }
        } catch (_: Exception) {
        } finally { lock.release() }
    }

    private fun scope(xml: String, key: String): String? =
        Regex("onvif://www\\.onvif\\.org/$key/([^\\s<]+)").find(xml)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    private fun brandFrom(s: String): Brand? {
        val t = s.lowercase()
        return when {
            listOf("hikvision", "hiwatch", "hilook", "ds-7", "ds-2", "ds-9").any { it in t } -> Brand.HIKVISION
            listOf("dahua", "imou", "nvr4", "nvr5", "dh-").any { it in t } -> Brand.DAHUA
            listOf("uniview", "unv", "nvr30").any { it in t } -> Brand.UNIVIEW
            "reolink" in t -> Brand.REOLINK
            else -> null
        }
    }

    private fun refresh() {
        // Show only devices that look like video gear first; everything else after
        shown = found.values
            .filter { 554 in it.ports || 8554 in it.ports || it.name != null || it.guessBrand() != null }
            .sortedByDescending { it.score() }
        list.adapter = ArrayAdapter(this, R.layout.item_device, shown.map { it.describe() })
    }
}
