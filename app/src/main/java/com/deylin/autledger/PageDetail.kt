package com.deylin.autledger

import android.app.AlertDialog
import android.app.DatePickerDialog
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

        // ---- v2.28：分类筛选（图表排行点进来时非空）----
        val fCat = act.filterCat
        val fType = if (fCat != null) act.filterType else -1
        val recs = if (fCat != null) {
            db.list(from, to, act.book, fType, true, fCat)
        } else {
            emptyList<Rec>()
        }

        // ---- 汇总卡 ----
        val sumCard = Ui.card(c, 8f, 16f)
        val sumRow = Ui.row(c)
        // 筛选模式下汇总只算该类别；非筛选时用整月汇总
        val exp: Double
        val inc: Double
        if (fCat != null) {
            var e = 0.0
            var g = 0.0
            for (r in recs) {
                if (r.type == 0) e += r.amount else g += r.amount
            }
            exp = e
            inc = g
        } else {
            exp = db.sum(from, to, act.book, 0)
            inc = db.sum(from, to, act.book, 1)
        }
        sumRow.addView(sumCol(if (fCat == null) "支出" else "本类支出", exp, Ui.RED, true), Ui.lp(0, Ui.WC, 1f))
        sumRow.addView(sumCol(if (fCat == null) "收入" else "本类收入", inc, Ui.GREEN, false), Ui.lp(0, Ui.WC, 1f))
        if (fCat == null) sumRow.addView(sumCol("结余", inc - exp, Ui.TEXT, false), Ui.lp(0, Ui.WC, 1f))
        sumCard.addView(sumRow)

        // 预算进度
        val month = String.format(Locale.CHINA, "%04d-%02d", y, m + 1)
        val budget = db.budget(month)
        if (budget > 0.0 && fCat == null) {
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

        // ---- v2.29：批量改消费日期 ----
        // 旧版把 ts 覆写成「确认时刻」，且没备份原始消费时间，程序无法凭空还原；
        // 但完全不用删除重录：点单笔可改日期，这里一次改一批。
        val redateRow = Ui.row(c)
        redateRow.setBackground(Ui.round(c, Ui.ORANGE_LIGHT, 12f))
        Ui.pad(redateRow, 14, 10, 14, 10)
        redateRow.addView(Ui.tv(c, "🗓 批量改消费日期", 13f, Ui.ORANGE_DEEP, true), Ui.lp(0, Ui.WC, 1f))
        redateRow.addView(Ui.tv(c, "不用删除重录 ›", 12f, Ui.ORANGE_DEEP))
        redateRow.setOnClickListener { batchRedate(from, to) }
        val rdp = Ui.lp(Ui.MP, Ui.WC)
        rdp.leftMargin = Ui.dp(c, 14f)
        rdp.rightMargin = Ui.dp(c, 14f)
        rdp.topMargin = Ui.dp(c, 10f)
        root.addView(redateRow, rdp)

        // ---- v2.28：分类筛选横幅（点 ✕ 或切月份/账本外的操作即取消）----
        if (fCat != null) {
            val banner = Ui.row(c)
            banner.setBackground(Ui.round(c, Ui.ORANGE_LIGHT, 12f))
            Ui.pad(banner, 14, 11, 14, 11)
            val t = Ui.tv(
                c,
                act.db().emojiOf(fCat) + " " + fCat + " · " +
                    (if (fType == 1) "收入" else "支出") + "明细 " + recs.size + " 笔",
                13f, Ui.ORANGE_DEEP, true
            )
            banner.addView(t, Ui.lp(0, Ui.WC, 1f))
            banner.addView(Ui.tv(c, "取消筛选 ✕", 13f, Ui.ORANGE_DEEP))
            banner.setOnClickListener {
                act.filterCat = null
                act.refresh()
            }
            val bp2 = Ui.lp(Ui.MP, Ui.WC)
            bp2.leftMargin = Ui.dp(c, 14f)
            bp2.rightMargin = Ui.dp(c, 14f)
            bp2.topMargin = Ui.dp(c, 10f)
            root.addView(banner, bp2)
        }

        // ---- 待确认横幅 ----
        val pend = db.pendingCount()
        if (pend > 0) {
            val banner = Ui.row(c)
            banner.setBackground(Ui.round(c, Ui.ORANGE_LIGHT, 12f))
            Ui.pad(banner, 14, 11, 14, 11)
            val t = Ui.tv(c, "有 $pend 笔自动抓取的消费待确认", 13f, Ui.ORANGE_DEEP, true)
            banner.addView(t, Ui.lp(0, Ui.WC, 1f))
            banner.addView(Ui.tv(c, "去确认 ›", 13f, Ui.ORANGE_DEEP))
            banner.setOnClickListener {
                act.startActivity(
                    Intent(act, PendingActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
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

        val recsAll = if (fCat != null) recs else db.list(from, to, act.book, -1, true)
        if (recsAll.isEmpty()) {
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
            for (r in recsAll) {
                val k = dayKey(r.ts)
                if (r.type == 0) dayExp[k] = (dayExp[k] ?: 0.0) + r.amount
                else dayInc[k] = (dayInc[k] ?: 0.0) + r.amount
            }
            var lastKey = ""
            for (r in recsAll) {
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

        // ---- v2.31：保存 / 恢复流水列表的滚动位置 ----
        // MainActivity.refresh() 会整页重建，新建 ScrollView 一定回到顶部（最新一天）。
        // 记录用户实际滚到的位置；必须等列表布局完成、内容高度确定后再滚回去，
        // 否则 scrollTo 会被 clamp 到 0，视觉上仍然跳回最新一天。
        sv.setOnScrollChangeListener(
            View.OnScrollChangeListener { _, _, _, _, _ -> act.detailScrollY = sv.scrollY }
        )
        sv.addOnLayoutChangeListener(
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val target = act.detailScrollY
                if (target > 0 && sv.scrollY != target) sv.scrollTo(0, target)
            }
        )
        sv.post {
            val target = act.detailScrollY
            if (target > 0) sv.scrollTo(0, target)
            // scrollTo 会同步触发上面的滚动监听；若此刻布局尚未完成会被 clamp 到 0，
            // 这里把目标写回，保证后续 layout 回调仍能恢复到用户原来停的位置。
            act.detailScrollY = target
        }

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
        // v2.30：用户备注单独显示，不与识别内容/来源混在一起
        if (r.remark.isNotBlank()) {
            if (sub.isNotEmpty()) sub.append(" · ")
            sub.append("备注：").append(r.remark)
        }
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
        // 点开可改金额/分类/备注/**消费日期** —— 历史记录日期错了不必删除重录
        row.setOnClickListener { PageEntry(act).show(r) }
        row.setOnLongClickListener {
            AlertDialog.Builder(act)
                .setTitle("删除这笔账单？")
                .setMessage("${r.cat}  ¥${Ui.money(r.amount)}")
                .setPositiveButton("删除") { _, _ ->
                    act.db().delRecord(r.id)
                    act.refresh(true)   // v2.31：删完仍停在原来的位置
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        return row
    }

    /**
     * v2.29：批量把本月的记录改到某个消费日期。
     * 保留每笔原来的时:分:秒，只改年月日，避免全部挤到 00:00。
     */
    private fun batchRedate(from: Long, to: Long) {
        val db = act.db()
        val recs = if (act.filterCat != null) {
            db.list(from, to, act.book, act.filterType, true, act.filterCat)
        } else {
            db.list(from, to, act.book, -1, true)
        }
        if (recs.isEmpty()) {
            AlertDialog.Builder(act)
                .setTitle("本月没有记录")
                .setMessage("有记录后，可在这里一次性把多笔改到同一消费日期。")
                .setPositiveButton("知道了", null).show()
            return
        }
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        val labels = recs.map { r ->
            fmt.format(Date(r.ts)) + "  " + (if (r.type == 0) "-" else "+") + "¥" +
                Ui.money(r.amount) + "  " + r.cat +
                (if (r.note.isNotBlank()) "  " + r.note.take(10) else "")
        }.toTypedArray()
        val picked = BooleanArray(recs.size)
        AlertDialog.Builder(act)
            .setTitle("选要改日期的记录（可多选）")
            .setMultiChoiceItems(labels, picked) { _, w, on -> picked[w] = on }
            .setPositiveButton("下一步") { _, _ ->
                val ids = ArrayList<Long>()
                for (i in picked.indices) if (picked[i]) ids.add(recs[i].id)
                if (ids.isEmpty()) {
                    android.widget.Toast.makeText(act, "没有选中任何记录", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val cal = Calendar.getInstance()
                DatePickerDialog(act, { _, y, m, d ->
                    val t = Calendar.getInstance()
                    t.clear()
                    t.set(y, m, d, 12, 0, 0)
                    val n = db.redateRecords(ids, t.timeInMillis)
                    android.widget.Toast.makeText(
                        act, "已把 $n 笔的消费日期改为 ${y}年${m + 1}月${d}日（原时分秒保留）",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    act.refresh(true)   // v2.31：批量改完日期仍停在原来的位置
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
            .setNegativeButton("取消", null)
            .show()
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
