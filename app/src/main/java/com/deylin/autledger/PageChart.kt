package com.deylin.autledger

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Calendar
import java.util.Locale

/** 图表页：分类占比环形图 + 分类排行 + 近6月趋势 */
class PageChart(private val act: MainActivity) {

    private val c: Context = act
    private var type = 0
    private lateinit var body: LinearLayout

    fun build(): View {
        val root = Ui.col(c)
        root.setBackgroundColor(Ui.BG)

        // ---- 顶栏 ----
        val top = Ui.row(c)
        Ui.pad(top, 16, 12, 16, 4)
        val y = act.curY
        val m = act.curM
        val prev = Ui.tv(c, "‹", 20f, Ui.SUB)
        prev.setPadding(Ui.dp(c, 8f), 0, Ui.dp(c, 8f), 0)
        prev.setOnClickListener {
            act.curM = if (m == 0) 11 else m - 1
            if (m == 0) act.curY = y - 1
            act.refresh()
        }
        val label = Ui.tv(c, "${y}年${m + 1}月", 15f, Ui.TEXT, true)
        val next = Ui.tv(c, "›", 20f, Ui.SUB)
        next.setPadding(Ui.dp(c, 8f), 0, Ui.dp(c, 8f), 0)
        next.setOnClickListener {
            act.curM = if (m == 11) 0 else m + 1
            if (m == 11) act.curY = y + 1
            act.refresh()
        }
        top.addView(prev)
        top.addView(label)
        top.addView(next)
        top.addView(View(c), Ui.lp(0, 1, 1f))

        val tExp = Ui.tv(c, "支出", 13f, Ui.ORANGE, true)
        val tInc = Ui.tv(c, "收入", 13f, Ui.SUB)
        tExp.setPadding(Ui.dp(c, 10f), Ui.dp(c, 5f), Ui.dp(c, 10f), Ui.dp(c, 5f))
        tInc.setPadding(Ui.dp(c, 10f), Ui.dp(c, 5f), Ui.dp(c, 10f), Ui.dp(c, 5f))
        tExp.setBackground(Ui.round(c, Ui.ORANGE_LIGHT, 12f))
        tExp.setOnClickListener {
            if (type != 0) {
                type = 0
                paintToggle(tExp, tInc)
                fill()
            }
        }
        tInc.setOnClickListener {
            if (type != 1) {
                type = 1
                paintToggle(tInc, tExp)
                fill()
            }
        }
        top.addView(tExp)
        val ip = Ui.lp(Ui.WC, Ui.WC)
        ip.leftMargin = Ui.dp(c, 6f)
        top.addView(tInc, ip)
        root.addView(top, Ui.lp(Ui.MP, Ui.WC))

        val sv = ScrollView(c)
        sv.isFillViewport = true
        body = Ui.col(c)
        Ui.pad(body, 14, 6, 14, 24)
        sv.addView(body, Ui.lp(Ui.MP, Ui.WC))
        root.addView(sv, Ui.lp(Ui.MP, 0, 1f))

        fill()
        return root
    }

    private fun paintToggle(on: android.widget.TextView, off: android.widget.TextView) {
        on.setTextColor(Ui.ORANGE)
        on.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        on.setBackground(Ui.round(c, Ui.ORANGE_LIGHT, 12f))
        off.setTextColor(Ui.SUB)
        off.setTypeface(android.graphics.Typeface.DEFAULT)
        off.setBackgroundColor(0x00000000)
    }

