package io.github.orzechowskilukasz.tagfinder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import tagfinder.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Phone heading from the gyro-based GAME_ROTATION_VECTOR. It deliberately ignores the
 * magnetometer: indoors, steel and electronics bend the compass by tens of degrees, while
 * the sweep only needs headings that are consistent relative to each other for ~30 s.
 *
 * Also rotates raw magnetometer readings into that same world frame for [tagfinder.core.MagnetAnomaly].
 */
class OrientationTracker(context: Context) : SensorEventListener {
    private val sm: SensorManager = requireNotNull(context.getSystemService(SensorManager::class.java)) { "No sensor service" }
    private val rot = FloatArray(9)

    var hasRotation = false
        private set

    /** Heading (rad, clockwise) of the direction the back of the phone points. */
    var yawRad = 0.0
        private set

    var magneticWorld: Vec3? = null
        private set

    fun start() {
        val rv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rv?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                hasRotation = true
                // R maps device → world. The camera looks along device −Z.
                var fx = -rot[2]
                var fy = -rot[5]
                val fz = -rot[8]
                if (abs(fz) > 0.8f) { // phone lying flat: use the direction its top edge points
                    fx = rot[1]
                    fy = rot[4]
                }
                yawRad = atan2(fx.toDouble(), fy.toDouble())
            }
            Sensor.TYPE_MAGNETIC_FIELD -> if (hasRotation) {
                val v = e.values
                magneticWorld = Vec3(
                    (rot[0] * v[0] + rot[1] * v[1] + rot[2] * v[2]).toDouble(),
                    (rot[3] * v[0] + rot[4] * v[1] + rot[5] * v[2]).toDouble(),
                    (rot[6] * v[0] + rot[7] * v[1] + rot[8] * v[2]).toDouble(),
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
