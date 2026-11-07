package tagfinder.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class LocalizerConfig(
    val cellM: Double = 0.20,
    val horizontalMarginM: Double = 4.0,
    /** Phone is assumed to be held at least this high above the floor if no floor plane is known. */
    val assumedPhoneHeightM: Double = 1.6,
    val ceilingAbovePhoneM: Double = 1.0,
    /** Shadowing σ (dB): large-scale variation not explained by distance. Typical indoors: 3–6 dB. */
    val shadowingStdDb: Double = 4.0,
    /** Attenuation when the user's body is directly between phone and tag (dB). */
    val bodyShadowDb: Double = 8.0,
    /** Extra loss per detected wall crossed (dB). Drywall ≈ 3–5, brick/concrete ≈ 8–15. */
    val wallLossDb: Double = 5.0,
    /** Shadowing decorrelation distance (m). Sites closer than this are not independent. */
    val shadowingDecorrelationM: Double = 1.0,
    /** Gaussian priors (mean, σ) for the 1 m power and path-loss exponent. */
    val p0Prior: Pair<Double, Double> = -60.0 to 12.0,
    val exponentPrior: Pair<Double, Double> = 2.0 to 0.6,
    val exponentRange: ClosedFloatingPointRange<Double> = 1.4..4.5,
    /** Prior log-odds penalty for a cell that is not resting on a detected surface. */
    val offSurfaceLogPrior: Double = -2.0,
    /** Height band above a horizontal surface counted as "on" it (m). */
    val onSurfaceBandM: Double = 0.25,
    val useBodyShadow: Boolean = true,
    val useSurfacePrior: Boolean = true,
    val useWalls: Boolean = true,
    val minSites: Int = 4,
    val minApertureM: Double = 1.0,
)

class LocalizerResult(
    val origin: Vec3,
    val cellM: Double,
    val nx: Int,
    val ny: Int,
    val nz: Int,
    /** Normalised posterior over cells, index = (ix·ny + iy)·nz + iz. */
    val posterior: DoubleArray,
    val map: Vec3,
    val mean: Vec3,
    val p0Dbm: Double,
    val exponent: Double,
    val sitesUsed: Int,
) {
    fun cellCenter(ix: Int, iy: Int, iz: Int) =
        Vec3(origin.x + (ix + 0.5) * cellM, origin.y + (iy + 0.5) * cellM, origin.z + (iz + 0.5) * cellM)

    /** Top-down (x, z) marginal, index = ix·nz + iz. */
    val floorMarginal: DoubleArray by lazy {
        val m = DoubleArray(nx * nz)
        for (ix in 0 until nx) for (iy in 0 until ny) for (iz in 0 until nz) {
            m[ix * nz + iz] += posterior[(ix * ny + iy) * nz + iz]
        }
        m
    }

    /**
     * Best place to search: the peak of the top-down marginal (more stable than the single
     * best 3-D cell because it integrates over height), at the most likely height in that column.
     */
    val searchPoint: Vec3 by lazy {
        val fm = floorMarginal
        var best = 0
        for (i in fm.indices) if (fm[i] > fm[best]) best = i
        val ix = best / nz
        val iz = best % nz
        var by = 0
        for (iy in 0 until ny) if (posterior[(ix * ny + iy) * nz + iz] > posterior[(ix * ny + by) * nz + iz]) by = iy
        cellCenter(ix, by, iz)
    }

    /** Radius (m) of a circle with the same floor area as the 50 % credible region. */
    val radius50M: Double by lazy {
        var acc = 0.0
        var cells = 0
        for (p in floorMarginal.sortedArrayDescending()) {
            acc += p; cells++
            if (acc >= 0.5) break
        }
        sqrt(cells * cellM * cellM / PI)
    }
}

/**
 * Bayesian grid localiser ("synthetic aperture" over a phone walk).
 *
 * Measurement model for site i (phone at pᵢ, camera direction fᵢ) and candidate tag position s:
 *
 *   mᵢ = P₀ − n·10·log₁₀‖s − pᵢ‖ − B·(1 − cos∠(s − pᵢ, fᵢ))/2 − L_w·walls(pᵢ, s) + εᵢ
 *   εᵢ ~ N(0, σ_shadow² + σ_fade,i²)
 *
 * The model is linear in (P₀, n), so for every candidate s they are solved in closed form
 * (ridge-regularised by their priors) and the residual gives a profile likelihood.
 * The posterior adds a camera-derived prior: things usually rest on floors, tables or shelves.
 * Shadowing is spatially correlated, so the likelihood is tempered by
 * (independent 1 m cells / sites) to avoid over-confidence.
 */
class Localizer(private val cfg: LocalizerConfig = LocalizerConfig()) {

