package io.github.orzechowskilukasz.tagfinder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import tagfinder.core.Localizer
import tagfinder.core.LocalizerResult
import tagfinder.core.PlaneKind
import tagfinder.core.PlaneRect
import tagfinder.core.PosedSample
import tagfinder.core.SiteAggregator
import tagfinder.core.TargetTracker
import tagfinder.core.Vec3
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Camera-assisted localisation.
 *
 * ARCore visual-inertial odometry gives the phone's position to ~centimetres, which lets us
 * (1) tag every RSSI reading with where it was taken, (2) know which way the camera, and so the
 * user's body, faced, and (3) detect floors, tables and walls. The core [Localizer] fuses these
 * into a probability map; the most likely spot is drawn over the camera view.
 */
class ArScanActivity : Activity(), GLSurfaceView.Renderer, ScanHub.Listener {
    private lateinit var surface: GLSurfaceView
    private lateinit var marker: MarkerOverlay
    private lateinit var heatmap: HeatmapView
    private lateinit var status: TextView
    private lateinit var tracker: TargetTracker

    @Volatile private var session: Session? = null
    @Volatile private var boundSession: Session? = null
    @Volatile private var geometryDirty = true
    @Volatile private var displayRotation = 0
    @Volatile private var planes: List<PlaneRect> = emptyList()
    private var installRequested = false
    private val background = BackgroundRenderer()
    private var viewW = 0
    private var viewH = 0
    private var frameCount = 0

    // Written on the GL thread, read on the main thread.
    private val lock = Any()
    private var camPos: Vec3? = null
    private var camFwd: Vec3? = null
    private var tracking = false
    private val viewMtx = FloatArray(16)
    private val projMtx = FloatArray(16)

