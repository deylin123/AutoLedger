package com.deylin.autledger

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 账单页抓取服务：
 * 仅当屏幕内容是支付宝/微信「账单详情」页时读取文字，
 * 提取金额、收款方、时间，自动预分类后弹出「记账表单」，
 * 分类 / 金额 / 日期 / 备注都可在弹窗里直接改，点保存才入库。
 */
class BillAccessibilityService : AccessibilityService() {

    companion object {
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
        const val PKG_WECHAT = "com.tencent.mm"

        private val TIME_LINE = Regex("(20[0-9]{2}[-/年.][0-9]{1,2}[-/月.][0-9]{1,2}[^0-9][0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)")

        // 金额识别分级（取命中里分数最高的那条），兼容支付宝「支出6.5元」与小票式「-47.52」两种排版
        // ① 带支出标记：支出6.5元 / 付款金额 ¥6.50 / 实付19元
        private val AMOUNT_LABELED = Regex(
            "^(?:支出|付款|支付|扣款|消费|实付|付款金额|支付金额|扣款金额|交易金额|支出金额)\\s*[¥￥]?\\s*(\\d{1,6}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元?$"
        )
        // ② 带正负号：-6.50 / -¥47.52
        private val AMOUNT_SIGNED = Regex("^(-|\\+)\\s*[¥￥]?\\s*(\\d{1,6}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元?$")
        // ③ 带货币符号：¥6.50 / ￥19
        private val AMOUNT_SYMBOL = Regex("^[¥￥]\\s*(\\d{1,6}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元?$")
        // ④ 纯小数：6.50 / 6.5元
        private val AMOUNT_PLAIN = Regex("^(\\d{1,6}(?:,\\d{3})*\\.\\d{1,2})\\s*元?$")
        // 兜底：整句里抠金额
        private val AMOUNT_IN_TEXT = Regex("(?:支出|付款|支付|扣款|消费|实付|金额)\\s*[¥￥]?\\s*(\\d{1,6}(?:\\.\\d{1,2})?)\\s*元")
        private val CURRENCY_IN_TEXT = Regex("[¥￥]\\s*(\\d{1,6}(?:\\.\\d{1,2})?)")

        private val BILL_PAGE_WORDS = listOf("账单详情", "付款详情", "交易详情", "转账详情")
        private val EXPENSE_WORDS = listOf("付款成功", "支付成功", "扣款成功", "自动扣款", "已付款", "付款金额", "支付时间", "付款时间", "当前状态", "支出")
        private val INCOME_WORDS = listOf("退款", "已存入", "已到账", "已收钱", "收款到账", "退款成功")
        private val STATUS_WORDS = listOf("自动扣款成功", "付款成功", "支付成功", "交易成功", "已付款", "转账成功", "扣款成功", "待付款")
        private val FIELD_KEYS = listOf("收款方全称", "收款方", "商户全称", "商户", "对方账户", "商品说明", "账单分类")
    }

    private var lastScan = 0L
    private var lastOtherScan = 0L
    private var lastPopupKey = ""
    private var lastPopupAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    // 弹窗中的编辑状态
    private var eAmount = 0.0
    private var eMerchant = ""
    private var eTs = 0L
    private var eCategory = "其它"
    private var eNote = ""
    private var catButtons = ArrayList<Pair<TextView, String>>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return    // 忽略自己
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
            // 排除把自己 App 首页误判成账单页（首页也有「账单」「支出」等字样）
            if (texts.any { it.contains("抓取诊断") || it.contains("权限1") || it.contains("本月已确认支出") }) return
            val hasBillPage = texts.any { t -> BILL_PAGE_WORDS.any { t.contains(it) } }
            if (!hasBillPage) return

            if (!whitelist) {
                val hasAmount = texts.any { hasAmountIn(it.trim()) }
                if (hasAmount) Diag.log(this, "其他App($pkg) 疑似账单详情页 → 未接入，仅记录包名")
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
            Diag.log(
                this,
                "$appName 账单页 → 仍未识别到金额（页面文字：${texts.take(12).joinToString("/").take(160)}）"
            )
            return
        }
        val amount = amt.value
        val amountIdx = amt.idx

