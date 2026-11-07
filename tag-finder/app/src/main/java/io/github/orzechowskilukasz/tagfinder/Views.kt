package io.github.orzechowskilukasz.tagfinder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import tagfinder.core.LocalizerResult
import tagfinder.core.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Horizontal "hot/cold" bar. */
class MeterView(context: Context) : View(context) {
    private var fraction = 0f
    private var hot = false
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.track }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(fraction: Double, hot: Boolean) {
        this.fraction = fraction.coerceIn(0.0, 1.0).toFloat()
        this.hot = hot
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, track)
        fill.color = if (hot) Palette.warm else Palette.accent
        if (fraction > 0f) c.drawRoundRect(RectF(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat()), r, r, fill)
    }
}

/**
 * Sweep plot. Up is the direction the user is currently facing; dots are readings at the
 * heading they were taken (further out = stronger), the arrow and wedge are the estimate.
 */
class PolarView(context: Context) : View(context) {
    private var points: List<Pair<Double, Double>> = emptyList()
    private var bearing: Double? = null
    private var sigma = 0.0
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Palette.track }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.warm; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND }
    private val wedge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0xE8, 0x5D, 0x1A) }

    /** Angles are relative to the current facing direction, clockwise, in radians. */
    fun set(points: List<Pair<Double, Double>>, bearingRel: Double?, sigmaRad: Double) {
        this.points = points
        this.bearing = bearingRel
        this.sigma = sigmaRad
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, min(w, context.dp(320)))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.9f
        for (k in 1..3) c.drawCircle(cx, cy, r * k / 3, ring)
        c.drawLine(cx, cy - r, cx, cy - r - context.dp(8), arrow.apply { alpha = 120 })
        arrow.alpha = 255
        bearing?.let { b ->
            val sweep = Math.toDegrees(2 * sigma.coerceIn(0.05, PI)).toFloat()
            val start = Math.toDegrees(b - PI / 2).toFloat() - sweep / 2
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, true, wedge)
        }
        if (points.isNotEmpty()) {
            val lo = points.minOf { it.second }
            val hi = maxOf(points.maxOf { it.second }, lo + 1)
            for ((a, v) in points) {
                val rr = r * (0.25 + 0.75 * (v - lo) / (hi - lo)).toFloat()
                c.drawCircle(cx + rr * sin(a).toFloat(), cy - rr * cos(a).toFloat(), context.dp(3).toFloat(), dot)
            }
        }
        bearing?.let { b ->
            c.drawLine(cx, cy, cx + r * sin(b).toFloat(), cy - r * cos(b).toFloat(), arrow)
        }
    }
}

