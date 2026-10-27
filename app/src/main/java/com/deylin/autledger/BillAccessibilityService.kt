package com.deylin.autledger

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 账单页抓取：只在支付宝/微信「账单详情」页读屏。
 * 抓到后**先落成待确认账单**（数据不会丢），再尝试弹悬浮卡片；没弹出来也能在「待确认」里处理。
 */
class BillAccessibilityService : AccessibilityService() {

    companion object {
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
        const val PKG_WECHAT = "com.tencent.mm"

        private val TIME_LINE =
            Regex("(20[0-9]{2}[-/年.][0-9]{1,2}[-/月.][0-9]{1,2}[^0-9][0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)")

        private val AMOUNT_LABELED = Regex(
            "^(?:支出|付款|支付|扣款|消费|实付|付款金额|支付金额|扣款金额|交易金额|支出金额)\\s*[¥￥]?\\s*(\\d{1,6}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元?$"
        )
        private val AMOUNT_SIGNED =
            Regex("^(-|\\+)\\s*[¥￥]?\\s*(\\d{1,6}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元?$")
        private val AMOUNT_SYMBOL =
            Regex("^[¥￥]\\s*(\\d{1,6}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元?$")
        private val AMOUNT_PLAIN = Regex("^(\\d{1,6}(?:,\\d{3})*\\.\\d{1,2})\\s*元?$")
        private val AMOUNT_IN_TEXT = Regex("(?:支出|付款|支付|扣款|消费|实付|金额)\\s*[¥￥]?\\s*(\\d{1,6}(?:\\.\\d{1,2})?)\\s*元")
        private val CURRENCY_IN_TEXT = Regex("[¥￥]\\s*(\\d{1,6}(?:\\.\\d{1,2})?)")

        private val BILL_PAGE_WORDS = listOf("账单详情", "付款详情", "交易详情", "转账详情")
        private val RESULT_WORDS = listOf("支付成功", "付款成功", "扣款成功", "交易成功", "已付款", "付款金额", "支付金额")
        private val EXPENSE_WORDS = listOf(
            "付款成功", "支付成功", "扣款成功", "自动扣款", "已付款", "付款金额",
            "支付时间", "付款时间", "当前状态", "支出"
        )
        private val INCOME_WORDS = listOf("退款", "已存入", "已到账", "已收钱", "收款到账", "退款成功")
        private val STATUS_WORDS = listOf(
            "自动扣款成功", "付款成功", "支付成功", "交易成功", "已付款",
            "转账成功", "扣款成功", "待付款"
        )
        private val FIELD_KEYS = listOf("收款方全称", "收款方", "商户全称", "商户", "对方账户", "商品说明", "账单分类")
    }

