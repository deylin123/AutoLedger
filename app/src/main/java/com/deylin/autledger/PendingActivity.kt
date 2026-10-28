package com.deylin.autledger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待确认页（v2.4 重做）
 *
 * 前几版的失败教训：用代码动态搭建「顶部固定行 + 高度 0 + weight 撑开」的结构，
 * 在部分机型上列表区被测量成 0 像素，表现为标题有数字、下面一片空白且点不进去。
 *
 * 本版改为：
 *   1. 整页只有一个 ScrollView 作为根视图，所有内容顺序排列，不使用任何撑高度的 weight；
 *   2. 每一条明细用独立 XML（item_pending.xml）填充，结构固定、可复用；
 *   3. 标题区直接显示版本号与库内笔数，装错版本或数据没落库一眼可见；
 *   4. 单条渲染失败只影响那一行，并显示失败原因，不会让整页空白。
 */
class PendingActivity : Activity() {

    private lateinit var helper: DbHelper
    private lateinit var listBox: LinearLayout
    private lateinit var tvHead: TextView
    private lateinit var tvStat: TextView
    private lateinit var tvTip: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvVer: TextView
    private var focusId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending)
        helper = DbHelper(this)
        focusId = intent.getLongExtra("focus", 0L)

        listBox = findViewById(R.id.listBox)
        tvHead = findViewById(R.id.tvHead)
        tvStat = findViewById(R.id.tvStat)
        tvTip = findViewById(R.id.tvTip)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvVer = findViewById(R.id.tvVer)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAll).setOnClickListener { confirmAll() }
        tvTip.setOnClickListener { openOverlay() }
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
            err = e.javaClass.simpleName + ": " + (e.message ?: "")
            Diag.log(this, "待确认页读取失败 → $err")
        }

        tvHead.text = "待确认（" + list.size + "）"
        tvStat.text = if (err.isNotEmpty()) {
            "读取失败 → $err"
        } else {
            "库内共 $total 笔 · 待确认 ${list.size} 笔 · 点「改分类」可换类别"
        }
        tvVer.text = "v" + verName() + " · 自动抓到的消费先存在这里，确认后才进正式账本"

        if (Settings.canDrawOverlays(this)) {
            tvTip.visibility = View.GONE
        } else {
            tvTip.visibility = View.VISIBLE
            tvTip.text = "⚠ 悬浮窗未开启：抓到消费时不会在别的 App 上面弹卡片（通知栏仍会提醒）。点此开启 →"
        }

        listBox.removeAllViews()
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        for (r in list) {
            try {
                val v = layoutInflater.inflate(R.layout.item_pending, listBox, false)
                bind(v, r)
                listBox.addView(v)
            } catch (e: Exception) {
                Diag.log(this, "待确认单条渲染失败 id=${r.id} → ${e.javaClass.simpleName}")
                val t = TextView(this)
                t.text = "这一笔显示不出来：" + e.javaClass.simpleName
                t.setTextColor(0xFFE8543F.toInt())
                t.textSize = 12f
                t.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
                listBox.addView(t)
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

    private fun bind(v: View, r: Rec) {
        val note = if (r.note.isBlank()) r.cat else r.note
        v.findViewById<TextView>(R.id.tvNote).text = note
        v.findViewById<TextView>(R.id.tvAmount).apply {
            text = (if (r.type == 0) "-¥" else "+¥") + Ui.money(r.amount)
            setTextColor(if (r.type == 0) Ui.TEXT else Ui.GREEN)
        }
        val src = if (r.src.isBlank()) "手工" else r.src
        v.findViewById<TextView>(R.id.tvCat).text = "分类：" + r.cat + " · " + src
        v.findViewById<TextView>(R.id.tvTime).text = fmt(r.ts)

        v.findViewById<View>(R.id.btnDel).setOnClickListener {
            helper.delRecord(r.id)
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            load()
        }
        v.findViewById<View>(R.id.btnCat).setOnClickListener { pickCat(r) }
        v.findViewById<View>(R.id.btnOk).setOnClickListener { confirmOne(r) }
    }

    /** 只改分类，仍留在待确认 */
    private fun pickCat(r: Rec) {
        val cats = helper.cats(r.type)
        val names = ArrayList<String>()
        for (cd in cats) names.add(cd.emoji + "  " + cd.name)
        AlertDialog.Builder(this)
            .setTitle("选择分类（当前：" + r.cat + "）")
            .setItems(names.toTypedArray()) { _, w ->
                val cat = cats[w].name
                helper.updateRecord(r.id, r.amount, cat, r.note, r.ts, r.status)
                Toast.makeText(this, "已改为：" + cat, Toast.LENGTH_SHORT).show()
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按当前分类直接入正式账本 */
    private fun confirmOne(r: Rec) {
        helper.updateRecord(r.id, r.amount, r.cat, r.note, r.ts, 1)
        Toast.makeText(
            this,
            (if (r.type == 0) "已记账：" else "已记收入：") + r.cat + " ¥" + Ui.money(r.amount),
            Toast.LENGTH_SHORT
        ).show()
        load()
    }

    private fun confirmAll() {
        val list = helper.pending()
        if (list.isEmpty()) {
            Toast.makeText(this, "没有待确认账单", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("全部确认 " + list.size + " 笔？")
            .setMessage("将按当前自动分类直接记入账本")
            .setPositiveButton("全部确认") { _, _ ->
                for (r in list) helper.updateRecord(r.id, r.amount, r.cat, r.note, r.ts, 1)
                Toast.makeText(this, "已确认 " + list.size + " 笔", Toast.LENGTH_SHORT).show()
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ts))

    private fun verName(): String {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            (pi.versionName ?: "?") + " (" + pi.versionCode + ")"
        } catch (_: Exception) {
            "?"
        }
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
