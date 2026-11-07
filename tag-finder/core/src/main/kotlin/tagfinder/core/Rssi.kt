package tagfinder.core

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

object Rssi {
    /** 2.4 GHz BLE wavelength (m). Advertising channels 37/38/39 are 2402/2426/2480 MHz. */
    const val WAVELENGTH_M = 0.125

    /** Small-scale fading decorrelates after roughly half a wavelength of movement. */
    const val FADING_DECORRELATION_M = WAVELENGTH_M / 2

    private val DB_PER_NEPER = 10.0 / ln(10.0)

    fun dbmToMw(dbm: Double): Double = 10.0.pow(dbm / 10.0)
    fun mwToDbm(mw: Double): Double = 10.0 * log10(mw)

    /**
     * Standard deviation (dB) of the mean of [n] independent Rayleigh-faded power samples
     * expressed in dB. The mean of n unit exponentials is Gamma(n, 1/n) and
     * Var[ln X] = ψ₁(n), so σ_dB = (10/ln10)·√ψ₁(n). n = 1 gives the well-known 5.57 dB.
     */
    fun fadingStdDb(n: Int): Double = DB_PER_NEPER * sqrt(trigamma(n.coerceAtLeast(1).toDouble()))

    /** Trigamma ψ₁(x) for x > 0 via recurrence plus asymptotic series. */
    fun trigamma(x0: Double): Double {
        var x = x0
        var acc = 0.0
        while (x < 6.0) {
            acc += 1.0 / (x * x)
            x += 1.0
        }
        val x2 = 1.0 / (x * x)
        return acc + 1.0 / x + x2 / 2.0 +
            (1.0 / (x * x * x)) * (1.0 / 6.0 - x2 * (1.0 / 30.0 - x2 * (1.0 / 42.0 - x2 / 30.0)))
    }

    /** Log-distance path-loss inversion: distance (m) from RSSI given the 1 m power and exponent. */
    fun distanceFor(rssiDbm: Double, p0Dbm: Double, exponent: Double): Double =
        10.0.pow((p0Dbm - rssiDbm) / (10.0 * exponent))
}

/** Scalar random-walk Kalman filter for a live "hot/cold" reading in dB. */
class RssiKalman(
    private val processNoiseDb2PerSec: Double = 2.0,
    private val measurementNoiseDb2: Double = 30.0,
) {
    var estimate = Double.NaN
        private set
    var variance = 0.0
        private set
    private var lastTimeMs = 0L

    fun update(rssiDbm: Double, timeMs: Long): Double {
        if (estimate.isNaN()) {
            estimate = rssiDbm
            variance = measurementNoiseDb2
        } else {
            val dt = ((timeMs - lastTimeMs).coerceAtLeast(0)) / 1000.0
            variance += processNoiseDb2PerSec * dt
            val k = variance / (variance + measurementNoiseDb2)
            estimate += k * (rssiDbm - estimate)
            variance *= (1 - k)
        }
        lastTimeMs = timeMs
        return estimate
    }

    fun reset() {
        estimate = Double.NaN
        variance = 0.0
    }
}
