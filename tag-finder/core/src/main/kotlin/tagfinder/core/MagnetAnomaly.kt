package tagfinder.core

/**
 * Last-centimetres helper. An AirTag contains a small speaker magnet. A dipole field falls
 * off as 1/r³, so it only stands out against Earth's ~25–65 µT within a few centimetres,
 * but that is exactly the "is it in this cushion?" range.
 *
 * Feed it the magnetic field rotated into a world frame (so turning the phone does not
 * look like an anomaly) and it reports |B − B₀| against a baseline captured away from the tag.
 */
class MagnetAnomaly(private val smoothing: Double = 0.3) {
    private var baseline: Vec3? = null
    private var smoothed = 0.0

    val calibrated: Boolean get() = baseline != null

    fun calibrate(fieldWorldMicroTesla: Vec3) {
        baseline = fieldWorldMicroTesla
        smoothed = 0.0
    }

    /** Returns the smoothed anomaly in µT, or NaN before calibration. */
    fun update(fieldWorldMicroTesla: Vec3): Double {
        val b0 = baseline ?: return Double.NaN
        val raw = (fieldWorldMicroTesla - b0).norm()
        smoothed += smoothing * (raw - smoothed)
        return smoothed
    }
}
