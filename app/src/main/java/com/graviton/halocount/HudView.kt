package com.graviton.halocount

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Full-screen, non-interactive heads-up display: the big count, status line, hint and credit. */
class HudView(context: Context) : View(context) {

    enum class Tone { STEADY, COUNTING, INFO, WARN }

    private val dp = resources.displayMetrics.density

    private var target = 0
    private var shown = 0f
    private var label = "COUNTED"
    private var status = ""
    private var tone = Tone.INFO
    private var hint = ""

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textSize = 96f * dp
        color = Palette.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = 11f * dp
        letterSpacing = 0.45f
        color = Palette.SOFT
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = 12f * dp
        letterSpacing = 0.12f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = 11f * dp
        letterSpacing = 0.06f
        color = Palette.SOFT
        alpha = 150
    }
    private val creditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = 9.5f * dp
        letterSpacing = 0.14f
        color = Palette.SOFT
        alpha = 105
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val topScrim = Paint()
    private val botScrim = Paint()
    private val topH = 250f * dp
    private val botH = 210f * dp

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val dark = 0xF004060B.toInt()
        val clear = 0x0004060B
        topScrim.shader = LinearGradient(0f, 0f, 0f, topH, dark, clear, Shader.TileMode.CLAMP)
        botScrim.shader = LinearGradient(0f, h - botH, 0f, h.toFloat(), clear, dark, Shader.TileMode.CLAMP)
    }

    fun setCount(n: Int) {
        target = n
    }

    fun setLabel(text: String) {
        label = text
    }

    fun setStatus(text: String, t: Tone) {
        status = text
        tone = t
    }

    fun setHint(text: String) {
        hint = text
    }

    private fun toneColor(): Int = when (tone) {
        Tone.STEADY -> Palette.MINT
        Tone.COUNTING -> Palette.CYAN
        Tone.INFO -> Palette.SOFT
        Tone.WARN -> Palette.AMBER
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()

        // scrims keep the text readable over the camera image
        c.drawRect(0f, 0f, w, topH, topScrim)
        c.drawRect(0f, h - botH, w, h, botScrim)

        // animated count
        val diff = target - shown
        if (abs(diff) > 0.5f) shown += diff * 0.22f else shown = target.toFloat()
        val col = toneColor()
        numPaint.setShadowLayer(26f * dp, 0f, 0f, col)
        c.drawText(shown.roundToInt().toString(), w / 2f, 96f * dp, numPaint)
        numPaint.clearShadowLayer()

        c.drawText(label, w / 2f, 124f * dp, labelPaint)

        // status line with a breathing dot
        if (status.isNotEmpty()) {
            statusPaint.color = col
            val tw = statusPaint.measureText(status)
            val dotR = 3f * dp
            val gap = 9f * dp
            val startX = (w - (dotR * 2f + gap + tw)) / 2f
            val cy = 148f * dp
            dotPaint.color = col
            dotPaint.alpha = (120f + 135f * (0.5f + 0.5f * sin(now / 600f))).toInt()
            c.drawCircle(startX + dotR, cy - 4f * dp, dotR, dotPaint)
            c.drawText(status, startX + dotR * 2f + gap, cy, statusPaint)
        }

        if (hint.isNotEmpty()) c.drawText(hint, w / 2f, h - 146f * dp, hintPaint)
        c.drawText("made by Unmesh Khare", w / 2f, h - 12f * dp, creditPaint)

        postInvalidateOnAnimation()
    }
}
