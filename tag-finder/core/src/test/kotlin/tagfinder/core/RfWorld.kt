package tagfinder.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic indoor radio channel used to test the estimators.
 *
 * RSSI = P₀ − 10·n·log₁₀d − body shadow − walls + shadowing(p) + fading(p, channel) + noise,
 * rounded to whole dB like Android reports it.
 *  - Shadowing: Gaussian random field (sum of random plane waves) with ~[shadowCorrM] correlation.
 *  - Fading: Rayleigh power, independent per λ/2 cell and per advertising channel.
 */
class RfWorld(
    seed: Int,
    val tag: Vec3,
    val p0: Double = -62.0,
    val exponent: Double = 2.2,
    val shadowStdDb: Double = 4.0,
    val shadowCorrM: Double = 1.5,
    val bodyDb: Double = 9.0,
    val walls: List<PlaneRect> = emptyList(),
    val wallLossDb: Double = 6.0,
    val fading: Boolean = true,
) {
    private val rng = Random(seed)
    private val fadeSalt = rng.nextLong()
    private val waves = List(48) {
        val k = Vec3(rng.gauss(), rng.gauss(), rng.gauss()) * (1.0 / shadowCorrM)
        k to rng.nextDouble(0.0, 2 * PI)
    }
    private val waveAmp = shadowStdDb * sqrt(2.0 / waves.size)

    fun shadowing(p: Vec3): Double = waves.sumOf { (k, phase) -> waveAmp * cos((k dot p) + phase) }

    fun rssi(p: Vec3, forward: Vec3, noise: Random): Int {
        val v = tag - p
        val d = maxOf(v.norm(), 0.1)
        var r = p0 - 10 * exponent * log10(d)
        val cosA = (v dot forward.normalized()) / d
        r -= bodyDb * (1 - cosA) / 2
        for (w in walls) if (w.segmentCrosses(p, tag)) r -= wallLossDb
        r += shadowing(p)
        if (fading) {
            val channel = noise.nextInt(3)
            val cell = Rssi.FADING_DECORRELATION_M
            val h = hash(floor(p.x / cell).toLong(), floor(p.y / cell).toLong(), floor(p.z / cell).toLong(), channel.toLong())
            val u = ((h ushr 11).toDouble() / (1L shl 53)).coerceIn(1e-9, 1.0)
            r += 10 * log10(-ln(u))
        }
        r += noise.gauss()
        return r.roundToInt()
    }

    private fun hash(a: Long, b: Long, c: Long, d: Long): Long {
        var h = fadeSalt
        for (x in longArrayOf(a, b, c, d)) {
            h = (h xor x) * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
        }
        return h
    }
}

fun Random.gauss(): Double {
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
}

/** A person walking through a room, stopping every [stopEveryM] to scribble the phone around. */
object WalkGenerator {
    fun lWalk(
        rng: Random,
        start: Vec3,
        legs: List<Vec3>,
        stopEveryM: Double = 0.6,
        scribbleSeconds: Double = 6.0,
        adIntervalS: Double = 0.5,
        scribbleRadiusM: Double = 0.15,
    ): List<Pair<Vec3, Vec3>> {
        val out = ArrayList<Pair<Vec3, Vec3>>()
        var pos = start
        for (leg in legs) {
            val len = leg.norm()
            val dir = leg.normalized()
            val forward = (dir + Vec3(0.0, -0.3, 0.0)).normalized()
            val stops = maxOf(1, (len / stopEveryM).toInt())
            for (s in 0 until stops) {
                val stop = pos + dir * (len * s / stops)
                val n = (scribbleSeconds / adIntervalS).toInt()
                val fx = rng.nextDouble(0.7, 1.3); val fy = rng.nextDouble(1.1, 1.9); val ph = rng.nextDouble(0.0, 2 * PI)
                for (k in 0 until n) {
                    val t = k * adIntervalS
                    val off = Vec3(
                        scribbleRadiusM * sin(fx * t + ph),
                        scribbleRadiusM * 0.6 * sin(fy * t),
                        scribbleRadiusM * cos(fx * t * 1.3),
                    )
                    // Wrist rotation while scribbling also swings the camera a little.
                    val wobble = Vec3(0.2 * sin(0.9 * t), 0.0, 0.2 * cos(0.7 * t))
                    out += (stop + off) to (forward + wobble).normalized()
                }
            }
            pos += leg
        }
        return out
    }
}
