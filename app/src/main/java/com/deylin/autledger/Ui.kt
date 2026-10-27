package com.deylin.autledger

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** 统一视觉：奶油底色 + 橙色主色，仿记账 App 的清爽风格 */
object Ui {
    val BG = 0xFFFFFBF5.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val ORANGE = 0xFFFF8A3D.toInt()
    val ORANGE_DEEP = 0xFFF2711C.toInt()
    val ORANGE_LIGHT = 0xFFFFF0E4.toInt()
    val TEXT = 0xFF333333.toInt()
    val SUB = 0xFF999999.toInt()
    val LINE = 0xFFF1E9DE.toInt()
    val RED = 0xFFE8543F.toInt()
    val GREEN = 0xFF2FA84F.toInt()

    val MP = LinearLayout.LayoutParams.MATCH_PARENT
    val WC = LinearLayout.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    /** 白色圆角卡片容器 */
    fun card(c: Context, padH: Float = 14f, padV: Float = 14f): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.setBackground(round(c, CARD, 16f))
        l.setPadding(dp(c, padH), dp(c, padV), dp(c, padH), dp(c, padV))
        return l
    }

    fun round(c: Context, color: Int, r: Float, stroke: Float = 0f, strokeColor: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.setColor(color)
        g.cornerRadius = dp(c, r).toFloat()
        if (stroke > 0f) g.setStroke(dp(c, stroke), strokeColor)
        return g
    }

    fun tv(c: Context, t: CharSequence, sp: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(c)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        v.setTextColor(color)
        v.includeFontPadding = false
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD)
        return v
    }

    fun row(c: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = gravity
        return l
    }

    fun col(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.weight = weight
        return p
    }

    fun margin(p: ViewGroup.MarginLayoutParams, l: Int, t: Int, r: Int, b: Int): ViewGroup.MarginLayoutParams {
        p.setMargins(l, t, r, b)
        return p
    }

    fun pad(v: View, l: Int, t: Int, r: Int, b: Int): View {
        v.setPadding(dp(v.context, l.toFloat()), dp(v.context, t.toFloat()), dp(v.context, r.toFloat()), dp(v.context, b.toFloat()))
        return v
    }

    fun divider(c: Context): View {
        val v = View(c)
        v.setBackgroundColor(LINE)
        return v
    }

    fun money(v: Double): String = String.format(Locale.CHINA, "%.2f", v)

    /** 大号圆角按钮 */
    fun button(c: Context, text: String, bg: Int, fg: Int, sp: Float = 15f): TextView {
        val b = tv(c, text, sp, fg, true)
        b.gravity = Gravity.CENTER
        b.setBackground(round(c, bg, 24f))
        b.setPadding(0, dp(c, 12f), 0, dp(c, 12f))
        return b
    }

    /** 圆形图标底 */
    fun iconCircle(c: Context, emoji: String): TextView {
        val t = tv(c, emoji, 17f, TEXT)
        t.gravity = Gravity.CENTER
        t.setBackground(round(c, ORANGE_LIGHT, 22f))
        val s = dp(c, 40f)
        t.layoutParams = LinearLayout.LayoutParams(s, s)
        return t
    }
}