/** Top-down probability map from the AR room scan. */
class HeatmapView(context: Context) : View(context) {
    private var result: LocalizerResult? = null
    private var path: List<Vec3> = emptyList()
    private var me: Vec3? = null
    private var forward: Vec3? = null
    private val cell = Paint()
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Palette.muted }
    private val mePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.accent }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Palette.warm }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; textSize = context.dp(12).toFloat() }

    fun set(result: LocalizerResult?, path: List<Vec3>, me: Vec3?, forward: Vec3?) {
        this.result = result
        this.path = path
        this.me = me
        this.forward = forward
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val pts = path + listOfNotNull(me)
        if (pts.isEmpty()) {
            c.drawText("Walk around to start mapping", context.dp(12).toFloat(), height / 2f, text)
            return
        }
        // World x → screen x, world z → screen y (top-down view).
        val r = result
        var minX = pts.minOf { it.x } - 1; var maxX = pts.maxOf { it.x } + 1
        var minZ = pts.minOf { it.z } - 1; var maxZ = pts.maxOf { it.z } + 1
        r?.searchPoint?.let { t ->
            minX = min(minX, t.x - 1); maxX = maxOf(maxX, t.x + 1)
            minZ = min(minZ, t.z - 1); maxZ = maxOf(maxZ, t.z + 1)
        }
        val s = min(width / (maxX - minX), height / (maxZ - minZ)).toFloat()
        val ox = (width - s * (maxX - minX).toFloat()) / 2
        val oz = (height - s * (maxZ - minZ).toFloat()) / 2
        fun sx(x: Double) = ox + s * (x - minX).toFloat()
        fun sz(z: Double) = oz + s * (z - minZ).toFloat()

        if (r != null) {
            val m = r.floorMarginal
            val peak = m.max().takeIf { it > 0 } ?: 1.0
            for (ix in 0 until r.nx) for (iz in 0 until r.nz) {
                val p = m[ix * r.nz + iz] / peak
                if (p < 0.03) continue
                cell.color = Color.argb((p * 200).toInt(), 0xE8, 0x5D, 0x1A)
                val x0 = r.origin.x + ix * r.cellM
                val z0 = r.origin.z + iz * r.cellM
                c.drawRect(sx(x0), sz(z0), sx(x0 + r.cellM), sz(z0 + r.cellM), cell)
            }
            val t = r.searchPoint
            c.drawCircle(sx(t.x), sz(t.z), maxOf(s * r.radius50M.toFloat(), context.dp(8).toFloat()), targetPaint)
        }
        if (path.size > 1) {
            val p = Path()
            p.moveTo(sx(path[0].x), sz(path[0].z))
            for (q in path.drop(1)) p.lineTo(sx(q.x), sz(q.z))
            c.drawPath(p, pathPaint)
        }
        me?.let { m ->
            val px = sx(m.x); val pz = sz(m.z)
            c.drawCircle(px, pz, context.dp(6).toFloat(), mePaint)
            forward?.let { f ->
                val len = context.dp(18).toFloat()
                val h = kotlin.math.hypot(f.x, f.z).takeIf { it > 1e-3 } ?: return@let
                c.drawLine(px, pz, px + len * (f.x / h).toFloat(), pz + len * (f.z / h).toFloat(), mePaint.apply { strokeWidth = 6f })
            }
        }
    }
}

/** Draws the estimated tag position over the camera image, or an edge arrow if it is off-screen. */
class MarkerOverlay(context: Context) : View(context) {
    private var visible = false
    private var onScreen = false
    private var x = 0f
    private var y = 0f
    private var angle = 0.0
    private var caption = ""
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = Palette.warm }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.warm }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.dp(16).toFloat()
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
        textAlign = Paint.Align.CENTER
    }

    fun hide() {
        if (visible) { visible = false; invalidate() }
    }

    fun showAt(x: Float, y: Float, caption: String) {
        visible = true; onScreen = true; this.x = x; this.y = y; this.caption = caption; invalidate()
    }

    /** [angle] is the screen-space direction (rad, 0 = right, clockwise positive since y points down). */
    fun showOffscreen(angle: Double, caption: String) {
        visible = true; onScreen = false; this.angle = angle; this.caption = caption; invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (!visible) return
        if (onScreen) {
            val r = context.dp(28).toFloat()
            c.drawCircle(x, y, r, ring)
            c.drawCircle(x, y, context.dp(4).toFloat(), fill)
            c.drawText(caption, x, y - r - context.dp(8), text)
        } else {
            val cx = width / 2f
            val cy = height / 2f
            val reach = min(cx, cy) * 0.8f
            val tx = cx + reach * cos(angle).toFloat()
            val ty = cy + reach * sin(angle).toFloat()
            val size = context.dp(26).toFloat()
            val p = Path()
            p.moveTo(tx + size * cos(angle).toFloat(), ty + size * sin(angle).toFloat())
            p.lineTo(tx + size * cos(angle + 2.5).toFloat(), ty + size * sin(angle + 2.5).toFloat())
            p.lineTo(tx + size * cos(angle - 2.5).toFloat(), ty + size * sin(angle - 2.5).toFloat())
            p.close()
            c.drawPath(p, fill)
            c.drawText(caption, cx, cy, text)
        }
    }
}
