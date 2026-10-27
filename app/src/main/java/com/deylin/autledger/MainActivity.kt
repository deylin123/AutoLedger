package com.deylin.autledger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
        findViewById<Button>(R.id.btnAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
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

    private fun accEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: return false
        return s.contains("$packageName/.BillAccessibilityService") ||
                s.contains("$packageName.BillAccessibilityService")
    }

    private fun refresh() {
        findViewById<TextView>(R.id.tvStatus).text =
            if (listenerEnabled())
                "✅ 通知监听已开启：支付推送自动记录"
            else
                "❌ 通知监听未开启：点下方「权限1」开启（支付到账推送实时记录用）"

        findViewById<TextView>(R.id.tvStatus2).text =
            if (accEnabled())
                "✅ 账单页抓取已开启：打开支付宝/微信「账单详情」页即自动弹窗"
            else
                "❌ 账单页抓取未开启：点下方「权限2」→ 已下载的服务 → 找到「自动记账」并开启"

        findViewById<TextView>(R.id.tvStatus3).text =
            if (Settings.canDrawOverlays(this))
                "✅ 悬浮窗已允许：抓取后屏幕顶部弹确认条"
            else
                "提示：点「权限3」允许「显示在其他应用上层」，弹窗体验更好（不允许可用通知提醒）"

        findViewById<TextView>(R.id.tvTotal).text =
            "本月已确认支出：¥" + String.format("%.2f", db.monthTotal())

        val box = findViewById<LinearLayout>(R.id.listBox)
        box.removeAllViews()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val inf = LayoutInflater.from(this)
        val records = db.recent(100)
        for (r in records) {
            val v = inf.inflate(R.layout.item_record, box, false)
            v.findViewById<TextView>(R.id.tvLine1).text =
                "${r.merchant}　¥" + String.format("%.2f", r.amount)
            v.findViewById<TextView>(R.id.tvLine2).text =
                "${fmt.format(Date(r.ts))} · ${r.category} · ${statusText(r.status)}"
            box.addView(v)
        }
        if (records.isEmpty()) {
            val empty = TextView(this)
            empty.text = "暂无记录。支付一笔或打开账单详情页试试～"
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
