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
        private val BURST_DELAYS = longArrayOf(0, 200, 500, 1000, 1800, 3000, 4500, 6500, 9000, 12000)

        /** 内容变化事件的节流间隔 */
        private const val CONTENT_THROTTLE = 700L

        /**
         * v2.16：已登记消费类 App（支付宝/微信/QQ…）在内容变化后走的补扫阶梯。
         *
         * 旧版内容变化只补扫两次（150ms / 1000ms），并且之后页面静止就不再有任何扫描。
         * 微信的内嵌浏览器（X5）、公众号页、小程序容器是**分段渲染**的：
         * 骨架先出、标题再出、金额往往 1.5~3 秒后才画出来，之后不再产生事件。
         * 于是「弹不弹」完全取决于当时加载快慢 —— 这就是微信端时有时无的主要成因。
         * 阶梯覆盖到 6 秒，拿到即停（hitToken 拦住后续），既稳又不空转。
         */
        private val LADDER_DELAYS = longArrayOf(150, 500, 1000, 1800, 2800, 4000, 6000)

        /** 阶梯启动的最小间隔：内容变化很密时不叠加任务，避免主线程堆积 */
        private const val LADDER_GAP = 1200L

        /**
         * v2.17：页面驻留轮询节拍与次数。
         *
         * 这是 v2.17 的核心改动 —— 把「事件时机驱动」换成「页面驻留 + 固定节拍」。
         *
         * v2.16 及以前的做法是：收到事件 → 排一串延迟任务 → 之后靠 burstToken 作废。
         * 问题在于微信的账单页/WebView **在加载过程中会连续抛 WINDOW_STATE_CHANGED**
         * （子窗口、弹层、输入法、Toast 窗口都算），每抛一次 burstToken++ 就把
         * 刚排好的任务和 6 秒阶梯全部作废。于是：
         *   · 页面加载快 → 恰好在两次事件的空档里扫到 → 弹了；
         *   · 页面加载慢 → 一直在自废武功 → 永远扫不到 → 不弹。
         * 这就是「7-8 笔里只有 2 笔弹、接着测全不弹」的真实成因。
         * 支付宝账单页是稳定的单 Activity，不这么乱抛事件，所以一直稳。
         *
         * 现在改为：只要有事件来，就保证节拍器在跑，每 [POLL_INTERVAL] 读一次屏，
         * 事件持续来就持续续期（最多 [POLL_TICKS] 拍）。不再依赖「哪一次事件碰巧赶上渲染完成」。
         */
        private const val POLL_INTERVAL = 700L

        /** 节拍器最多跑多少拍（≈ 700ms × 18 ≈ 12.6 秒，覆盖微信 H5 慢加载） */
        private const val POLL_TICKS = 18

        /**
         * 同一屏文字在这段时间内只判一次。
         * 防止节拍器在页面静止时反复把同一笔入库；内容一变（切到下一笔账单）就重新判定。
         */
        private const val SAME_SIG_SKIP_MS = 1200L

        /** 滑动事件的节流间隔（v2.16：WebView 页面滑动后内容才真正可读） */
        private const val SCROLL_THROTTLE = 800L

        /**
         * v2.16：同一笔卡片的去重窗口。
         * 旧逻辑是「同一 id 永不重弹」，用户重新打开同一笔账单时只响通知、不弹卡 ——
         * 观感上就是「有时弹有时不弹」。现在改为：进入新页面允许重弹，但 5 秒内不重复闪。
         */
        private const val CARD_DEDUP_MS = 5000L

        /**
         * v2.16：这些 App 会把「聊天窗口」和「当前页面」同时挂在 windows 里，
         * 两者包名相同。合并多窗口文本会把聊天内容（含交易卡片文字）拼进账单页，
         * 造成 v2.14 那类跨消息块串味，所以只做逐窗口判定、不做合并兜底。
         */
        private val NO_MERGE_PKGS = setOf("com.tencent.mm", "com.tencent.mobileqq")

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

        /**
         * 这些文本不可能是商户/商品名（v2.8）：
         * 网速「92KB/s」、流量「1.5GB」、电量「80%」、纯数字、底部导航词。
         *
         * 之前备注取「金额上方最近的文本」时没有任何过滤，状态栏的网速被当成商户名记进了账。
         */
        private val TEXT_NOISE_UNITS = listOf(
            "KB", "MB", "GB", "TB", "/s", "%", "‰", "℃", "dB", "mAh", "Hz", "ms"
        )
        private val TEXT_NOISE_WORDS = listOf(
            "首页", "我的", "消息", "搜索", "购物车", "推荐", "发现", "设置",
            "返回", "确定", "取消", "更多", "全部", "查看", "加载"
        )
        private val NOISE_SHAPE = Regex("^[\\d.,:：\\s\\-+/%]+$")

        /** 取消后同金额的抑制窗口：覆盖页面停留/划动重扫的场景，又不至于把下一笔真实同金额消费挡太久 */
        private const val CANCEL_SUPPRESS_MS = 5 * 60_000L

        /**
         * v2.12：「刚刚发生」的判定窗。页面上的付款/交易时间比这更早 → 是在浏览历史账单，不记。
         * 支付是有过程的行为（输金额→收银台→密码→成功页），10 分钟足以覆盖正常支付全程。
         */
        private const val FRESH_WINDOW_MS = 10 * 60_000L

        /**
         * v2.19：当前活着的无障碍服务实例。
         *
         * 用途：让**通知通道也能弹同一套卡片**。
         * 旧版只有无障碍通道弹卡，通知通道只发通知栏提醒 ——
         * 同一笔消费若由通知通道记下（常见于页面一闪而过、或页面文本没读到的场景），
         * 用户就「只听到铃声响、没看到卡片」，表现出来正是「有的弹有的没弹」。
         */
        @Volatile
        private var live: BillAccessibilityService? = null

        /** 通知通道调用：复用无障碍服务的卡片呈现（含确认/取消/改分类三个按钮） */
        fun presentCard(
            ctx: android.content.Context,
            id: Long, amount: Double, note: String, cat: String, income: Boolean
        ): Boolean {
            val s = live
            if (s == null) {
                Diag.logCard(ctx, "跳过：无障碍服务未运行，无法弹卡（已存入待确认，改由通知栏提醒）")
                return false
            }
            s.showCard(id, amount, note, cat, income)
            return true
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** burst 代次：新的页面切换会自增，旧代次的定时任务自动作废 */
    private var burstToken = 0

    /** 本代次已成功抓取，后续重试不再执行 */
    private var hitToken = -1

    private var lastContentAt = 0L
    private var lastLadderAt = 0L
    private var lastScrollAt = 0L

    /** v2.17 节拍器状态：当前轮询的包名与剩余拍数 */
    private var pollPkg: String? = null
    private var pollLeft = 0

    /** v2.17：上一屏文字指纹与判定时间（同屏不重复判、内容变即重判） */
    private var lastSig = ""
    private var lastSigAt = 0L
    private var lastAmount = -1.0
    private var lastAmountAt = 0L
    private var lastCardAt = 0L

    /** 用户在弹窗上点过「取消」的笔：5 分钟内同金额不再重复弹/记（v2.10） */
    private var lastCancelledAmount = -1.0
    private var lastCancelledAt = 0L

    private val suspectLogged = HashMap<String, Long>()
    private val skipLogged = HashMap<String, Long>()
    private val labelCache = HashMap<String, String>()

    private var card: View? = null
    private var lastCardId = -1L

    private val hideTask = Runnable { removeCard() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
        Diag.log(this, "无障碍服务已连接（v2.19 双通道弹窗版）")
    }

    // ---------------- 事件分派 ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg == "com.android.systemui" || pkg == "android") return
        // v2.16/2.17：本阶段停用的平台（淘宝/京东/拼多多等）在入口就返回，
        // 不为它们起节拍器、不做任何读屏，彻底「屏蔽已有逻辑」。
        if (PayApps.isDisabledPkg(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 新窗口 = 新页面：允许同一笔重新弹一次（真正的防抖交给 showCard 的时间窗）
                lastCardId = -1L
                burstToken++
                // 立刻扫一次（post 到主线程队列，不阻塞事件回调——微信加载期窗口事件很密），
                // 再交给节拍器兜住后续渲染
                val tok = burstToken
                handler.post { runScan(pkg, tok) }
                ensurePoll(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentAt < CONTENT_THROTTLE) return
                lastContentAt = now
                // v2.17：不再用 burstToken 作废任务。微信加载期会连续抛窗口状态变化，
                // 「作废」机制会让扫描永远赶不上渲染完成 —— 这是时有时无的根因。
                // 改为「有事件就续期节拍器」，扫描节奏与事件时机解耦。
                ensurePoll(pkg)
            }

            // 滑动事件：微信 H5/小程序账单页首次渲染常在滑动之后才把金额挂进无障碍树。
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val now = System.currentTimeMillis()
                if (now - lastScrollAt < SCROLL_THROTTLE) return
                lastScrollAt = now
                ensurePoll(pkg)
            }
        }
    }

    // ---------------- 页面驻留节拍器（v2.17） ----------------

    /**
     * 节拍器：页面驻留期间每 [POLL_INTERVAL] 读一次屏，最多 [POLL_TICKS] 拍。
     *
     * 与旧机制的根本区别：不再赌「哪一次事件赶上了渲染完成」，
     * 只要页面还在动（持续有事件），就按固定节奏一直试，命中或超时才停。
     * 微信端从「看运气」变成「必然能中」，靠的就是这一层。
     */
    private val pollTick = object : Runnable {
        override fun run() {
            val p = pollPkg ?: return
            if (pollLeft <= 0) {
                pollPkg = null
                return
            }
            pollLeft--
            runScan(p, burstToken)
            if (pollLeft > 0) handler.postDelayed(this, POLL_INTERVAL) else pollPkg = null
        }
    }

    /**
     * 保证节拍器在跑，并把剩余拍数续满（每次有事件都续期）。
     *
     * 只对已登记的消费类 App 生效：未登记 App 不轮询，保持低开销。
     * 这不是「一直读屏」——没有事件就不会启动，页面静止后最多再跑 12 秒就自己停。
     */
    private fun ensurePoll(pkg: String) {
        val app = PayApps.of(pkg) ?: return
        if (!app.enabled) return
        val was = pollPkg
        pollPkg = pkg
        pollLeft = POLL_TICKS
        if (was != pkg) {
            handler.removeCallbacks(pollTick)
            handler.post(pollTick)
        }
    }

    // ---------------- 扫描 ----------------

    /** 一个窗口里读到的同包文本（v2.16）。active = 活动窗口 */
    private class PageGroup(val texts: ArrayList<String>, val active: Boolean)

    private fun runScan(pkg: String, token: Int) {
        try {
            val foreign = LinkedHashSet<String>()
            val groups = grabGroups(pkg, foreign)
            // v2.19：未登记的 App（桌面/输入法/智能助理/安全服务…）不写这两类日志。
            // 旧版它们在用户测试期间刷了几十条，「最近 40 条」里真正有价值的支付宝/微信记录被整屏挤掉。
            val registered = PayApps.of(pkg) != null
            if (foreign.isNotEmpty() && registered) {
                // 排查「读到别家文字」用，同一组合 60 秒只记一次
                logSkip("foreign#$pkg#${foreign.joinToString(",")}",
                    "已丢弃非本页文字：${foreign.joinToString(", ")}（当前 ${appLabel(pkg)}）")
            }
            if (groups.isEmpty() && registered) {
                // 微信 H5/小程序常见：这一刻页面还没渲染出可读文本，交给节拍器继续试
                logSkip("$pkg#nodata", "${appLabel(pkg)} → 本次未读到本 App 的窗口文本（可能仍在渲染），稍后重试")
            }

            // v2.17：内容指纹节流 —— 同一屏文字在 [SAME_SIG_SKIP_MS] 内只判一次。
            // 节拍器 700ms 一拍，页面静止时会反复读到同样内容；不节流会把同一笔反复判定。
            // 内容一变（滑到 / 切到下一笔账单）指纹立刻变化 → 立即重新判定，
            // 这样「连续打开 7-8 笔账单」每一笔都会各自走一遍闸门，不再被上一笔的状态卡住。
            val sig = groups.joinToString("|") { it.texts.joinToString("\u0001") }
            val sigNow = System.currentTimeMillis()
            if (sig == lastSig && sigNow - lastSigAt < SAME_SIG_SKIP_MS) return
            lastSig = sig
            lastSigAt = sigNow
            // v2.16 核心：**逐窗口独立判定**。
            // 微信打开账单页时，聊天窗口与本页可能同时挂在 windows 里且包名相同，
            // 合并后的文本既含聊天输入栏（命中排除词）又含聊天里的交易卡片（命中支付词），
            // 于是整页判定结果随窗口存续时机漂移 —— 这就是支付宝稳定、微信时有时无的结构性原因。
            // 现在每个窗口单独跑一遍闸门链，活动窗口优先，任一窗口判定通过即记账。
            for (g in groups.sortedByDescending { it.active }) {
                if (attempt(pkg, g.texts, token)) {
                    hitToken = token
                    return
                }
            }
            // 合并兜底：支付宝的账单内容常挂在独立 window 上，单窗口取不全 —— 与 v2.8 行为一致。
            // 微信/QQ 例外（NO_MERGE_PKGS）：合并会把聊天窗口文字混进账单页，宁可漏记也不串味。
            if (groups.size > 1 && pkg !in NO_MERGE_PKGS) {
                val merged = LinkedHashSet<String>()
                groups.forEach { merged.addAll(it.texts) }
                if (attempt(pkg, ArrayList(merged), token)) {
                    hitToken = token
                    return
                }
            }

            // v2.9：事件的 packageName 未必是页面真正所在的 App。
            // MIUI 上指纹支付框、权限弹窗、侧边栏抢走 WINDOW_STATE_CHANGED 时，
            // 微信/支付宝自己的窗口会被当成「非本页文字」全部丢弃 → 表现为微信消费不识别。
            // 被丢弃的窗口里若有已登记的消费类 App，就用那个包名再扫一次（该 App 自己的窗口）。
            val cands = foreign.filter { it != pkg && PayApps.of(it) != null }
            for (p in cands) {
                val f2 = LinkedHashSet<String>()
                for (g in grabGroups(p, f2).sortedByDescending { it.active }) {
                    if (attempt(p, g.texts, token)) {
                        hitToken = token
                        Diag.log(this, "按窗口归属重扫命中：$p")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Diag.log(this, "扫描异常：${e.javaClass.simpleName}")
        }
    }

    /** 对给定包名的页面文本跑完整闸门，命中记账返回 true */
    private fun attempt(pkg: String, texts: ArrayList<String>, token: Int): Boolean {
        try {
            // 门槛不能太高：支付宝付款结果页这类页面往往只有「支付成功」「¥32.50」「完成」两三条文本，
            // 卡在 3 条会整页读不到。
            if (texts.size < 2) return false
            // 排除本应用自己的界面（明细/待确认里的文字）
            if (texts.any { it.contains("自动记账") || it.contains("本月预算") }) return false

            val app = PayApps.of(pkg)
            // v2.16：购物平台（淘宝/天猫/京东/拼多多等）本阶段不接入识别与弹窗。
            // 放在所有闸门之前：既不做判定，也不落待确认、不弹卡片、不发通知。
            if (PayApps.isDisabled(app)) {
                logSkip("$pkg#off", "${app?.name ?: pkg} → 跳过：该平台本阶段不接入弹窗（v2.16 范围限制）")
                return false
            }
            if (PayApps.isExcluded(texts, app)) {
                logSkip("$pkg#excl", "${app?.name ?: appLabel(pkg)} → 跳过：命中排除词（聊天窗口等非账单界面）")
                return false
            }
            if (!PayApps.hasTitle(texts, app)) {
                if (app == null) noteSuspect(pkg, texts)
                return false
            }
            // 未注册的 App：必须有方向佐证才敢认定，避免"订单详情"浏览页误抓
            if (app == null && !PayApps.hasExpenseSignal(texts) && !PayApps.hasIncomeSignal(texts)) return false

            val name = app?.name ?: appLabel(pkg)

            // 闸门一·补四（v2.18）：首页 / 信息流 / 消息卡片页。
            //
            // v2.18 实测事故：支付宝首页「最近消息」卡片里躺着两条真实服务消息
            // 「万家乐百货 付款成功 ¥4.00」「*明 付款成功 ¥50.00」，
            // 页面因此凑齐标题/已扣款/金额/方向四道闸门，整页被记成「-¥4.00 蚂蚁保」。
            //
            // [PayApps.looksLikeHome] 在这里拦不住：它的短路条件是「页面出现支付证据行就当结果页」，
            // 本意是给电商支付成功页放行（那类页面底部同样有首页/我的/购物车导航），
            // 但首页消息卡片里的「付款成功 ¥4.00」也是真实证据行，首页判定被整体短路。
            // 本闸门改用形态特征（相对时间小字 + 首页导航 + 无账单字段），不受该短路影响。
            if (PayApps.looksLikeHomeFeed(texts)) {
                logSkip("$pkg#feed", "$name → 跳过：首页/信息流/消息卡片页（带相对时间标注、无账单字段），不是某笔账单")
                return false
            }

            // 闸门一（v2.8）：页面形态是首页 / 列表页，一律不记。
            // 12306 首页有「我的行程 / 行程详情」入口，正好命中票务类标题词，光靠标题词挡不住。
            if (PayApps.looksLikeHome(texts)) {
                logSkip("$pkg#home", "$name → 跳过：页面形态是首页/列表页，不是某笔账单")
                return false
            }

            // 闸门一·补（v2.8）：一屏多笔（3 个以上金额 + 2 个以上单号）= 订单列表，不是某一笔账单
            if (PayApps.looksLikeList(texts)) {
                logSkip("$pkg#list", "$name → 跳过：这一屏是多笔订单的列表，不是单笔账单")
                return false
            }

            // 闸门一·补二（v2.14）：消息流/会话页（微信支付会话等），一屏混着多条交易消息。
            // 金额/商户/方向会跨消息块拼接（v2.14「收入 +1.30 拼多多」事故），浏览一律不记。
            if (PayApps.looksLikeMessageStream(texts)) {
                logSkip("$pkg#stream", "$name → 跳过：消息流/会话页（多条交易消息混排），浏览不记账")
                return false
            }

            // 闸门一·补三（v2.15）：营销门户/活动会场页（支付宝芝麻信用等）。
            // 该类页面满屏额度、特权、领券、免押租机广告，广告价签+扣款类广告词
            // 会把标题/支付状态/金额/方向四道闸门全部凑齐（-¥37.26「挪了个箭」事故）。
            // 没有账单结构字段、没有强标签金额、没有结果页出口词的营销页，浏览一律不记。
            if (PayApps.looksLikePortal(texts)) {
                logSkip("$pkg#portal", "$name → 跳过：营销门户/活动会场页（无账单结构字段），浏览不记账")
                return false
            }

            // 闸门二（v2.6）：必须是「已经扣款」的页面。
            // 收银台、输入金额页同样有「实付款 ¥89.00」这类强标签金额，不拦住就会在付款前先记一笔，
            // 付款成功后再记一笔 —— 用户看到的「一次支付识别两次」就是这个。
            //
            // v2.8 去掉了 v2.7 的「金额强度≥105 就当已支付」兜底：
            // 那条兜底让「标题像账单 + 页面上有个看着像金额的数」就能记账，
            // 首页广告位、行程卡片都可能满足。现在只认成功词或账单结构字段。
            val state = PayApps.payState(texts)

            // 闸门三（v2.8）：只命中弱标题词（订单详情 / 行程详情 / 交易记录…）时，
            // 必须另有账单结构字段（时间 / 单号 / 分类），否则订单列表、行程卡片都能凑巧满足。
            if (PayApps.onlyWeakTitle(texts, app) && !PayApps.hasBillStructure(texts)) {
                logSkip("$pkg#weak", "$name → 跳过：只有弱标题词且无账单字段（时间/单号/分类），按列表页处理")
                return false
            }

            if (state != PayApps.STATE_PAID) {
                val v = PayApps.findAmount(texts)?.value
                if (state == PayApps.STATE_UNPAID) {
                    logSkip("$pkg#$state#$v",
                        "$name ¥${v ?: "-"} → 跳过：还在支付流程里（输金额/收银台/等密码），钱未扣")
                } else {
                    logSkip("$pkg#$state",
                        "$name → 跳过：页面上没有支付完成证据，按浏览页处理")
                }
                return false
            }

            // 闸门四（v2.12）：只认「刚刚发生」的支付，浏览历史订单一律不记。
            // 历史订单详情页与支付成功页结构完全相同（交易成功 + 实付款 + 订单编号），
            // 结构闸门分不出来；唯一可靠的区别是时间。
            // ① 页面能解析出时间、且最新时间早于 10 分钟前 → 历史账单。
            //    取最新时间：补付款的订单页同时有创建时间（几天前）和付款时间（此刻），取最新才不会误杀。
            // ② 「再次购买」按钮只出现在已完成的旧订单上，刚付完的结果页不会有。
            //
            // v2.13 豁免：支付工具（支付宝/微信/QQ）的单笔「账单详情」本质是事后核对页，
            // 支付成功页几秒就关，用户靠它回看每一笔 —— 一刀切拦截正是「微信账单详情不弹窗」的
            // 根因之一。豁免后靠「页面付款时间 + 30 分钟同额查重」防与成功页那一次重复。
            // 电商「订单详情」不在豁免范围，v2.12 修的误记账照旧拦截。
            if (!PayApps.isWalletBillDetail(texts, app)) {
                val latest = PayApps.latestParsedTime(texts)
                if (latest != null && latest < System.currentTimeMillis() - FRESH_WINDOW_MS) {
                    val whenStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date(latest))
                    logSkip("$pkg#history", "$name → 跳过：页面时间是 $whenStr（10 分钟前），这是在浏览历史账单，不是刚完成的支付")
                    return false
                }
                if (texts.any { it.contains("再次购买") }) {
                    logSkip("$pkg#oldorder", "$name → 跳过：页面带「再次购买」按钮，是历史订单详情，不是刚完成的支付")
                    return false
                }
            }

            return scan(pkg, texts, app)
        } catch (e: Exception) {
            Diag.log(this, "扫描异常：${e.javaClass.simpleName}")
            return false
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

        // 收支方向：这一笔是钱出去还是钱进来。
        // 旧逻辑只在「金额行没有强标签」时才看收入词；金额行一旦命中「实付款/支付金额/合计」
        // 就直接按支出入库 —— 收入账单里的「金额 ¥500.00」正好走这条路径，于是收入被记成支出。
        val dir = PayApps.direction(texts, amt)
        if (dir == PayApps.DIR_UNKNOWN) {
            Diag.log(
                this,
                "$appName ¥$amount → 跳过：看不出收支方向（${texts.take(10).joinToString("/").take(140)}）"
            )
            return false
        }
        val income = dir == PayApps.DIR_INCOME

        // 商户名取值顺序（v2.18）：
        // ① 页面有「商户/收款方」字段 → 直接用；
        // ② 金额那一行本身就带商户名（「万家乐百货 付款成功 ¥4.00」这种消息/条目形态）→ 剔除金额与状态词后取；
        // ③ 都没有才回落「金额上方最近的文本」。
        // 第 ② 步是 v2.18 新增：只靠 ③ 时，金额节点的上一个邻居可能是个毫不相干的功能入口，
        // 首页消息卡片被记成「蚂蚁保」就是这么来的。
        val merchant = PayApps.fieldOf(texts, PayApps.MERCHANT_KEYS)
            ?: PayApps.merchantInLine(texts.getOrNull(amt.idx) ?: "")
            ?: goodsNear(texts, amt.idx, appName, income)
        val goods = PayApps.fieldOf(texts, PayApps.GOODS_KEYS) ?: ""
        val catHint = PayApps.fieldOf(texts, PayApps.CAT_KEYS) ?: ""
        // 入账时间优先用页面上的付款/交易时间：账单详情是事后核对页，
        // 页面时间才是这笔钱真实发生的时间（v2.13）。
        val pageTs = PayApps.TIME.find(joined)?.groupValues?.getOrNull(1)
            ?.let { PayApps.parseTime(it) }
        val ts = pageTs ?: System.currentTimeMillis()

        val cat = if (income) Category.incomeOf(merchant, goods, catHint, appName)
        else Category.of(merchant, goods, catHint, appName)
        val type = if (income) 1 else 0
        val note = if (goods.isNotBlank() && goods != merchant) "$merchant·$goods" else merchant

        // 同一笔在多次重试之间保持幂等。
        // v2.17：从 8 秒收紧到 2 秒 —— 同屏重复扫描已由内容指纹节流挡住，
        // 8 秒会误杀「几秒内连续发生的两笔同额消费」（连续测试时尤其明显）。
        val now = System.currentTimeMillis()
        if (amount == lastAmount && now - lastAmountAt < 2000) return false
        // 用户明确取消过的笔：5 分钟内页面再触发扫描也不重复弹/记。
        // 不加这道闸，取消后留在账单详情页随手一划，同笔会重新入库再弹一次。
        if (amount == lastCancelledAmount && now - lastCancelledAt < CANCEL_SUPPRESS_MS) {
            Diag.log(this, "$appName ¥$amount → 跳过：用户已取消这笔，${CANCEL_SUPPRESS_MS / 60000}分钟内不再重复弹")
            return false
        }

        val db = DbHelper(this)
        try {
            // 一笔支付只允许落一条记录。
            // 同一笔可能在「结果页」和「账单详情」各被识别一次（两个页面文字不同、商户名不同），
            // 也可能页面和通知各命中一次 —— 所以按「金额 + 时间窗」收敛，不按商户名比对。
            // v2.13：页面带付款时间（账单详情）时用 30 分钟窗 —— 详情页是事后打开的，
            // 按页面付款时间查重仍能对上成功页那一次；页面无时间的支付现场仍用 90 秒。
            val win = if (pageTs != null) PayApps.MERGE_WINDOW_DETAIL_MS else MERGE_WINDOW
            val prev = db.recentSameAmount(amount, ts, win)
            if (prev != null) {
                if (prev.status == 0) {
                    // 先前那条还没确认：用本次更完整的信息覆盖（商品名通常只有结果页才有），不新增、不重复弹卡片。
                    // 方向也一并覆盖 —— 先前那条可能是被误判成支出的收入。
                    val merged = betterNote(prev.note, note)
                    db.updateRecordFull(prev.id, amount, type, cat, merged, ts, 0)
                    lastAmount = amount
                    lastAmountAt = now
                    Diag.log(
                        this,
                        "$appName ¥$amount（${if (income) "收入" else "支出"}）→ 同一笔已合并到待确认 #${prev.id}（$cat）"
                    )
                    // v2.13：合并后仍要弹卡。典型场景：支付成功时通知通道已记下待确认（只发了通知栏提醒），
                    // 用户事后打开这笔的账单详情 —— 这是他主动打开的核对页，必须把确认卡片弹出来，
                    // 否则那笔待确认就一直挂着没人管，用户看到的就是「打开账单详情什么反应都没有」。
                    // showCard 内部按 lastCardId 去重：支付现场的连续重扫不会因此反复闪卡。
                    showCard(prev.id, amount, merged, cat, income)
                    return true
                }
                // 已确认入账的：不弹卡也不重复记，Toast 一句让用户知道 App 看到了这页、
                // 只是这笔已经在账本里 —— 否则表现上和「完全没识别」无法区分。
                Diag.log(this, "$appName ¥$amount → 跳过：该笔已确认入账（#${prev.id}），不重复记账")
                Toast.makeText(
                    this,
                    "该笔已在账本中：" + (if (income) "收入 " else "支出 ") + "¥" + Ui.money(amount) + "，不重复记账",
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }
            val id = db.addRecord(
                Rec(
                    amount = amount, type = type, cat = cat, note = note, ts = ts,
                    book = bookOf(), status = 0, src = appName
                )
            )
            if (id <= 0) return false
            lastAmount = amount
            lastAmountAt = now
            Diag.log(
                this,
                "$appName ¥$amount $note → 已存为待确认（${if (income) "收入" else "支出"}·$cat，金额强度 ${amt.score}）"
            )
            showCard(id, amount, note, cat, income)
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
     * 读当前页面文字：活动窗口 + 同一 App 的其他窗口。
     *
     * v2.8 的关键修正：**必须按包名过滤**。
     *
     * v2.7 为了读到支付宝挂在独立 window 上的账单内容，改成了「合并所有窗口」，
     * 但只排除了 systemui 与本应用 —— 于是状态栏/网速悬浮窗的「92KB/s」、输入法、
     * 其他 App 的悬浮通知文字全被拼进了页面文本：
     *   ① 备注取了金额上方最近的文本，于是把「92KB/s」当成商户名；
     *   ② 别的窗口若带「已支付 / 支付成功」，会让首页通过"已扣款"闸门，于是凭空记一笔。
     * 现在只收 root.packageName 与当前 App 一致的窗口，其余（系统界面、输入法、悬浮窗、
     * 其他 App）一律丢弃。支付宝/电商的独立 window 包名仍是自身，不影响读取。
     *
     * @param pkg 触发事件的包名
     * @param foreign 输出：被丢弃的外来包名，用于诊断日志
     */
    private fun grabGroups(pkg: String, foreign: MutableSet<String>): List<PageGroup> {
        val out = ArrayList<PageGroup>()
        // 活动窗口常同时出现在 windows 列表里，不去重会把同一页判两遍；
        // 用文本签名去重（窗口 id 在不同 Android 版本上不稳定）。
        val seen = HashSet<String>()
        try {
            val root = rootInActiveWindow
            val rp = root?.packageName?.toString()
            if (root != null && (rp == null || rp == pkg || rp == packageName)) {
                val set = LinkedHashSet<String>()
                collect(root, set)
                if (set.isNotEmpty()) {
                    val sig = set.joinToString("\u0001")
                    if (seen.add(sig)) out.add(PageGroup(ArrayList(set), true))
                }
            }
        } catch (_: Exception) {
        }
        try {
            windows?.forEach { w ->
                val r = w?.root ?: return@forEach
                val p = r.packageName?.toString()
                if (p == null) return@forEach
                if (p == "com.android.systemui" || p == "android") return@forEach
                if (p == packageName) return@forEach
                if (p != pkg) {
                    if (foreign.size < 6) foreign.add(p)
                    return@forEach
                }
                val set = LinkedHashSet<String>()
                collect(r, set)
                if (set.isNotEmpty()) {
                    val sig = set.joinToString("\u0001")
                    if (seen.add(sig)) out.add(PageGroup(ArrayList(set), false))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        node ?: return
        if (out.size > MAX_TEXTS) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        // 部分 App（含自绘/电商页）把文字放在 contentDescription 里，只读 text 会漏
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    private fun goodsNear(texts: List<String>, idx: Int, appName: String, income: Boolean): String {
        for (j in (idx - 1) downTo maxOf(0, idx - 5)) {
            val s = texts[j].trim()
            if (s.length !in 2..24) continue
            // v2.8：先把「不像名字」的文本全部否掉，否则网速、百分比、纯数字都会被当商户名
            if (isNoiseText(s)) continue
            if (!PayApps.hasAmountIn(s) &&
                PayApps.TIME.find(s) == null &&
                !PayApps.hasExpenseSignal(listOf(s)) &&
                FIELD_NOISE.none { s.contains(it) } &&
                !s.contains("账单") && !s.contains("详情")
            ) return s
        }
        return appName + if (income) "收入" else "消费"
    }

    /** 这段文字不可能是商户 / 商品名 */
    private fun isNoiseText(s: String): Boolean {
        if (NOISE_SHAPE.matches(s)) return true
        if (s.contains("/")) return true
        if (TEXT_NOISE_UNITS.any { s.contains(it) }) return true
        if (TEXT_NOISE_WORDS.any { s == it || s.startsWith(it) }) return true
        // v2.18：首页/服务页的功能入口名（蚂蚁保、蚂蚁森林、市民中心…）不是商户名
        if (PayApps.isEntryNoise(s)) return true
        // 数字开头 + 字母单位（92KB、1.5G、3ms…）
        if (Regex("^[\\d.,]+\\s*[A-Za-z%/]+.*$").containsMatchIn(s)) return true
        return false
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
    private fun showCard(id: Long, amount: Double, note: String, cat: String, income: Boolean) {
        // v2.16：去重判断放在通知之前。
        // 旧顺序是「先发通知、再判断是否弹卡」，同一笔被重复识别时会出现
        // 「铃声响了、卡片没出来」—— 用户侧就是「时有时无」。
        val now = System.currentTimeMillis()
        if (id == lastCardId && now - lastCardAt < CARD_DEDUP_MS) {
            // v2.19：这里以前是静默 return，导致「明明识别到了却不弹」无法回溯
            Diag.logCard(this, "跳过重复弹卡 #$id ¥$amount（${now - lastCardAt}ms 内已弹过同一笔）")
            return
        }
        lastCardId = id
        lastCardAt = now
        Diag.logCard(this, "弹卡 #$id ¥$amount $note（${if (income) "收入" else "支出"}·$cat）")

        // 通道一：通知栏。
        // 原因：小米/红米（MIUI/HyperOS）的「后台弹出界面」权限被拒时，WindowManager.addView 不报错、
        // 只是窗口不可见，代码会误判为"已显示"。通知不受该权限限制，是唯一可靠通道，故作为主通道。
        notifyPending(id, amount, note, cat, income)

        handler.post {
            try {
                buildCard(id, amount, note, cat, income)
            } catch (e: Exception) {
                Diag.logCard(this, "悬浮卡片构建失败（${e.javaClass.simpleName}）")
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
    private fun buildCard(id: Long, amount: Double, note: String, cat: String, income: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            Diag.logCard(this, "跳过：悬浮窗权限未开（通知已发，可在通知栏确认）")
            return
        }
        handler.removeCallbacks(hideTask)
        removeCard()

        val v = android.view.LayoutInflater.from(this).inflate(R.layout.view_card, null)
        (v.findViewById<View>(R.id.tvHead) as TextView).text =
            if (income) "自动识别到收入" else "自动识别到消费"
        (v.findViewById<View>(R.id.tvAmount) as TextView).apply {
            text = (if (income) "+¥" else "-¥") + Ui.money(amount)
            setTextColor(if (income) Ui.GREEN else Ui.TEXT)
        }
        (v.findViewById<View>(R.id.tvNote) as TextView).text = note
        (v.findViewById<View>(R.id.tvCat) as TextView).text = "分类：" + cat
        (v.findViewById<View>(R.id.tvIcon) as TextView).text = emojiOf(cat)

        v.findViewById<View>(R.id.btnCancel).setOnClickListener {
            // 取消：删掉已入库的待确认记录 + 撤掉通知栏提醒，不保留任何数据，无需二次确认。
            // 误触的代价可控：明细里可手动补记；且 5 分钟内同笔不会因页面重扫而再次弹出。
            try {
                val db = DbHelper(this)
                db.delRecord(id)
                db.close()
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(id.toInt())
                lastCancelledAmount = amount
                lastCancelledAt = System.currentTimeMillis()
                Diag.log(this, "用户取消：¥$amount $note 已删除待确认记录 #$id，不记账")
            } catch (e: Exception) {
                Diag.log(this, "取消失败：${e.javaClass.simpleName}")
            }
            handler.removeCallbacks(hideTask)
            removeCard()
            Toast.makeText(this, "已取消，不记这笔", Toast.LENGTH_SHORT).show()
        }
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
            Toast.makeText(
                this,
                (if (income) "已记收入：" else "已记账：") + "$cat ¥" + Ui.money(amount),
                Toast.LENGTH_SHORT
            ).show()
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
            Diag.logCard(this, "卡片已 addView（通知同时已发）")
        } catch (e: Exception) {
            Diag.logCard(this, "addView 失败（${e.javaClass.simpleName}），通知已发。小米/红米请确认「后台弹出界面」权限")
            return
        }
        // 记录真实渲染尺寸：万一又变成一个小块，这行日志能直接看出宽高异常
        v.postDelayed({
            Diag.logCard(this, "卡片尺寸 " + v.width + "x" + v.height +
                "，屏幕宽 " + resources.displayMetrics.widthPixels +
                (if (v.width == 0) " ← 宽为 0，窗口被系统拦掉了" else ""))
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

    private fun notifyPending(id: Long, amount: Double, note: String, cat: String, income: Boolean) {
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
            Diag.log(this, "已发送通知提醒：¥$amount $note")
        } catch (e: Exception) {
            Diag.log(this, "通知发送失败（${e.javaClass.simpleName}）：请检查通知权限是否开启")
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        live = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        live = null
        removeCard()
        super.onDestroy()
    }
}
