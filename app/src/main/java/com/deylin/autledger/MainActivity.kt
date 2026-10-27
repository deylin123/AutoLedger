package com.deylin.autledger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var db: DbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = DbHelper(this)

        findViewById<Button>(R.id.btnPerm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun listenerEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.split(":").any { it.contains(packageName) }
    }

    private fun refresh() {
        findViewById<TextView>(R.id.tvStatus).text =
            if (listenerEnabled())
                "✅ 通知监听已开启，正在自动监控微信/支付宝消费"
            else
                "❌ 尚未开启通知监听权限。点击下方按钮，在列表中找到「自动记账」并允许"

        findViewById<TextView>(R.id.tvTotal).text =
            "本月已确认支出：¥" + String.format("%.2f", db.monthTotal())

        val box = findViewById<LinearLayout>(R.id.listBox)
        box.removeAllViews()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val inf = LayoutInflater.from(this)
        for (r in db.recent(100)) {
            val v = inf.inflate(R.layout.item_record, box, false)
            v.findViewById<TextView>(R.id.tvLine1).text =
                "${r.merchant}　¥" + String.format("%.2f", r.amount)
            v.findViewById<TextView>(R.id.tvLine2).text =
                "${fmt.format(Date(r.ts))} · ${r.category} · ${statusText(r.status)}"
            box.addView(v)
        }
        if (db.recent(1).isEmpty()) {
            val empty = TextView(this)
            empty.text = "暂无记录。支付一笔试试～"
            empty.setPadding(0, 24, 0, 0)
            box.addView(empty)
        }
    }

    private fun statusText(st: Int) = when (st) {
        0 -> "待确认"
        1 -> "已确认"
        else -> "已忽略"
    }
}
