package com.deylin.autledger

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 明细页：月份切换 + 收支汇总 + 预算进度 + 按日分组流水 */
class PageDetail(private val act: MainActivity) {

    private val c: Context = act

    fun build(): View {
        val root = Ui.col(c)
        root.setBackgroundColor(Ui.BG)

        val y = act.curY
        val m = act.curM
        val from = DbHelper.monthStart(y, m)
        val to = DbHelper.monthEnd(y, m)
        val db = act.db()

        // ---- 顶栏：账本 + 月份 ----
        val top = Ui.row(c)
        Ui.pad(top, 16, 12, 16, 4)
        val bookTv = Ui.tv(c, act.book + "  ▾", 14f, Ui.TEXT, true)
        bookTv.setOnClickListener { pickBook() }
        top.addView(bookTv)
        top.addView(View(c), Ui.lp(0, 1, 1f))

        val prev = Ui.tv(c, "‹", 20f, Ui.SUB)
        prev.setPadding(Ui.dp(c, 10f), 0, Ui.dp(c, 10f), 0)
        prev.setOnClickListener {
            act.curM = if (m == 0) 11 else m - 1
            if (m == 0) act.curY = y - 1
            act.refresh()
        }
        val label = Ui.tv(c, "${y}年${m + 1}月", 15f, Ui.TEXT, true)
        label.setPadding(Ui.dp(c, 6f), 0, Ui.dp(c, 6f), 0)
        val next = Ui.tv(c, "›", 20f, Ui.SUB)
        next.setPadding(Ui.dp(c, 10f), 0, Ui.dp(c, 10f), 0)
        next.setOnClickListener {
            act.curM = if (m == 11) 0 else m + 1
            if (m == 11) act.curY = y + 1
            act.refresh()
        }
        top.addView(prev)
        top.addView(label)
        top.addView(next)
        root.addView(top, Ui.lp(Ui.MP, Ui.WC))

        // ---- 汇总卡 ----
        val sumCard = Ui.card(c, 8f, 16f)
        val sumRow = Ui.row(c)
        val exp = db.sum(from, to, act.book, 0)
        val inc = db.sum(from, to, act.book, 1)
        sumRow.addView(sumCol("支出", exp, Ui.RED, true), Ui.lp(0, Ui.WC, 1f))
        sumRow.addView(sumCol("收入", inc, Ui.GREEN, false), Ui.lp(0, Ui.WC, 1f))
        sumRow.addView(sumCol("结余", inc - exp, Ui.TEXT, false), Ui.lp(0, Ui.WC, 1f))
        sumCard.addView(sumRow)

        // 预算进度
        val month = String.format(Locale.CHINA, "%04d-%02d", y, m + 1)
        val budget = db.budget(month)
        if (budget > 0.0) {
            val bar = LinearLayout(c)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.setBackground(Ui.round(c, 0xFFF3EDE4.toInt(), 5f))
            val used = if (exp > budget) 1.0 else exp / budget
            val fill = View(c)
            fill.setBackground(Ui.round(c, if (exp > budget) Ui.RED else Ui.ORANGE, 5f))
            bar.addView(fill, Ui.lp(0, Ui.dp(c, 7f), used.toFloat()))
            if (used < 1.0) bar.addView(View(c), Ui.lp(0, Ui.dp(c, 7f), (1.0 - used).toFloat()))
            val bp = Ui.lp(Ui.MP, Ui.dp(c, 7f))
            bp.topMargin = Ui.dp(c, 12f)
            sumCard.addView(bar, bp)
            val txt = Ui.tv(
                c,
                "本月预算 ¥" + Ui.money(budget) + " · 已用 " + (used * 100).toInt() + "%",
                11.5f,
                if (exp > budget) Ui.RED else Ui.SUB
            )
            val tp = Ui.lp(Ui.MP, Ui.WC)
            tp.topMargin = Ui.dp(c, 6f)
            sumCard.addView(txt, tp)
        }
        val scp = Ui.lp(Ui.MP, Ui.WC)
        scp.leftMargin = Ui.dp(c, 14f)
        scp.rightMargin = Ui.dp(c, 14f)
        root.addView(sumCard, scp)

        // ---- 待确认横幅 ----
        val pend = db.pendingCount()
        if (pend > 0) {
            val banner = Ui.row(c)
            banner.setBackground(Ui.round(c, Ui.ORANGE_LIGHT, 12f))
            Ui.pad(banner, 14, 11, 14, 11)
            val t = Ui.tv(c, "有 $pend 笔自动抓取的消费待确认", 13f, Ui.ORANGE_DEEP, true)
            banner.addView(t, Ui.lp(0, Ui.WC, 1f))
            banner.addView(Ui.tv(c, "去确认 ›", 13f, Ui.ORANGE_DEEP))
            banner.setOnClickListener { act.startActivity(Intent(act, PendingActivity::class.java)) }
            val bp = Ui.lp(Ui.MP, Ui.WC)
            bp.leftMargin = Ui.dp(c, 14f)
            bp.rightMargin = Ui.dp(c, 14f)
            bp.topMargin = Ui.dp(c, 10f)
            root.addView(banner, bp)
        }

        // ---- 流水列表 ----
        val sv = ScrollView(c)
        sv.isFillViewport = true
        val list = Ui.col(c)
        Ui.pad(list, 14, 10, 14, 24)

        val recs = db.list(from, to, act.book, -1, true)
        if (recs.isEmpty()) {
            val empty = Ui.col(c)
            empty.gravity = Gravity.CENTER
            val p = Ui.lp(Ui.MP, Ui.WC)
            p.topMargin = Ui.dp(c, 60f)
            val t1 = Ui.tv(c, "这个月还没有记录", 14f, Ui.SUB)
            t1.gravity = Gravity.CENTER
            empty.addView(t1)
            val t2 = Ui.tv(c, "点下方「＋」记一笔，或打开支付宝账单页自动抓取", 12f, Ui.SUB)
            t2.gravity = Gravity.CENTER
            t2.setPadding(0, Ui.dp(c, 8f), 0, 0)
            empty.addView(t2)
            list.addView(empty, p)
        } else {
            val dayExp = HashMap<String, Double>()
            val dayInc = HashMap<String, Double>()
            for (r in recs) {
                val k = dayKey(r.ts)
                if (r.type == 0) dayExp[k] = (dayExp[k] ?: 0.0) + r.amount
                else dayInc[k] = (dayInc[k] ?: 0.0) + r.amount
            }
            var lastKey = ""
            for (r in recs) {
                val k = dayKey(r.ts)
                if (k != lastKey) {
                    lastKey = k
                    list.addView(dayHeader(k, dayExp[k] ?: 0.0, dayInc[k] ?: 0.0))
                }
                list.addView(recRow(r))
            }
        }
        sv.addView(list)
        root.addView(sv, Ui.lp(Ui.MP, 0, 1f))

        return root
    }

