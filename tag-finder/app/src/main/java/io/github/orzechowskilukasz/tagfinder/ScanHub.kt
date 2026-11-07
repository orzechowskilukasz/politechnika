package io.github.orzechowskilukasz.tagfinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import tagfinder.core.FindMyAdvertisement
import java.util.concurrent.CopyOnWriteArraySet

/**
 * One shared BLE scan for the whole app. Android throttles apps that start scans more than
 * ~5 times in 30 s, so activities subscribe here instead of starting their own, and the scan
 * is stopped only after a short grace period with no subscribers.
 */
object ScanHub {
    data class Sighting(val address: String, val rssi: Int, val timeMs: Long, val ad: FindMyAdvertisement)

    fun interface Listener {
        fun onSighting(s: Sighting)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null

    var lastError: String? = null
        private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            lastError = "Bluetooth scan failed (code $errorCode)"
            scanner = null
        }
    }

    private val stopRunnable = Runnable { stopNow() }

    private fun handle(r: ScanResult) {
        val data = r.scanRecord?.getManufacturerSpecificData(FindMyAdvertisement.APPLE_COMPANY_ID) ?: return
        val ad = FindMyAdvertisement.parse(data) ?: return
        val s = Sighting(r.device.address, r.rssi, SystemClock.elapsedRealtime(), ad)
        for (l in listeners) l.onSighting(s)
    }

    /** Subscribes [l]; returns false (see [lastError]) if scanning cannot start. Main thread only. */
    @SuppressLint("MissingPermission")
    fun addListener(context: Context, l: Listener): Boolean {
        main.removeCallbacks(stopRunnable)
        listeners += l
        if (scanner != null) return true
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            lastError = "Bluetooth is off"
            return false
        }
        val sc = adapter.bluetoothLeScanner ?: run {
            lastError = "Bluetooth LE scanner unavailable"
            return false
        }
        // Match manufacturer data that starts with the Find My record type (0x12).
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                FindMyAdvertisement.APPLE_COMPANY_ID,
                byteArrayOf(FindMyAdvertisement.TYPE_FIND_MY.toByte()),
                byteArrayOf(0xFF.toByte()),
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        return try {
            sc.startScan(listOf(filter), settings, callback)
            scanner = sc
            lastError = null
            true
        } catch (e: SecurityException) {
            lastError = "Missing Bluetooth permission"
            false
        }
    }

    fun removeListener(l: Listener) {
        listeners -= l
        if (listeners.isEmpty()) main.postDelayed(stopRunnable, 3_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopNow() {
        if (listeners.isNotEmpty()) return
        try {
            scanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        scanner = null
    }
}
