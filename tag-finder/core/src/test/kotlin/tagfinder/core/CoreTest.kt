package tagfinder.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RssiTest {
    @Test
    fun `single Rayleigh look has 5_57 dB spread and averaging shrinks it`() {
        assertEquals(5.57, Rssi.fadingStdDb(1), 0.01)
        assertTrue(Rssi.fadingStdDb(25) < 1.2)
    }

    @Test
    fun `trigamma matches known values`() {
        assertEquals(PI * PI / 6, Rssi.trigamma(1.0), 1e-9)
        assertEquals(PI * PI / 6 - 1, Rssi.trigamma(2.0), 1e-9)
    }

    @Test
    fun `distance inversion round trips`() {
        assertEquals(4.0, Rssi.distanceFor(-60 - 20 * kotlin.math.log10(4.0), -60.0, 2.0), 1e-9)
    }
}

class FindMyAdvertisementTest {
    private fun separatedPayload(status: Int): ByteArray =
        byteArrayOf(0x12, 25, status.toByte()) + ByteArray(22) { it.toByte() } + byteArrayOf(0x01, 0x00)

    @Test
    fun `parses separated advertisement`() {
        val ad = FindMyAdvertisement.parse(separatedPayload(0x10 or (2 shl 6)))
        assertNotNull(ad)
        assertTrue(ad.separated)
        assertTrue(ad.probablyAirTag)
        assertEquals(FindMyAdvertisement.Battery.LOW, ad.battery)
        assertEquals(22, ad.keyFragment.size)
    }

    @Test
    fun `parses near-owner advertisement after another record`() {
        val data = byteArrayOf(0x10, 2, 1, 2, 0x12, 2, 0x00, 0x05)
        val ad = FindMyAdvertisement.parse(data)
        assertNotNull(ad)
        assertFalse(ad.separated)
    }

    @Test
    fun `rejects other and truncated data`() {
        assertNull(FindMyAdvertisement.parse(byteArrayOf(0x10, 5, 1)))
        assertNull(FindMyAdvertisement.parse(byteArrayOf(0x07, 1, 0)))
        assertNull(FindMyAdvertisement.parse(null))
    }
}

class TargetTrackerTest {
    private val ad = FindMyAdvertisement(0x10, true, ByteArray(22))

    @Test
    fun `follows a single address rotation`() {
        val t = TargetTracker("A")
        assertTrue(t.accept("A", -60.0, ad, 0))
        assertFalse(t.accept("B", -61.0, ad, 500)) // another device, active while A is
        assertTrue(t.accept("A", -60.0, ad, 1_000))
        assertFalse(t.accept("C", -59.0, ad, 5_000)) // A only silent for 4 s: too soon
        assertFalse(t.accept("B", -60.0, ad, 9_500)) // B predates A's silence: never a successor
        assertTrue(t.accept("C", -59.0, ad, 10_000))
        assertEquals("C", t.address)
        assertEquals(1, t.handovers)
    }

    @Test
    fun `does not switch when ambiguous`() {
        val t = TargetTracker("A")
        t.accept("A", -60.0, ad, 0)
        t.accept("C", -60.0, ad, 3_000)
        t.accept("D", -61.0, ad, 4_000)
        assertFalse(t.accept("C", -60.0, ad, 9_000))
        assertEquals("A", t.address)
    }
}

class GeometryTest {
    private val wall = PlaneRect(
        PlaneKind.VERTICAL, Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), 2.0, 1.0,
    )

    @Test
    fun `segment crossing`() {
        assertTrue(wall.segmentCrosses(Vec3(-1.0, 1.0, 0.5), Vec3(1.0, 1.0, 0.5)))
        assertFalse(wall.segmentCrosses(Vec3(-1.0, 1.0, 5.0), Vec3(1.0, 1.0, 5.0)))
        assertFalse(wall.segmentCrosses(Vec3(0.5, 1.0, 0.5), Vec3(1.0, 1.0, 0.5)))
    }
}

class BearingEstimatorTest {
    @Test
    fun `finds direction from body shadowing under fading`() {
        val errors = (1..20).map { seed ->
            val rng = Random(seed)
            val tag = Vec3(3 * cos(seed.toDouble()), 0.8, 3 * sin(seed.toDouble()))
            val world = RfWorld(seed, tag)
            val p = Vec3(0.0, 1.3, 0.0)
            val yaws = ArrayList<Double>()
            val rssi = ArrayList<Double>()
            // 1.5 slow turns, one reading every ~10°, with the hand drifting a few cm.
            for (k in 0 until 54) {
                val yaw = k * 2 * PI / 36
                val wobble = Vec3(0.05 * sin(k * 0.7), 0.0, 0.05 * cos(k * 0.9))
                val f = Vec3(cos(yaw), 0.0, sin(yaw))
                yaws += yaw
                rssi += world.rssi(p + wobble + f * 0.25, f, rng).toDouble()
            }
            val est = BearingEstimator.estimate(yaws, rssi)!!
            abs(BearingEstimator.wrap(est.bearingRad - atan2(tag.z - p.z, tag.x - p.x)))
        }.sorted()
        val medianDeg = errors[errors.size / 2] * 180 / PI
        println("Body-shadow bearing: median error %.0f°".format(medianDeg))
        assertTrue(medianDeg < 35, "median bearing error $medianDeg°")
    }

    @Test
    fun `refuses partial sweeps`() {
        val yaws = List(10) { it * 0.1 }
        assertNull(BearingEstimator.estimate(yaws, List(10) { -60.0 }))
    }
}

class LocalizerTest {
    @Test
    fun `recovers tag in noiseless world`() {
        val sc = Scenario(3)
        val world = RfWorld(1, sc.tag, shadowStdDb = 0.0, fading = false)
        val rng = Random(1)
        val samples = sc.path.map { (p, f) -> PosedSample(0, world.rssi(p, f, rng).toDouble(), p, f) }
        val sites = SiteAggregator().apply { samples.forEach(::add) }.snapshot()
        val r = Localizer(LocalizerConfig(bodyShadowDb = world.bodyDb)).solve(sites, sc.planes)!!
        assertTrue(horizontalError(r.map, sc.tag) < 0.5, "error ${horizontalError(r.map, sc.tag)}")
    }

    @Test
    fun `each sensor-fusion step improves accuracy on average`() {
        val trials = 24
        val mean = Variant.entries.associateWith { v ->
            (1..trials).map { seed ->
                val sc = Scenario(seed)
                horizontalError(runVariant(sc, v)!!.searchPoint, sc.tag)
            }.average()
        }
        mean.forEach { (v, e) -> println("%-30s mean error %.2f m".format(v.label, e)) }
        // Thresholds come from the benchmark (./gradlew -PcoreOnly :core:simulate) with margin.
        assertTrue(mean[Variant.BODY]!! < mean[Variant.NAIVE]!! - 0.5)
        assertTrue(mean[Variant.GUIDED]!! < mean[Variant.SURFACES]!!)
        assertTrue(mean[Variant.GUIDED]!! < 1.4)
    }

    @Test
    fun `needs a real aperture`() {
        val s = List(10) { SiteMeasurement(Vec3(0.0, 1.3, 0.0), Vec3(1.0, 0.0, 0.0), -60.0, 5, 5) }
        assertNull(Localizer().solve(s))
    }
}
