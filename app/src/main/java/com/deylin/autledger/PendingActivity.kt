package com.deylin.autledger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待确认：自动抓取到的消费先落在这里，确认后才进正式账本。
 *
 * 结构说明（v2.3）：改成与「我的」页完全同构的写法 —— 根 LinearLayout + 顶部行 + 滚动区(weight=1)。
 * 「我的」页在同一台手机上显示正常，故以此为唯一可信基准，不再使用「直接 setContentView(ScrollView)」的形式。
 * 每条只使用 row + TextView 的最简组合，避免多层嵌套 LayoutParams 造成的渲染异常。
 * 顶部固定显示一行统计（全库笔数 / 待确认笔数），用于区分「数据没落库」与「渲染没出来」。
 */
class PendingActivity : Activity() {

    private lateinit var helper: DbHelper
    private lateinit var listBox: LinearLayout
    private lateinit var head: TextView
    private lateinit var tip: TextView
    private lateinit var stat: TextView
    private var focusId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helper = DbHelper(this)
        focusId = intent.getLongExtra("focus", 0L)

        val root = Ui.col(this)
        root.setBackgroundColor(Ui.BG)

        // 顶部行：返回 / 标题 / 全部确认
        val top = Ui.row(this)
        Ui.pad(top, 14, 12, 14, 6)
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
        root.addView(top, Ui.lp(Ui.MP, Ui.WC))

        // 滚动区（与「我的」页同一个写法：weight=1 撑满剩余高度）
        val sv = ScrollView(this)
        sv.isFillViewport = true
        val col = Ui.col(this)
        Ui.pad(col, 14, 4, 14, 28)

        tip = Ui.tv(this, "", 11.5f, Ui.ORANGE_DEEP)
        tip.setBackground(Ui.round(this, Ui.ORANGE_LIGHT, 10f))
        tip.setPadding(Ui.dp(this, 10f), Ui.dp(this, 9f), Ui.dp(this, 10f), Ui.dp(this, 9f))
        tip.visibility = View.GONE
        tip.setOnClickListener { openOverlay() }
        val tp = Ui.lp(Ui.MP, Ui.WC)
        tp.topMargin = Ui.dp(this, 10f)
        col.addView(tip, tp)

        // 统计行：用于自证「数据在不在」
        stat = Ui.tv(this, "读取中…", 11f, Ui.SUB)
        stat.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 2f))
        col.addView(stat, Ui.lp(Ui.MP, Ui.WC))

        listBox = Ui.col(this)
        col.addView(listBox, Ui.lp(Ui.MP, Ui.WC))

        sv.addView(col, Ui.lp(Ui.MP, Ui.WC))
        root.addView(sv, Ui.lp(Ui.MP, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        var list: List<Rec> = ArrayList()
        var total = -1
        var err = ""
        try {
            list = helper.pending()
            total = helper.totalCount()
        } catch (e: Exception) {
            err = e.javaClass.simpleName + ":" + (e.message ?: "")
        }

        head.text = "待确认（${list.size}）"
        stat.text = if (err.isNotEmpty()) {
            "读取失败 → $err"
        } else {
            "库内共 $total 笔，待确认 ${list.size} 笔"
        }

        if (!Settings.canDrawOverlays(this)) {
            tip.visibility = View.VISIBLE
            tip.text = "⚠ 悬浮窗未开启：后台抓到的消费不会弹卡片。通知栏仍会提醒。点此开启 →"
        } else {
            tip.visibility = View.GONE
        }

        listBox.removeAllViews()
        if (list.isEmpty()) {
            val t = Ui.tv(this, "没有待确认的账单\n自动抓取到的消费会显示在这里", 13f, Ui.SUB)
            t.gravity = Gravity.CENTER
            t.setPadding(0, Ui.dp(this, 60f), 0, 0)
            listBox.addView(t, Ui.lp(Ui.MP, Ui.WC))
            return
        }

        for (r in list) {
            try {
                val v = rowFor(r)
                val lp = Ui.lp(Ui.MP, Ui.WC)
                lp.topMargin = Ui.dp(this, 9f)
                listBox.addView(v, lp)
            } catch (e: Exception) {
                val t = Ui.tv(this, "这笔渲染异常：" + e.javaClass.simpleName, 12f, Ui.RED)
                t.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
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

    /** 单条渲染：只用 row + TextView，不嵌套卡片类组件 */
    private fun rowFor(r: Rec): View {
        val box = Ui.col(this)
        box.setBackground(Ui.round(this, Ui.CARD, 14f))
        Ui.pad(box, 14, 12, 14, 12)

        val l1 = Ui.row(this)
        l1.addView(
            Ui.tv(this, if (r.note.isBlank()) r.cat else r.note, 14.5f, Ui.TEXT, true),
            Ui.lp(0, Ui.WC, 1f)
        )
        l1.addView(Ui.tv(this, "-¥" + Ui.money(r.amount), 15.5f, Ui.TEXT, true))
        box.addView(l1, Ui.lp(Ui.MP, Ui.WC))

        val l2 = Ui.row(this)
        val p2 = Ui.lp(Ui.MP, Ui.WC)
        p2.topMargin = Ui.dp(this, 6f)
        val src = if (r.src.isBlank()) "手工" else r.src
        l2.addView(Ui.tv(this, r.cat + " · " + src, 11.5f, Ui.SUB), Ui.lp(0, Ui.WC, 1f))
        l2.addView(Ui.tv(this, fmt(r.ts), 11.5f, Ui.SUB))
        box.addView(l2, p2)

        val l3 = Ui.row(this)
        val p3 = Ui.lp(Ui.MP, Ui.WC)
        p3.topMargin = Ui.dp(this, 10f)
        val del = Ui.tv(this, "删除", 12.5f, Ui.SUB)
        del.setPadding(Ui.dp(this, 10f), Ui.dp(this, 7f), Ui.dp(this, 10f), Ui.dp(this, 7f))
        del.setOnClickListener {
            helper.delRecord(r.id)
            load()
        }
        l3.addView(del, Ui.lp(0, Ui.WC, 1f))

        val ok = Ui.tv(this, "确认记账", 12.5f, 0xFFFFFFFF.toInt(), true)
        ok.setBackground(Ui.round(this, Ui.ORANGE, 14f))
        ok.setPadding(Ui.dp(this, 16f), Ui.dp(this, 8f), Ui.dp(this, 16f), Ui.dp(this, 8f))
        ok.setOnClickListener { pickCat(r) }
        l3.addView(ok, Ui.lp(Ui.WC, Ui.WC))
        box.addView(l3, p3)

        // 整块也可点，避免按钮点不到时的死路
        box.setOnClickListener { pickCat(r) }
        return box
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ts))

    private fun pickCat(r: Rec) {
        val cats = helper.cats(r.type)
        val names = ArrayList<String>()
        names.add("用当前分类「" + r.cat + "」直接记账")
        for (cd in cats) names.add(cd.emoji + "  " + cd.name)
        AlertDialog.Builder(this)
            .setTitle("确认这笔的分类")
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
