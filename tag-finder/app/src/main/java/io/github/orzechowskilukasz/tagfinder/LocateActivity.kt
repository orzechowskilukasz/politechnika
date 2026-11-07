package io.github.orzechowskilukasz.tagfinder

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import tagfinder.core.BearingEstimator
import tagfinder.core.BearingResult
import tagfinder.core.MagnetAnomaly
import tagfinder.core.Rssi
import tagfinder.core.RssiKalman
import tagfinder.core.TargetTracker
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Finding one device without the camera:
 *  - Hot/cold: Kalman-smoothed signal strength with a warmer/colder trend.
 *  - Sweep: turn a full circle holding the phone to your chest; your body's radio shadow
 *    reveals the direction (see BearingEstimator).
 *  - Magnet: for the last few centimetres, the magnetometer picks up the tag's speaker magnet.
 */
class LocateActivity : Activity(), ScanHub.Listener {
    companion object {
        const val EXTRA_ADDRESS = "address"
        private const val DEFAULT_P0 = -60.0
        private const val DEFAULT_EXPONENT = 2.0
    }

    private enum class Mode { HOT_COLD, SWEEP, MAGNET }

    private lateinit var tracker: TargetTracker
    private lateinit var orientation: OrientationTracker
    private val kalman = RssiKalman()
    private val magnet = MagnetAnomaly()
    private var mode = Mode.HOT_COLD

    private val history = ArrayDeque<Pair<Long, Double>>()
    private var lastSeenMs = 0L
    private var firstSeenMs = 0L
    private var readings = 0

    private var sweeping = false
    private val sweepYaw = ArrayList<Double>()
    private val sweepRssi = ArrayList<Double>()
    private var bearing: BearingResult? = null

    private lateinit var title: TextView
    private lateinit var big: TextView
    private lateinit var detail: TextView
    private lateinit var help: TextView
    private lateinit var meter: MeterView
    private lateinit var polar: PolarView
    private lateinit var action: Button
    private val modeButtons = HashMap<Mode, Button>()

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracker = TargetTracker(intent.getStringExtra(EXTRA_ADDRESS) ?: run { finish(); return })
        orientation = OrientationTracker(this)

