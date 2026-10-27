package com.deylin.autledger

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 记一笔：类型切换 + 金额键盘 + 分类九宫格 + 日期备注 */
class PageEntry(private val act: MainActivity) {

    private val c: Context = act
    private var type = 0
    private var amt = ""
    private var cat = "餐饮"
    private var ts = System.currentTimeMillis()
    private var note = ""
    private var editing: Rec? = null

    private lateinit var tvAmount: TextView
    private lateinit var chip: TextView
    private lateinit var catBox: LinearLayout
    private lateinit var tvDate: TextView
    private lateinit var tvNote: TextView
    private val cells = ArrayList<Pair<View, String>>()

    fun show(rec: Rec?) {
        editing = rec
        if (rec != null) {
            type = rec.type
            cat = rec.cat
            ts = rec.ts
            note = rec.note
            amt = plain(rec.amount)
        } else {
            type = 0
            cat = "餐饮"
            ts = System.currentTimeMillis()
            note = ""
            amt = ""
        }

        val root = Ui.col(c)
        root.setBackgroundColor(Ui.BG)

        // ---- 顶栏 ----
        val top = Ui.row(c)
        Ui.pad(top, 14, 12, 18, 4)
        val close = Ui.tv(c, "✕", 17f, Ui.SUB)
        close.setPadding(Ui.dp(c, 8f), 0, Ui.dp(c, 8f), 0)
        close.setOnClickListener { closePanel() }
        top.addView(close)
        top.addView(View(c), Ui.lp(0, 1, 1f))

        val tabE = Ui.tv(c, "支出", 15f, Ui.ORANGE, true)
        val tabI = Ui.tv(c, "收入", 15f, Ui.SUB)
        tabE.setPadding(Ui.dp(c, 10f), 0, Ui.dp(c, 10f), 0)
        tabI.setPadding(Ui.dp(c, 10f), 0, Ui.dp(c, 10f), 0)
        tabE.setOnClickListener {
            if (type != 0) {
                type = 0
                cat = firstCat(0)
                repaintTabs(tabE, tabI)
                buildCats()
                refreshHead()
            }
        }
        tabI.setOnClickListener {
            if (type != 1) {
                type = 1
                cat = firstCat(1)
                repaintTabs(tabI, tabE)
                buildCats()
                refreshHead()
            }
        }
        top.addView(tabE)
        top.addView(tabI)
        root.addView(top, Ui.lp(Ui.MP, Ui.WC))

        // ---- 头部：已选分类 + 金额 ----
        val head = Ui.row(c)
        Ui.pad(head, 16, 4, 16, 10)
        chip = Ui.tv(c, cat, 12.5f, Ui.ORANGE_DEEP, true)
        chip.setBackground(Ui.round(c, Ui.ORANGE_LIGHT, 14f))
        chip.setPadding(Ui.dp(c, 12f), Ui.dp(c, 7f), Ui.dp(c, 12f), Ui.dp(c, 7f))
        head.addView(chip)
        head.addView(View(c), Ui.lp(0, 1, 1f))
        tvAmount = Ui.tv(c, "- ¥0", 30f, Ui.TEXT, true)
        head.addView(tvAmount)
        root.addView(head, Ui.lp(Ui.MP, Ui.WC))

        // ---- 分类九宫格 ----
        val sv = ScrollView(c)
        catBox = Ui.col(c)
        sv.addView(catBox, Ui.lp(Ui.MP, Ui.WC))
        root.addView(sv, Ui.lp(Ui.MP, 0, 1f))
        buildCats()

        // ---- 键盘 ----
        val pad = GridLayout(c)
        pad.columnCount = 3
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫")
        for (k in keys) {
            val b = Ui.tv(c, k, 20f, Ui.TEXT, true)
            b.gravity = Gravity.CENTER
            b.setBackground(Ui.round(c, Ui.CARD, 10f))
            val lp = GridLayout.LayoutParams()
            lp.width = 0
            lp.height = Ui.dp(c, 46f)
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.setMargins(Ui.dp(c, 3f), Ui.dp(c, 3f), Ui.dp(c, 3f), Ui.dp(c, 3f))
            b.layoutParams = lp
            b.setOnClickListener { press(k) }
            pad.addView(b)
        }
        Ui.pad(pad, 8, 2, 8, 2)
        root.addView(pad, Ui.lp(Ui.MP, Ui.WC))

        // ---- 底部：日期 / 备注 / 保存 ----
        val bottom = Ui.col(c)
        Ui.pad(bottom, 14, 4, 14, 12)
        val meta = Ui.row(c)
        tvDate = Ui.tv(c, dateLabel(), 12.5f, Ui.TEXT)
        tvDate.setBackground(Ui.round(c, Ui.CARD, 14f))
        tvDate.setPadding(Ui.dp(c, 12f), Ui.dp(c, 9f), Ui.dp(c, 12f), Ui.dp(c, 9f))
        tvDate.setOnClickListener { pickDate() }
        meta.addView(tvDate)
        tvNote = Ui.tv(c, if (note.isBlank()) "＋ 备注" else note, 12.5f, if (note.isBlank()) Ui.SUB else Ui.TEXT)
        tvNote.setBackground(Ui.round(c, Ui.CARD, 14f))
        tvNote.setPadding(Ui.dp(c, 12f), Ui.dp(c, 9f), Ui.dp(c, 12f), Ui.dp(c, 9f))
        tvNote.setOnClickListener { askNote() }
        val np = Ui.lp(0, Ui.WC, 1f)
        np.leftMargin = Ui.dp(c, 8f)
        meta.addView(tvNote, np)
        bottom.addView(meta, Ui.lp(Ui.MP, Ui.WC))

        val saveBtn = Ui.button(c, if (editing == null) "保存" else "保存修改", Ui.ORANGE, 0xFFFFFFFF.toInt(), 16f)
        saveBtn.setOnClickListener { save() }
        val sp = Ui.lp(Ui.MP, Ui.WC)
        sp.topMargin = Ui.dp(c, 9f)
        bottom.addView(saveBtn, sp)
        root.addView(bottom, Ui.lp(Ui.MP, Ui.WC))

        refreshHead()

        act.host.removeAllViews()
        act.host.addView(root, FrameLayout.LayoutParams(Ui.MP, Ui.MP))
        act.host.visibility = View.VISIBLE
    }

