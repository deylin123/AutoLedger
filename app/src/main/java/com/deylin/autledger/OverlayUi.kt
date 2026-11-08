package com.deylin.autledger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.20：记账卡片与通知提醒的统一呈现层。
 *
 * 为什么从无障碍服务里剥离出来：
 * 1. 卡片窗口是 `TYPE_APPLICATION_OVERLAY`，只需要悬浮窗权限，**技术上不依赖无障碍服务**。
 *    旧版把它写在 `BillAccessibilityService` 内部，导致「无障碍一关，两条通道都不弹卡片」——
 *    通知通道虽然能记账，但弹卡是委托给无障碍服务的（presentCard），无障碍不在就直接失败。
 * 2. 银行类 App 会检测无障碍服务并拒绝运行。用户为了进招商永隆只能关掉无障碍，
 *    于是记账能跑、卡片永远不出现 —— 这正是「自动弹窗一直没解决」的真相之一。
 *
 * 现在两条通道（页面 / 通知）都调用本对象，仅依赖 `SYSTEM_ALERT_WINDOW`：
 *   - 无障碍开着：行为与旧版完全一致
 *   - 无障碍关着：通知通道仍能弹卡记账
 *
 * v2.21：新增「已记录」模式。
 * 用户主动打开支付宝/微信账单核对时，如果这一笔早就入过账，旧版只弹一个 Toast，
 * 用户看不出「我已经记过了」还是「它根本没识别到」。现在统一弹卡片并显著标注：
 *   - 已确认入账 → 「✓ 该笔消费已记录 + 记入时间 + 分类」，按钮收敛为「知道了」，不给重复入账的入口
 *   - 仍是待确认 → 正常确认卡片（这一笔本来就等他确认）
 *
 * 用 applicationContext 而非 Service 的 context，是为了让窗口的生命周期不被某个 Service 的
 * 存活状态绑死；进程内任何组件都能触发，进程被回收时窗口自然消失。
 */
object OverlayUi {

    /** 同一笔的去重窗：避免页面重扫/双通道命中时反复重建窗口造成闪烁 */
    private const val CARD_DEDUP_MS = 5_000L

    /** 卡片自动收起时间 */
    private const val CARD_AUTO_HIDE_MS = 45_000L

    /** 用户点过「取消」的笔，这段时间内同金额不再弹/记 */
    private const val CANCEL_SUPPRESS_MS = 5 * 60_000L

