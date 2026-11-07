package io.github.orzechowskilukasz.tagfinder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView

/** Lists nearby Find My devices, strongest first. Tap one to start finding it. */
class MainActivity : Activity(), ScanHub.Listener {
    private data class Row(val address: String, var rssi: Int, var lastSeen: Long, var sighting: ScanHub.Sighting)

    private val rows = LinkedHashMap<String, Row>()
    private val shown = ArrayList<Row>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = label("Scanning for Find My devices…", 14f, color = Palette.muted)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                shown.getOrNull(pos)?.let { row ->
                    startActivity(Intent(this@MainActivity, LocateActivity::class.java).putExtra(LocateActivity.EXTRA_ADDRESS, row.address))
                }
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Nearby Find My devices", 20f, bold = true))
                addView(
                    label(
                        "Strongest signal first. If you only own one AirTag, it is usually the strongest " +
                            "\"separated\" entry when your iPhone is out of range. Tap a device to find it.",
                        14f, color = Palette.muted,
                    ),
                )
                addView(status)
                addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
    }

    override fun onResume() {
        super.onResume()
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            status.text = "Bluetooth and location permissions are needed to see nearby devices."
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        if (!ScanHub.addListener(this, this)) status.text = ScanHub.lastError ?: "Could not start scanning"
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ScanHub.removeListener(this)
        handler.removeCallbacks(refresh)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            status.text = "Permission denied — grant Nearby devices and Location in system settings."
        }
    }

    override fun onSighting(s: ScanHub.Sighting) {
        val row = rows[s.address]
        if (row == null) {
            rows[s.address] = Row(s.address, s.rssi, s.timeMs, s)
            return
        }
        row.rssi = (row.rssi * 2 + s.rssi) / 3 // light smoothing so the list does not jump around
        row.lastSeen = s.timeMs
        row.sighting = s
    }

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        rows.values.removeIf { now - it.lastSeen > 30_000 }
        shown.clear()
        shown += rows.values.sortedByDescending { it.rssi }
        adapter.clear()
        for (r in shown) {
            val ad = r.sighting.ad
            val kind = if (ad.probablyAirTag) "AirTag (probably)" else "Find My device"
            val state = if (ad.separated) "separated from owner" else "near its owner"
            adapter.add("$kind · ${r.rssi} dBm\n$state · battery ${ad.battery.name.lowercase()} · ${(now - r.lastSeen) / 1000}s ago · ${r.address}")
        }
        status.text = ScanHub.lastError ?: "${shown.size} device(s) heard in the last 30 s"
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
