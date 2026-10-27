package com.deylin.autledger

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfirmActivity : Activity() {

    private var recId = -1L
    private var screenMode = false
    private var amount = 0.0
    private var merchant = ""
    private var ts = 0L
    private var selected = "其他"
    private val catButtons = ArrayList<Pair<Button, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm)

        val db = DbHelper(this)
        screenMode = intent.getBooleanExtra("screen", false)

        if (screenMode) {
            // 抓屏链路：还未入库，确认后才入库
            amount = intent.getDoubleExtra("amount", 0.0)
            merchant = intent.getStringExtra("merchant") ?: ""
            ts = intent.getLongExtra("ts", System.currentTimeMillis())
            selected = intent.getStringExtra("category") ?: "其他"
        } else {
            // 通知链路：已入库为待确认，改类别后确认
            recId = intent.getLongExtra("id", -1L)
            val rec = if (recId > 0) db.get(recId) else null
            if (rec == null) {
                Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            amount = rec.amount
            merchant = rec.merchant
            ts = rec.ts
            selected = if (rec.category == "待确认") "其他" else rec.category
        }

        val fmt = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
        findViewById<TextView>(R.id.tvAmount).text = "¥" + String.format("%.2f", amount)
        findViewById<TextView>(R.id.tvMerchant).text =
            "$merchant · ${fmt.format(Date(ts))}"

        val grid = findViewById<GridLayout>(R.id.gridCats)
        val cats = listOf("餐饮", "交通", "购物", "日用", "娱乐", "居住", "医疗", "人情", "其他")
        for (c in cats) {
            val b = Button(this)
            b.text = c
            b.setOnClickListener {
                selected = c
                restyle()
            }
            grid.addView(b)
            catButtons.add(Pair(b, c))
        }
        restyle()

        findViewById<Button>(R.id.btnOk).setOnClickListener {
            if (screenMode) {
                db.insertFinal(amount, merchant, selected, ts)
            } else {
                db.confirm(recId, selected)
            }
            Toast.makeText(this, "已记录：$selected ¥${String.format("%.2f", amount)}", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<Button>(R.id.btnIgnore).setOnClickListener {
            if (screenMode) {
                // 未入库，直接丢弃
                Toast.makeText(this, "未记录", Toast.LENGTH_SHORT).show()
            } else {
                db.setStatus(recId, 2)
                Toast.makeText(this, "已忽略", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun restyle() {
        for ((b, c) in catButtons) {
            if (c == selected) {
                b.alpha = 1f
                b.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                b.alpha = 0.55f
                b.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }
}
