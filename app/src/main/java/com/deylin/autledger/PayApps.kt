package com.deylin.autledger

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 消费类应用注册表 + 通用账单页识别规则。
 *
 * 把「哪些 App 算消费类」「怎样才算账单页」「金额怎么认」三件事集中在这里，
 * 无障碍服务与通知监听共用同一套规则。
 *
 * 扩展方式：新增一个 App 只需在 [APPS] 里加一行（包名 + 中文名 + 该 App 页面的标题特征词），
 * 不用改动扫描流程；完全没注册过的 App 还有 [COMMON_TITLE_WORDS] 通用兜底。
 */
object PayApps {

    /**
     * 同一笔消费被判为「重复识别」的时间窗，页面抓取与通知监听共用。
     *
     * 支付是有过程的行为：输金额 → 收银台 → 密码/指纹 → 成功页，中间可能隔几十秒。
     * 窗口取 90 秒既能覆盖整个支付流程，又不至于把「两笔真实的同金额消费」误合并。
     */
    const val MERGE_WINDOW_MS = 90_000L


    /**
     * 一个消费类应用。
     *
     * @param titleWords   命中其一，才认为当前页面是「支付明细 / 结果页」
     * @param excludeWords 命中任一，直接否掉。用于排掉「看起来像账单页」的其他界面，
     *                     典型是微信聊天窗口 —— 微信支付服务号的聊天消息里也有「支付成功 + 金额」。
     */
    class App(
        val pkg: String,
        val name: String,
        val titleWords: List<String>,
        val excludeWords: List<String> = emptyList()
    )

    // ---- 各类 App 的标题特征词模板，避免重复书写 ----
    /** 电商 / 商城：订单详情、支付成功页 */
    private val ECOM = listOf(
        "订单详情", "订单信息", "支付成功", "付款成功", "购买成功",
        "交易成功", "已支付", "支付详情", "付款详情"
    )
    /** 出行 / 票务：订单详情、预订成功页 */
    private val TRIP = listOf(
        "订单详情", "订单信息", "支付成功", "付款成功", "预订成功",
        "购票成功", "已支付", "支付详情", "行程详情", "出票成功"
    )
    /** 外卖 / 本地生活：订单详情、支付成功页 */
    private val LOCAL = listOf(
        "订单详情", "订单信息", "支付成功", "付款成功", "支付详情", "已支付", "下单成功"
    )
    /** 支付工具：账单详情 + 付款结果页 */
    private val WALLET = listOf(
        "账单详情", "交易详情", "付款详情", "支付详情", "支付成功", "付款成功",
        "交易成功", "支付结果", "付款结果", "扣款成功", "自动扣款成功"
    )

    /**
     * 已注册的消费类应用。
     * 注意：微信刻意不包含「支付成功」——微信「微信支付」服务号的聊天窗口里也会出现
     * 「支付成功 + 金额」，若放行会把聊天消息误判成账单页。微信只认账单/交易/转账详情页。
     */
    val APPS: List<App> = listOf(
        App("com.eg.android.AlipayGphone", "支付宝", WALLET),
        App("com.taobao.taobao", "淘宝", ECOM),
        App("com.tmall.wireless", "天猫", ECOM),
        App("com.jingdong.app.mall", "京东", ECOM),
        App("com.jd.jrapp", "京东金融", ECOM),
        App("com.xunmeng.pinduoduo", "拼多多", ECOM),
        App("com.ss.android.ugc.aweme", "抖音", ECOM),
        App("com.ss.android.ugc.aweme.lite", "抖音极速版", ECOM),
        App("com.xingin.xhs", "小红书", ECOM),
        App("com.achievo.vipshop", "唯品会", ECOM),
        App("com.shizhuang.duapp", "得物", ECOM),
        App("com.taobao.idlefish", "闲鱼", ECOM),
        App("com.suning.mobile.ebuy", "苏宁易购", ECOM),
        App("com.netease.yanxuan", "网易严选", ECOM),
        App("com.sankuai.meituan", "美团", LOCAL),
        App("com.sankuai.meituan.takeoutnew", "美团外卖", LOCAL),
        App("com.dianping.v1", "大众点评", LOCAL),
        App("me.ele", "饿了么", LOCAL),
        App("com.Qunar", "去哪儿", TRIP),
        App("ctrip.android.view", "携程", TRIP),
        App("com.taobao.trip", "飞猪", TRIP),
        App("com.sdu.didi.psnger", "滴滴出行", TRIP),
        App("com.MobileTicket", "铁路12306", TRIP),
        App("com.lucky.luckyclient", "瑞幸咖啡", LOCAL),
        App("com.starbucks.cn", "星巴克", LOCAL),
        // 微信：v2.7 补上「支付成功 / 付款成功 / 支付结果」。
        // 上一版把它们去掉是为了躲开微信支付服务号的聊天窗口，但代价是真正的「支付结果页」
        // （只有 支付成功 + 金额 + 商户 + 完成，没有「账单详情」四个字）整页被判为非账单页，
        // 表现为「微信消费完全不识别、不弹窗」。现在改用 excludeWords 精确排掉聊天界面。
        App(
            "com.tencent.mm", "微信",
            listOf(
                "账单详情", "交易详情", "转账详情", "转账成功", "支付详情",
                "支付成功", "付款成功", "支付结果", "交易记录"
            ),
            listOf(
                "按住 说话", "按住说话", "发送消息", "聊天信息", "表情面板", "语音输入",
                // 「微信支付凭证」是聊天里那张卡片的标题，支付结果页不会出现；
                // 这是区分「聊天里的支付消息」与「真正的支付结果页」最稳的特征
                "微信支付凭证"
            )
        )
    )

