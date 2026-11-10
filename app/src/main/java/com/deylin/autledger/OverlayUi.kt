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

    /**
     * 同一笔记账卡在这段时间内不重复弹。
     *
     * v2.23：从 5 秒放宽到 60 秒，定位也随之改变 —— 不再是「防抖」，而是
     * 「同一次页面驻留内，同一笔只弹一次」。
     *
     * 为什么必须改：v2.21 的 5 秒窗配合「每次窗口事件就 resetDedup」等于每 5 秒重弹一次。
     * 实测量化（用户日志 10:14:05–10:14:12）：同一笔 #13 ¥15.12 先后弹了 3 次，
     * 每次 addView 都重建窗口 —— 用户看到的就是「卡片反复闪」。
     * 现在的两道防线：
     *   ① 服务端只在**切换 App** 时 resetDedup，同页窗口事件不再清空状态；
     *   ② 这里按记录 id 去重，同一笔在账本里有唯一 id，不同笔天然不会互相挡。
     * 60 秒覆盖页面驻留轮询窗口（12.6 秒）且留足余量，同时保证「隔一会儿重新打开同一笔」仍会提示。
     */
    private const val CARD_DEDUP_MS = 60_000L

    /**
     * 「已记录」提示的抑制窗（v2.23）。
     *
     * v2.22 该去重键不设时间窗 —— 只要 id/金额/分类/状态不变就永不重弹。
     * 好处是不骚扰，代价是「重新打开同一笔核对」永远看不到提示。
     * 现在改为 60 秒窗：页面静止时轮询不会重弹（12.6 秒内），离开再回来超过 1 分钟则重新提示。
     */
    private const val RECORDED_DEDUP_MS = 60_000L

    /** 卡片自动收起时间 */
    private const val CARD_AUTO_HIDE_MS = 45_000L

    /** 用户点过「取消」的笔，这段时间内同金额不再弹/记 */
    private const val CANCEL_SUPPRESS_MS = 5 * 60_000L

    /**
     * v2.22：用户处理过某笔后，同一页面内不再对它重复提示的静默窗。
     *
     * 修的是「确认记账后卡片又弹一次『该笔消费已记录』」：
     * 确认后这条记录变成 status=1，而节拍器还在同一页上继续扫（每 1.2 秒一拍），
     * 下一拍必然命中 status=1 分支 → 又弹一张「已记录」卡。
     * 静默窗只作用于「同一页面、同一笔」，重新进入页面（[resetDedup]）会清空。
     */
    private const val HANDLED_QUIET_MS = 10 * 60_000L

    /**
     * v2.22：通知栏提醒的存活时间。
     *
     * 旧版没有超时，用户处理完这笔后通知仍留在通知栏（只有「取消」会撤掉），
     * 表现为「通知一直在，不会自动消失」。现在用户任何操作都会立即撤销，
     * 完全不操作时最多留 [NOTIF_TIMEOUT_MS] 自动过期。
     */
    private const val NOTIF_TIMEOUT_MS = 10 * 60_000L

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
    private var lastRecordedAt = 0L

    /**
     * v2.24：屏幕上这张卡片属于哪一页账单。
     *
     * null 表示「这张卡不归页面扫描管」（通知通道发的卡），不会被页面切换收掉。
     * 非空是页面扫描给的账单指纹（金额 + 页面付款时间）。
     *
     * 为什么需要它：卡片原来只按「自己 45 秒超时」消失，页面切到另一笔账单时不收。
     * 实测（v2.23，微信/支付宝账单核对）：11-07 那笔的「已记录」卡还挂着时翻到 11-04 的
     * 另一笔，卡片仍在屏幕上显示的是 11-07 的商户与入账时间 —— 读起来就是
     * 「它把两天的账搞混了」。实际并没有记错，是旧卡没跟着页面走。
     */
    private var cardSig: String? = null

    private var cancelledAmount = -1.0
    private var cancelledAt = 0L

    /** 用户刚刚处理过的记录（确认 / 取消 / 存入待确认 / 改分类） */
    private var handledId = -1L
    private var handledAt = 0L

    private val hideTask = Runnable { removeCard() }

    /**
     * 切换 App 时放行同一笔重新提示（真正的防抖交给 [CARD_DEDUP_MS] / [RECORDED_DEDUP_MS] 时间窗）。
     *
     * v2.23：调用点从「每次 TYPE_WINDOW_STATE_CHANGED」收窄到「包名变化」。
     * 旧调用点等于把去重状态归零的动作交给了最频繁的事件 —— 用户日志里
     * 同一笔 5 秒内弹 3 次，就是被这条清空动作一次次放出来的。
     */
    fun resetDedup() {
        lastCardId = -1L
        // v2.24：**不再清空「已记录」去重键**。
        //
        // 实测日志（v2.23，13:27:44 与 13:27:45）：同一笔 #22 ¥1.0 在 1 秒内弹了两张
        // 「已记录」卡，随后一秒才打出「跳过重复（1447ms 内已提示过同一笔）」。
        // 中间唯一能清空状态的动作就是本函数 —— 切到另一个已登记 App 再切回来、
        // 或窗口状态抖动，都会把「已记录」键归零，于是同一笔立刻又能弹一次。
        // 现在这个键只由它自己的 [RECORDED_DEDUP_MS] 时间窗管理：
        // 页面静止时不重弹，离开超过 1 分钟再回来看才重新提示，行为是可预期的。
        // v2.22：新窗口 = 新页面，允许对同一笔重新给出提示（用户重新打开账单核对属于新场景）
        handledId = -1L
        handledAt = 0L
    }

    /**
     * v2.22：登记「这一笔用户已经处理过」。
     *
     * 由卡片的四个按钮调用。作用有两点：
     * 1. 同页静默 —— 处理完后节拍器还会继续扫，不登记就会立刻再弹一张
     *    「该笔消费已记录」（用户确认后又被提示一次）或重弹确认卡（用户点了存入待确认）；
     * 2. 去重键同步刷新，避免紧接着的一次重扫绕过 [CARD_DEDUP_MS] 又弹一次。
     */
    fun markHandled(id: Long) {
        if (id <= 0) return
        handledId = id
        handledAt = System.currentTimeMillis()
        lastCardId = id
        lastCardAt = handledAt
    }

    private fun isHandledRecently(id: Long): Boolean {
        return id > 0 && id == handledId &&
            System.currentTimeMillis() - handledAt < HANDLED_QUIET_MS
    }

    /** 撤掉某笔的通知栏提醒（用户一旦处理过，这条提醒就不该继续占着通知栏） */
    private fun cancelNotification(app: Context, id: Long) {
        if (id <= 0) return
        try {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(id.toInt())
        } catch (_: Exception) {
        }
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
        cardSig = null
    }

    /**
     * v2.24：页面上的这一笔如果已经不是屏幕上那张卡的账单，立刻收掉旧卡。
     *
     * 由无障碍服务在**识别出本页账单身份之后、任何闸门返回之前**调用，
     * 所以「被 2 秒同额闸拦掉」「被取消抑制拦掉」「识别到已记录」这些不弹新卡的路径
     * 同样会执行到 —— 这正是旧版残留卡片的场景：本页没有弹新卡，旧卡就一直挂着。
     *
     * 用「金额 + 页面付款时间」作指纹而不是整页文本：账单页滚动/重绘/加载
     * （「更多」「立即领取」这类动态内容）都会改变整页文本，用它做指纹会把
     * 用户正在看的卡误收掉；而这两项在同一笔账单上恒定、换一笔必然变化。
     */
    fun keepIfSameBill(sig: String) {
        val v = card ?: return
        val cur = cardSig ?: return   // 这张卡由通知通道发出，不归页面扫描管
        if (cur == sig) return
        Diag.logCard(v.context, "收掉上一页残留的卡片（旧页 $cur → 本页 $sig）")
        handler.removeCallbacks(hideTask)
        removeCard()
    }

    /**
     * 呈现一笔账单：先发通知栏提醒（可靠通道），再尝试悬浮卡片。
     *
     * @param recorded 非空表示这一笔已经在账本里，卡片走「已记录」样式，不写库、不重复响铃。
     * @param sig 页面扫描给出的账单指纹（金额 + 页面付款时间），用于「换页就收掉旧卡」；
     *            通知通道不传，表示这张卡不跟页面绑定，只按自身超时消失。
     * @return true 表示本次已处理（含「命中去重被跳过」）
     */
    fun show(
        ctx: Context,
        id: Long,
        amount: Double,
        note: String,
        cat: String,
        income: Boolean,
        recorded: RecordedInfo? = null,
        sig: String? = null
    ): Boolean {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()

        // v2.22：用户刚处理过这一笔（确认/取消/存入待确认/改分类），本页不再对它提示。
        // 确认后记录变成 status=1，节拍器下一拍必然命中「已记录」分支 —— 这是
        // 「记完又弹一次『该笔消费已记录』」的直接来源。重新进入页面会清空该状态。
        if (isHandledRecently(id)) {
            Diag.logCard(app, "跳过 #$id ¥$amount：用户刚处理过这一笔，同一页面不再重复提示")
            return false
        }

        // 已记录模式按「记录 id + 金额 + 分类 + 状态」做去重键，配 [RECORDED_DEDUP_MS] 时间窗：
        // 页面静止时轮询（最多 12.6 秒）不会重弹，用户离开再回来超过 1 分钟会重新提示。
        if (recorded != null) {
            val key = "$id|$amount|${recorded.cat}|${recorded.status}"
            if (key == lastRecordedKey && now - lastRecordedAt < RECORDED_DEDUP_MS) {
                Diag.logCard(app, "跳过重复「已记录」提示 #$id ¥$amount（${now - lastRecordedAt}ms 内已提示过同一笔）")
                return false
            }
            lastRecordedKey = key
            lastRecordedAt = now
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
                buildCard(app, id, amount, note, cat, income, recorded, sig)
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
        recorded: RecordedInfo?,
        sig: String?
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
                markHandled(id)
                cancelNotification(app, id)
            }
            btnOk.setOnClickListener {
                handler.removeCallbacks(hideTask)
                removeCard()
                markHandled(id)
                cancelNotification(app, id)
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
                    cancelledAmount = amount
                    cancelledAt = System.currentTimeMillis()
                    Diag.log(app, "用户取消：¥$amount $note 已删除待确认记录 #$id，不记账")
                } catch (e: Exception) {
                    Diag.log(app, "取消失败：${e.javaClass.simpleName}")
                }
                markHandled(id)
                cancelNotification(app, id)
                handler.removeCallbacks(hideTask)
                removeCard()
                Toast.makeText(app, "已取消，不记这笔", Toast.LENGTH_SHORT).show()
            }
            btnLater.setOnClickListener {
                handler.removeCallbacks(hideTask)
                removeCard()
                markHandled(id)
                cancelNotification(app, id)
                Toast.makeText(app, "已存入待确认", Toast.LENGTH_SHORT).show()
            }
            btnCat.setOnClickListener {
                handler.removeCallbacks(hideTask)
                removeCard()
                markHandled(id)
                cancelNotification(app, id)
                try {
                    app.startActivity(
                        Intent(app, PendingActivity::class.java)
                            // v2.24：CLEAR_TOP + SINGLE_TOP，复用已有待确认页而不是再叠一层
                            .setFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
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
                // v2.22：登记已处理 + 撤通知。
                // 旧版只 removeCard()，通知栏那条提醒永远留着，用户看到的就是
                // 「每次记录完消费，悬浮窗的通知一直在」。同时登记后节拍器不会
                // 立刻再弹一张「该笔消费已记录」。
                markHandled(id)
                cancelNotification(app, id)
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
            // v2.24：记住这张卡属于哪一页账单，页面一切换就会被 keepIfSameBill 收掉
            cardSig = sig
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
                    .setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
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
                // v2.22：用户不处理时的兜底过期时间。
                // 关键路径：用户点了卡片按钮 → cancelNotification 立即撤销；
                // 用户完全没碰（例如自动扣费发生在锁屏状态）→ 最多留 10 分钟自动消失，
                // 不再像旧版那样一直挂在通知栏。
                .setTimeoutAfter(NOTIF_TIMEOUT_MS)
                .build()
            nm.notify(id.toInt(), n)
            Diag.log(app, "已发送通知提醒：¥$amount $note")
        } catch (e: Exception) {
            Diag.log(app, "通知发送失败（${e.javaClass.simpleName}）：请检查通知权限是否开启")
        }
    }
}