    // Main thread only.
    private val aggregator = SiteAggregator()
    private val path = ArrayList<Vec3>()
    private val localizer = Localizer()
    private val executor = Executors.newSingleThreadExecutor()
    private var solving = false
    private var result: LocalizerResult? = null
    private var lastRssi = Double.NaN
    private var lastSolveMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateUi()
            if (android.os.SystemClock.elapsedRealtime() - lastSolveMs > 1_500) solve()
            handler.postDelayed(this, 66)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracker = TargetTracker(intent.getStringExtra(LocateActivity.EXTRA_ADDRESS) ?: run { finish(); return })
        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArScanActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        marker = MarkerOverlay(this)
        heatmap = HeatmapView(this).apply { setBackgroundColor(Palette.panel) }
        status = label("Starting camera…", 14f).apply { setBackgroundColor(Palette.panel) }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.panel)
            addView(button("Reset") { reset() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Done") { finish() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heatmap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)))
            addView(buttons)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(surface)
                addView(marker)
                addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            },
        )
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        displayRotation = windowManager.defaultDisplay.rotation
        if (session == null && !createSession()) return
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            status.text = "Camera not available. Close other camera apps and try again."
            session = null
            return
        }
        surface.onResume()
        if (!ScanHub.addListener(this, this)) status.text = ScanHub.lastError
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        ScanHub.removeListener(this)
        if (this::surface.isInitialized) surface.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        session?.close()
        session = null
    }

    private fun createSession(): Boolean {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
            return false
        }
        return try {
            if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return false
            }
            val s = Session(this)
            val config = Config(s).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
            }
            s.configure(config)
            session = s
            geometryDirty = true
            true
        } catch (e: UnavailableException) {
            status.text = "AR is not available on this phone (${e.javaClass.simpleName}). Use the hot/cold, direction and magnet modes instead."
            false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            status.text = "The camera is needed to measure where you are in the room."
        }
    }

    private fun reset() {
        aggregator.clear()
        path.clear()
        result = null
    }

    // ---- Bluetooth (main thread) ----

    override fun onSighting(s: ScanHub.Sighting) {
        if (!tracker.accept(s.address, s.rssi.toDouble(), s.ad, s.timeMs)) return
        lastRssi = s.rssi.toDouble()
        val (pos, fwd) = synchronized(lock) { if (tracking) camPos to camFwd else null to null }
        if (pos == null || fwd == null) return
        aggregator.add(PosedSample(s.timeMs, s.rssi.toDouble(), pos, fwd))
        if (path.isEmpty() || (path.last() - pos).norm() > 0.1) path += pos
    }

    private fun solve() {
        if (solving) return
        lastSolveMs = android.os.SystemClock.elapsedRealtime()
        val sites = aggregator.snapshot()
        val pl = planes
        solving = true
        executor.execute {
            val r = try {
                localizer.solve(sites, pl)
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (r != null) result = r
                solving = false
            }
        }
    }

    private fun updateUi() {
        val view = FloatArray(16)
        val proj = FloatArray(16)
        val (pos, fwd, isTracking) = synchronized(lock) {
            System.arraycopy(viewMtx, 0, view, 0, 16)
            System.arraycopy(projMtx, 0, proj, 0, 16)
            Triple(camPos, camFwd, tracking)
        }
        val r = result
        heatmap.set(r, path, pos, fwd)

        if (r != null && pos != null && isTracking) {
            val target = r.searchPoint
            val clip = FloatArray(4)
            val vp = FloatArray(16)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(target.x.toFloat(), target.y.toFloat(), target.z.toFloat(), 1f), 0)
            val dist = (target - pos).norm()
            val caption = "%.1f m (±%.1f m)".format(dist, r.radius50M)
            val w = clip[3]
            val nx = clip[0] / w
            val ny = clip[1] / w
            if (w > 0 && abs(nx) <= 1f && abs(ny) <= 1f) {
                marker.showAt((nx + 1) / 2 * marker.width, (1 - ny) / 2 * marker.height, caption)
            } else {
                val sign = if (w > 0) 1f else -1f
                marker.showOffscreen(atan2((-ny * sign).toDouble(), (nx * sign).toDouble()), caption)
            }
        } else {
            marker.hide()
        }

        val n = aggregator.totalSamples
        status.text = buildString {
            append(
                when {
                    !isTracking -> "Point the camera at the room and move the phone slowly so it can see where you are."
                    r == null -> "Walk slowly through the room. Every step or two, stop and wave the phone in small circles for a few seconds."
                    r.radius50M > 1.0 -> "Walk toward the marker, stop about 1 m short and wave the phone around again."
                    else -> "Close. Search around the marker, or switch to hot/cold or the magnet for the last metre."
                },
            )
            append("\n$n readings at ${r?.sitesUsed ?: aggregator.snapshot().size} spots")
            if (!lastRssi.isNaN()) append(" · last ${lastRssi.toInt()} dBm")
            if (r != null) append(" · fit: %.0f dBm at 1 m, exponent %.1f".format(r.p0Dbm, r.exponent))
            if (planes.isNotEmpty()) append(" · ${planes.size} surfaces")
        }
    }

    // ---- GL thread ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        boundSession = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width
        viewH = height
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (boundSession !== s) {
            s.setCameraTextureName(background.textureId)
            boundSession = s
        }
        if (geometryDirty && viewW > 0) {
            s.setDisplayGeometry(displayRotation, viewW, viewH)
            geometryDirty = false
        }
        val frame = try {
            s.update()
        } catch (e: Exception) {
            return
        }
        background.draw(frame)

        val cam = frame.camera
        synchronized(lock) {
            tracking = cam.trackingState == TrackingState.TRACKING
            if (tracking) {
                val pose = cam.pose
                val z = pose.zAxis
                camPos = Vec3(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble())
                camFwd = Vec3(-z[0].toDouble(), -z[1].toDouble(), -z[2].toDouble())
                cam.getViewMatrix(viewMtx, 0)
                cam.getProjectionMatrix(projMtx, 0, 0.05f, 100f)
            }
        }
        if (++frameCount % 30 == 0) {
            planes = s.getAllTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                .mapNotNull(::toRect)
        }
    }

    private fun toRect(p: Plane): PlaneRect? {
        val kind = when (p.type) {
            Plane.Type.HORIZONTAL_UPWARD_FACING -> PlaneKind.HORIZONTAL_UP
            Plane.Type.VERTICAL -> PlaneKind.VERTICAL
            else -> return null
        }
        val pose = p.centerPose
        fun v(a: FloatArray) = Vec3(a[0].toDouble(), a[1].toDouble(), a[2].toDouble())
        return PlaneRect(
            kind = kind,
            center = Vec3(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble()),
            u = v(pose.xAxis),
            v = v(pose.zAxis),
            normal = v(pose.yAxis),
            halfU = p.extentX / 2.0,
            halfV = p.extentZ / 2.0,
        )
    }
}
