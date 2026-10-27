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
 * 支付通知监听：从通知栏文字里取消费金额。
 *
 * v2.5 起不再限定微信/支付宝，而是覆盖所有 App 的支付成功通知 ——
 * 这是页面读屏的重要补充：抖音商城、拼多多、美团等大量页面是自绘或 WebView，
 * 无障碍读不到文字，但支付成功后一定会推一条通知。
 *
 * 为避免把营销推送、聊天消息当成消费，判定要求同时满足：
 *   ① 命中「强支付成功词」（支付成功/付款成功/已支付/扣款成功…）
 *   ② 能提取到带 ¥ 或「元」的金额
 *   ③ 不含退款/到账词，不含优惠券/满减/抽奖等营销词
 */
class PayNotificationListener : NotificationListenerService() {

    private val seen = HashSet<String>()
    private val labelCache = HashMap<String, String>()

    companion object {
        const val CHAN = "pay_alert"

        /** 只有命中这些词，才认为「刚刚完成了一笔支付」 */
        private val STRONG_PAY_WORDS = listOf(
            "支付成功", "付款成功", "已支付", "支付完成", "付款完成",
            "扣款成功", "扣费成功", "交易成功", "缴费成功", "下单成功",
            "自动扣款", "已付款", "付款给"
        )

        /** 营销类通知：即使含金额也不是消费 */
        private val AD_WORDS = listOf(
            "优惠券", "立减", "满减", "活动", "红包", "抽奖", "限时", "领取",
            "返现", "积分", "会员日", "秒杀", "领券", "折扣", "免单",
            "充值优惠", "赠送", "特价", "抢购"
        )

        private val AMOUNT_YUAN = Regex("[¥￥]\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)")
        private val AMOUNT_META = Regex("([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元")
        private val MERCHANT = Regex("(?:收款方|商户|店铺|向|转给|付款给)[:：]?\\s*([^,，。;；\\s]{2,20})")

        /** 「向 XX 付款」「付款给 XX」这类句式，微信支付凭证里最常见 */
        private val PAY_PATTERN = Regex("向.{1,16}付款|付款给.{1,16}|已向.{1,16}付款")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Diag.log(this, "通知解析异常：${e.javaClass.simpleName}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (seen.contains(sbn.key)) return
        seen.add(sbn.key)
        if (seen.size > 800) seen.clear()

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        val body = "$title $big $text"

        // ① 必须是支付成功类通知（词表 + 「向XX付款」句式）
        val strongPay = STRONG_PAY_WORDS.any { body.contains(it) } || PAY_PATTERN.containsMatchIn(body)
        if (!strongPay) return
        // ①.5 排除「还没付钱」的提醒：不少 App 会推「订单待付款，付款成功后将…」，
        //     里面同时含「付款成功」，不单独拦会被当成已支付
        if (PayApps.isUnsettledText(body)) return
        // ② 排除退款 / 到账
        if (PayApps.INCOME_WORDS.any { body.contains(it) }) return
        // ③ 排除营销推送
        if (AD_WORDS.any { body.contains(it) }) return

        // ④ 金额：优先带 ¥ 的，其次 X元
        var amount = 0.0
        val m1 = AMOUNT_YUAN.find(body)
        if (m1 != null) {
            amount = m1.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        } else {
            val m2 = AMOUNT_META.find(body)
            if (m2 != null) amount = m2.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        }
        if (amount <= 0.0 || amount > 500000.0) return

        val appName = appLabel(sbn.packageName)
        val merchant = MERCHANT.find(body)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() && !PayApps.hasAmountIn(it) } ?: (appName + "消费")
        val cat = Category.of(merchant, body, appName)
        val ts = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()

        val db = DbHelper(this)
        try {
            // 同一笔可能已被页面通道记下（商户名往往不一致），按「金额 + 90 秒」收敛，不按商户名比对
            val prev = db.recentSameAmount(amount, ts, PayApps.MERGE_WINDOW_MS)
            if (prev != null) {
                if (prev.status == 0 && prev.note.endsWith("消费") && !merchant.endsWith("消费")) {
                    // 待确认那条只是兜底名，用通知里的具体商户名补全，时间仍保留页面抓到的
                    db.updateRecord(prev.id, amount, prev.cat, merchant, prev.ts, 0)
                    Diag.log(this, "$appName 通知 ¥$amount → 合并商户名到待确认 #${prev.id}（$merchant）")
                } else {
                    Diag.log(this, "$appName 通知 ¥$amount → 跳过：90秒内同金额已有记录 #${prev.id}")
                }
                return
            }
            val book = getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("book", "日常账本") ?: "日常账本"
            val id = db.addRecord(
                Rec(
                    amount = amount, type = 0, cat = cat, note = merchant,
                    ts = ts, book = book, status = 0, src = appName
                )
            )
            if (id <= 0) return
            Diag.log(this, "$appName 通知 ¥$amount $merchant → 已存为待确认（$cat）")
            showConfirmNotification(id, amount, merchant, cat)
        } finally {
            db.close()
        }
    }

    private fun appLabel(pkg: String): String {
        labelCache[pkg]?.let { return it }
        val name = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
        labelCache[pkg] = name
        return name
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