    private var lastScan = 0L
    private var lastOtherScan = 0L
    private var lastKey = ""
    private var lastAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var card: View? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
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
            if (now - lastOtherScan < 5000) return
            lastOtherScan = now
        }

        val root = rootInActiveWindow ?: return
        try {
            val texts = ArrayList<String>()
            collect(root, texts)
            if (texts.size < 3) return
            if (texts.any { it.contains("自动记账") || it.contains("待确认") || it.contains("本月预算") }) return
            val isBill = texts.any { t -> BILL_PAGE_WORDS.any { t.contains(it) } }
            val isPayResult = texts.any { t -> RESULT_WORDS.any { t.contains(it) } }
            // 支付宝：账单详情页、付款结果页都抓；微信及其他 App：只认账单详情页，避免聊天/列表内容误判
            if (!isBill && !(pkg == PKG_ALIPAY && isPayResult)) return

            if (!whitelist) {
                val hasAmount = texts.any { hasAmountIn(it.trim()) }
                if (hasAmount) Diag.log(this, "其他App($pkg) 疑似账单页 → 未接入，仅记包名")
                return
            }
            scan(pkg, texts)
        } catch (e: Exception) {
            Diag.log(this, "扫描异常：${e.javaClass.simpleName}")
        }
    }

    private fun scan(pkg: String, texts: ArrayList<String>) {
        val joined = texts.joinToString("|")
        val appName = if (pkg == PKG_WECHAT) "微信" else "支付宝"

        val amt = findAmount(texts)
        if (amt == null) {
            Diag.log(this, "$appName 账单页 → 未识别到金额（文字：${texts.take(12).joinToString("/").take(160)}）")
            return
        }
        val amount = amt.value
        if (!amt.expense && INCOME_WORDS.any { joined.contains(it) }) {
            Diag.log(this, "$appName ¥$amount → 跳过：退款/收入")
            return
        }
        if (!amt.expense && EXPENSE_WORDS.none { joined.contains(it) }) {
            Diag.log(this, "$appName ¥$amount → 跳过：不像支出")
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
            ?: goodsNear(texts, amt.idx, appName)

        val goods = fieldVals["商品说明"] ?: ""
        val catHint = fieldVals["账单分类"] ?: ""
        val ts = parseTime(TIME_LINE.find(joined)?.groupValues?.getOrNull(1)) ?: System.currentTimeMillis()
        val cat = Category.of(merchant, goods, catHint)

        val db = DbHelper(this)
        if (db.existsSimilar(amount, merchant, ts)) {
            db.close()
            Diag.log(this, "$appName ¥$amount $merchant → 跳过：2分钟内已记录")
            return
        }
        val key = "$amount|$merchant|${ts / 60000}"
        if (key == lastKey && System.currentTimeMillis() - lastAt < 15000) {
            db.close()
            return
        }
        lastKey = key
        lastAt = System.currentTimeMillis()

        val id = db.addRecord(
            Rec(
                amount = amount, type = 0, cat = cat, note = merchant, ts = ts,
                book = bookOf(), status = 0, src = appName
            )
        )
        db.close()
        Diag.log(this, "$appName ¥$amount $merchant → 已存为待确认（预分类：$cat）")
        if (id > 0) showCard(id, amount, merchant, cat)
    }

    private fun goodsNear(texts: List<String>, idx: Int, appName: String): String {
        for (j in (idx - 1) downTo maxOf(0, idx - 4)) {
            val s = texts[j].trim()
            if (s.length in 2..20 && !hasAmountIn(s) &&
                STATUS_WORDS.none { s.contains(it) } && !s.contains("账单")
            ) return s
        }
        return appName + "支付"
    }

    private class Amt(val value: Double, val expense: Boolean, val score: Int, val idx: Int)

    private fun toNum(s: String?): Double? = s?.replace(",", "")?.replace(" ", "")?.toDoubleOrNull()

    private fun hasAmountIn(s: String): Boolean =
        AMOUNT_LABELED.containsMatchIn(s) || AMOUNT_SIGNED.containsMatchIn(s) ||
            AMOUNT_SYMBOL.containsMatchIn(s) || AMOUNT_PLAIN.containsMatchIn(s)

    private fun findAmount(texts: List<String>): Amt? {
        var best: Amt? = null
        for (i in texts.indices) {
            val s = texts[i].trim()
            if (s.isEmpty() || s.length > 30) continue
            if (s.contains("时间") || s.contains("单号") || s.contains("订单") ||
                s.contains("余额") || s.contains("次数") || s.contains("笔数")
            ) continue
            if (TIME_LINE.containsMatchIn(s)) continue

            var cand: Amt? = null
            AMOUNT_LABELED.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, true, 100, i) }
            }
            if (cand == null) AMOUNT_SIGNED.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(2))?.let {
                    cand = Amt(it, m.groupValues.getOrNull(1) == "-", 90, i)
                }
            }
            if (cand == null) AMOUNT_SYMBOL.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, false, 85, i) }
            }
            if (cand == null) AMOUNT_PLAIN.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, false, 75, i) }
            }

            val c = cand ?: continue
            if (c.value <= 0.0 || c.value > 500000.0) continue
            val cur = best
            if (cur == null || c.score > cur.score) best = c
        }
        if (best != null) return best

        for (i in texts.indices) {
            val s = texts[i].trim()
            if (s.isEmpty() || TIME_LINE.containsMatchIn(s)) continue
            val v = toNum(AMOUNT_IN_TEXT.find(s)?.groupValues?.getOrNull(1))
                ?: toNum(CURRENCY_IN_TEXT.find(s)?.groupValues?.getOrNull(1))
            if (v != null && v >= 0.01 && v <= 500000.0) return Amt(v, true, 50, i)
        }
        return null
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
                val p = java.text.SimpleDateFormat(f, java.util.Locale.CHINA)
                p.isLenient = false
                return p.parse(s)!!.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun bookOf(): String {
        return getSharedPreferences("cfg", MODE_PRIVATE).getString("book", "日常账本") ?: "日常账本"
    }

    // ---------- 悬浮卡片 ----------

    private val hideTask = Runnable { removeCard() }

    /** 同一笔只弹一次，避免重复触发反复重建窗口造成闪烁 */
    private var lastCardId = -1L

    private fun showCard(id: Long, amount: Double, note: String, cat: String) {
        // 通道一：通知栏，无条件发送。
        // 原因：小米/红米（MIUI/HyperOS）的「后台弹出界面」权限被拒时，WindowManager.addView 不报错、
        // 只是窗口不可见，代码会误判为"已显示"。通知不受该权限限制，是唯一可靠通道，故作为主通道。
        notifyPending(id, amount, note, cat)

        if (id == lastCardId) return
        lastCardId = id

        handler.post {
            try {
                buildCard(id, amount, note, cat)
            } catch (e: Exception) {
                Diag.log(this, "悬浮卡片构建失败（${e.javaClass.simpleName}）")
            }
        }
    }

    /**
     * 通道二：悬浮记账卡片。
     *
     * v2.4 修掉两个会导致"不稳定"的真实缺陷：
     *  1. 自动关闭定时器无法取消。之前每笔都 post 一个 60 秒匿名 Runnable，
     *     连续抓多笔时，先前的定时器到点会把当前正在显示的卡片移除，表现为"卡片闪一下就不见了"。
     *     现在改为可取消的 hideTask，显示新卡片前先 removeCallbacks。
     *  2. 窗口宽度用 MATCH_PARENT。部分 ROM 下 overlay 窗口宽度会被测量成 0，
     *     卡片就塌缩成一个小圆角块（用户看到的"只剩一个圈"）。现在改为明确的像素宽度。
     */
    private fun buildCard(id: Long, amount: Double, note: String, cat: String) {
        if (!Settings.canDrawOverlays(this)) {
            Diag.log(this, "悬浮卡片跳过：悬浮窗权限未开（通知已发）")
            return
        }
        handler.removeCallbacks(hideTask)
        removeCard()

        val v = android.view.LayoutInflater.from(this).inflate(R.layout.view_card, null)
        (v.findViewById<View>(R.id.tvAmount) as TextView).text = "-¥" + Ui.money(amount)
        (v.findViewById<View>(R.id.tvNote) as TextView).text = note
        (v.findViewById<View>(R.id.tvCat) as TextView).text = "分类：" + cat
        (v.findViewById<View>(R.id.tvIcon) as TextView).text = emojiOf(cat)

        v.findViewById<View>(R.id.btnLater).setOnClickListener {
            handler.removeCallbacks(hideTask)
            removeCard()
            Toast.makeText(this, "已存入待确认", Toast.LENGTH_SHORT).show()
        }
        v.findViewById<View>(R.id.btnCat).setOnClickListener {
            handler.removeCallbacks(hideTask)
            removeCard()
            try {
                startActivity(
                    Intent(this, PendingActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("focus", id)
                )
            } catch (_: Exception) {
            }
        }
        v.findViewById<View>(R.id.btnOk).setOnClickListener {
            try {
                val db = DbHelper(this)
                db.updateRecord(id, amount, cat, note, System.currentTimeMillis(), 1)
                db.close()
            } catch (e: Exception) {
                Diag.log(this, "确认记账失败：${e.javaClass.simpleName}")
            }
            handler.removeCallbacks(hideTask)
            removeCard()
            Toast.makeText(this, "已记账：$cat ¥" + Ui.money(amount), Toast.LENGTH_SHORT).show()
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val winW = resources.displayMetrics.widthPixels - Ui.dp(this, 28f)
        val params = WindowManager.LayoutParams(
            winW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        params.y = Ui.dp(this, 96f)
        try {
            wm.addView(v, params)
            card = v
            Diag.log(this, "悬浮卡片已显示（通知同时已发）")
        } catch (e: Exception) {
            Diag.log(this, "悬浮卡片显示失败（${e.javaClass.simpleName}），通知已发")
            return
        }
        // 记录真实渲染尺寸：万一又变成一个小块，这行日志能直接看出宽高异常
        v.postDelayed({
            Diag.log(this, "卡片实际尺寸 " + v.width + "x" + v.height +
                "，屏幕宽 " + resources.displayMetrics.widthPixels)
        }, 500)
        handler.postDelayed(hideTask, 45000)
    }

    private fun emojiOf(cat: String): String {
        val db = DbHelper(this)
        return try {
            db.emojiOf(cat)
        } catch (e: Exception) {
            "🧾"
        } finally {
            db.close()
        }
    }

    private fun removeCard() {
        try {
            card?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {
        }
        card = null
    }

    private fun notifyPending(id: Long, amount: Double, note: String, cat: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // 渠道一旦创建，重要性不可修改，故 v2.3 使用新渠道 id 以确保「弹窗+响铃+震动」生效
            val chId = "bill_capture_v2"
            if (nm.getNotificationChannel(chId) == null) {
                val ch = NotificationChannel(chId, "自动记账提醒", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "识别到消费时提醒确认"
                ch.enableVibration(true)
                ch.vibrationPattern = longArrayOf(0, 220, 120, 220)
                ch.enableLights(true)
                nm.createNotificationChannel(ch)
            }
            val pi = PendingIntent.getActivity(
                this, id.toInt(),
                Intent(this, PendingActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("focus", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, chId)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("识别到消费 ¥" + Ui.money(amount))
                .setContentText("$note · 分类：$cat · 点此确认")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()
            nm.notify(id.toInt(), n)
            Diag.log(this, "已发送通知提醒：¥$amount $note")
        } catch (e: Exception) {
            Diag.log(this, "通知发送失败（${e.javaClass.simpleName}）：请检查通知权限是否开启")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeCard()
        super.onDestroy()
    }
}