    private fun sumCol(title: String, v: Double, color: Int, bold: Boolean): View {
        val col = Ui.col(c)
        val t = Ui.tv(c, title, 11.5f, Ui.SUB)
        t.gravity = Gravity.CENTER
        col.addView(t)
        val n = Ui.tv(c, "¥" + Ui.money(v), 17f, color, true)
        n.gravity = Gravity.CENTER
        n.setPadding(0, Ui.dp(c, 5f), 0, 0)
        col.addView(n)
        return col
    }

    private fun dayKey(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))

    private fun dayHeader(key: String, exp: Double, inc: Double): View {
        val row = Ui.row(c)
        Ui.pad(row, 2, 14, 2, 6)
        var label = key
        try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(key)
            if (d != null) {
                val cal = Calendar.getInstance()
                cal.time = d
                val w = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
                label = "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 ${w[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
            }
        } catch (_: Exception) {
        }
        row.addView(Ui.tv(c, label, 12.5f, Ui.SUB, true), Ui.lp(0, Ui.WC, 1f))
        val sb = StringBuilder()
        if (exp > 0) sb.append("支出 ¥").append(Ui.money(exp))
        if (inc > 0) {
            if (sb.isNotEmpty()) sb.append("   ")
            sb.append("收入 ¥").append(Ui.money(inc))
        }
        row.addView(Ui.tv(c, sb.toString(), 11.5f, Ui.SUB))
        return row
    }

    private fun recRow(r: Rec): View {
        val row = Ui.row(c)
        row.setBackground(Ui.round(c, Ui.CARD, 14f))
        Ui.pad(row, 12, 11, 12, 11)
        val lp = Ui.lp(Ui.MP, Ui.WC)
        lp.topMargin = Ui.dp(c, 6f)

        row.addView(Ui.iconCircle(c, act.db().emojiOf(r.cat)))

        val mid = Ui.col(c)
        val mp = Ui.lp(0, Ui.WC, 1f)
        mp.leftMargin = Ui.dp(c, 10f)
        mid.layoutParams = mp
        val name = r.cat
        mid.addView(Ui.tv(c, name, 14.5f, Ui.TEXT))
        val sub = StringBuilder()
        if (r.note.isNotBlank()) sub.append(r.note)
        if (r.src != "手工") {
            if (sub.isNotEmpty()) sub.append(" · ")
            sub.append(r.src)
        }
        if (sub.isNotEmpty()) {
            val n = Ui.tv(c, sub.toString(), 11.5f, Ui.SUB)
            n.setPadding(0, Ui.dp(c, 3f), 0, 0)
            mid.addView(n)
        }
        row.addView(mid)

        val sign = if (r.type == 0) "-" else "+"
        val color = if (r.type == 0) Ui.TEXT else Ui.GREEN
        row.addView(Ui.tv(c, "$sign¥" + Ui.money(r.amount), 15.5f, color, true))

        row.layoutParams = lp
        row.setOnClickListener { PageEntry(act).show(r) }
        row.setOnLongClickListener {
            AlertDialog.Builder(act)
                .setTitle("删除这笔账单？")
                .setMessage("${r.cat}  ¥${Ui.money(r.amount)}")
                .setPositiveButton("删除") { _, _ ->
                    act.db().delRecord(r.id)
                    act.refresh()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        return row
    }

    private fun pickBook() {
        val db = act.db()
        val books = db.books()
        val arr = books.toTypedArray() + "＋ 新建账本"
        AlertDialog.Builder(act)
            .setTitle("选择账本")
            .setItems(arr) { _, which ->
                if (which == books.size) {
                    PageMine(act).newBookDialog { act.refresh() }
                } else {
                    act.book = books[which]
                    act.refresh()
                }
            }
            .show()
    }
}