        if (!amt.expense && INCOME_WORDS.any { joined.contains(it) }) {
            Diag.log(this, "$appName 账单页 ¥$amount → 跳过：疑似收入/退款")
            return
        }
        val isExpense = amt.expense || EXPENSE_WORDS.any { joined.contains(it) }
        if (!isExpense) {
            Diag.log(
                this,
                "$appName 账单页 ¥$amount → 跳过：不像支出（页面文字：${texts.take(10).joinToString("/").take(120)}）"
            )
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
                    if (s.length in 2..20 && !hasAmountIn(s) &&
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
        showForm(amount, merchant, ts, category)
    }

    private class Amt(val value: Double, val expense: Boolean, val score: Int, val idx: Int)

    private fun toNum(s: String?): Double? =
        s?.replace(",", "")?.replace(" ", "")?.toDoubleOrNull()

    private fun hasAmountIn(s: String): Boolean =
        AMOUNT_LABELED.containsMatchIn(s) || AMOUNT_SIGNED.containsMatchIn(s) ||
            AMOUNT_SYMBOL.containsMatchIn(s) || AMOUNT_PLAIN.containsMatchIn(s)

    /**
     * 多级识别金额，取可信度最高的一条：
     * 带支出标记(100) > 带正负号(90) > 带¥符号(85) > 纯小数(75) > 整句抠取(50)
     */
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

        // 兜底：整句里抠（如「交易成功 支出 6.5 元」或「¥6.50」夹在句子里）
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
                val p = SimpleDateFormat(f, Locale.CHINA)
                p.isLenient = false
                return p.parse(s)!!.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 悬浮记账表单：分类、金额、日期、备注可改，取消/保存都在弹窗内完成 */
    private fun showForm(amount: Double, merchant: String, ts: Long, category: String) {
        if (!Settings.canDrawOverlays(this)) {
            Diag.log(this, "无悬浮窗权限 → 改用通知提醒")
            notifyFallback(amount, merchant, ts, category)
            return
        }
        removeOverlay()

        eAmount = amount
        eMerchant = merchant
        eTs = ts
        eCategory = category
        eNote = ""

        val themeCtx = ContextThemeWrapper(this, android.R.style.Theme_Material_Light)
        val view = LayoutInflater.from(themeCtx).inflate(R.layout.overlay_form, null)
        val tvAmount = view.findViewById<TextView>(R.id.tvAmount)
        val tvMerchant = view.findViewById<TextView>(R.id.tvMerchant)
        val tvCategory = view.findViewById<TextView>(R.id.tvCategory)
        val tvTime = view.findViewById<TextView>(R.id.tvTime)
        val tvNote = view.findViewById<TextView>(R.id.tvNote)
        val scrollCats = view.findViewById<ScrollView>(R.id.scrollCats)
        val grid = view.findViewById<GridLayout>(R.id.gridCats)

        tvMerchant.text = merchant
        tvAmount.text = "¥" + String.format("%.2f", eAmount)
        tvCategory.text = eCategory
        tvTime.text = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(eTs)
        tvNote.text = "点击填写"

        catButtons = ArrayList()
        for (c in Category.ALL) {
            val b = TextView(this)
            b.text = c
            b.textSize = 12f
            b.gravity = Gravity.CENTER
            b.setPadding(6, 14, 6, 14)
            b.setBackgroundResource(R.drawable.bg_chip)
            val lp = GridLayout.LayoutParams()
            lp.width = 0
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.setMargins(3, 3, 3, 3)
            b.layoutParams = lp
            b.setOnClickListener {
                eCategory = c
                tvCategory.text = c
                highlightCats()
                scrollCats.visibility = View.GONE
            }
            grid.addView(b)
            catButtons.add(Pair(b, c))
        }
        highlightCats()

        view.findViewById<TextView>(R.id.btnPickCat).setOnClickListener {
            scrollCats.visibility = if (scrollCats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        view.findViewById<TextView>(R.id.btnEditAmount).setOnClickListener {
            askText("修改金额", String.format("%.2f", eAmount), true) { txt ->
                txt.toDoubleOrNull()?.let {
                    if (it > 0) {
                        eAmount = it
                        tvAmount.text = "¥" + String.format("%.2f", eAmount)
                    }
                }
            }
        }

        view.findViewById<TextView>(R.id.btnEditNote).setOnClickListener {
            askText("备注", eNote, false) { txt ->
                eNote = txt
                tvNote.text = if (txt.isBlank()) "点击填写" else txt
            }
        }

        view.findViewById<TextView>(R.id.btnPickDate).setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = eTs }
            val dlg = DatePickerDialog(
                ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert),
                { _, y, m, d ->
                    val c2 = Calendar.getInstance()
                    c2.timeInMillis = eTs
                    c2.set(Calendar.YEAR, y)
                    c2.set(Calendar.MONTH, m)
                    c2.set(Calendar.DAY_OF_MONTH, d)
                    eTs = c2.timeInMillis
                    tvTime.text = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(eTs)
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            )
            dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dlg.show()
        }

        view.findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val db = DbHelper(this)
            db.insertFinal(eAmount, eMerchant, eCategory, eTs, eNote)
            db.close()
            Diag.log(this, "已保存：¥$eAmount $eMerchant · $eCategory")
            Toast.makeText(this, "已记录：$eCategory ¥" + String.format("%.2f", eAmount), Toast.LENGTH_SHORT).show()
            removeOverlay()
        }

        view.findViewById<TextView>(R.id.btnCancel).setOnClickListener {
            Diag.log(this, "已取消：¥$eAmount $eMerchant")
            removeOverlay()
        }
        view.findViewById<TextView>(R.id.btnClose).setOnClickListener { removeOverlay() }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.y = 90

        val holder = FrameLayout(themeCtx)
        holder.setPadding(28, 0, 28, 0)
        holder.addView(view)

        try {
            wm.addView(holder, params)
            overlayView = holder
        } catch (e: Exception) {
            Diag.log(this, "悬浮窗添加失败（${e.javaClass.simpleName}）→ 改用通知")
            notifyFallback(amount, merchant, ts, category)
            return
        }
        // 60 秒未处理自动收起，避免一直挡屏
        handler.postDelayed({ removeOverlay() }, 60000)
    }

    private fun highlightCats() {
        for ((b, c) in catButtons) {
            if (c == eCategory) {
                b.setTypeface(null, Typeface.BOLD)
                b.setTextColor(0xFF1E88E5.toInt())
            } else {
                b.setTypeface(null, Typeface.NORMAL)
                b.setTextColor(0xFF555555.toInt())
            }
        }
    }

    private fun askText(title: String, init: String, number: Boolean, onOk: (String) -> Unit) {
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)
        val et = EditText(ctx)
        et.setText(init)
        et.setSelection(init.length)
        if (number) et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("确定") { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .create()
        dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dlg.show()
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {
        }
        overlayView = null
    }

    private fun notifyFallback(amount: Double, merchant: String, ts: Long, category: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("screen_alert", "账单页抓取提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            this, (ts / 1000).toInt(),
            Intent(this, ConfirmActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("screen", true)
                .putExtra("amount", amount)
                .putExtra("merchant", merchant)
                .putExtra("ts", ts)
                .putExtra("category", category),
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
