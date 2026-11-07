package tagfinder.core

import kotlin.math.floor

/** One RSSI reading tagged with where the phone was and which way its camera faced. */
data class PosedSample(val timeMs: Long, val rssiDbm: Double, val position: Vec3, val forward: Vec3)

/**
 * A "site": all readings taken while the phone was inside one small cube of space,
 * reduced to a local-mean power estimate.
 *
 * [independentLooks] counts distinct λ/2 cells visited. Readings from the same λ/2 cell see
 * the same multipath fade, so only distinct cells reduce the fading error.
 */
data class SiteMeasurement(
    val position: Vec3,
    val forward: Vec3,
    val meanDbm: Double,
    val samples: Int,
    val independentLooks: Int,
) {
    val fadingStdDb: Double get() = Rssi.fadingStdDb(independentLooks)
}

/**
 * Spatial-diversity averaging ("scribble scanning").
 *
 * Indoors the received power is (path loss) × (shadowing, metres-scale) × (multipath fading,
 * centimetre-scale). Waving the phone around a ~30 cm region makes it sample many
 * independent fades of the *same* path loss and shadowing. Averaging those samples in
 * linear power (not dB, which is biased low by ~2.5 dB under Rayleigh fading) gives the
 * local mean, with error falling roughly as 5.6 dB / √N.
 */
class SiteAggregator(
    private val siteSizeM: Double = 0.30,
    private val fadingCellM: Double = Rssi.FADING_DECORRELATION_M,
) {
    private class Acc {
        var sumMw = 0.0
        var count = 0
        var sumPos = Vec3.ZERO
        var sumFwd = Vec3.ZERO
        val cells = HashSet<Long>()
    }

    private val sites = LinkedHashMap<Long, Acc>()
    var totalSamples = 0
        private set

    fun add(s: PosedSample) {
        val acc = sites.getOrPut(key(s.position, siteSizeM)) { Acc() }
        acc.sumMw += Rssi.dbmToMw(s.rssiDbm)
        acc.count++
        acc.sumPos += s.position
        acc.sumFwd += s.forward
        acc.cells += key(s.position, fadingCellM)
        totalSamples++
    }

    fun clear() {
        sites.clear()
        totalSamples = 0
    }

    fun snapshot(): List<SiteMeasurement> = sites.values.map { a ->
        SiteMeasurement(
            position = a.sumPos * (1.0 / a.count),
            forward = a.sumFwd.normalized(),
            meanDbm = Rssi.mwToDbm(a.sumMw / a.count),
            samples = a.count,
            independentLooks = minOf(a.count, a.cells.size),
        )
    }

    private fun key(p: Vec3, cell: Double): Long {
        val ix = floor(p.x / cell).toLong() and 0x1FFFFF
        val iy = floor(p.y / cell).toLong() and 0x1FFFFF
        val iz = floor(p.z / cell).toLong() and 0x1FFFFF
        return (ix shl 42) or (iy shl 21) or iz
    }
}
