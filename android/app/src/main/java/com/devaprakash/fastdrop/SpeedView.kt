package com.devaprakash.fastdrop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Animated speed gauge: smoothed arc, auto-scaling, particles flowing around the ring (↑ clockwise, ↓ counter). */
class SpeedView(ctx: Context) : View(ctx) {
    private val cUp = Color.parseColor("#4CC2FF")
    private val cDown = Color.parseColor("#3DDC97")
    private val cTrack = Color.parseColor("#262D38")
    private val cText = Color.parseColor("#E6EDF3")
    private val cMute = Color.parseColor("#8B96A5")
    private val steps = floatArrayOf(25f, 50f, 100f, 200f, 400f, 800f, 1600f)

    private var upT = 0f
    private var dnT = 0f
    private var up = 0f
    private var dn = 0f
    private var scale = 25f
    private var peak = 0f
    private var last = 0L
    private val oval = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private class Dot(var a: Float, val r: Float, val lane: Int)
    private val dots = List(36) { Dot(Random.nextFloat() * 270f, 2f + Random.nextFloat() * 2.5f, it % 2) }

    fun update(upMBs: Float, downMBs: Float) { upT = upMBs; dnT = downMBs }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        val k = min(1f, dt * 5f)
        up += (upT - up) * k
        dn += (dnT - dn) * k
        val tot = up + dn
        if (tot > peak) peak = tot
        val target = steps.firstOrNull { it >= peak * 1.15f } ?: steps.last()
        scale += (target - scale) * min(1f, dt * 2f)
        val frac = (tot / scale).coerceIn(0f, 1f)

        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val sw = size * 0.075f
        val r = size / 2f - sw
        oval.set(cx - r, cy - r, cx + r, cy + r)

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = sw
        p.shader = null
        p.color = cTrack
        c.drawArc(oval, 135f, 270f, false, p)

        val sweep = 270f * frac
        if (sweep > 0.5f) {
            val sg = SweepGradient(cx, cy, intArrayOf(cUp, cDown, cDown), floatArrayOf(0f, 0.75f, 1f))
            sg.setLocalMatrix(Matrix().apply { postRotate(135f, cx, cy) })
            p.shader = sg
            c.drawArc(oval, 135f, sweep, false, p)
            p.shader = null
        }

        // particles flowing inside the filled part of the ring
        if (tot > 0.3f && sweep > 4f) {
            p.style = Paint.Style.FILL
            val speedDeg = 40f + 360f * frac
            val shown = (6 + (dots.size - 6) * frac).toInt()
            for (i in 0 until shown) {
                val d = dots[i]
                val dir = if (d.lane == 0) 1f else -1f
                d.a += dir * speedDeg * dt * (0.7f + d.r / 5f)
                if (d.a > sweep) d.a -= sweep
                if (d.a < 0f) d.a += sweep
                val rad = Math.toRadians((135f + d.a).toDouble())
                val off = if (d.lane == 0) -sw * 0.18f else sw * 0.18f
                p.color = Color.argb(235, 255, 255, 255)
                c.drawCircle(cx + (r + off) * cos(rad).toFloat(), cy + (r + off) * sin(rad).toFloat(), d.r * size / 220f, p)
            }
        }

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = cText
        p.textSize = size * 0.21f
        c.drawText(if (tot < 10f) "%.1f".format(tot) else "%.0f".format(tot), cx, cy + size * 0.04f, p)
        p.typeface = Typeface.DEFAULT
        p.color = cMute
        p.textSize = size * 0.065f
        c.drawText("MB/s  ·  ${(tot * 8.388608f).toInt()} Mbit/s", cx, cy + size * 0.13f, p)
        p.textSize = size * 0.065f
        p.color = cUp
        c.drawText("↑ %.1f".format(up), cx - size * 0.16f, cy + size * 0.24f, p)
        p.color = cDown
        c.drawText("↓ %.1f".format(dn), cx + size * 0.16f, cy + size * 0.24f, p)
        p.color = cMute
        p.textSize = size * 0.05f
        c.drawText("peak %.0f".format(peak), cx, cy - size * 0.2f, p)

        postInvalidateOnAnimation()
    }
}
