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
import android.widget.TextView
import android.widget.Toast

/**
 * 账单页抓取。
 *
 * 支持范围由 [PayApps] 统一维护：支付宝、微信、淘宝/天猫、京东、拼多多、抖音、
 * 美团/点评/饿了么、去哪儿/携程/飞猪、滴滴、12306 等消费类应用，
 * 以及未注册 App 的通用兜底（严格判定）。
 *
 * 抓到后**先落成待确认账单**（数据不会丢），再尝试弹悬浮卡片；没弹出来也能在「待确认」里处理。
 */
class BillAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 进入新页面后的重试时间点（毫秒）。
         *
         * 这是 v2.5 修掉「打开支付宝账单页不弹、手动滑一下才弹」的核心机制：
         *  1. 进入页面的瞬间，rootInActiveWindow 往往还是旧页面或只有骨架，文本不足；
         *  2. 页面渲染完成的那一刻会发一次 CONTENT_CHANGED，但常被节流丢掉；
         *  3. 之后页面静止，不再产生任何事件 —— 除非用户手动滑动。
         * 所以不能"收到事件扫一次"，必须进页面后按时间点反复扫，拿到即停。
         */
        private val BURST_DELAYS = longArrayOf(0, 200, 500, 1000, 1800, 3000, 4500, 6500)

        /** 内容变化事件的节流间隔 */
        private const val CONTENT_THROTTLE = 700L

        /** 同一包名的"疑似未接入"日志间隔，避免刷屏 */
        private const val SUSPECT_LOG_GAP = 10 * 60 * 1000L

        /** 单页最多收集多少条文本 */
        private const val MAX_TEXTS = 260

        /**
         * 同一笔消费被判为"重复识别"的时间窗（与通知通道共用，见 [PayApps.MERGE_WINDOW_MS]）。
         * 旧版只按 20 秒判重，跨过这 20 秒就会把同一笔存成两条。
         */
        private const val MERGE_WINDOW = PayApps.MERGE_WINDOW_MS

        /** 这些是订单页的字段名而非商户/商品，不能拿来当备注 */
        private val FIELD_NOISE = listOf(
            "乘机人", "乘客", "联系人", "收货人", "收件人", "手机号", "电话", "地址",
            "数量", "件数", "单价", "航班", "车次", "座位", "里程", "积分", "备注"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    /** burst 代次：新的页面切换会自增，旧代次的定时任务自动作废 */
    private var burstToken = 0

    /** 本代次已成功抓取，后续重试不再执行 */
    private var hitToken = -1

    private var lastContentAt = 0L
    private var lastAmount = -1.0
    private var lastAmountAt = 0L

    private val suspectLogged = HashMap<String, Long>()
    private val skipLogged = HashMap<String, Long>()
    private val labelCache = HashMap<String, String>()

    private var card: View? = null
    private var lastCardId = -1L

    private val hideTask = Runnable { removeCard() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diag.log(this, "无障碍服务已连接（v2.5 通用识别版）")
    }

    // ---------------- 事件分派 ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg == "com.android.systemui" || pkg == "android") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> startBurst(pkg)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentAt < CONTENT_THROTTLE) return
                lastContentAt = now
                val token = burstToken
                // 内容变化后补两次，覆盖「先出标题、再加载金额」的页面
                handler.postDelayed({ if (token == burstToken) runScan(pkg, token) }, 150)
                handler.postDelayed({ if (token == burstToken) runScan(pkg, token) }, 1000)
            }
        }
    }

    /** 新页面：立即按时间点排一串扫描任务，任一命中即停 */
    private fun startBurst(pkg: String) {
        burstToken++
        val token = burstToken
        for (d in BURST_DELAYS) {
            handler.postDelayed({
                if (token != burstToken) return@postDelayed
                if (hitToken == token) return@postDelayed
                runScan(pkg, token)
            }, d)
        }
    }

    // ---------------- 扫描 ----------------

    private fun runScan(pkg: String, token: Int) {
        try {
            val texts = grabTexts()
            if (texts.size < 3) return
            // 排除本应用自己的界面（明细/待确认里的文字）
            if (texts.any { it.contains("自动记账") || it.contains("本月预算") }) return

            val app = PayApps.of(pkg)
            if (!PayApps.hasTitle(texts, app)) {
                if (app == null) noteSuspect(pkg, texts)
                return
            }
            // 未注册的 App：必须有支出方向佐证才敢认定，避免"订单详情"浏览页误抓
            if (app == null && !PayApps.hasExpenseSignal(texts)) return

            // 关键闸门（v2.6）：必须是「已经扣款」的页面。
            // 收银台、输入金额页同样有「实付款 ¥89.00」这类强标签金额，不拦住就会在付款前先记一笔，
            // 付款成功后再记一笔 —— 用户看到的「一次支付识别两次」就是这个。
            val state = PayApps.payState(texts)
            if (state != PayApps.STATE_PAID) {
                val name = app?.name ?: appLabel(pkg)
                val v = PayApps.findAmount(texts)?.value
                if (state == PayApps.STATE_UNPAID) {
                    logSkip("$pkg#$state#$v",
                        "$name ¥${v ?: "-"} → 跳过：还在支付流程里（输金额/收银台/等密码），钱未扣")
                } else {
                    logSkip("$pkg#$state",
                        "$name → 跳过：页面上没有支付完成证据，按浏览页处理")
                }
                return
            }

            if (scan(pkg, texts, app)) hitToken = token
        } catch (e: Exception) {
            Diag.log(this, "扫描异常：${e.javaClass.simpleName}")
        }
    }

    private fun scan(pkg: String, texts: ArrayList<String>, app: PayApps.App?): Boolean {
        val appName = app?.name ?: appLabel(pkg)
        val joined = texts.joinToString("|")

        val amt = PayApps.findAmount(texts)
        if (amt == null) {
            Diag.log(this, "$appName 账单页 → 未识别到金额（${texts.take(14).joinToString("/").take(180)}）")
            return false
        }
        val amount = amt.value

        if (!amt.expense && PayApps.hasIncomeSignal(texts)) {
            Diag.log(this, "$appName ¥$amount → 跳过：退款/收入")
            return false
        }
        if (!amt.expense && !PayApps.hasExpenseSignal(texts)) {
            Diag.log(this, "$appName ¥$amount → 跳过：不像支出")
            return false
        }

        val merchant = PayApps.fieldOf(texts, PayApps.MERCHANT_KEYS)
            ?: goodsNear(texts, amt.idx, appName)
        val goods = PayApps.fieldOf(texts, PayApps.GOODS_KEYS) ?: ""
        val catHint = PayApps.fieldOf(texts, PayApps.CAT_KEYS) ?: ""
        val ts = PayApps.TIME.find(joined)?.groupValues?.getOrNull(1)
            ?.let { PayApps.parseTime(it) } ?: System.currentTimeMillis()

        val cat = Category.of(merchant, goods, catHint, appName)
        val note = if (goods.isNotBlank() && goods != merchant) "$merchant·$goods" else merchant

        // 同一笔在多次重试之间保持幂等：8 秒内同金额不再入库
        val now = System.currentTimeMillis()
        if (amount == lastAmount && now - lastAmountAt < 8000) return false

        val db = DbHelper(this)
        try {
            // 一笔支付只允许落一条记录。
            // 同一笔可能在「结果页」和「账单详情」各被识别一次（两个页面文字不同、商户名不同），
            // 也可能页面和通知各命中一次 —— 所以按「金额 + 90 秒」收敛，不按商户名比对。
            val prev = db.recentSameAmount(amount, ts, MERGE_WINDOW)
            if (prev != null) {
                if (prev.status == 0) {
                    // 先前那条还没确认：用本次更完整的信息覆盖（商品名通常只有结果页才有），不新增、不重复弹卡片
                    val merged = betterNote(prev.note, note)
                    db.updateRecord(prev.id, amount, cat, merged, ts, 0)
                    lastAmount = amount
                    lastAmountAt = now
                    Diag.log(this, "$appName ¥$amount → 同一笔已合并到待确认 #${prev.id}（备注：$merged）")
                    return true
                }
                Diag.log(this, "$appName ¥$amount → 跳过：${MERGE_WINDOW / 1000}秒内同金额已记账（#${prev.id}）")
                return false
            }
            val id = db.addRecord(
                Rec(
                    amount = amount, type = 0, cat = cat, note = note, ts = ts,
                    book = bookOf(), status = 0, src = appName
                )
            )
            if (id <= 0) return false
            lastAmount = amount
            lastAmountAt = now
            Diag.log(this, "$appName ¥$amount $note → 已存为待确认（预分类：$cat）")
            showCard(id, amount, note, cat)
            return true
        } finally {
            db.close()
        }
    }

    /** 合并备注：本次只是兜底名（"支付宝消费"）时，保留上一次识别到的具体商户名 */
    private fun betterNote(oldNote: String, newNote: String): String {
        if (oldNote.isBlank()) return newNote
        if (newNote.isBlank()) return oldNote
        if (newNote.endsWith("消费") && !oldNote.endsWith("消费")) return oldNote
        return newNote
    }

    /**
     * 跳过原因的日志节流。
     * 输金额页停留期间会反复触发扫描与重试，不节流会把诊断日志刷满、把有用信息挤掉。
     */
    private fun logSkip(key: String, msg: String) {
        val now = System.currentTimeMillis()
        if (now - (skipLogged[key] ?: 0L) < 60_000L) return
        if (skipLogged.size > 60) skipLogged.clear()
        skipLogged[key] = now
        Diag.log(this, msg)
    }

    /** 未登记 App 的疑似消费页：只记一次包名，方便后续在 PayApps 里补登记 */
    private fun noteSuspect(pkg: String, texts: List<String>) {
        if (!texts.any { PayApps.hasAmountIn(it) }) return
        if (!PayApps.hasExpenseSignal(texts)) return
        val now = System.currentTimeMillis()
        if (now - (suspectLogged[pkg] ?: 0L) < SUSPECT_LOG_GAP) return
        suspectLogged[pkg] = now
        Diag.log(this, "未接入App($pkg) 疑似消费页 → 仅记包名，可在 PayApps.kt 登记")
    }

    /**
     * 读当前页面文字：优先活动窗口；活动窗口内容太少时（常见于支付宝，
     * 详情页在另一个 window 里），再把所有窗口的文本合并读一遍。
     */
    private fun grabTexts(): ArrayList<String> {
        val set = LinkedHashSet<String>()
        rootInActiveWindow?.let { collect(it, set) }
        if (set.size < 4) {
            try {
                val ws = windows
                ws?.forEach { w ->
                    val r = w?.root ?: return@forEach
                    collect(r, set)
                }
            } catch (_: Exception) {
            }
        }
        return ArrayList(set)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        node ?: return
        if (out.size > MAX_TEXTS) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        // 部分 App（含自绘/电商页）把文字放在 contentDescription 里，只读 text 会漏
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    private fun goodsNear(texts: List<String>, idx: Int, appName: String): String {
        for (j in (idx - 1) downTo maxOf(0, idx - 5)) {
            val s = texts[j].trim()
            if (s.length in 2..24 && !PayApps.hasAmountIn(s) &&
                PayApps.TIME.find(s) == null &&
                !PayApps.hasExpenseSignal(listOf(s)) &&
                FIELD_NOISE.none { s.contains(it) } &&
                !s.contains("账单") && !s.contains("详情")
            ) return s
        }
        return appName + "消费"
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

    private fun bookOf(): String =
        getSharedPreferences("cfg", MODE_PRIVATE).getString("book", "日常账本") ?: "日常账本"

    // ---------------- 悬浮卡片 ----------------

    /** 同一笔只弹一次，避免重复触发反复重建窗口造成闪烁 */
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
