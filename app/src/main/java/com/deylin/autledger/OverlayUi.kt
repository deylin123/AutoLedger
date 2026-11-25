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

    /**
     * v2.41：新卡片的**最小存活时间** —— 建窗后这段时间内一律不接受「换页收卡」。
     *
     * 修的是「微信转账：顶部通知有了、待确认也记了，屏幕底部却一张卡都没有」。
     *
     * 真相链条：微信是单 Activity 多窗口。转账结果页是自绘页，无障碍只能读到「微信支付」
     * 一个标题，所以卡片是 OCR 通道弹出来的；但**同一时刻底层聊天窗口也在 windows 里**，
     * 而 [bindPage] 是拿 `PayApps.pageTime()` 判「换页」的 —— `pageTime` 第 ③ 支
     * 「相邻日期节点 + 时间节点」会把群聊里那条「11月24日 / 21:33」解析成一个时间，
     * 与这张卡片的页面时间不相同，于是下一拍（700ms 后）就把它当成「换到另一笔了」收掉。
     * 用户那边看到的就是「一闪都没有」，只剩通知栏那条提醒。
     *
     * 为什么付款不会这样：微信收付款页是独立页面，底层没有带日期时间的聊天窗口，
     * `pageTime` 返回 null，[bindPage] 直接 return —— 所以「付款能弹、转账不能弹」。
     *
     * 6 秒的依据：页面驻留节拍器一拍 700ms，用户从看到卡片到伸手点至少需要 1~2 秒，
     * 6 秒足够覆盖「刚弹出就被误判收掉」的窗口，且远小于 [CARD_AUTO_HIDE_MS]。
     */
    private const val MIN_CARD_LIFE_MS = 6_000L

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
     * v2.24/v2.25：屏幕上这张卡片属于哪一页账单（页面上的付款时间）。
     *
     * 0 表示「不归页面扫描管」—— 通知通道发的卡没有页面身份，不会被页面切换收掉。
     *
     * 为什么需要它：卡片原来只按「自己 45 秒超时」消失。用户翻到另一笔账单时，
     * 屏幕上仍挂着上一笔的卡片（写着上一笔的商户与入账时间），
     * 读起来就是「它把两天的账搞混了」。卡片上的内容并没有错，错在它不该还在屏幕上。
     */
    private var cardPageTs = 0L

    /**
     * v2.41：这张卡是不是「截屏 OCR 通道」弹出来的。
     *
     * OCR 目标页（微信转账结果页这类自绘页）在无障碍树里没有页面身份，
     * 卡片上的 pageTs 来自 OCR 文本，与底层聊天窗口解析出的时间天然不是一回事 ——
     * 一旦拿它去跟别的窗口比「换页」，必然误收。pin 的卡片只由用户操作 /
     * 45 秒超时 / 下一张卡覆盖来收，不被 [bindPage] 收走。
     */
    private var cardPinned = false

    /** v2.41：建卡时刻，配合 [MIN_CARD_LIFE_MS] 做最小存活保护 */
    private var cardAt = 0L

    /** v2.30：当前卡片对应的记录 id，备注保存后据此实时刷新卡片文案 */
    private var cardRecId = -1L

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
        cardPageTs = 0L
        cardRecId = -1L
        cardPinned = false
        cardAt = 0L
    }

    /**
     * v2.25：把屏幕上的卡片与「当前这一页」绑定；不是同一页就立刻收掉。
     *
     * 由无障碍服务在 [attempt] 的**每个闸门之前**调用，所以本页即使被判成历史账单、
     * 方向不明、读不到金额，卡片也会跟着页面走掉 —— 不会再出现
     * 「这一页是 A，屏幕上写着 B」的情况。
     *
     * 只比页面付款时间，不比整页文本：账单页滚动、重绘、动态内容都会改变整页文本，
     * 用文本做指纹会把用户正在看的卡误收掉；付款时间在同一笔上恒定、换一笔必变。
     */
    fun bindPage(pageTs: Long?) {
        if (pageTs == null || pageTs <= 0L) return   // 支付现场页无绝对时间，不参与
        val owned = cardPageTs
        if (owned <= 0L || owned == pageTs) return
        val v = card ?: run { cardPageTs = 0L; return }
        // v2.41：OCR 通道的卡片不接受「换页收卡」。
        // 它所属的页面（微信转账结果页）在无障碍树里没有身份，卡片时间来自 OCR 文本；
        // 拿它去跟底层聊天窗口解析出的时间比，必然误判 —— 这正是「顶部有通知、
        // 底部一张卡都没有」的成因（详见 MIN_CARD_LIFE_MS 注释）。
        if (cardPinned) {
            Diag.logCard(
                v.context,
                "本窗口时间 ${fmtTs(pageTs)} 与 OCR 卡（${fmtTs(owned)}）不同，但该卡来自截屏 OCR 通道，保留不收"
            )
            return
        }
        // v2.41：最小存活保护 —— 刚弹出来的卡片先让用户看几秒，再接受换页判定。
        val age = System.currentTimeMillis() - cardAt
        if (cardAt > 0L && age < MIN_CARD_LIFE_MS) {
            Diag.logCard(
                v.context,
                "页面已换成另一笔（旧页 ${fmtTs(owned)} → 本页 ${fmtTs(pageTs)}），但卡片才建 ${age}ms，" +
                    "未到最小存活 ${MIN_CARD_LIFE_MS}ms，先保留"
            )
            return
        }
        Diag.logCard(
            v.context,
            "页面已换成另一笔（旧页 ${fmtTs(owned)} → 本页 ${fmtTs(pageTs)}），立即收掉旧卡"
        )
        handler.removeCallbacks(hideTask)
        removeCard()
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    private fun fmtTs(ts: Long): String = try { fmt.format(java.util.Date(ts)) } catch (_: Exception) { ts.toString() }

    /**
     * 呈现一笔账单：先发通知栏提醒（可靠通道），再尝试悬浮卡片。
     *
     * @param recorded 非空表示这一笔已经在账本里，卡片走「已记录」样式，不写库、不重复响铃。
     * @param pageTs 本页账单的付款时间（0 = 无/通知通道），用于「换页就收掉旧卡」。
     * @param pin v2.41：true 表示这张卡来自「截屏 OCR 通道」，其页面在无障碍树里没有身份，
     *            不受「换页收卡」约束（否则微信转账卡会被底层聊天窗口的时间误收）。
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
        pageTs: Long = 0L,
        remembered: Boolean = false,
        pin: Boolean = false
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
                buildCard(app, id, amount, note, cat, income, recorded, pageTs, remembered, pin)
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
        pageTs: Long,
        remembered: Boolean,
        pin: Boolean = false
    ) {
        // v2.43：**这里原来有一道 `if (!Settings.canDrawOverlays(app)) return` 的硬闸门，已删除。**
        // 2026-11-25 11:20 真机日志（v2.41/47）把它钉死了 —— 同一个 APK、同一个进程：
        //   10:15:24 支付宝 → 「卡片已 addView，1116x562」（canDrawOverlays 返回 true）
        //   10:00:33 微信   → 「跳过：悬浮窗权限未开」（canDrawOverlays 返回 false）
        //   10:52:36 微信   → 「跳过：悬浮窗权限未开」
        // 权限客观上是有的（十几分钟前刚成功建过窗，用户手动测也弹得出来），
        // 变的只是这个 API 的返回值：服务进程/后台状态下会**假阴性**。
        // 而假阴性直接 return，卡片永远建不出来；主界面又拿 Activity context 复查到 true
        // 把报警清掉 —— 于是「App 认为权限在、用户什么都没有」，且橙条不亮，查无可查。
        // 结论：**绝对不能拿 Settings.canDrawOverlays 当建窗前置条件。**
        // 唯一权威判定是 addView 自己抛不抛异常（见下面的 catch）。
        handler.removeCallbacks(hideTask)
        removeCard()

        val v = LayoutInflater.from(app).inflate(R.layout.view_card, null)
        val tvHead = v.findViewById<View>(R.id.tvHead) as TextView
        val tvAmount = v.findViewById<View>(R.id.tvAmount) as TextView
        val tvNote = v.findViewById<View>(R.id.tvNote) as TextView
        val tvCat = v.findViewById<View>(R.id.tvCat) as TextView
        val tvIcon = v.findViewById<View>(R.id.tvIcon) as TextView
        val tvRemark = v.findViewById<View>(R.id.tvRemark) as TextView
        val btnCancel = v.findViewById<View>(R.id.btnCancel) as TextView
        val btnLater = v.findViewById<View>(R.id.btnLater) as TextView
        val btnCat = v.findViewById<View>(R.id.btnCat) as TextView
        val btnOk = v.findViewById<View>(R.id.btnOk) as TextView

        tvAmount.text = (if (income) "+¥" else "-¥") + Ui.money(amount)
        tvAmount.setTextColor(if (income) Ui.GREEN else Ui.TEXT)
        tvNote.text = note
        tvIcon.text = emojiOf(app, cat)

        // v2.30：可编辑备注栏。备注存独立 remark 列，与 note（识别内容/商户分类记忆 key）分离。
        // 悬浮窗是 FLAG_NOT_FOCUSABLE，内嵌 EditText 在 MIUI/HyperOS 上拿不到焦点、弹不出软键盘，
        // 故这里只呈现 + 点击启动独立输入页（与「改分类」跳 PendingActivity 同一条已验证路径）。
        // 新记录 / 已记录两种模式都能写：历史已入账的笔也可以补备注。
        val savedRemark = try {
            val rdb = DbHelper(app)
            val s = rdb.remarkOf(id)
            rdb.close()
            s
        } catch (_: Exception) { "" }
        paintRemark(tvRemark, savedRemark)
        tvRemark.setOnClickListener {
            try {
                app.startActivity(
                    Intent(app, RemarkActivity::class.java)
                        .setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        .putExtra("recId", id)
                )
            } catch (_: Exception) {
            }
        }

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
            // v2.28：商户记忆命中时明确告诉用户「这是按你的习惯填的，确认即可」
            tvCat.text = if (remembered) "分类：$cat（按你的习惯，确认即可）" else "分类：" + cat
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
                    // v2.28：确认时保留入库时的原始时间，不再用「现在」覆盖。
                    // 旧版这里写 System.currentTimeMillis() —— 用户事后（哪怕几天后）
                    // 点确认，这笔账的消费日期就被改写成确认时刻，
                    // 这就是「记账日期不该以用户记录时间为基准」的确定来源。
                    // 页面通道入库用页面付款时间、通知通道用通知时间，都保住。
                    val keepTs = db.get(id)?.ts ?: System.currentTimeMillis()
                    db.updateRecord(id, amount, cat, note, keepTs, 1)
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
            cardRecId = id
            // v2.25：记住这张卡属于哪一页（页面付款时间），页面一换就被 bindPage 收掉
            cardPageTs = if (pageTs > 0L) pageTs else 0L
            // v2.41：记录「这张卡来自哪条通道」与建卡时刻。
            // pin 的卡（截屏 OCR 通道）不被 bindPage 收；所有卡都受最小存活保护。
            cardPinned = pin
            cardAt = System.currentTimeMillis()
            markCardState(app, "ok")
            // v2.43：把 canDrawOverlays 的实际返回值一并记下来。既然这一行就是成功建窗的证据，
            // 若它写着 canDrawOverlays=false，那就是该 API 假阴性的铁证，下次不必重新论证。
            Diag.logCard(
                app,
                "卡片已 addView（通知同时已发）" +
                    (if (pin) "｜来自截屏 OCR 通道，已锁定、不接受换页收卡" else "") +
                    "｜canDrawOverlays=" + Settings.canDrawOverlays(app)
            )
        } catch (e: Exception) {
            // v2.43：addView 抛异常才是「真的没有窗口权限」的唯一证据。
            // SecurityException / BadTokenException / 文案含 permission = 系统拒绝窗口类型；
            // 其余异常（inflate、measure 等）与权限无关，不能再误导用户去开权限。
            val denied = e is SecurityException ||
                e.javaClass.simpleName.contains("BadToken", ignoreCase = true) ||
                (e.message ?: "").contains("permission", ignoreCase = true)
            markCardState(app, if (denied) "no_perm" else "addview:" + e.javaClass.simpleName)
            Diag.logCard(
                app,
                "addView 失败（${e.javaClass.simpleName}" +
                    (if (denied) "，系统拒绝窗口" else "") + "），通知已发。${e.message ?: ""}"
            )
            if (recorded == null) {
                notifyPending(
                    app, id, amount, note, cat, income,
                    if (denied) "卡片未显示：系统拒绝浮窗，请开「显示悬浮窗」和「后台弹出界面」"
                    else "卡片未显示（建窗异常 ${e.javaClass.simpleName}），可在通知栏确认记账"
                )
            }
            return
        }
        // 记录真实渲染尺寸：万一又变成一个小块，这行日志能直接看出宽高异常
        v.postDelayed({
            // v2.45：**先确认这张卡还是当前这张**。
            //
            // 2026-11-25 12:00 用户报「首页出现橙条写着悬浮卡片被拦住」，但卡片其实弹出来了。
            // 原因就在这里：这张 v 可能在 500ms 内被 removeCard（用户点确认 / 换页 / 被新卡顶掉），
            // 此时 v 已 detach，isShown 恒为 false → 写入 hidden → 主界面橙条长期误报
            // 「卡片窗口被系统隐藏」，而真相是它正常显示过、正常收起了。
            // 只有「现在还挂在窗口上的就是这张卡」时，可见性结论才有意义。
            if (card !== v) {
                Diag.logCard(
                    app,
                    "卡片在建窗自检前已收起（正常换页/确认），不写入可见性结论"
                )
            } else {
                // v2.38：addView 没抛异常但宽为 0，是 MIUI「后台弹出界面」被拒的典型表现 ——
                // 代码以为成功了，用户什么都没看到。这种情况单独落一个 zero 状态，
                // 权限条才能提示到点子上（否则用户会以为识别没工作）。
                // v2.42：补上比「宽为 0」更隐蔽的一种 —— addView 成功、宽高都正常，
                // 但窗口被系统隐藏（isShown=false）。HyperOS 上「后台弹出界面」被拒时就是这样：
                // 日志里写着「卡片已 addView」，主界面橙条不亮（因为状态是 ok），
                // 而用户屏幕上什么都没有。这正是「转账只能在吸顶处出现、底部没卡」的最后一种可能。
                val shown = try { v.isShown } catch (_: Exception) { true }
                if (v.width == 0) {
                    markCardState(app, "zero")
                    // v2.40：宽为 0 = 窗口被系统吞了（MIUI/HyperOS「后台弹出界面」被拒的典型表现），
                    // 代码以为成功、用户什么都看不到 —— 必须把原因写进通知，否则又是「只有通知没有卡片」。
                    if (recorded == null) {
                        notifyPending(app, id, amount, note, cat, income, "卡片被系统拦住：请开「后台弹出界面」")
                    }
                } else if (!shown) {
                    markCardState(app, "hidden")
                    if (recorded == null) {
                        notifyPending(app, id, amount, note, cat, income, "卡片窗口被系统隐藏：请开「后台弹出界面」")
                    }
                }
                Diag.logCard(
                    app,
                    "卡片尺寸 " + v.width + "x" + v.height +
                        "，屏幕宽 " + metrics.widthPixels +
                        "，可见=" + shown +
                        (if (v.width == 0) " ← 宽为 0，窗口被系统拦掉了"
                        else if (!shown) " ← 建窗成功但窗口不可见（后台弹出界面被拒）" else "")
                )
            }
        }, 500)
        handler.postDelayed(hideTask, CARD_AUTO_HIDE_MS)
    }

    /**
     * v2.38：卡片通道的结局落盘。
     *
     * 取值：ok / no_perm / addview:<异常名> / zero（addView 成功但宽为 0，MIUI 典型表现）。
     * 主界面权限条读这个值提示用户，而不是自己再调一次 Settings.canDrawOverlays ——
     * 真机日志证明两者结论可以不一致，判据必须用「实际建窗结果」而不是「权限查询结果」。
     */
    private fun markCardState(app: Context, v: String) {
        try {
            app.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString("card_state", v)
                .putLong("card_state_at", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** v2.30：备注栏文案（有备注显示内容，无备注显示引导） */
    private fun paintRemark(tv: TextView, remark: String) {
        tv.text = if (remark.isBlank()) "✎ 添加备注" else "✎ 备注：" + remark
        tv.setTextColor(if (remark.isBlank()) 0xFFB0B0B0.toInt() else Ui.TEXT)
    }

    /**
     * v2.30：备注输入页 / 待确认页保存备注后，若这张卡还在屏幕上且是同一笔，实时刷新文案。
     * 用 cardRecId 而不是 lastCardId：后者在「已记录」模式下不会被设置。
     */
    fun refreshRemark(app: Context, id: Long, text: String) {
        Diag.logCard(app, "备注已保存 #$id → ${if (text.isBlank()) "(空)" else text.take(40)}")
        handler.post {
            if (id <= 0L || id != cardRecId) return@post
            val v = card ?: return@post
            val tv = v.findViewById<View>(R.id.tvRemark) as? TextView ?: return@post
            paintRemark(tv, text)
        }
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
        income: Boolean,
        hint: String = ""
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
                // v2.40：卡片没弹出来时，把原因直接写进通知。
                // 这样用户不必再导诊断日志就能知道该去开哪个权限 —— 之前这条信息只存在于 logcat/文件里，
                // 用户看到的现象永远是「只有通知、没有卡片」，只能来回猜。
                .setContentText(
                    "$note · 分类：$cat" +
                        if (hint.isEmpty()) " · 点此确认" else "｜$hint"
                )
                // 补写原因属于「更新同一条通知」，不能再次响铃震动
                .setOnlyAlertOnce(hint.isNotEmpty())
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