    private fun fill() {
        body.removeAllViews()
        val db = act.db()
        val y = act.curY
        val m = act.curM
        val from = DbHelper.monthStart(y, m)
        val to = DbHelper.monthEnd(y, m)
        val total = db.sum(from, to, act.book, type)
        val slices = db.catSum(from, to, act.book, type)

        // ---- 环形图 ----
        val card1 = Ui.card(c, 10f, 12f)
        val donut = DonutView(c)
        donut.slices = slices
        donut.centerTop = "¥" + Ui.money(total)
        donut.centerBottom = if (type == 0) "本月支出" else "本月收入"
        card1.addView(donut, Ui.lp(Ui.MP, Ui.WC))
        body.addView(card1, Ui.lp(Ui.MP, Ui.WC))

        // ---- 分类排行 ----
        val card2 = Ui.card(c, 14f, 6f)
        val title = Ui.tv(c, if (type == 0) "支出排行" else "收入排行", 13.5f, Ui.TEXT, true)
        title.setPadding(0, Ui.dp(c, 6f), 0, Ui.dp(c, 8f))
        card2.addView(title)
        if (slices.isEmpty()) {
            val t = Ui.tv(c, "本月还没有记录", 12.5f, Ui.SUB)
            t.setPadding(0, Ui.dp(c, 14f), 0, Ui.dp(c, 16f))
            card2.addView(t)
        } else {
            for (i in slices.indices) {
                card2.addView(rankRow(slices[i], total, i))
            }
        }
        val c2p = Ui.lp(Ui.MP, Ui.WC)
        c2p.topMargin = Ui.dp(c, 10f)
        body.addView(card2, c2p)

        // ---- 近6月趋势 ----
        val card3 = Ui.card(c, 14f, 12f)
        val t3 = Ui.tv(c, "近 6 个月趋势", 13.5f, Ui.TEXT, true)
        t3.setPadding(0, 0, 0, Ui.dp(c, 8f))
        card3.addView(t3)
        val labels = ArrayList<String>()
        val values = ArrayList<Double>()
        val base = Calendar.getInstance()
        base.set(y, m, 1, 0, 0, 0)
        for (i in 5 downTo 0) {
            val cc = Calendar.getInstance()
            cc.timeInMillis = base.timeInMillis
            cc.add(Calendar.MONTH, -i)
            val yy = cc.get(Calendar.YEAR)
            val mm = cc.get(Calendar.MONTH)
            labels.add(String.format(Locale.CHINA, "%d月", mm + 1))
            values.add(db.sum(DbHelper.monthStart(yy, mm), DbHelper.monthEnd(yy, mm), act.book, type))
        }
        val bar = BarView(c)
        bar.labels = labels
        bar.values = values
        card3.addView(bar, Ui.lp(Ui.MP, Ui.WC))
        val c3p = Ui.lp(Ui.MP, Ui.WC)
        c3p.topMargin = Ui.dp(c, 10f)
        body.addView(card3, c3p)
    }

    private fun rankRow(p: Pair<String, Double>, total: Double, index: Int): View {
        val col = Ui.col(c)
        col.setPadding(0, Ui.dp(c, 7f), 0, Ui.dp(c, 7f))

        val row = Ui.row(c)
        val dot = View(c)
        dot.setBackground(Ui.round(c, DonutView.COLORS[index % DonutView.COLORS.size], 4f))
        row.addView(dot, Ui.lp(Ui.dp(c, 8f), Ui.dp(c, 8f)))
        val name = Ui.tv(c, act.db().emojiOf(p.first) + " " + p.first, 13.5f, Ui.TEXT)
        val np = Ui.lp(0, Ui.WC, 1f)
        np.leftMargin = Ui.dp(c, 8f)
        row.addView(name, np)
        val pct = if (total <= 0.0) 0 else (p.second / total * 100).toInt()
        row.addView(Ui.tv(c, "¥" + Ui.money(p.second) + "  $pct%", 12.5f, Ui.SUB))
        col.addView(row, Ui.lp(Ui.MP, Ui.WC))

        val track = LinearLayout(c)
        track.orientation = LinearLayout.HORIZONTAL
        track.setBackground(Ui.round(c, 0xFFF5EFE7.toInt(), 4f))
        val fill = View(c)
        fill.setBackground(Ui.round(c, DonutView.COLORS[index % DonutView.COLORS.size], 4f))
        val ratio = if (total <= 0.0) 0.0 else p.second / total
        track.addView(fill, Ui.lp(0, Ui.dp(c, 6f), ratio.toFloat()))
        if (ratio < 1.0) track.addView(View(c), Ui.lp(0, Ui.dp(c, 6f), (1.0 - ratio).toFloat()))
        val tp = Ui.lp(Ui.MP, Ui.dp(c, 6f))
        tp.topMargin = Ui.dp(c, 7f)
        col.addView(track, tp)
        return col
    }
}
