package tagfinder.core

import kotlin.math.abs
import kotlin.math.sqrt

/** 3-D vector in the AR world frame: metres, +Y is up (ARCore convention). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(this dot this)
    fun normalized(): Vec3 {
        val n = norm()
        return if (n < 1e-12) this else this * (1.0 / n)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

enum class PlaneKind { HORIZONTAL_UP, VERTICAL }

/**
 * A finite rectangular surface detected by the camera (ARCore plane bounding rectangle).
 *
 * [u] and [v] are unit vectors spanning the plane, [normal] is perpendicular to it,
 * and [halfU]/[halfV] are the half-extents along u and v.
 */
data class PlaneRect(
    val kind: PlaneKind,
    val center: Vec3,
    val u: Vec3,
    val v: Vec3,
    val normal: Vec3,
    val halfU: Double,
    val halfV: Double,
) {
    /** True if the orthogonal projection of [p] onto the plane falls inside the rectangle. */
    fun projectsInside(p: Vec3, margin: Double = 0.0): Boolean {
        val d = p - center
        return abs(d dot u) <= halfU + margin && abs(d dot v) <= halfV + margin
    }

    /** Signed distance of [p] from the plane along its normal. */
    fun heightAbove(p: Vec3): Double = (p - center) dot normal

    /** True if the open segment a→b passes through the rectangle. */
    fun segmentCrosses(a: Vec3, b: Vec3): Boolean {
        val da = heightAbove(a)
        val db = heightAbove(b)
        if (da * db >= 0.0) return false
        val t = da / (da - db)
        return projectsInside(a + (b - a) * t)
    }
}
