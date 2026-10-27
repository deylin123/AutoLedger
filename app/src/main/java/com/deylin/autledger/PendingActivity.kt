package com.deylin.autledger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待确认：自动抓取到的消费先落在这里，确认后才进正式账本。
 * 布局说明：整页 ScrollView 承载全部内容，不使用「高度 0 + weight 撑开」，
 * 避免窗口未给出确定高度时列表高度被算成 0（表现为标题有数字、列表全空）。
 */
class PendingActivity : Activity() {

    private lateinit var helper: DbHelper
    private lateinit var listBox: LinearLayout
    private lateinit var head: TextView
    private lateinit var tip: TextView
    private var focusId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helper = DbHelper(this)
        focusId = intent.getLongExtra("focus", 0L)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Ui.BG)
        sv.isFillViewport = true

        val col = Ui.col(this)
        Ui.pad(col, 14, 14, 14, 28)
        sv.addView(
            col,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val top = Ui.row(this)
        val back = Ui.tv(this, "‹ 返回", 14f, Ui.TEXT)
        back.setPadding(0, Ui.dp(this, 6f), Ui.dp(this, 10f), Ui.dp(this, 6f))
        back.setOnClickListener { finish() }
        top.addView(back, Ui.lp(0, Ui.WC, 1f))
        head = Ui.tv(this, "待确认", 16f, Ui.TEXT, true)
        top.addView(head)
        top.addView(View(this), Ui.lp(0, Ui.WC, 1f))
        val all = Ui.tv(this, "全部确认", 13f, Ui.ORANGE, true)
        all.setPadding(Ui.dp(this, 8f), Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
        all.setOnClickListener { confirmAll() }
        top.addView(all)
        col.addView(top, Ui.lp(Ui.MP, Ui.WC))

        tip = Ui.tv(this, "", 11.5f, Ui.ORANGE_DEEP)
        tip.setBackground(Ui.round(this, Ui.ORANGE_LIGHT, 10f))
        tip.setPadding(Ui.dp(this, 10f), Ui.dp(this, 9f), Ui.dp(this, 10f), Ui.dp(this, 9f))
        tip.visibility = View.GONE
        tip.setOnClickListener { openOverlay() }
        val tp = Ui.lp(Ui.MP, Ui.WC)
        tp.topMargin = Ui.dp(this, 10f)
        col.addView(tip, tp)

        listBox = Ui.col(this)
        col.addView(listBox, Ui.lp(Ui.MP, Ui.WC))

        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val list = helper.pending()
        head.text = "待确认（${list.size}）"

        if (!Settings.canDrawOverlays(this)) {
            tip.visibility = View.VISIBLE
            tip.text = "⚠ 悬浮窗未开启，后台抓到的消费不会弹窗，只能在这里补确认。点此开启 →"
        } else {
            tip.visibility = View.GONE
        }

        listBox.removeAllViews()
        if (list.isEmpty()) {
            val t = Ui.tv(this, "没有待确认的账单\n自动抓取的消费会出现在这里", 13f, Ui.SUB)
            t.gravity = Gravity.CENTER
            t.setPadding(0, Ui.dp(this, 70f), 0, 0)
            listBox.addView(t, Ui.lp(Ui.MP, Ui.WC))
            return
        }

        for (r in list) {
            try {
                listBox.addView(rowFor(r))
            } catch (e: Exception) {
                val t = Ui.tv(this, "这笔显示异常：" + e.javaClass.simpleName, 12f, Ui.RED)
                listBox.addView(t, Ui.lp(Ui.MP, Ui.WC))
            }
        }

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
        p.topMargin = Ui.dp(this, 9f)
        card.layoutParams = p

        val line1 = Ui.row(this)
        line1.addView(Ui.iconCircle(this, helper.emojiOf(r.cat)))

        val mid = Ui.col(this)
        val mp = Ui.lp(0, Ui.WC, 1f)
        mp.leftMargin = Ui.dp(this, 10f)
        mid.layoutParams = mp
        val title = if (r.note.isBlank()) r.cat else r.note
        mid.addView(Ui.tv(this, title, 14.5f, Ui.TEXT), Ui.lp(Ui.WC, Ui.WC))
        mid.addView(
            Ui.tv(this, r.cat + " · " + (if (r.src.isBlank()) "手动" else r.src), 11f, Ui.SUB),
            Ui.lp(Ui.WC, Ui.WC)
        )
        line1.addView(mid)
        line1.addView(Ui.tv(this, "-¥" + Ui.money(r.amount), 15.5f, Ui.TEXT, true))
        card.addView(line1, Ui.lp(Ui.MP, Ui.WC))

        val line2 = Ui.row(this)
        val lp2 = Ui.lp(Ui.MP, Ui.WC)
        lp2.topMargin = Ui.dp(this, 10f)
        val dateTv = Ui.tv(
            this,
            SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(r.ts)),
            11.5f, Ui.SUB
        )
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

    private fun openOverlay() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
        }
    }
}
