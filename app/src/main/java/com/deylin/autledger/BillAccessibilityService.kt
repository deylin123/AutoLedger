package com.deylin.autledger

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 账单页抓取服务：
 * 仅当屏幕内容是支付宝/微信「账单详情」页时读取文字，
 * 提取金额（精确到分的大字金额行）、收款方、时间，自动预分类后弹窗确认。
 * 确认后才入库，忽略则不入库。
 * 每次扫描结果都写入诊断日志（首页可见）。
 */
class BillAccessibilityService : AccessibilityService() {

    companion object {
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
        const val PKG_WECHAT = "com.tencent.mm"

        // 金额行：整行就是一个金额，如 "-47.52"、"¥47.52"、"1,234.56"
        private val AMOUNT_LINE = Regex("^-?[¥￥]?\\s*-?[0-9]{1,7}(?:,[0-9]{3})*\\.[0-9]{2}$")
        private val TIME_LINE = Regex("(20[0-9]{2}[-/年.][0-9]{1,2}[-/月.][0-9]{1,2}[^0-9][0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)")

        // 页面特征：明确是账单/交易详情页
        private val BILL_PAGE_WORDS = listOf("账单详情", "付款详情", "交易详情", "转账详情")
        private val EXPENSE_WORDS = listOf("付款成功", "支付成功", "扣款成功", "自动扣款", "已付款", "付款金额", "支付时间", "付款时间", "当前状态")
        private val INCOME_WORDS = listOf("退款", "已存入", "已到账", "已收钱", "收入", "收款到账")
        private val STATUS_WORDS = listOf("自动扣款成功", "付款成功", "支付成功", "交易成功", "已付款", "转账成功", "扣款成功", "待付款")
        private val FIELD_KEYS = listOf("收款方全称", "收款方", "商户全称", "商户", "对方账户", "商品说明", "账单分类")
    }

    private var lastScan = 0L
    private var lastOtherScan = 0L
    private var lastPopupKey = ""
    private var lastPopupAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: LinearLayout? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val et = event.eventType
        if (et != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            et != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val whitelist = pkg == PKG_ALIPAY || pkg == PKG_WECHAT
        val now = System.currentTimeMillis()
        if (whitelist) {
            if (now - lastScan < 1500) return
            lastScan = now
        } else {
            // 非白名单App：低频扫描，只用于发现「别的App里也有账单详情页」并记录包名
            if (now - lastOtherScan < 5000) return
            lastOtherScan = now
        }

        val root = rootInActiveWindow ?: return
        try {
            val texts = ArrayList<String>()
            collect(root, texts)
            if (texts.size < 3) return
            val hasBillPage = texts.any { t -> BILL_PAGE_WORDS.any { t.contains(it) } }

            if (!whitelist) {
                if (hasBillPage) {
                    Diag.log(this, "其他App($pkg) 疑似账单详情页 → 未接入，仅记录包名")
                }
                return
            }

            if (!hasBillPage) return   // 白名单里的普通页面不记日志，避免刷屏

            scan(pkg, texts)
        } catch (e: Exception) {
            Diag.log(this, "扫描异常：${e.javaClass.simpleName}")
        }
    }