    /**
     * 「这一笔已经在账本里」的信息。
     *
     * [status] 1 = 已确认入账；0 = 仍是待确认。
     * [ts] 用账本里的记账时间，不是页面上的付款时间 —— 用户核对的是「我什么时候记的」。
     */
    data class RecordedInfo(val ts: Long, val cat: String, val status: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    private var card: View? = null
    private var lastCardId = -1L
    private var lastCardAt = 0L
    private var lastRecordedKey = ""

    private var cancelledAmount = -1.0
    private var cancelledAt = 0L

    private val hideTask = Runnable { removeCard() }

    /** 进入新窗口时允许同一笔重新弹一次（真正的防抖交给 [CARD_DEDUP_MS] 时间窗） */
    fun resetDedup() {
        lastCardId = -1L
        lastRecordedKey = ""
    }

    /** 用户是否在最近 5 分钟内取消过「这个金额」的账单 */
    fun isCancelledRecently(amount: Double): Boolean {
        return amount == cancelledAmount &&
            System.currentTimeMillis() - cancelledAt < CANCEL_SUPPRESS_MS
    }

    fun removeCard() {
        try {
            card?.let {
                (it.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(it)
            }
        } catch (_: Exception) {
        }
        card = null
    }

    /**
     * 呈现一笔账单：先发通知栏提醒（可靠通道），再尝试悬浮卡片。
     *
     * @param recorded 非空表示这一笔已经在账本里，卡片走「已记录」样式，不写库、不重复响铃。
     * @return true 表示本次已处理（含「命中去重被跳过」）
     */
    fun show(
        ctx: Context,
        id: Long,
        amount: Double,
        note: String,
        cat: String,
        income: Boolean,
        recorded: RecordedInfo? = null
    ): Boolean {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()

        // 已记录模式按「记录 id + 金额 + 分类 + 状态」做去重键，且**不设时间窗**：
        // 节拍器在页面静止时每 1.2 秒还会判一次，若沿用 5 秒时间窗，
        // 用户盯着账单看 30 秒就会看到卡片反复弹出 —— 那是骚扰，不是提示。
        // 只有真正换了一笔（id/金额/分类变化）或重进页面（resetDedup）才重新弹。
        if (recorded != null) {
            val key = "$id|$amount|${recorded.cat}|${recorded.status}"
            if (key == lastRecordedKey) {
                Diag.logCard(app, "跳过重复「已记录」提示 #$id ¥$amount（同页同笔只提示一次）")
                return false
            }
            lastRecordedKey = key
            Diag.logCard(
                app,
                "已记录提示 #$id ¥$amount $note（${if (income) "收入" else "支出"}·${recorded.cat}）"
            )
        } else {
            if (id == lastCardId && now - lastCardAt < CARD_DEDUP_MS) {
                Diag.logCard(app, "跳过重复弹卡 #$id ¥$amount（${now - lastCardAt}ms 内已弹过同一笔）")
                return false
            }
            lastCardId = id
            lastCardAt = now
            Diag.logCard(app, "弹卡 #$id ¥$amount $note（${if (income) "收入" else "支出"}·$cat）")
        }

        // 通道一：通知栏。
        // 小米/红米（MIUI/HyperOS）的「后台弹出界面」权限被拒时，WindowManager.addView 不报错、
        // 只是窗口不可见，代码会误判为「已显示」。通知不受该权限限制，是唯一可靠通道。
        // 已记录模式不再重复提醒 —— 账本里已经有了，反复响铃是骚扰，只在屏幕上提示一下。
        if (recorded == null) {
            notifyPending(app, id, amount, note, cat, income)
        }

        // 通道二：悬浮卡片。addView 必须在主线程。
        handler.post {
            try {
                buildCard(app, id, amount, note, cat, income, recorded)
            } catch (e: Exception) {
                Diag.logCard(app, "悬浮卡片构建失败（${e.javaClass.simpleName}）")
            }
        }
        return true
    }

    private fun buildCard(
        app: Context,
        id: Long,
        amount: Double,
        note: String,
        cat: String,
        income: Boolean,
        recorded: RecordedInfo?
    ) {
        if (!Settings.canDrawOverlays(app)) {
            Diag.logCard(app, "跳过：悬浮窗权限未开（通知已发，可在通知栏确认）")
            return
        }
        handler.removeCallbacks(hideTask)
        removeCard()

        val v = LayoutInflater.from(app).inflate(R.layout.view_card, null)
        val tvHead = v.findViewById<View>(R.id.tvHead) as TextView
        val tvAmount = v.findViewById<View>(R.id.tvAmount) as TextView
        val tvNote = v.findViewById<View>(R.id.tvNote) as TextView
        val tvCat = v.findViewById<View>(R.id.tvCat) as TextView
        val tvIcon = v.findViewById<View>(R.id.tvIcon) as TextView
        val btnCancel = v.findViewById<View>(R.id.btnCancel) as TextView
        val btnLater = v.findViewById<View>(R.id.btnLater) as TextView
        val btnCat = v.findViewById<View>(R.id.btnCat) as TextView
        val btnOk = v.findViewById<View>(R.id.btnOk) as TextView

        tvAmount.text = (if (income) "+¥" else "-¥") + Ui.money(amount)
        tvAmount.setTextColor(if (income) Ui.GREEN else Ui.TEXT)
        tvNote.text = note
        tvIcon.text = emojiOf(app, cat)

        if (recorded != null) {
            // ---- 已记录模式：一眼看出「这笔已经在账本里」，不给重复入账的入口 ----
            tvHead.text = if (income) "✓ 该笔收入已记录" else "✓ 该笔消费已记录"
            tvHead.setTextColor(Ui.GREEN)
            val whenStr = timeFmt.format(Date(recorded.ts))
            val catStr = if (recorded.cat.isNotBlank()) "「${recorded.cat}」" else ""
            tvCat.text = if (recorded.status == 1) {
                "已于 $whenStr 记入${catStr}"
            } else {
                "已于 $whenStr 记录${catStr} · 仍待确认"
            }
            tvCat.setTextColor(Ui.GREEN)

            // 取消会删掉真实记录、改分类跳的是待确认列表 —— 两种都不适合已入账的笔，收掉
            btnCancel.visibility = View.GONE
            btnCat.visibility = View.GONE
            btnLater.text = "关闭"
            btnOk.text = "知道了"

            btnLater.setOnClickListener {
                handler.removeCallbacks(hideTask)
                removeCard()
            }
            btnOk.setOnClickListener {
                handler.removeCallbacks(hideTask)
                removeCard()
                Toast.makeText(app, "这笔已在账本中，未重复记账", Toast.LENGTH_SHORT).show()
            }
        } else {
            // ---- 新记录 / 待确认模式：原有行为 ----
            tvHead.text = if (income) "自动识别到收入" else "自动识别到消费"
            tvHead.setTextColor(Ui.SUB)
            tvCat.text = "分类：" + cat
            tvCat.setTextColor(Ui.SUB)

            btnCancel.setOnClickListener {
                // 取消：删掉已入库的待确认记录 + 撤掉通知栏提醒，不保留任何数据，无需二次确认。
                // 误触的代价可控：明细里可手动补记；且 5 分钟内同笔不会因页面重扫而再次弹出。
                try {
                    val db = DbHelper(app)
                    db.delRecord(id)
                    db.close()
                    (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .cancel(id.toInt())
                    cancelledAmount = amount
                    cancelledAt = System.currentTimeMillis()
                    Diag.log(app, "用户取消：¥$amount $note 已删除待确认记录 #$id，不记账")
                } catch (e: Exception) {
                    Diag.log(app, "取消失败：${e.javaClass.simpleName}")
                }
                handler.removeCallbacks(hideTask)
                removeCard()
                Toast.makeText(app, "已取消，不记这笔", Toast.LENGTH_SHORT).show()
            }
            btnLater.setOnClickListener {
                handler.removeCallbacks(hideTask)
                removeCard()
                Toast.makeText(app, "已存入待确认", Toast.LENGTH_SHORT).show()
            }
            btnCat.setOnClickListener {
                handler.removeCallbacks(hideTask)
                removeCard()
                try {
                    app.startActivity(
                        Intent(app, PendingActivity::class.java)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("focus", id)
                    )
                } catch (_: Exception) {
                }
            }
            btnOk.setOnClickListener {
                try {
                    val db = DbHelper(app)
                    db.updateRecord(id, amount, cat, note, System.currentTimeMillis(), 1)
                    db.close()
                } catch (e: Exception) {
                    Diag.log(app, "确认记账失败：${e.javaClass.simpleName}")
                }
                handler.removeCallbacks(hideTask)
                removeCard()
                Toast.makeText(
                    app,
                    (if (income) "已记收入：" else "已记账：") + "$cat ¥" + Ui.money(amount),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = app.resources.displayMetrics
        // 窗口宽度用明确像素而不是 MATCH_PARENT：部分 ROM 下 overlay 会被测量成 0，
        // 卡片塌缩成一个小圆角块（用户看到的「只剩一个圈」）。
        val winW = metrics.widthPixels - Ui.dp(app, 28f)
        val params = WindowManager.LayoutParams(
            winW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        params.y = Ui.dp(app, 96f)
        try {
            wm.addView(v, params)
            card = v
            Diag.logCard(app, "卡片已 addView（通知同时已发）")
        } catch (e: Exception) {
            Diag.logCard(
                app,
                "addView 失败（${e.javaClass.simpleName}），通知已发。小米/红米请确认「后台弹出界面」权限"
            )
            return
        }
        // 记录真实渲染尺寸：万一又变成一个小块，这行日志能直接看出宽高异常
        v.postDelayed({
            Diag.logCard(
                app,
                "卡片尺寸 " + v.width + "x" + v.height +
                    "，屏幕宽 " + metrics.widthPixels +
                    (if (v.width == 0) " ← 宽为 0，窗口被系统拦掉了" else "")
            )
        }, 500)
        handler.postDelayed(hideTask, CARD_AUTO_HIDE_MS)
    }

    private fun emojiOf(app: Context, cat: String): String {
        val db = DbHelper(app)
        return try {
            db.emojiOf(cat)
        } catch (e: Exception) {
            "🧾"
        } finally {
            db.close()
        }
    }

    private fun notifyPending(
        app: Context,
        id: Long,
        amount: Double,
        note: String,
        cat: String,
        income: Boolean
    ) {
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 渠道一旦创建，重要性不可修改，故沿用 v2.3 起的渠道 id 以确保「弹窗+响铃+震动」生效
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
                app, id.toInt(),
                Intent(app, PendingActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("focus", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(app, chId)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle((if (income) "识别到收入 ¥" else "识别到消费 ¥") + Ui.money(amount))
                .setContentText("$note · 分类：$cat · 点此确认")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()
            nm.notify(id.toInt(), n)
            Diag.log(app, "已发送通知提醒：¥$amount $note")
        } catch (e: Exception) {
            Diag.log(app, "通知发送失败（${e.javaClass.simpleName}）：请检查通知权限是否开启")
        }
    }
}
