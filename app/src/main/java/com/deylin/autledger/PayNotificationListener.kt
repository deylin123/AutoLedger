package com.deylin.autledger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.Manifest

class PayNotificationListener : NotificationListenerService() {

    private val seen = HashSet<String>()

    companion object {
        const val CHAN = "pay_alert"
        const val PKG_WECHAT = "com.tencent.mm"
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

        // 支出关键词（命中才记）
        private val EXPENSE_KEYWORDS = listOf(
            "支付成功", "付款成功", "已支付", "付款", "消费", "扣款", "扣费", "支出"
        )
        // 金额提取：优先 ¥ 符号，其次 xx元
        private val AMOUNT_YUAN = Regex("[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)")
        private val AMOUNT_META = Regex("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元")
        private val MERCHANT = Regex("(?:收款方|商户|向|转给)[:：]?\\s*([^,，。\\s]+)")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 同一条通知只处理一次
        if (seen.contains(sbn.key)) return
        seen.add(sbn.key)
        if (seen.size > 500) seen.clear()

        val pkg = sbn.packageName
        if (pkg != PKG_WECHAT && pkg != PKG_ALIPAY) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        val body = "$title $big"

        // 退款不是支出
        if (body.contains("退款") || body.contains("已存入") || body.contains("已到账")) return
        if (EXPENSE_KEYWORDS.none { body.contains(it) }) return

        // 提取金额
        var amount = 0.0
        val m1 = AMOUNT_YUAN.find(big) ?: AMOUNT_YUAN.find(body)
        if (m1 != null) {
            amount = m1.groupValues[1].toDouble()
        } else {
            val m2 = AMOUNT_META.find(big) ?: AMOUNT_META.find(body)
            if (m2 != null) amount = m2.groupValues[1].toDouble()
        }
        if (amount <= 0.0) return

        // 提取商户
        val merchant = MERCHANT.find(big)?.groupValues?.get(1)
            ?: if (pkg == PKG_WECHAT) "微信支付" else "支付宝"

        // 入库（待确认）
        val db = DbHelper(this)
        val id = db.insert(amount, merchant, System.currentTimeMillis(), 0)
        db.close()
        if (id <= 0) return

        showConfirmNotification(id, amount, merchant, Category.of(merchant, body))
    }

    private fun showConfirmNotification(id: Long, amount: Double, merchant: String, category: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHAN, "消费提醒", NotificationManager.IMPORTANCE_HIGH)
        )

        val intent = Intent(this, ConfirmActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("id", id)
            .putExtra("category", category)
        val pi = PendingIntent.getActivity(
            this, id.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = Notification.Builder(this, CHAN)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("检测到消费 ¥" + String.format("%.2f", amount))
            .setContentText("$merchant · 预分类：$category · 点击确认")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            nm.notify(id.toInt(), n)
        }
    }
}
