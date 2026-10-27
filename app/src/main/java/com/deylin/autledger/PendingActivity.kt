package com.deylin.autledger

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 待确认：自动抓取到的消费先落在这里，确认后才进正式账本 */
class PendingActivity : Activity() {

    private lateinit var helper: DbHelper
    private lateinit var box: android.widget.LinearLayout
    private lateinit var head: TextView
    private var focusId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helper = DbHelper(this)
        focusId = intent.getLongExtra("focus", 0L)

        val root = Ui.col(this)
        root.setBackgroundColor(Ui.BG)

        val top = Ui.row(this)
        Ui.pad(top, 16, 14, 16, 8)
        val back = Ui.tv(this, "‹ 返回", 14f, Ui.TEXT)
        back.setOnClickListener { finish() }
        top.addView(back, Ui.lp(0, Ui.WC, 1f))
        head = Ui.tv(this, "待确认", 16f, Ui.TEXT, true)
        top.addView(head)
        top.addView(View(this), Ui.lp(0, Ui.WC, 1f))
        val all = Ui.tv(this, "全部确认", 13f, Ui.ORANGE, true)
        all.setPadding(Ui.dp(this, 8f), Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
        all.setOnClickListener { confirmAll() }
        top.addView(all)
        root.addView(top, Ui.lp(Ui.MP, Ui.WC))

        val sv = ScrollView(this)
        sv.isFillViewport = true
        box = Ui.col(this)
        Ui.pad(box, 14, 4, 14, 24)
        sv.addView(box, Ui.lp(Ui.MP, Ui.WC))
        root.addView(sv, Ui.lp(Ui.MP, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val list = helper.pending()
        head.text = "待确认（${list.size}）"
        box.removeAllViews()
        if (list.isEmpty()) {
            val t = Ui.tv(this, "没有待确认的账单\n自动抓取的消费会出现在这里", 13f, Ui.SUB)
            t.gravity = Gravity.CENTER
            t.setPadding(0, Ui.dp(this, 70f), 0, 0)
            box.addView(t, Ui.lp(Ui.MP, Ui.WC))
            return
        }
        for (r in list) box.addView(rowFor(r))
        if (focusId > 0L) {
            for (r in list) {
                if (r.id == focusId) {
                    focusId = 0L
                    pickCat(r)
                    break
                }
            }
        }
    }

    private fun rowFor(r: Rec): View {
        val card = Ui.card(this, 14f, 12f)
        val p = Ui.lp(Ui.MP, Ui.WC)
        p.topMargin = Ui.dp(this, 8f)
        card.layoutParams = p

        val line1 = Ui.row(this)
        line1.addView(Ui.iconCircle(this, helper.emojiOf(r.cat)))
        val mid = Ui.col(this)
        val mp = Ui.lp(0, Ui.WC, 1f)
        mp.leftMargin = Ui.dp(this, 10f)
        mid.layoutParams = mp
        mid.addView(Ui.tv(this, if (r.note.isBlank()) r.cat else r.note, 14.5f, Ui.TEXT))
        mid.addView(Ui.tv(this, r.cat + " · " + r.src, 11f, Ui.SUB))
        line1.addView(mid)
        line1.addView(Ui.tv(this, "-¥" + Ui.money(r.amount), 15.5f, Ui.TEXT, true))
        card.addView(line1)

        val line2 = Ui.row(this)
        val lp2 = Ui.lp(Ui.MP, Ui.WC)
        lp2.topMargin = Ui.dp(this, 10f)
        val dateTv = Ui.tv(this, SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(r.ts)), 11.5f, Ui.SUB)
        line2.addView(dateTv, Ui.lp(0, Ui.WC, 1f))
        val del = Ui.tv(this, "删除", 12.5f, Ui.SUB)
        del.setPadding(Ui.dp(this, 10f), Ui.dp(this, 6f), Ui.dp(this, 10f), Ui.dp(this, 6f))
        del.setOnClickListener {
            helper.delRecord(r.id)
            load()
        }
        line2.addView(del)
        val ok = Ui.tv(this, "确认记账", 12.5f, 0xFFFFFFFF.toInt(), true)
        ok.setBackground(Ui.round(this, Ui.ORANGE, 14f))
        ok.setPadding(Ui.dp(this, 14f), Ui.dp(this, 7f), Ui.dp(this, 14f), Ui.dp(this, 7f))
        ok.setOnClickListener { pickCat(r) }
        val op = Ui.lp(Ui.WC, Ui.WC)
        op.leftMargin = Ui.dp(this, 8f)
        line2.addView(ok, op)
        card.addView(line2, lp2)
        return card
    }

    private fun pickCat(r: Rec) {
        val cats = helper.cats(r.type)
        val names = ArrayList<String>()
        names.add("用当前分类「" + r.cat + "」")
        for (cd in cats) names.add(cd.emoji + "  " + cd.name)
        AlertDialog.Builder(this)
            .setTitle("选择分类")
            .setItems(names.toTypedArray()) { _, w ->
                val cat = if (w == 0) r.cat else cats[w - 1].name
                helper.updateRecord(r.id, r.amount, cat, r.note, r.ts, 1)
                Toast.makeText(this, "已记账：" + cat, Toast.LENGTH_SHORT).show()
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmAll() {
        val list = helper.pending()
        if (list.isEmpty()) {
            Toast.makeText(this, "没有待确认账单", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("全部确认 ${list.size} 笔？")
            .setMessage("将按当前自动分类直接记账")
            .setPositiveButton("全部确认") { _, _ ->
                for (r in list) helper.updateRecord(r.id, r.amount, r.cat, r.note, r.ts, 1)
                Toast.makeText(this, "已确认 ${list.size} 笔", Toast.LENGTH_SHORT).show()
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