    fun solve(sites: List<SiteMeasurement>, planes: List<PlaneRect> = emptyList()): LocalizerResult? {
        if (sites.size < cfg.minSites) return null
        val minX = sites.minOf { it.position.x }; val maxX = sites.maxOf { it.position.x }
        val minY = sites.minOf { it.position.y }; val maxY = sites.maxOf { it.position.y }
        val minZ = sites.minOf { it.position.z }; val maxZ = sites.maxOf { it.position.z }
        val aperture = sqrt((maxX - minX).sq() + (maxY - minY).sq() + (maxZ - minZ).sq())
        if (aperture < cfg.minApertureM) return null

        val horizontal = planes.filter { it.kind == PlaneKind.HORIZONTAL_UP }
        val walls = if (cfg.useWalls) planes.filter { it.kind == PlaneKind.VERTICAL } else emptyList()
        val floorY = horizontal.minOfOrNull { it.center.y }?.takeIf { it < minY - 0.5 }
            ?: (minY - cfg.assumedPhoneHeightM)

        val c = cfg.cellM
        val origin = Vec3(minX - cfg.horizontalMarginM, floorY - c / 2, minZ - cfg.horizontalMarginM)
        val nx = ((maxX - minX + 2 * cfg.horizontalMarginM) / c).toInt() + 1
        val ny = ((maxY + cfg.ceilingAbovePhoneM - origin.y) / c).toInt() + 1
        val nz = ((maxZ - minZ + 2 * cfg.horizontalMarginM) / c).toInt() + 1

        val m = sites.size
        val px = DoubleArray(m) { sites[it].position.x }
        val py = DoubleArray(m) { sites[it].position.y }
        val pz = DoubleArray(m) { sites[it].position.z }
        val w = DoubleArray(m) { 1.0 / (cfg.shadowingStdDb.sq() + sites[it].fadingStdDb.sq()) }
        val temper = independentShadowCells(sites).toDouble() / m

        val (muP, sdP) = cfg.p0Prior
        val (muN, sdN) = cfg.exponentPrior
        val lamP = 1.0 / sdP.sq()
        val lamN = 1.0 / sdN.sq()

        val total = nx * ny * nz
        val logPost = DoubleArray(total)
        val bestP0 = DoubleArray(total)
        val bestN = DoubleArray(total)
        val u = DoubleArray(m)
        val y = DoubleArray(m)

        for (ix in 0 until nx) for (iy in 0 until ny) for (iz in 0 until nz) {
            val sx = origin.x + (ix + 0.5) * c
            val sy = origin.y + (iy + 0.5) * c
            val sz = origin.z + (iz + 0.5) * c
            val s = Vec3(sx, sy, sz)
            var sw = lamP; var swu = 0.0; var swuu = lamN; var swy = lamP * muP; var swuy = lamN * muN
            for (i in 0 until m) {
                val dx = sx - px[i]; val dy = sy - py[i]; val dz = sz - pz[i]
                val d = max(sqrt(dx * dx + dy * dy + dz * dz), 0.15)
                u[i] = -10.0 * log10(d)
                var extra = 0.0
                if (cfg.useBodyShadow) {
                    val f = sites[i].forward
                    val cosA = (dx * f.x + dy * f.y + dz * f.z) / d
                    extra += cfg.bodyShadowDb * (1.0 - cosA) / 2.0
                }
                if (walls.isNotEmpty()) {
                    val p = sites[i].position
                    for (wall in walls) if (wall.segmentCrosses(p, s)) extra += cfg.wallLossDb
                }
                y[i] = sites[i].meanDbm + extra
                sw += w[i]; swu += w[i] * u[i]; swuu += w[i] * u[i] * u[i]
                swy += w[i] * y[i]; swuy += w[i] * u[i] * y[i]
            }
            val det = sw * swuu - swu * swu
            var n = (sw * swuy - swu * swy) / det
            n = n.coerceIn(cfg.exponentRange)
            val p0 = (swy - n * swu) / sw
            var cost = lamP * (p0 - muP).sq() + lamN * (n - muN).sq()
            for (i in 0 until m) cost += w[i] * (y[i] - p0 - n * u[i]).sq()
            val idx = (ix * ny + iy) * nz + iz
            logPost[idx] = -0.5 * temper * cost + logPrior(s, horizontal, floorY)
            bestP0[idx] = p0
            bestN[idx] = n
        }

        val maxLog = logPost.max()
        val post = DoubleArray(total) { exp(logPost[it] - maxLog) }
        val z = post.sum()
        for (i in post.indices) post[i] /= z

        var best = 0
        var mx = 0.0; var my = 0.0; var mz = 0.0
        for (i in post.indices) {
            if (post[i] > post[best]) best = i
            val ix = i / (ny * nz); val iy = (i / nz) % ny; val iz = i % nz
            mx += post[i] * (origin.x + (ix + 0.5) * c)
            my += post[i] * (origin.y + (iy + 0.5) * c)
            mz += post[i] * (origin.z + (iz + 0.5) * c)
        }
        return LocalizerResult(
            origin, c, nx, ny, nz, post,
            map = Vec3(
                origin.x + (best / (ny * nz) + 0.5) * c,
                origin.y + ((best / nz) % ny + 0.5) * c,
                origin.z + (best % nz + 0.5) * c,
            ),
            mean = Vec3(mx, my, mz),
            p0Dbm = bestP0[best],
            exponent = bestN[best],
            sitesUsed = m,
        )
    }

    private fun logPrior(s: Vec3, horizontal: List<PlaneRect>, floorY: Double): Double {
        if (!cfg.useSurfacePrior) return 0.0
        if (s.y < floorY - cfg.cellM) return cfg.offSurfaceLogPrior * 3
        if (s.y - floorY <= cfg.onSurfaceBandM) return 0.0
        for (p in horizontal) {
            val h = p.heightAbove(s)
            if (h >= -cfg.cellM / 2 && h <= cfg.onSurfaceBandM && p.projectsInside(s, cfg.cellM / 2)) return 0.0
        }
        return cfg.offSurfaceLogPrior
    }

    private fun independentShadowCells(sites: List<SiteMeasurement>): Int {
        val d = cfg.shadowingDecorrelationM
        return sites.map {
            Triple(floor(it.position.x / d).toInt(), floor(it.position.y / d).toInt(), floor(it.position.z / d).toInt())
        }.toSet().size.coerceIn(1, sites.size)
    }

    private fun Double.sq() = this * this
}
