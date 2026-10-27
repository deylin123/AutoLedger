package com.deylin.autledger

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfirmActivity : Activity() {

    private var recId = -1L
    private var selected = "其他"
    private val catButtons = ArrayList<Pair<Button, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm)

        recId = intent.getLongExtra("id", -1L)
        val db = DbHelper(this)
        val rec = if (recId > 0) db.get(recId) else null
        if (rec == null) {
            Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        selected = if (rec.category == "待确认") "其他" else rec.category

        val fmt = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
        findViewById<TextView>(R.id.tvAmount).text = "¥" + String.format("%.2f", rec.amount)
        findViewById<TextView>(R.id.tvMerchant).text =
            "${rec.merchant} · ${fmt.format(Date(rec.ts))}"

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
            db.confirm(recId, selected)
            Toast.makeText(this, "已记录：$selected ¥${String.format("%.2f", rec.amount)}", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<Button>(R.id.btnIgnore).setOnClickListener {
            db.setStatus(recId, 2)
            Toast.makeText(this, "已忽略", Toast.LENGTH_SHORT).show()
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
            b.layoutParams = b.layoutParams
            (b.parent as? ViewGroup)?.requestLayout()
            b.visibility = View.VISIBLE
        }
    }
}
