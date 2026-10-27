package com.deylin.autledger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 支付通知监听：微信/支付宝的支付结果通知里通常已含金额，
 * 抓到后同样先落成待确认账单，再发一条点击即可确认的通知。
 */
class PayNotificationListener : NotificationListenerService() {

    private val seen = HashSet<String>()

    companion object {
        const val CHAN = "pay_alert"
        const val PKG_WECHAT = "com.tencent.mm"
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

        private val EXPENSE_KEYWORDS = listOf("支付成功", "付款成功", "已支付", "付款", "消费", "扣款", "扣费", "支出")
        private val AMOUNT_YUAN = Regex("[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)")
        private val AMOUNT_META = Regex("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元")
        private val MERCHANT = Regex("(?:收款方|商户|向|转给)[:：]?\\s*([^,，。\\s]+)")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
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

        if (body.contains("退款") || body.contains("已存入") || body.contains("已到账")) return
        if (EXPENSE_KEYWORDS.none { body.contains(it) }) return

        var amount = 0.0
        val m1 = AMOUNT_YUAN.find(big) ?: AMOUNT_YUAN.find(body)
        if (m1 != null) {
            amount = m1.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        } else {
            val m2 = AMOUNT_META.find(big) ?: AMOUNT_META.find(body)
            if (m2 != null) amount = m2.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        }
        if (amount <= 0.0) return

        val appName = if (pkg == PKG_WECHAT) "微信" else "支付宝"
        val merchant = MERCHANT.find(big)?.groupValues?.getOrNull(1) ?: appName + "支付"
        val cat = Category.of(merchant, body)
        val ts = System.currentTimeMillis()

        val db = DbHelper(this)
        if (db.existsSimilar(amount, merchant, ts)) {
            db.close()
            Diag.log(this, "$appName 通知 ¥$amount → 跳过：2分钟内已记录")
            return
        }
        val book = getSharedPreferences("cfg", MODE_PRIVATE).getString("book", "日常账本") ?: "日常账本"
        val id = db.addRecord(
            Rec(amount = amount, type = 0, cat = cat, note = merchant, ts = ts, book = book, status = 0, src = appName)
        )
        db.close()
        if (id <= 0) return
        Diag.log(this, "$appName 通知 ¥$amount $merchant → 已存为待确认（$cat）")
        showConfirmNotification(id, amount, merchant, cat)
    }

    private fun showConfirmNotification(id: Long, amount: Double, merchant: String, category: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHAN, "消费提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            this, id.toInt(),
            Intent(this, PendingActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("focus", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHAN)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("识别到消费 ¥" + Ui.money(amount))
            .setContentText("$merchant · 分类：$category · 点击确认")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                nm.notify(id.toInt(), n)
            } catch (_: Exception) {
            }
        }
    }
}
