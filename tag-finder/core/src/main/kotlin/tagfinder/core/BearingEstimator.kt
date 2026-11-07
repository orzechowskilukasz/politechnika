package tagfinder.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class BearingResult(
    /** Heading (rad, same frame as input yaw) the tag most likely lies in. */
    val bearingRad: Double,
    /** Peak-to-mean body-shadow modulation (dB); larger means a clearer answer. */
    val amplitudeDb: Double,
    /** 1-σ bearing uncertainty (rad) from the least-squares covariance. */
    val sigmaRad: Double,
    val sectorsCovered: Int,
    val samples: Int,
    val residualStdDb: Double,
)

/**
 * Body-shadow direction finding. A human torso attenuates 2.4 GHz by roughly 10–20 dB.
 * Holding the phone against your chest and turning a full circle makes RSSI follow
 * approximately r(θ) = a + b·cos(θ − φ): highest when you face the tag (φ).
 *
 * Writing b·cos(θ − φ) = c₁cosθ + c₂sinθ makes this an ordinary linear least-squares
 * problem; φ = atan2(c₂, c₁), b = √(c₁² + c₂²).
 */
object BearingEstimator {
    private const val SECTORS = 8

    fun estimate(yawRad: List<Double>, rssiDbm: List<Double>, minSectors: Int = 6): BearingResult? {
        require(yawRad.size == rssiDbm.size)
        val n = yawRad.size
        if (n < 6) return null
        val covered = yawRad.map { sector(it) }.toSet().size
        if (covered < minSectors) return null

        val xtx = Array(3) { DoubleArray(3) }
        val xty = DoubleArray(3)
        for (k in 0 until n) {
            val row = doubleArrayOf(1.0, cos(yawRad[k]), sin(yawRad[k]))
            for (i in 0..2) {
                xty[i] += row[i] * rssiDbm[k]
                for (j in 0..2) xtx[i][j] += row[i] * row[j]
            }
        }
        val inv = LinAlg.invert3(xtx) ?: return null
        val beta = DoubleArray(3) { i -> (0..2).sumOf { j -> inv[i][j] * xty[j] } }
        val (a, c1, c2) = Triple(beta[0], beta[1], beta[2])
        var rss = 0.0
        for (k in 0 until n) {
            val e = rssiDbm[k] - (a + c1 * cos(yawRad[k]) + c2 * sin(yawRad[k]))
            rss += e * e
        }
        val s2 = rss / (n - 3).coerceAtLeast(1)
        val b = hypot(c1, c2)
        // Delta method: ∂φ/∂c₁ = −c₂/b², ∂φ/∂c₂ = c₁/b².
        val g1 = -c2 / (b * b)
        val g2 = c1 / (b * b)
        val varPhi = s2 * (g1 * g1 * inv[1][1] + 2 * g1 * g2 * inv[1][2] + g2 * g2 * inv[2][2])
        return BearingResult(
            bearingRad = wrap(atan2(c2, c1)),
            amplitudeDb = b,
            sigmaRad = sqrt(varPhi.coerceAtLeast(0.0)).coerceAtMost(PI),
            sectorsCovered = covered,
            samples = n,
            residualStdDb = sqrt(s2),
        )
    }

    fun sector(yaw: Double): Int = floor(wrap(yaw).let { if (it < 0) it + 2 * PI else it } / (2 * PI) * SECTORS)
        .toInt().coerceIn(0, SECTORS - 1)

    fun wrap(a: Double): Double {
        var x = a % (2 * PI)
        if (x > PI) x -= 2 * PI
        if (x < -PI) x += 2 * PI
        return x
    }
}

internal object LinAlg {
    fun invert3(m: Array<DoubleArray>): Array<DoubleArray>? {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
        val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
        val g = m[2][0]; val h = m[2][1]; val i = m[2][2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12) return null
        val r = 1.0 / det
        return arrayOf(
            doubleArrayOf((e * i - f * h) * r, (c * h - b * i) * r, (b * f - c * e) * r),
            doubleArrayOf((f * g - d * i) * r, (a * i - c * g) * r, (c * d - a * f) * r),
            doubleArrayOf((d * h - e * g) * r, (b * g - a * h) * r, (a * e - b * d) * r),
        )
    }
}
