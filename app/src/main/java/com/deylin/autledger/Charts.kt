package com.deylin.autledger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** 分类占比环形图 */
class DonutView(context: Context) : View(context) {

    companion object {
        val COLORS = intArrayOf(
            0xFFFF8A3D.toInt(), 0xFFFFB865.toInt(), 0xFF4DB6AC.toInt(), 0xFF7986CB.toInt(),
            0xFFE57373.toInt(), 0xFF9575CD.toInt(), 0xFF4FC3F7.toInt(), 0xFFAED581.toInt(),
            0xFFFFD54F.toInt(), 0xFFA1887F.toInt(), 0xFFF06292.toInt(), 0xFF90A4AE.toInt()
        )
    }

    var slices: List<Pair<String, Double>> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    var centerTop: String = ""
        set(v) {
            field = v
            invalidate()
        }

    var centerBottom: String = ""
        set(v) {
            field = v
            invalidate()
        }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        arc.style = Paint.Style.STROKE
        txt.textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Ui.dp(context, 214f))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val thick = Ui.dp(context, 26f).toFloat()
        val r = minOf(cx, cy) - thick
        if (r <= 0f) return
        arc.strokeWidth = thick
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        arc.color = 0xFFF3EDE4.toInt()
        canvas.drawCircle(cx, cy, r, arc)

        var total = 0.0
        for (s in slices) total += s.second
        if (total > 0.0) {
            var start = -90f
            for (i in slices.indices) {
                val sweep = (slices[i].second / total * 360.0).toFloat()
                arc.color = COLORS[i % COLORS.size]
                canvas.drawArc(rect, start + 1.2f, maxOf(sweep - 2.4f, 1.2f), false, arc)
                start += sweep
            }
        }

        txt.color = Ui.TEXT
        txt.textSize = Ui.dp(context, 16f).toFloat()
        txt.isFakeBoldText = true
        canvas.drawText(centerTop, cx, cy + Ui.dp(context, 1f), txt)

        txt.color = Ui.SUB
        txt.textSize = Ui.dp(context, 11f).toFloat()
        txt.isFakeBoldText = false
        canvas.drawText(centerBottom, cx, cy + Ui.dp(context, 18f), txt)
    }
}

/** 近几月趋势柱状图 */
class BarView(context: Context) : View(context) {

    var labels: List<String> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    var values: List<Double> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Ui.dp(context, 156f))
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        var maxV = 0.0
        for (v in values) if (v > maxV) maxV = v
        val gap = Ui.dp(context, 9f).toFloat()
        val barW = (width - gap * (n + 1)) / n
        if (barW <= 0f) return
        val baseY = height - Ui.dp(context, 24f).toFloat()
        val topPad = Ui.dp(context, 12f).toFloat()
        t.textAlign = Paint.Align.CENTER
        t.textSize = Ui.dp(context, 10f).toFloat()

        for (i in 0 until n) {
            val ratio = if (maxV <= 0.0) 0.0 else values[i] / maxV
            val bh = ((baseY - topPad) * ratio).toFloat()
            val left = gap + i * (barW + gap)
            p.color = if (i == n - 1) Ui.ORANGE else 0xFFFFD3B0.toInt()
            val rect = RectF(left, baseY - bh, left + barW, baseY)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, p)
            t.color = Ui.SUB
            canvas.drawText(if (i < labels.size) labels[i] else "", left + barW / 2f, height - Ui.dp(context, 7f).toFloat(), t)
        }
    }
}