    private fun scan(pkg: String, texts: ArrayList<String>) {
        val joined = texts.joinToString("|")
        val appName = if (pkg == PKG_WECHAT) "微信" else "支付宝"

        // 找金额：只认整行为金额格式的节点（大字金额）
        var amount = -1.0
        var amountNeg = false
        var amountIdx = -1
        for ((i, t) in texts.withIndex()) {
            val s = t.trim()
            if (AMOUNT_LINE.matches(s)) {
                val v = s.replace(Regex("[¥￥,\\s]"), "").replace("-", "").toDoubleOrNull() ?: continue
                if (v > 0.0) {
                    amount = v
                    amountNeg = s.contains("-")
                    amountIdx = i
                    break
                }
            }
        }
        if (amount <= 0.0 || amountIdx < 0) {
            Diag.log(this, "$appName 账单页 → 未找到金额行（页面文字片段：${texts.take(6).joinToString("/").take(40)}）")
            return
        }

        if (INCOME_WORDS.any { joined.contains(it) }) {
            Diag.log(this, "$appName 账单页 ¥$amount → 跳过：疑似收入/退款")
            return
        }
        val isExpense = amountNeg || EXPENSE_WORDS.any { joined.contains(it) }
        if (!isExpense) {
            Diag.log(this, "$appName 账单页 ¥$amount → 跳过：不像支出")
            return
        }

        val fieldVals = HashMap<String, String>()
        for (k in FIELD_KEYS) {
            val i = texts.indexOf(k)
            if (i >= 0 && i + 1 < texts.size) fieldVals[k] = texts[i + 1].trim()
        }

        val merchant = fieldVals["收款方全称"]
            ?: fieldVals["商户全称"]
            ?: fieldVals["收款方"]
            ?: fieldVals["商户"]
            ?: fieldVals["对方账户"]
            ?: run {
                var found = ""
                for (j in (amountIdx - 1) downTo maxOf(0, amountIdx - 4)) {
                    val s = texts[j].trim()
                    if (s.length in 2..20 && !AMOUNT_LINE.matches(s) &&
                        STATUS_WORDS.none { s.contains(it) } && !s.contains("账单")
                    ) {
                        found = s
                        break
                    }
                }
                found.ifEmpty { appName + "支付" }
            }

        val goods = fieldVals["商品说明"] ?: ""
        val catHint = fieldVals["账单分类"] ?: ""
        val timeStr = TIME_LINE.find(joined)?.groupValues?.get(1)
        val ts = parseTime(timeStr) ?: System.currentTimeMillis()

        val db = DbHelper(this)
        val dup = db.existsSimilar(amount, merchant, ts)
        db.close()
        if (dup) {
            Diag.log(this, "$appName 账单页 ¥$amount $merchant → 跳过：2分钟内已记录过")
            return
        }
        val key = "$amount|$merchant|${ts / 60000}"
        if (key == lastPopupKey && System.currentTimeMillis() - lastPopupAt < 15000) return
        lastPopupKey = key
        lastPopupAt = System.currentTimeMillis()

        val category = Category.of(merchant, goods, catHint)
        Diag.log(this, "$appName 账单页 ¥$amount $merchant → 已弹窗（预分类：$category）")
        showConfirm(amount, merchant, ts, category)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        if (out.size > 150) return
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    private fun parseTime(s: String?): Long? {
        if (s == null) return null
        val fmts = arrayOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss",
            "yyyy年MM月dd日 HH:mm:ss", "yyyy.MM.dd HH:mm:ss"
        )
        for (f in fmts) {
            try {
                val p = SimpleDateFormat(f, Locale.CHINA)
                p.isLenient = false
                return p.parse(s)!!.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun confirmIntent(amount: Double, merchant: String, ts: Long, category: String): Intent =
        Intent(this, ConfirmActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("screen", true)
            .putExtra("amount", amount)
            .putExtra("merchant", merchant)
            .putExtra("ts", ts)
            .putExtra("category", category)

    private fun showConfirm(amount: Double, merchant: String, ts: Long, category: String) {
        if (Settings.canDrawOverlays(this)) {
            showOverlay(amount, merchant, ts, category)
        } else {
            Diag.log(this, "无悬浮窗权限 → 改用通知提醒")
            notifyFallback(amount, merchant, ts, category)
        }
    }

    private fun showOverlay(amount: Double, merchant: String, ts: Long, category: String) {
        removeOverlay()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP
        params.y = 120

        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.setBackgroundColor(0xEE323232.toInt())
        box.setPadding(36, 28, 36, 28)
        box.gravity = Gravity.CENTER_VERTICAL

        val tv = TextView(this)
        tv.text = "检测到消费 ¥" + String.format("%.2f", amount) + "\n$merchant · 预分类：$category"
        tv.setTextColor(Color.WHITE)
        tv.textSize = 15f
        box.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val ok = Button(this)
        ok.text = "确认"
        ok.setOnClickListener {
            startActivity(confirmIntent(amount, merchant, ts, category))
            removeOverlay()
        }
        val ig = Button(this)
        ig.text = "忽略"
        ig.setOnClickListener { removeOverlay() }
        box.addView(ok)
        box.addView(ig)

        try {
            wm.addView(box, params)
            overlay = box
        } catch (e: Exception) {
            Diag.log(this, "悬浮窗添加失败 → 改用通知（${e.javaClass.simpleName}）")
            notifyFallback(amount, merchant, ts, category)
        }
        handler.postDelayed({ removeOverlay() }, 10000)
    }

    private fun removeOverlay() {
        try {
            overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {
        }
        overlay = null
    }

    private fun notifyFallback(amount: Double, merchant: String, ts: Long, category: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("screen_alert", "账单页抓取提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            this, (ts / 1000).toInt(),
            confirmIntent(amount, merchant, ts, category),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "screen_alert")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("检测到消费 ¥" + String.format("%.2f", amount))
            .setContentText("$merchant · 预分类：$category · 点击确认记录")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify((ts / 1000).toInt(), n)
        } catch (_: SecurityException) {
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