        title = label("", 14f, color = Palette.muted)
        big = label("—", 34f, bold = true)
        detail = label("", 16f)
        help = label("", 14f, color = Palette.muted)
        meter = MeterView(this)
        polar = PolarView(this)
        action = button("") { onAction() }

        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((m, text) in listOf(Mode.HOT_COLD to "Hot/cold", Mode.SWEEP to "Direction", Mode.MAGNET to "Magnet")) {
                val b = button(text) { setMode(m) }
                modeButtons[m] = b
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
            addView(modes)
            addView(title)
            addView(big)
            addView(meter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)).apply {
                setMargins(dp(16), dp(4), dp(16), dp(8))
            })
            addView(polar)
            addView(detail)
            addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(8), dp(16), 0)
            })
            addView(help)
            addView(button("Map the room with the camera (AR scan)") {
                startActivity(Intent(this@LocateActivity, ArScanActivity::class.java).putExtra(EXTRA_ADDRESS, tracker.address))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(16), dp(16), 0)
            })
        }
        setContentView(ScrollView(this).apply { addView(content) })
        setMode(Mode.HOT_COLD)
    }

    override fun onResume() {
        super.onResume()
        if (!ScanHub.addListener(this, this)) detail.text = ScanHub.lastError
        orientation.start()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ScanHub.removeListener(this)
        orientation.stop()
        handler.removeCallbacks(tick)
    }

    override fun onSighting(s: ScanHub.Sighting) {
        if (!tracker.accept(s.address, s.rssi.toDouble(), s.ad, s.timeMs)) return
        if (firstSeenMs == 0L) firstSeenMs = s.timeMs
        lastSeenMs = s.timeMs
        readings++
        val est = kalman.update(s.rssi.toDouble(), s.timeMs)
        history.addLast(s.timeMs to est)
        while (history.size > 2 && s.timeMs - history.first().first > 6_000) history.removeFirst()
        if (sweeping && orientation.hasRotation) {
            sweepYaw += orientation.yawRad
            sweepRssi += s.rssi.toDouble()
            bearing = BearingEstimator.estimate(sweepYaw, sweepRssi)
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        sweeping = false
        for ((k, b) in modeButtons) b.alpha = if (k == m) 1f else 0.55f
        polar.visibility = if (m == Mode.SWEEP) View.VISIBLE else View.GONE
        meter.visibility = if (m == Mode.SWEEP) View.GONE else View.VISIBLE
        when (m) {
            Mode.HOT_COLD -> {
                action.visibility = View.GONE
                help.text = "Walk slowly. At each spot, wave the phone in small circles for a few seconds: " +
                    "radio reflections make the reading jump by up to ±10 dB over a few centimetres, " +
                    "and averaging over a small area cancels that out. Rising = warmer."
            }
            Mode.SWEEP -> {
                action.visibility = View.VISIBLE
                help.text = "Hold the phone flat against your chest, screen facing you, and turn slowly " +
                    "on the spot for one and a half turns (about 30–40 s). Your body blocks the signal " +
                    "when your back is to the tag, so the strongest heading points toward it."
            }
            Mode.MAGNET -> {
                action.visibility = View.VISIBLE
                help.text = "For the last few centimetres. Calibrate with the phone away from metal, then " +
                    "slowly pass the top of the phone over cushions, bags or shelves. The AirTag's " +
                    "speaker magnet shows up only within ~5–10 cm."
            }
        }
        render()
    }

    private fun onAction() {
        when (mode) {
            Mode.SWEEP -> {
                sweeping = !sweeping
                if (sweeping) {
                    sweepYaw.clear(); sweepRssi.clear(); bearing = null
                }
            }
            Mode.MAGNET -> orientation.magneticWorld?.let { magnet.calibrate(it) }
            Mode.HOT_COLD -> Unit
        }
        render()
    }

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val ago = if (lastSeenMs == 0L) -1 else ((now - lastSeenMs) / 1000).toInt()
        val rate = if (readings > 1) (readings - 1) * 1000.0 / (lastSeenMs - firstSeenMs).coerceAtLeast(1) else 0.0
        title.text = buildString {
            append("Tracking ${tracker.address}")
            if (tracker.handovers > 0) append(" (followed ${tracker.handovers} address change(s))")
            append(if (ago < 0) " · waiting for signal…" else " · last heard ${ago}s ago · %.1f readings/s".format(rate))
        }
        when (mode) {
            Mode.HOT_COLD -> renderHotCold()
            Mode.SWEEP -> renderSweep()
            Mode.MAGNET -> renderMagnet()
        }
    }

    private fun renderHotCold() {
        val est = kalman.estimate
        if (est.isNaN()) {
            big.text = "—"; detail.text = "No readings yet."; meter.set(0.0, false); return
        }
        val trend = if (history.size >= 2) history.last().second - history.first().second else 0.0
        val d = Rssi.distanceFor(est, DEFAULT_P0, DEFAULT_EXPONENT)
        big.text = "${est.roundToInt()} dBm"
        meter.set((est + 95) / 55, trend > 1.5)
        val word = when {
            trend > 1.5 -> "Warmer ↑"
            trend < -1.5 -> "Colder ↓"
            else -> "Steady"
        }
        detail.text = "$word  ·  roughly %s away (very approximate: walls, bodies and furniture change this a lot)"
            .format(if (d < 1) "under 1 m" else "%.0f m".format(d))
    }

    private fun renderSweep() {
        action.text = if (sweeping) "Stop sweep" else "Start sweep"
        val yawNow = orientation.yawRad
        val pts = sweepYaw.indices.map { BearingEstimator.wrap(sweepYaw[it] - yawNow) to sweepRssi[it] }
        val b = bearing
        polar.set(pts, b?.let { BearingEstimator.wrap(it.bearingRad - yawNow) }, b?.sigmaRad ?: 0.0)
        big.text = if (b == null) "${sweepYaw.size} readings" else {
            val rel = Math.toDegrees(BearingEstimator.wrap(b.bearingRad - yawNow)).roundToInt()
            when {
                abs(rel) <= 15 -> "Ahead"
                rel > 0 -> "$rel° right"
                else -> "${-rel}° left"
            }
        }
        detail.text = when {
            !orientation.hasRotation -> "This phone has no rotation sensor, so direction finding is unavailable."
            b == null && sweeping -> "Keep turning slowly… need readings from at least 6 of 8 directions."
            b == null -> "Press start, then turn."
            b.amplitudeDb < 2.0 -> "The signal hardly changes as you turn (%.1f dB). The tag is probably very close, or reflections are hiding the direction. Try again a few metres away.".format(b.amplitudeDb)
            else -> "±%d° (1σ) · body-shadow contrast %.1f dB · %d readings. The arrow keeps pointing the right way as you turn."
                .format(Math.toDegrees(b.sigmaRad).roundToInt(), b.amplitudeDb, b.samples)
        }
    }

    private fun renderMagnet() {
        action.text = if (magnet.calibrated) "Re-calibrate here" else "Calibrate (hold away from the search area)"
        val field = orientation.magneticWorld
        if (field == null) {
            big.text = "—"; detail.text = "Waiting for magnetometer…"; meter.set(0.0, false); return
        }
        val a = magnet.update(field)
        if (a.isNaN()) {
            big.text = "%.0f µT".format(field.norm()); detail.text = "Not calibrated yet."; meter.set(0.0, false); return
        }
        big.text = "%.1f µT".format(a)
        meter.set(a / 40.0, a > 10)
        detail.text = when {
            a > 25 -> "Strong magnetic anomaly right here — check this spot by hand."
            a > 10 -> "Something magnetic nearby. Speakers, clasps and screws also trigger this."
            else -> "Nothing unusual."
        }
    }
}