    private fun repaintTabs(on: TextView, off: TextView) {
        on.setTextColor(Ui.ORANGE)
        on.setTypeface(Typeface.DEFAULT_BOLD)
        off.setTextColor(Ui.SUB)
        off.setTypeface(Typeface.DEFAULT)
    }

    private fun firstCat(t: Int): String {
        val list = act.db().cats(t)
        return if (list.isEmpty()) "其它" else list[0].name
    }

    private fun buildCats() {
        catBox.removeAllViews()
        cells.clear()
        val list = act.db().cats(type)
        if (list.isEmpty()) return
        var row: LinearLayout? = null
        for (i in list.indices) {
            if (i % 4 == 0) {
                val r = Ui.row(c)
                Ui.pad(r, 10, 2, 10, 2)
                catBox.addView(r, Ui.lp(Ui.MP, Ui.WC))
                row = r
            }
            val cell = Ui.col(c)
            cell.gravity = Gravity.CENTER
            cell.setPadding(0, Ui.dp(c, 9f), 0, Ui.dp(c, 9f))
            cell.layoutParams = Ui.lp(0, Ui.WC, 1f)

            val em = Ui.tv(c, list[i].emoji, 20f, Ui.TEXT)
            em.gravity = Gravity.CENTER
            cell.addView(em)
            val nm = Ui.tv(c, list[i].name, 11f, Ui.SUB)
            nm.gravity = Gravity.CENTER
            nm.setPadding(0, Ui.dp(c, 4f), 0, 0)
            cell.addView(nm)

            val name = list[i].name
            cell.setOnClickListener {
                cat = name
                refreshHead()
            }
            row?.addView(cell, Ui.lp(0, Ui.WC, 1f))
            cells.add(Pair(cell, name))
        }
    }

    private fun refreshHead() {
        val show = if (amt.isEmpty() || amt == ".") "0" else amt
        tvAmount.text = (if (type == 0) "- ¥" else "+ ¥") + show
        tvAmount.setTextColor(if (type == 0) Ui.TEXT else Ui.GREEN)
        chip.text = cat
        for (p in cells) {
            if (p.second == cat) p.first.setBackground(Ui.round(c, Ui.ORANGE_LIGHT, 12f))
            else p.first.setBackgroundColor(0x00000000)
        }
    }

    private fun press(k: String) {
        when {
            k == "⌫" -> if (amt.isNotEmpty()) amt = amt.substring(0, amt.length - 1)
            k == "." -> if (!amt.contains(".")) amt = if (amt.isEmpty()) "0." else amt + "."
            amt.length < 9 -> {
                if (amt == "0") amt = k else amt += k
            }
        }
        refreshHead()
    }

    private fun plain(v: Double): String {
        val s = String.format(Locale.CHINA, "%.2f", v)
        return if (s.endsWith(".00")) s.substring(0, s.length - 3) else s
    }

    private fun dateLabel(): String =
        SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ts))

    private fun pickDate() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        DatePickerDialog(
            act,
            { _, y, m, d ->
                val n = Calendar.getInstance()
                n.timeInMillis = ts
                n.set(Calendar.YEAR, y)
                n.set(Calendar.MONTH, m)
                n.set(Calendar.DAY_OF_MONTH, d)
                ts = n.timeInMillis
                tvDate.text = dateLabel()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun askNote() {
        val et = EditText(c)
        et.setText(note)
        et.setHint("例如：和同事午餐")
        et.inputType = InputType.TYPE_CLASS_TEXT
        AlertDialog.Builder(act)
            .setTitle("备注")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                note = et.text.toString().trim()
                tvNote.text = if (note.isBlank()) "＋ 备注" else note
                tvNote.setTextColor(if (note.isBlank()) Ui.SUB else Ui.TEXT)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun save() {
        val v = amt.toDoubleOrNull() ?: 0.0
        if (v <= 0.0) {
            Toast.makeText(act, "请输入金额", Toast.LENGTH_SHORT).show()
            return
        }
        val e = editing
        if (e == null) {
            act.db().addRecord(
                Rec(
                    amount = v, type = type, cat = cat, note = note,
                    ts = ts, book = act.book, status = 1, src = "手工"
                )
            )
        } else {
            act.db().updateRecord(e.id, v, cat, note, ts, 1)
        }
        closePanel()
        act.refresh()
    }

    private fun closePanel() {
        act.host.removeAllViews()
        act.refresh()
    }
}
