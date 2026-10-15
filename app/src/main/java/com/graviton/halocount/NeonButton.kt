package com.graviton.halocount

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** A glowing round icon button with a tiny caption underneath. */
class NeonButton(context: Context, private val kind: Kind, initialCaption: String) : View(context) {

    enum class Kind { TORCH, SHUTTER, AUTO }

    private val dp = resources.displayMetrics.density

    var caption: String = initialCaption
        set(value) {
            field = value
            invalidate()
        }

    /** Highlighted state (torch on, size locked ...). */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Shutter only: false = "snap" icon, true = "resume" (play) icon. */
    var alt: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var big: Boolean = false

    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val capP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = 9.5f * dp
        letterSpacing = 0.3f
    }
    private val path = Path()
    private val rect = RectF()

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = 40f * dp
        val dia = (if (big) 74f else 58f) * dp
        val u = dia / 2f * (if (isPressed) 0.94f else 1f)
        val col = Palette.CYAN

        if (active) {
            fillP.color = col
            fillP.alpha = 46
            c.drawCircle(cx, cy, u, fillP)
        }
        ringP.color = col
        ringP.strokeWidth = 7f * dp
        ringP.alpha = if (active) 60 else 28
        c.drawCircle(cx, cy, u, ringP)
        ringP.strokeWidth = 3.5f * dp
        ringP.alpha = if (active) 120 else 64
        c.drawCircle(cx, cy, u, ringP)
        ringP.strokeWidth = 1.5f * dp
        ringP.alpha = 230
        c.drawCircle(cx, cy, u, ringP)

        iconP.color = if (active) Palette.MINT else Palette.WHITE
        iconP.strokeWidth = 1.8f * dp

        when (kind) {
            Kind.TORCH -> {
                val s = u * 0.85f
                path.reset()
                path.moveTo(cx + 0.14f * s, cy - 0.62f * s)
                path.lineTo(cx - 0.34f * s, cy + 0.10f * s)
                path.lineTo(cx - 0.02f * s, cy + 0.10f * s)
                path.lineTo(cx - 0.14f * s, cy + 0.62f * s)
                path.lineTo(cx + 0.34f * s, cy - 0.10f * s)
                path.lineTo(cx + 0.02f * s, cy - 0.10f * s)
                path.close()
                c.drawPath(path, iconP)
            }
            Kind.SHUTTER -> {
                if (!alt) {
                    fillP.color = col
                    fillP.alpha = 235
                    c.drawCircle(cx, cy, u * 0.46f, fillP)
                } else {
                    path.reset()
                    path.moveTo(cx - 0.22f * u, cy - 0.34f * u)
                    path.lineTo(cx + 0.36f * u, cy)
                    path.lineTo(cx - 0.22f * u, cy + 0.34f * u)
                    path.close()
                    fillP.color = col
                    fillP.alpha = 90
                    c.drawPath(path, fillP)
                    iconP.strokeWidth = 2f * dp
                    c.drawPath(path, iconP)
                }
            }
            Kind.AUTO -> {
                val r = u * 0.42f
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, 40f, 280f, false, iconP)
                val a = Math.toRadians(320.0).toFloat()
                val ex = cx + r * cos(a)
                val ey = cy + r * sin(a)
                val bx = sin(a)
                val by = -cos(a)
                val hl = u * 0.30f
                for (sg in intArrayOf(-1, 1)) {
                    val ang = 0.55f * sg
                    val ca = cos(ang)
                    val sa = sin(ang)
                    val rx = bx * ca - by * sa
                    val ry = bx * sa + by * ca
                    c.drawLine(ex, ey, ex + rx * hl, ey + ry * hl, iconP)
                }
            }
        }

        capP.color = if (active) col else Palette.SOFT
        c.drawText(caption, cx, 96f * dp, capP)
    }
}
