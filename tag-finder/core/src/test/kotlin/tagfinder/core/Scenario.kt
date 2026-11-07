package tagfinder.core

import kotlin.random.Random

/** A furnished 6 m × 5 m room with a table, used by tests and the benchmark. */
class Scenario(val seed: Int, tagOnTable: Boolean = seed % 2 == 0) {
    val rng = Random(seed)
    val floor = horizontal(Vec3(3.0, 0.0, 2.5), 3.0, 2.5)
    val table = horizontal(Vec3(rng.nextDouble(1.5, 4.5), 0.75, rng.nextDouble(1.5, 3.5)), 0.6, 0.4)
    val sofa = horizontal(Vec3(rng.nextDouble(1.0, 5.0), 0.45, 4.4), 1.0, 0.4)
    val planes = listOf(floor, table, sofa)

    val tag: Vec3 = if (tagOnTable) {
        Vec3(table.center.x + rng.nextDouble(-0.5, 0.5), 0.78, table.center.z + rng.nextDouble(-0.3, 0.3))
    } else {
        Vec3(rng.nextDouble(0.3, 5.7), 0.02, rng.nextDouble(0.3, 4.7))
    }

    val world = RfWorld(seed * 31 + 7, tag)

    /** Phone path: enter at a corner and walk an L through the room at chest height. */
    val path: List<Pair<Vec3, Vec3>> = WalkGenerator.lWalk(
        rng,
        start = Vec3(0.5, 1.3, 0.5),
        legs = listOf(Vec3(4.5, 0.0, 0.0), Vec3(0.0, 0.0, 3.5)),
    )

    fun samples(noise: Random = Random(seed.toLong() * 17)): List<PosedSample> =
        path.mapIndexed { i, (p, f) -> PosedSample(i * 500L, world.rssi(p, f, noise).toDouble(), p, f) }

    companion object {
        fun horizontal(c: Vec3, hu: Double, hv: Double) = PlaneRect(
            PlaneKind.HORIZONTAL_UP, c, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0), hu, hv,
        )
    }
}

enum class Variant(val label: String) {
    NAIVE("raw RSSI, path loss only"),
    SCRIBBLE("+ scribble averaging"),
    BODY("+ body-shadow model"),
    SURFACES("+ camera surface prior"),
    GUIDED("+ guided 2nd pass to estimate"),
}

fun runVariant(sc: Scenario, v: Variant): LocalizerResult? {
    if (v == Variant.GUIDED) return runGuided(sc)
    val samples = sc.samples()
    val sites = if (v == Variant.NAIVE) {
        samples.map { SiteMeasurement(it.position, it.forward, it.rssiDbm, 1, 1) }
    } else {
        SiteAggregator().apply { samples.forEach(::add) }.snapshot()
    }
    val cfg = LocalizerConfig(
        useBodyShadow = v >= Variant.BODY,
        useSurfacePrior = v >= Variant.SURFACES,
    )
    return Localizer(cfg).solve(sites, if (v >= Variant.SURFACES) sc.planes else emptyList())
}

/**
 * What the app asks the user to do: after the first pass, walk toward the on-screen estimate,
 * stop about 0.7 m short of it and scribble around, then solve again with all readings.
 */
fun runGuided(sc: Scenario): LocalizerResult? {
    val loc = Localizer()
    val agg = SiteAggregator()
    val first = sc.samples()
    first.forEach(agg::add)
    val r1 = loc.solve(agg.snapshot(), sc.planes) ?: return null
    val from = sc.path.last().first
    val target = r1.searchPoint
    val flat = Vec3(target.x - from.x, 0.0, target.z - from.z)
    val leg = flat.normalized() * maxOf(0.3, flat.norm() - 0.7)
    val path2 = WalkGenerator.lWalk(sc.rng, from, listOf(leg), stopEveryM = 0.5)
    val noise = kotlin.random.Random(sc.seed.toLong() * 101)
    path2.forEachIndexed { i, (p, f) ->
        agg.add(PosedSample(first.size * 500L + i * 500L, sc.world.rssi(p, f, noise).toDouble(), p, f))
    }
    return loc.solve(agg.snapshot(), sc.planes)
}

/** Horizontal (floor-plan) error, which is what matters when searching a room. */
fun horizontalError(a: Vec3, b: Vec3): Double = kotlin.math.hypot(a.x - b.x, a.z - b.z)