    private val byPkg: Map<String, App> = APPS.associateBy { it.pkg }

    fun of(pkg: String): App? = byPkg[pkg]

    /**
     * 未注册 App 的通用标题词。
     * 刻意不放「支付成功」——没有 App 上下文时这四个字太容易出现在闲聊、推送里，
     * 只保留「XX详情 / XX凭证 / XX结果」这类强结构词。
     */
    val COMMON_TITLE_WORDS = listOf(
        "账单详情", "交易详情", "付款详情", "支付详情", "订单详情", "订单信息",
        "支付结果", "付款结果", "交易结果", "支付凭证", "付款凭证", "消费详情"
    )

    /** 支出方向的佐证词 */
    val EXPENSE_WORDS = listOf(
        "支出", "实付", "实付款", "付款金额", "支付金额", "扣款金额", "交易金额",
        "应付", "应付金额", "订单金额", "商品金额", "总价", "合计", "待付款",
        "付款成功", "支付成功", "扣款成功", "交易成功", "已付款", "已支付",
        "订单编号", "待发货", "待收货", "已发货", "已下单"
    )

    /** 收入 / 退款方向，命中即跳过（不放「收入」二字，避免被页面上的"收支"误杀） */
    val INCOME_WORDS = listOf(
        "退款", "退款成功", "已退款", "已存入", "已到账", "已收钱",
        "收款到账", "已收款", "入账成功"
    )

    /** 营销 / 优惠文案，这些行里的金额不是消费金额 */
    val MARKETING_WORDS = listOf(
        "优惠", "立减", "满减", "折扣", "领券", "优惠券", "红包", "返现",
        "积分", "减免", "秒杀", "限时", "会员日", "抽奖"
    )

    // ---------------- 时间 ----------------

    /** 时间串：2026-10-27 17:41:23 / 2026/10/27 17:41 / 10-27 17:41 / 10月27日 17:41 */
    val TIME = Regex(
        "((?:20\\d{2}[-/年.])?\\d{1,2}[-/月.]\\d{1,2}日?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)"
    )

    fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val raw = s.trim().replace("年", "-").replace("月", "-").replace("日", "")
            .replace("/", "-").replace(".", "-")
        val hasYear = Regex("20\\d{2}").containsMatchIn(raw)
        val fmts = if (hasYear) arrayOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"
        ) else arrayOf(
            "MM-dd HH:mm:ss", "MM-dd HH:mm"
        )
        for (f in fmts) {
            try {
                val p = SimpleDateFormat(f, Locale.CHINA)
                p.isLenient = false
                val d = p.parse(raw) ?: continue
                val cal = Calendar.getInstance()
                cal.time = d
                if (!hasYear) {
                    // 无年份：补当前年；若算出未来时间（跨年账单），回退一年
                    val now = System.currentTimeMillis()
                    cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
                    if (cal.timeInMillis > now + 24L * 3600 * 1000) {
                        cal.add(Calendar.YEAR, -1)
                    }
                }
                return cal.timeInMillis
            } catch (_: Exception) {
            }
        }
        return null
    }

    // ---------------- 金额 ----------------

    class Amt(val value: Double, val expense: Boolean, val score: Int, val idx: Int)

    private const val NUM = "\\d{1,7}(?:,\\d{3})*(?:\\.\\d{1,2})?"

    /** 强标签：订单/收银台上是"最终付款金额"的字段 */
    private val STRONG_LABELED = Regex(
        "^(?:实付款|实付金额|实付|支付金额|付款金额|扣款金额|交易金额|支出金额|在线支付|本次支付|本次付款|已付金额|应付金额)\\s*[:：]?\\s*[¥￥]?\\s*($NUM)\\s*元?$"
    )
    /** 强标签行内版：「实付款 ¥47.52（含运费 ¥10）」 */
    private val STRONG_INLINE = Regex(
        "(?:实付款|实付金额|实付|支付金额|付款金额|在线支付|本次支付)[^0-9]{0,6}($NUM)"
    )
    /** 弱标签：可能是总额，也可能不是，得分低于强标签 */
    private val WEAK_LABELED = Regex(
        "^(?:支出|付款|支付|扣款|消费|合计|总计|订单金额|应付|应付款|小计|商品总额|待付款)\\s*[:：]?\\s*[¥￥]?\\s*($NUM)\\s*元?$"
    )
    private val AMOUNT_SIGNED = Regex("^(?:-|－|\\+)\\s*[¥￥]?\\s*($NUM)\\s*元?$")
    private val AMOUNT_SYMBOL = Regex("^[¥￥]\\s*($NUM)\\s*元?$")
    private val AMOUNT_PLAIN = Regex("^(\\d{1,7}(?:,\\d{3})*[.．]\\d{1,2})\\s*元?$")
    private val CURRENCY_IN_TEXT = Regex("[¥￥]\\s*($NUM)")
    private val AMOUNT_IN_TEXT = Regex(
        "(?:支出|实付|实付款|付款|支付|扣款|消费|合计|金额)\\s*[:：]?\\s*[¥￥]?\\s*($NUM)\\s*元"
    )

    /** 标签与金额分成两个节点时用的标签白名单（电商订单页的典型排版） */
    private val STRONG_LABELS = listOf(
        "实付款", "实付金额", "实付", "支付金额", "付款金额", "扣款金额",
        "交易金额", "支出金额", "在线支付", "本次支付", "本次付款", "已付金额", "应付金额"
    )
    private val WEAK_LABELS = listOf(
        "支出", "付款", "支付", "扣款", "消费", "合计", "总计",
        "订单金额", "应付", "应付款", "小计", "商品总额", "待付款", "商品金额"
    )

    /** 无条件跳过的行：出现这些词说明这行讲的不是消费金额 */
    private val HARD_SKIP = listOf(
        "时间", "单号", "订单号", "订单编号", "余额", "次数", "笔数", "期数"
    )
    /** 有优惠/营销词的行：只有当这行没有「实付款/支付金额」这类强标签时才跳过 */
    private val SOFT_SKIP = listOf(
        "优惠", "立减", "满减", "折扣", "减免", "积分", "运费险", "手续费说明"
    )

    private fun toNum(s: String?): Double? =
        s?.replace(",", "")?.replace(" ", "")?.replace("．", ".")?.toDoubleOrNull()

    fun hasAmountIn(s: String): Boolean =
        STRONG_LABELED.containsMatchIn(s) || STRONG_INLINE.containsMatchIn(s) ||
            WEAK_LABELED.containsMatchIn(s) || AMOUNT_SIGNED.containsMatchIn(s) ||
            AMOUNT_SYMBOL.containsMatchIn(s) || AMOUNT_PLAIN.containsMatchIn(s)

    /**
     * 从页面文字里挑出消费金额。分级评分，取最高分：
     *  110 强标签整行（实付款 / 支付金额）  105 强标签行内  100 弱标签整行（合计 / 订单金额）
     *   90 正负号（-47.52）  85 货币符号（¥47.52）  75 纯小数  50 句子中抠取
     */
    fun findAmount(texts: List<String>): Amt? {
        // 电商订单页常把「实付款」和「¥89.00」拆成两个节点。
        // 这里先做一次标签→金额配对，把配对成功的金额抬到 115（强标签）/102（弱标签）分。
        val paired = HashMap<Int, Int>()
        for (i in texts.indices) {
            val lab = texts[i].trim().trimEnd('：', ':').trim()
            val strong = STRONG_LABELS.contains(lab)
            if (!strong && !WEAK_LABELS.contains(lab)) continue
            val nxt = texts.getOrNull(i + 1)?.trim() ?: continue
            if (nxt.isEmpty() || nxt.length > 14) continue
            val v = AMOUNT_SYMBOL.find(nxt)?.groupValues?.getOrNull(1)
                ?: AMOUNT_SIGNED.find(nxt)?.groupValues?.getOrNull(1)
                ?: AMOUNT_PLAIN.find(nxt)?.groupValues?.getOrNull(1)
            if (v == null) continue
            paired[i + 1] = if (strong) 115 else 102
        }

        var best: Amt? = null
        for (i in texts.indices) {
            val s = texts[i].trim()
            var cand: Amt? = null
            if (s.isEmpty() || s.length > 40) continue
            if (TIME.containsMatchIn(s)) continue
            if (HARD_SKIP.any { s.contains(it) }) continue
            // 「实付款 ¥47.52（已优惠 ¥5）」这种行带优惠词但金额是准的，不能被跳过
            val strongLine = STRONG_LABELED.containsMatchIn(s) || STRONG_INLINE.containsMatchIn(s)
            if (!strongLine && SOFT_SKIP.any { s.contains(it) }) continue

            STRONG_LABELED.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, true, 110, i) }
            }
            if (cand == null) STRONG_INLINE.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, true, 105, i) }
            }
            if (cand == null) WEAK_LABELED.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, true, 100, i) }
            }
            if (cand == null) AMOUNT_SIGNED.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let {
                    val neg = m.value.startsWith("-") || m.value.startsWith("－")
                    // 负号在账单页表示支出，但在电商页常是「优惠 -¥5.00」，
                    // 所以给 80 分，低于货币符号的 85 分：有正数金额时优先取正数。
                    cand = Amt(it, neg, if (neg) 80 else 70, i)
                }
            }
            if (cand == null) AMOUNT_SYMBOL.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, false, 85, i) }
            }
            if (cand == null) AMOUNT_PLAIN.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, false, 75, i) }
            }

            val c0 = cand ?: continue
            if (c0.value <= 0.0 || c0.value > 500000.0) continue
            val bonus = paired[i] ?: 0
            val c = if (bonus > c0.score) Amt(c0.value, true, bonus, i) else c0
            val cur = best
            if (cur == null || c.score > cur.score) best = c
        }
        if (best != null) return best

        // 兜底：句子中抠取
        for (i in texts.indices) {
            val s = texts[i].trim()
            if (s.isEmpty() || TIME.containsMatchIn(s)) continue
            val v = toNum(AMOUNT_IN_TEXT.find(s)?.groupValues?.getOrNull(1))
                ?: toNum(CURRENCY_IN_TEXT.find(s)?.groupValues?.getOrNull(1))
            if (v != null && v >= 0.01 && v <= 500000.0) {
                val exp = !MARKETING_WORDS.any { s.contains(it) }
                return Amt(v, exp, 50, i)
            }
        }
        return null
    }

    // ---------------- 页面判定 ----------------

    /**
     * 支付已完成的强证据：出现其一就说明这笔钱已经扣掉了。
     */
    private val PAID_WORDS = listOf(
        "支付成功", "付款成功", "交易成功", "扣款成功", "扣费成功", "消费成功",
        "购买成功", "下单成功", "支付完成", "付款完成", "转账成功", "缴费成功",
        "已支付", "已付款", "已扣款", "支付结果", "付款结果"
    )

    /**
     * 详情页的结构证据：这些字段只出现在「已经发生过的账单」里。
     * 用于那些页面本身没写「支付成功」、但确实是账单详情的情况（京东订单详情、支付宝账单详情等）。
     *
     * 刻意不含「收款方」：微信/支付宝的转账输入页也会出现「收款方」，放进来会把输金额页判成已支付。
     */
    private val DETAIL_WORDS = listOf(
        "账单详情", "交易详情", "付款详情", "支付详情", "账单分类",
        "交易单号", "商户单号", "转账单号", "账单号", "付款时间", "支付时间",
        "交易时间", "商品说明", "订单编号", "订单号",
        // v2.7 补充：简版/大字版账单页常用这些字段名，缺了会被判成「看不出支付痕迹」；
        // 「转账详情」是微信转账详情页自身的标题，漏了会导致收款页被判为无支付证据
        "创建时间", "完成时间", "商户订单号", "交易状态", "当前状态", "订单时间",
        "转账详情"
    )

    /**
     * 支付**尚未完成**的强信号：还在输金额、选支付方式、等密码。
     *
     * 这是 v2.6 修「输入金额时就记一次、支付成功又记一次」的核心：
     * 收银台页面同样有「实付款 ¥89.00」这种强标签金额，不拦住就会被当成账单。
     * 注意这里刻意不放「支付方式」「优惠」等成功页上也会出现的词。
     */
    private val UNSETTLED_WORDS = listOf(
        "确认支付", "立即支付", "去支付", "立即付款", "确认付款", "请付款",
        "请输入支付密码", "输入支付密码", "请输入密码", "请输入交易密码",
        "指纹支付", "面容支付", "待支付", "待付款", "等待付款", "等待支付",
        "请尽快支付", "订单已提交", "提交订单", "去结算", "确认订单",
        "请选择支付方式", "选择付款方式", "收银台", "订单待支付",
        "确认转账", "立即转账"
    )

    const val STATE_UNKNOWN = 0
    const val STATE_UNPAID = 1
    const val STATE_PAID = 2

    /**
     * 判断当前页面处于什么阶段：
     *  [STATE_PAID]    已扣款 —— 可以记账
     *  [STATE_UNPAID]  还在支付流程里（输金额 / 收银台 / 等密码）—— 绝不能记账
     *  [STATE_UNKNOWN] 看不出支付痕迹（商品详情、订单列表、首页）—— 不记账
     *
     * 判定顺序很重要：先看成功词，再看未完成词，最后才看详情页结构。
     * 支付成功页上也会出现「支付方式：余额宝」这类字样，所以未完成词表里不含这类通用词；
     * 而收银台上同时出现「订单编号」（结构证据）和「立即支付」（未完成信号），
     * 必须让未完成信号优先，否则收银台会被结构证据放行。
     */
    fun payState(texts: List<String>): Int {
        val hasPaid = PAID_WORDS.any { w -> texts.any { it.contains(w) } }
        if (hasPaid) return STATE_PAID
        val hasUnsettled = UNSETTLED_WORDS.any { w -> texts.any { it.contains(w) } }
        if (hasUnsettled) return STATE_UNPAID
        val detail = DETAIL_WORDS.any { w -> texts.any { it.contains(w) } }
        return if (detail) STATE_PAID else STATE_UNKNOWN
    }

    // ---------------- 收支方向 ----------------

    const val DIR_UNKNOWN = 0
    const val DIR_EXPENSE = 1
    const val DIR_INCOME = 2

    /**
     * 决定性收入词：出现即判收入，不再比较支出词。
     * 这些词几乎只出现在「钱进来了」的页面上，是修正「收入被记成支出」的主依据。
     */
    private val INCOME_DECISIVE = listOf(
        "收款成功", "收款到账", "已收款", "已到账", "到账成功", "入账成功", "已入账",
        "退款成功", "已退款", "退款到账", "收到转账", "已收钱", "收款金额",
        "红包已存入", "已存入零钱", "收益到账", "利息到账"
    )

    /** 一般收入词（弱），需与支出词比较后再定方向 */
    private val INCOME_WEAK = listOf(
        "收入", "收款", "入账", "到账", "退款", "退回", "转入", "收益"
    )

    /** 支出方向的强词（页面级） */
    private val EXPENSE_STRONG = listOf(
        "支出", "实付", "付款成功", "支付成功", "扣款成功", "扣费成功",
        "消费成功", "已支付", "已付款", "付款给", "支付金额",
        // 微信「转账成功」页只有「转账成功 + 转账给 XX」，不补这两条会被判成方向不明而漏记
        "转账给", "转给", "已转账"
    )

    /** 单条文本是不是收入语义（通知通道用） */
    fun isIncomeText(s: String): Boolean {
        if (INCOME_DECISIVE.any { s.contains(it) }) return true
        return INCOME_WEAK.any { s.contains(it) } && EXPENSE_STRONG.none { s.contains(it) }
    }

    /**
     * 判定这一页是「钱出去」还是「钱进来」。
     *
     * 旧逻辑的缺陷：只在金额行不带强标签时才看收入词；金额行一旦命中「实付款 / 支付金额 / 合计」，
     * 就直接按支出入库。收入账单里的「金额 ¥500.00」正好走这条路径，于是收入被记成了支出。
     *
     * 现在的判定顺序：
     *  1. 决定性收入词 → 收入（优先级最高：宁可漏记一笔支出，也不要把收入记成支出）
     *  2. 只有收入词、没有支出词 → 收入
     *  3. 有支出词 → 支出
     *  4. 页面没写方向 → 看金额节点自身（负号 / 支出标签算支出，其余判不明）
     */
    fun direction(texts: List<String>, amt: Amt?): Int {
        if (texts.any { t -> INCOME_DECISIVE.any { t.contains(it) } }) return DIR_INCOME
        val inc = INCOME_WEAK.count { w -> texts.any { it.contains(w) } }
        val exp = EXPENSE_STRONG.count { w -> texts.any { it.contains(w) } }
        if (inc > 0 && exp == 0) return DIR_INCOME
        if (exp > 0) return DIR_EXPENSE
        if (inc > 0) return DIR_INCOME
        amt ?: return DIR_UNKNOWN
        return if (amt.expense) DIR_EXPENSE else DIR_UNKNOWN
    }

    /** 单条文本是否属于「钱还没付」的提醒（订单待付款、请尽快支付之类），通知通道用 */
    fun isUnsettledText(s: String): Boolean = UNSETTLED_WORDS.any { s.contains(it) }

    /** 是否命中该 App 的排除词（聊天窗口等） */
    fun isExcluded(texts: List<String>, app: App?): Boolean =
        app != null && app.excludeWords.any { w -> texts.any { it.contains(w) } }

    fun hasTitle(texts: List<String>, app: App?): Boolean {
        if (isExcluded(texts, app)) return false
        val words = app?.titleWords ?: COMMON_TITLE_WORDS
        return texts.any { t -> words.any { w -> t.contains(w) } }
    }

    fun hasExpenseSignal(texts: List<String>): Boolean =
        texts.any { t -> EXPENSE_WORDS.any { w -> t.contains(w) } }

    fun hasIncomeSignal(texts: List<String>): Boolean =
        texts.any { t -> INCOME_WORDS.any { w -> t.contains(w) } }

    /** 字段标签：商户类 */
    val MERCHANT_KEYS = listOf(
        "收款方全称", "收款方", "商户全称", "商户名称", "商户", "对方账户",
        "店铺名称", "店铺", "卖家昵称", "卖家", "商家名称", "服务商", "收款商户"
    )
    /** 字段标签：商品/说明类 */
    val GOODS_KEYS = listOf(
        "商品说明", "商品名称", "商品信息", "商品", "订单信息", "订单内容",
        "交易说明", "宝贝名称", "套餐", "备注", "说明"
    )
    /** 字段标签：分类提示 */
    val CAT_KEYS = listOf("账单分类", "交易类型", "订单类型", "消费类型", "业务类型")

    /**
     * 取字段值。兼容两类排版：
     *  ①「标签」「值」分属两个节点（支付宝账单详情）
     *  ②「标签：值」同一节点（电商订单页）
     */
    fun fieldOf(texts: List<String>, keys: List<String>): String? {
        for (k in keys) {
            for (t in texts) {
                val s = t.trim()
                if (!s.startsWith(k)) continue
                val rest = s.substring(k.length).trim().trimStart('：', ':', '-')
                if (rest.isNotEmpty() && rest.length in 1..30 &&
                    !hasAmountIn(rest) && TIME.find(rest) == null
                ) return rest
            }
            for (i in texts.indices) {
                if (texts[i].trim() != k) continue
                val next = texts.getOrNull(i + 1)?.trim() ?: continue
                if (next.isEmpty() || next.length > 30) continue
                if (hasAmountIn(next) || EXPENSE_WORDS.any { next.contains(it) }) continue
                return next
            }
        }
        return null
    }
}
