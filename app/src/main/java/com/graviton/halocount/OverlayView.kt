package com.graviton.halocount

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the glowing rings around counted objects, the scanning frame and the focus pulse,
 * and turns touches into grid coordinates. Sits exactly on top of the camera preview.
 */
class OverlayView(context: Context) : View(context) {

    class Marker(
        var x: Float,
        var y: Float,
        var r: Float,
        var area: Float,
        var manual: Boolean
    ) {
        var hits = 1
        var miss = 0
        var seen = false
        var birth = 0L
        var label = ""
    }

    interface Listener {
        fun onTap(gx: Float, gy: Float, vx: Float, vy: Float)
        fun onHold(gx: Float, gy: Float)
    }

    var listener: Listener? = null

    var frozen = false
        private set

    private val dp = resources.displayMetrics.density
    private val markers = ArrayList<Marker>()
    private var gw = 4
    private var gh = 3
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var focusX = 0f
    private var focusY = 0f
    private var focusT = -100000L

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * dp
    }
    private val bandH = 56f * dp
    private val scanBand = Paint().apply {
        shader = LinearGradient(0f, -bandH, 0f, 0f, 0x002DF3FF, 0x402DF3FF, Shader.TileMode.CLAMP)
    }
    private val scanLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
        color = Palette.CYAN
        alpha = 120
    }

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                listener?.onTap(toGx(e.x), toGy(e.y), e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                listener?.onHold(toGx(e.x), toGy(e.y))
            }
        }
    )

    // ------------------------------------------------------------------ geometry
    private fun computeMap() {
        if (width == 0 || height == 0) return
        scale = min(width.toFloat() / gw, height.toFloat() / gh)
        ox = (width - gw * scale) / 2f
        oy = (height - gh * scale) / 2f
    }

    private fun toGx(vx: Float): Float = (vx - ox) / scale
    private fun toGy(vy: Float): Float = (vy - oy) / scale

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeMap()
    }

    private fun setGrid(w: Int, h: Int) {
        if (w != gw || h != gh) {
            gw = w
            gh = h
            computeMap()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return true
    }

    // ------------------------------------------------------------------ live mode
    /** Feed one live detection; markers are matched across frames so rings glide instead of flicker. */
    fun updateLive(blobs: List<Blob>, w: Int, h: Int) {
        frozen = false
        setGrid(w, h)
        val now = SystemClock.uptimeMillis()
        for (m in markers) m.seen = false
        val fresh = ArrayList<Marker>()
        for (b in blobs) {
            var best: Marker? = null
            var bd = Float.MAX_VALUE
            for (m in markers) {
                if (m.seen) continue
                val dx = m.x - b.x
                val dy = m.y - b.y
                val d = dx * dx + dy * dy
                val lim = max(b.r, m.r) * 0.9f
                if (d < lim * lim && d < bd) {
                    bd = d
                    best = m
                }
            }
            val hit = best
            if (hit != null) {
                hit.seen = true
                hit.x += (b.x - hit.x) * 0.5f
                hit.y += (b.y - hit.y) * 0.5f
                hit.r += (b.r - hit.r) * 0.5f
                hit.area = b.area
                hit.miss = 0
                hit.hits++
                if (hit.hits == 2) hit.birth = now
            } else {
                val nm = Marker(b.x, b.y, b.r, b.area, false)
                nm.birth = now
                fresh.add(nm)
            }
        }
        val iter = markers.iterator()
        while (iter.hasNext()) {
            val m = iter.next()
            if (!m.seen) {
                m.miss++
                if (m.miss > 2) iter.remove()
            }
        }
        markers.addAll(fresh)
    }

    // ------------------------------------------------------------------ photo mode
    fun showFrozen(blobs: List<Blob>, w: Int, h: Int) {
        frozen = true
        setGrid(w, h)
        markers.clear()
        for (b in blobs) {
            val m = Marker(b.x, b.y, b.r, b.area, b.manual)
            m.hits = 2
            markers.add(m)
        }
        renumber(true)
    }

    fun clear() {
        markers.clear()
        frozen = false
    }

    fun count(): Int = markers.size

    private fun renumber(animate: Boolean) {
        if (markers.isEmpty()) return
        var rs = 0f
        for (m in markers) rs += m.r
        val band = max(3f, rs / markers.size * 1.6f)
        markers.sortWith(compareBy<Marker>({ (it.y / band).toInt() }, { it.x }))
        val now = SystemClock.uptimeMillis()
        val step = min(14f, 1500f / markers.size)
        for (i in markers.indices) {
            val m = markers[i]
            m.label = (i + 1).toString()
            if (animate) m.birth = now + (i * step).toLong()
        }
    }

    private fun findAt(gx: Float, gy: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in markers.indices) {
            val m = markers[i]
            val reach = max(m.r, 14f * dp / scale)
            val dx = m.x - gx
            val dy = m.y - gy
            val d = dx * dx + dy * dy
            if (d <= reach * reach && d < bd) {
                bd = d
                best = i
            }
        }
        return best
    }

    /** Removes the marker under the touch, if any. */
    fun removeAt(gx: Float, gy: Float): Boolean {
        val i = findAt(gx, gy)
        if (i < 0) return false
        markers.removeAt(i)
        renumber(false)
        return true
    }

    /** Area (in grid cells) of the object under the touch, or -1 if none. */
    fun areaAt(gx: Float, gy: Float): Float {
        val i = findAt(gx, gy)
        return if (i < 0) -1f else markers[i].area
    }

    fun addManual(gx: Float, gy: Float) {
        if (gx < 0f || gy < 0f || gx >= gw || gy >= gh) return
        val m = Marker(gx, gy, median(true), median(false), true)
        m.hits = 2
        m.birth = SystemClock.uptimeMillis()
        markers.add(m)
        renumber(false)
    }

    private fun median(radius: Boolean): Float {
        if (markers.isEmpty()) return if (radius) 6f else 110f
        val v = FloatArray(markers.size)
        for (i in markers.indices) v[i] = if (radius) markers[i].r else markers[i].area
        v.sort()
        return v[v.size / 2]
    }

    fun pulseFocus(vx: Float, vy: Float) {
        focusX = vx
        focusY = vy
        focusT = SystemClock.uptimeMillis()
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val iw = gw * scale
        val ih = gh * scale
        drawBrackets(c, iw, ih)
        if (!frozen) drawScan(c, now, iw, ih)
        for (m in markers) {
            if (!frozen && (m.hits < 2 || m.miss > 1)) continue
            drawMarker(c, m, now)
        }
        drawFocus(c, now)
        postInvalidateOnAnimation()
    }

    private fun drawBrackets(c: Canvas, iw: Float, ih: Float) {
        val len = 22f * dp
        val inset = 4f * dp
        val l = ox + inset
        val t = oy + inset
        val r = ox + iw - inset
        val b = oy + ih - inset
        bracket.color = if (frozen) Palette.VIOLET else Palette.CYAN
        bracket.alpha = 160
        c.drawLine(l, t, l + len, t, bracket)
        c.drawLine(l, t, l, t + len, bracket)
        c.drawLine(r, t, r - len, t, bracket)
        c.drawLine(r, t, r, t + len, bracket)
        c.drawLine(l, b, l + len, b, bracket)
        c.drawLine(l, b, l, b - len, bracket)
        c.drawLine(r, b, r - len, b, bracket)
        c.drawLine(r, b, r, b - len, bracket)
    }

    private fun drawScan(c: Canvas, now: Long, iw: Float, ih: Float) {
        val period = 4200L
        val f = (now % period).toFloat() / period
        val y = ih * f
        c.save()
        c.clipRect(ox, oy, ox + iw, oy + ih)
        c.translate(ox, oy + y)
        c.drawRect(0f, -bandH, iw, 0f, scanBand)
        c.drawLine(0f, 0f, iw, 0f, scanLine)
        c.restore()
    }

    private fun drawMarker(c: Canvas, m: Marker, now: Long) {
        val age = now - m.birth
        if (age < 0) return
        val t = min(1f, age / 420f)
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        val cx = ox + m.x * scale
        val cy = oy + m.y * scale
        val base = max(m.r * scale * 1.08f, 5f * dp)
        val rad = base * (0.5f + 0.5f * e)
        val breathe = 0.5f + 0.5f * sin(now / 900f + (m.x + m.y) * 0.35f)
        val col = if (m.manual) Palette.VIOLET else Palette.CYAN

        fill.color = col
        fill.alpha = (e * (16f + 18f * breathe)).toInt()
        c.drawCircle(cx, cy, rad, fill)

        ring.color = col
        ring.strokeWidth = 6f * dp
        ring.alpha = (34f * e).toInt()
        c.drawCircle(cx, cy, rad, ring)
        ring.strokeWidth = 3f * dp
        ring.alpha = (80f * e).toInt()
        c.drawCircle(cx, cy, rad, ring)
        ring.strokeWidth = 1.3f * dp
        ring.alpha = ((170f + 70f * breathe) * e).toInt()
        c.drawCircle(cx, cy, rad, ring)

        if (age < 800) {
            val u = age / 800f
            ring.strokeWidth = 1.4f * dp
            ring.alpha = ((1f - u) * 150f).toInt()
            c.drawCircle(cx, cy, base * (1f + 0.8f * u), ring)
        }

        val numbered = frozen && base > 9f * dp
        if (numbered) {
            text.color = Palette.WHITE
            text.alpha = (230f * e).toInt()
            text.textSize = min(max(base * 0.62f, 8f * dp), 15f * dp)
            val fm = text.fontMetrics
            c.drawText(m.label, cx, cy - (fm.ascent + fm.descent) / 2f, text)
        } else {
            fill.color = Palette.WHITE
            fill.alpha = (210f * e).toInt()
            c.drawCircle(cx, cy, 1.6f * dp, fill)
        }
    }

    private fun drawFocus(c: Canvas, now: Long) {
        val age = now - focusT
        if (age in 0L..700L) {
            val u = age / 700f
            ring.color = Palette.WHITE
            ring.strokeWidth = 1.6f * dp
            ring.alpha = ((1f - u) * 200f).toInt()
            c.drawCircle(focusX, focusY, (46f - 24f * u) * dp, ring)
        }
    }
}
