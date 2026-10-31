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
            // v2.15：裸「自动扣款」会把签约/续费提醒当成支付
            // （「您已签约自动扣款服务」）；「扣款成功」已覆盖真正的自动扣款成功通知。
            "自动扣款成功", "已付款", "付款给",
            // v2.12：QQ/话费充值通知只写「充值成功」，缺了通知通道整类漏记（v2.11 只补了页面通道）
            "充值成功", "充值完成"
        )

        /**
         * 收入类通知的进入门槛。
         * 收入通知不会写「支付成功」，旧版因此一条都进不来 —— 用户看到的「收入被记成支出」
         * 有一部分是通知通道把收入当支出处理，另一部分是收入通知被直接丢弃。
         */
        private val INCOME_NOTIFY_WORDS = listOf(
            "收款成功", "收款到账", "已到账", "入账成功", "已入账",
            "退款成功", "已退款", "退款到账", "收到转账", "已收款", "已收钱"
        )

        /** 营销类通知：即使含金额也不是消费 */
        private val AD_WORDS = listOf(
            "优惠券", "立减", "满减", "活动", "红包", "抽奖", "限时", "领取",
            "返现", "积分", "会员日", "秒杀", "领券", "折扣", "免单",
            "充值优惠", "赠送", "特价", "抢购",
            // v2.15：免押租机/租赁营销通知（芝麻信用页事故的广告来源）
            "免押", "月租", "日租", "租机", "租金", "押金", "特权", "额度", "勋章"
        )

        private val AMOUNT_YUAN = Regex("[¥￥]\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)")
        private val AMOUNT_META = Regex("([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元")
        private val MERCHANT = Regex("(?:收款方|商户|店铺|向|转给|付款给)[:：]?\\s*([^,，。;；\\s]{2,20})")

        /** 「向 XX 付款」「付款给 XX」这类句式，微信支付凭证里最常见 */
        private val PAY_PATTERN = Regex("向.{1,16}付款|付款给.{1,16}|已向.{1,16}付款")

        /**
         * 资金搬运：钱在自己名下的账户之间移动（提现、信用卡还款、转入零钱通），
         * 既不是消费也不是收入，必须排掉。
         */
        private val TRANSFER_WORDS = listOf(
            "提现", "还款", "转出", "转入余额宝", "零钱通转入", "转入零钱通"
        )
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
        // v2.16：购物平台（淘宝/天猫/京东/拼多多等）本阶段不接入弹窗 ——
        // 通知通道同样在入口直接跳过，不落待确认、不发提醒。
        if (PayApps.isDisabledPkg(sbn.packageName)) return
        if (seen.contains(sbn.key)) return
        seen.add(sbn.key)
        if (seen.size > 800) seen.clear()

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        val body = "$title $big $text"

        // ① 必须是支付成功 / 收款到账类通知（词表 + 「向XX付款」句式）
        val strongPay = STRONG_PAY_WORDS.any { body.contains(it) } || PAY_PATTERN.containsMatchIn(body)
        val strongIncome = INCOME_NOTIFY_WORDS.any { body.contains(it) }
        if (!strongPay && !strongIncome) return
        // ①.5 排除「还没付钱」的提醒：不少 App 会推「订单待付款，付款成功后将…」，
        //     里面同时含「付款成功」，不单独拦会被当成已支付
        if (PayApps.isUnsettledText(body)) return
        // ② 资金搬运（提现 / 还款 / 转入零钱通）既不是消费也不是收入
        if (TRANSFER_WORDS.any { body.contains(it) }) return
        // ③ 方向：收入 / 退款类通知记成收入。
        //    旧版这里直接 return 丢掉，导致「收到转账 / 退款到账」这类本来就是收入的钱彻底记不上；
        //    若被误判成支出更是直接污染账本。现在统一走方向判定。
        val income = PayApps.isIncomeText(body)
        // ④ 排除营销推送
        if (AD_WORDS.any { body.contains(it) }) return

        // ⑤ 金额：优先带 ¥ 的，其次 X元
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
            ?.takeIf { it.isNotBlank() && !PayApps.hasAmountIn(it) }
            ?: (appName + if (income) "收入" else "消费")
        val cat = if (income) Category.incomeOf(merchant, body, appName)
        else Category.of(merchant, body, appName)
        val type = if (income) 1 else 0
        val ts = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()

        val db = DbHelper(this)
        try {
            // 同一笔可能已被页面通道记下（商户名往往不一致），按「金额 + 90 秒」收敛，不按商户名比对
            val prev = db.recentSameAmount(amount, ts, PayApps.MERGE_WINDOW_MS)
            if (prev != null) {
                if (prev.status == 0 && prev.note.endsWith("消费") && !merchant.endsWith("消费")) {
                    // 待确认那条只是兜底名，用通知里的具体商户名补全，时间仍保留页面抓到的
                    db.updateRecordFull(prev.id, amount, type, cat, merchant, prev.ts, 0)
                    Diag.log(this, "$appName 通知 ¥$amount → 合并到待确认 #${prev.id}（$merchant）")
                } else {
                    Diag.log(this, "$appName 通知 ¥$amount → 跳过：90秒内同金额已有记录 #${prev.id}")
                }
                return
            }
            val book = getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("book", "日常账本") ?: "日常账本"
            val id = db.addRecord(
                Rec(
                    amount = amount, type = type, cat = cat, note = merchant,
                    ts = ts, book = book, status = 0, src = appName
                )
            )
            if (id <= 0) return
            Diag.log(
                this,
                "$appName 通知 ¥$amount $merchant → 已存为待确认（${if (income) "收入" else "支出"}·$cat）"
            )
            // v2.19：通知通道也弹同一套卡片。
            // 旧版这里只发通知栏提醒，用户侧表现就是「有的弹卡片、有的只响一声」——
            // 差别只在于这一笔是被页面通道还是通知通道先抓到的。
            if (!BillAccessibilityService.presentCard(this, id, amount, merchant, cat, income)) {
                showConfirmNotification(id, amount, merchant, cat, income)
            }
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

    private fun showConfirmNotification(
        id: Long, amount: Double, merchant: String, category: String, income: Boolean
    ) {
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
            .setContentTitle((if (income) "识别到收入 ¥" else "识别到消费 ¥") + Ui.money(amount))
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
