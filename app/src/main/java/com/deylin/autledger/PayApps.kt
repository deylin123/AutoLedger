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
        // 12306 首页/功能区（不是某笔车票的账单页）：
        // 它的「我的行程 / 行程详情」入口正好命中 TRIP 词表，光靠标题词挡不住，这里直接否掉。
        App(
            "com.MobileTicket", "铁路12306", TRIP,
            listOf("时刻表", "车站大屏", "车票预订", "铁路会员", "积分兑换", "温馨服务", "出行指南")
        ),
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
        ),
        // v2.11 补登记 QQ：QQ 钱包的支付结果页同样只有「支付成功 + 金额 + 完成」，
        // 不登记的话走通用兜底，而通用标题词刻意不含「支付成功」→ 整页不命中 → QQ 消费漏记。
        // 聊天窗口排除词与微信同一套（按住说话/发送消息等输入栏特征）。
        App(
            "com.tencent.mobileqq", "QQ",
            listOf(
                "账单详情", "交易详情", "支付详情", "支付成功", "付款成功",
                "支付结果", "付款结果", "交易成功", "充值成功"
            ),
            listOf(
                "按住 说话", "按住说话", "发送消息", "聊天信息", "表情面板", "语音输入"
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

    /**
     * 弱标题词：这些词既能出现在账单页，也常出现在首页卡片、订单列表、行程卡片上。
     * 只命中它们不足以认定「这是账单页」，必须再配合「已扣款」证据。
     *
     * 这是 v2.8 修「打开 12306 首页就弹窗」的第一道闸：
     * 12306 首页的「我的行程 / 行程详情」入口正好命中 TRIP 词表。
     */
    private val WEAK_TITLE = setOf(
        "订单详情", "订单信息", "订单列表", "我的订单",
        "行程详情", "交易记录", "支付详情", "付款详情"
    )

    /**
     * 只命中了弱标题词（没有命中任何强标题词）。
     * 调用方据此要求更强的支付证据，避免首页/列表页误判。
     */
    fun onlyWeakTitle(texts: List<String>, app: App?): Boolean {
        val words = app?.titleWords ?: COMMON_TITLE_WORDS
        val hits = words.filter { w -> texts.any { it.contains(w) } }
        return hits.isNotEmpty() && hits.all { WEAK_TITLE.contains(it) }
    }

    /** 主界面 / 列表页的导航特征词（底部 tab 栏、首页入口） */
    private val HOME_WORDS = listOf(
        "首页", "我的", "购物车", "推荐", "搜索", "发现", "消息", "分类",
        "全部订单", "待付款", "待收货", "我的订单", "个人中心"
    )

    /**
     * 是否明显是首页 / 列表页（而不是某笔账单的详情页）。
     * 判据：导航类词命中 ≥3 个，且没有任何「账单结构」证据。
     *
     * 这是第二道闸：即便页面上某句话凑巧满足支付条件，只要整体形态是首页，一律不记。
     */
    fun looksLikeHome(texts: List<String>): Boolean {
        // 页面自己写了「支付成功」这类字样的，一定不是首页 ——
        // 电商的支付成功页底部同样有「首页/我的/购物车」导航，不能误判成首页。
        // v2.15：广告行里的「支付成功」不算 —— 否则首页挂一条租机广告
        // （「到期自动扣款成功继续租用」）就把首页判定短路掉。
        if (PAID_WORDS.any { w -> texts.any { isEvidenceLine(it) && it.contains(w) } }) return false
        val n = HOME_WORDS.count { w -> texts.any { t -> t.contains(w) } }
        if (n < 3) return false
        return DETAIL_WORDS.none { w -> texts.any { t -> t.contains(w) } }
    }

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

    /**
     * 广告/营销语境词（v2.15）：一行里出现这些词，说明这行是营销话术，
     * 其中的「支付成功 / 自动扣款成功」只是广告文案，不能当作账单页标题或支付证据。
     *
     * 刻意不放「优惠」「红包」「积分」这类单词——真实结果页常写
     * 「支付成功 实付¥47.52（已优惠¥5）」，把它们算广告词会误杀真账单。
     */
    val AD_CONTEXT = listOf(
        "领券", "优惠券", "立减", "满减", "折扣", "返现", "抽奖", "秒杀", "限时",
        "特价", "免押", "体验价", "试用", "免费领", "赠", "去看看", "去领取",
        "立即领", "点击领取", "签到", "勋章", "特权", "额度", "月租", "日租",
        "租机", "租金", "押金", "租用", "租赁", "续租", "元起", "种草", "爆款",
        "清仓", "特卖", "上新", "包邮", "大促", "好评", "立省", "直降", "做任务"
    )

    /** 这一行是广告/营销话术 */
    fun isAdLine(s: String): Boolean = AD_CONTEXT.any { s.contains(it) }

    /**
     * 结果页抬头词。打头即真证据，即便后半句带营销词 ——
     * 「支付成功，可领取优惠券」是真结果页抬头；「新人支付成功立减5元」是广告。
     */
    private val HEAD_WORDS = listOf(
        "支付成功", "付款成功", "交易成功", "扣款成功", "扣费成功", "支付完成", "付款完成",
        "转账成功", "充值成功", "充值完成", "缴费成功", "购买成功", "下单成功",
        "预订成功", "购票成功", "出票成功", "已支付", "已付款", "已扣款",
        "自动扣款成功", "支付结果", "付款结果",
        "账单详情", "交易详情", "付款详情", "支付详情", "转账详情", "退款成功", "退款到账"
    )

    /**
     * 这一行能否作为标题/支付证据（v2.15）：广告行不行，结果词打头的行可以。
     */
    fun isEvidenceLine(s: String): Boolean {
        val t = s.trim()
        return !isAdLine(t) || HEAD_WORDS.any { t.startsWith(it) }
    }

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

    /** signed：金额带明确 + / - 号。方向判定里这是最硬的证据，优先于页面上的弱方向词。 */
    class Amt(val value: Double, val expense: Boolean, val score: Int, val idx: Int, val signed: Boolean = false)

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
    // v2.13：兼容「¥-25.00」形态（货币符号在前、负号在后），此前这种写法任何正则都吃不到
    private val AMOUNT_SIGNED = Regex("^[¥￥]?\\s*(?:-|－|\\+)\\s*[¥￥]?\\s*($NUM)\\s*元?$")
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
        "优惠", "立减", "满减", "折扣", "减免", "积分", "运费险", "手续费说明",
        // v2.15：广告价签行（免押租机价、领券价、秒杀价…）里的数字不是消费金额。
        // 芝麻信用页广告卡「¥37.26」被当消费金额的根因之一就在这。
        "免押", "领券", "优惠券", "红包", "返现", "秒杀", "限时", "特价", "体验", "试用",
        "免费", "赠", "勋章", "特权", "额度", "种草", "爆款", "清仓", "特卖", "上新",
        "包邮", "立省", "直降", "好评", "月租", "日租", "租机", "租金", "押金", "元起"
    )

    private fun toNum(s: String?): Double? =
        s?.replace(",", "")?.replace(" ", "")?.replace("．", ".")?.toDoubleOrNull()

    /**
     * 数字后面紧跟这些内容，说明它根本不是金额：
     * 网速（92KB/s）、流量（1.5GB）、百分比（80%）、计数（3次 / 5笔）、
     * 计量（2kg / 500ml）、广告起价（¥92起）。
     *
     * 这是 v2.8 修「把 92KB/s 当消费」的金额侧防线：
     * 之前只挡住了整行纯数字，但行内抠取（"实付款…92"）没有任何单位校验。
     */
    private val NON_MONEY_AFTER = Regex(
        "^\\s*(?:[A-Za-z%/‰]|起|次|笔|人|件|个|天|日|岁|分|秒|米|公里|kg|斤|克|毫升|ml|积分|里程|台|张|位|折|期|小时|分钟|步|层|楼|号)"
    )

    /** 匹配到的数字紧邻字符是不是「非货币单位」 */
    private fun hasNonMoneyUnit(s: String, endExclusive: Int): Boolean {
        if (endExclusive >= s.length) return false
        return NON_MONEY_AFTER.containsMatchIn(s.substring(endExclusive))
    }

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
                // 行内抠取必须做单位校验，否则「实付款…92KB/s」这类也能过
                if (!hasNonMoneyUnit(s, m.range.last + 1)) {
                    toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, true, 105, i) }
                }
            }
            if (cand == null) WEAK_LABELED.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let { cand = Amt(it, true, 100, i) }
            }
            if (cand == null) AMOUNT_SIGNED.find(s)?.let { m ->
                toNum(m.groupValues.getOrNull(1))?.let {
                    // 符号可能在「¥-25.00」里排在货币符号后面，不能只看开头，全匹配串里找负号
                    val neg = m.value.contains('-') || m.value.contains('－')
                    // 负号在账单页表示支出，但在电商页常是「优惠 -¥5.00」，
                    // 所以给 80 分，低于货币符号的 85 分：有正数金额时优先取正数。
                    // signed=true：「-47.52」「+500.00」这类明确符号，方向判定时优先级最高。
                    cand = Amt(it, neg, if (neg) 80 else 70, i, signed = true)
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
            // v2.9：配对抬分时保留金额节点原本的方向与符号信息，不再强制按支出处理
            val c = if (bonus > c0.score) Amt(c0.value, c0.expense, bonus, i, c0.signed) else c0
            val cur = best
            if (cur == null || c.score > cur.score) best = c
        }
        if (best != null) return best

        // 兜底：句子中抠取
        for (i in texts.indices) {
            val s = texts[i].trim()
            if (s.isEmpty() || TIME.containsMatchIn(s)) continue
            // 整行是网速/流量/百分比这类，直接否掉
            if (Regex("^[\\d.,]+\\s*[A-Za-z%/]+.*$").containsMatchIn(s)) continue
            // v2.15：广告价签行（免押租机 ¥37.26 / 秒杀价 ¥99）兜底阶段同样不取。
            // 与主循环一致：带强标签的行不受影响（「实付款 ¥47.52（已优惠 ¥5）」照取）。
            val strongLine = STRONG_LABELED.containsMatchIn(s) || STRONG_INLINE.containsMatchIn(s) ||
                STRONG_LABELS.any { s.contains(it) }
            if (!strongLine && SOFT_SKIP.any { s.contains(it) }) continue
            val m1 = AMOUNT_IN_TEXT.find(s)
            val m2 = if (m1 == null) CURRENCY_IN_TEXT.find(s) else null
            val m = m1 ?: m2 ?: continue
            if (hasNonMoneyUnit(s, m.range.last + 1)) continue
            val v = toNum(m.groupValues.getOrNull(1))
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
        "已支付", "已付款", "已扣款", "支付结果", "付款结果",
        // v2.11：QQ 充值、话费充值等页面只写「充值成功」，缺了会整页漏记
        "充值成功", "充值完成",
        // v2.12：出行/票务结果页只有「预订成功/购票成功/出票成功」，缺了整页状态判未知而漏记
        "预订成功", "购票成功", "出票成功"
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
        // v2.15：广告行里的「支付成功」不算已扣款证据（同 hasTitle 的理由）
        val hasPaid = PAID_WORDS.any { w -> texts.any { isEvidenceLine(it) && it.contains(w) } }
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
        "红包已存入", "已存入零钱", "已存入", "收益到账", "利息到账"
    )

    /**
     * 流出证据：字面像收款、实际是钱出去的词。
     * 微信转出转账详情页写「朋友已收款 / 对方已收款」——那是对方收到了我的钱，
     * 不加这道守卫会被 INCOME_DECISIVE 判成收入。
     */
    private val OUT_EVIDENCE = listOf(
        "朋友已收款", "对方已收款", "转账给", "转给", "付款给", "已转账"
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
        "转账给", "转给", "已转账",
        // v2.11：QQ 充值/话费充值页只有「充值成功 + 金额」，没有任何其他方向词
        "充值成功", "充值完成", "已充值",
        // v2.12：出行/票务结果页只有「预订成功 + 金额」，没有任何其他方向词
        "预订成功", "购票成功", "出票成功",
        // v2.13 修「微信账单详情不弹窗」：微信账单详情页（支出）只有这些字段，
        // 一个方向词都没有 —— 金额大字又是无符号的「¥25.00」，方向判不出直接被跳过。
        // 支付宝账单详情金额带「-47.50」符号所以一直正常，这正是两端结构差异。
        // （微信收入详情有「已存入零钱/退款」这类决定性收入词，优先级更高，不受影响）
        "付款时间", "付款方式", "已收货",
        // 支付宝账单详情的时间字段叫「交易时间」，同样承担无符号金额时的方向兜底
        "交易时间"
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
    /**
     * 中性字段标签：这些词后面的「收款/付款」只是字段名，不代表方向。
     *
     * 之前支付宝「支出」账单详情被判成收入的根因就在这：页面上有「收款方全称 星巴克」——
     * 收入弱词「收款」被它凑出来，而详情页又没写「支出/支付金额」这类支出强词，
     * inc>0 && exp==0 直接判成收入。判定前先把字段标签剔除，这一类误判就不存在了。
     */
    private val NEUTRAL_FIELD_LABELS = listOf(
        "收款方全称", "收款方", "收款商户", "对方账户", "付款方", "对方名称"
    )

    private fun stripFieldLabels(texts: List<String>): List<String> =
        texts.map { t ->
            var s = t
            NEUTRAL_FIELD_LABELS.forEach { s = s.replace(it, "") }
            s
        }

    fun direction(texts: List<String>, amt: Amt?): Int {
        val clean = stripFieldLabels(texts)
        // v2.11：金额节点带明确 +/- 号是「这笔钱」方向最硬的证据，优先于页面级词。
        // 放在决定性收入词之前：微信/支付宝账单详情里可能出现「已到账」等字样，
        // 但金额自身的「-47.52」才是这笔钱真实方向的直接证据。
        if (amt != null && amt.signed) return if (amt.expense) DIR_EXPENSE else DIR_INCOME
        // v2.11：微信转出转账详情页写「朋友已收款」——字面是收款，实际钱出去了。
        // 且「朋友已收款」包含决定性收入词「已收款」的子串，必须先把它从文本里剔除再匹配，
        // 否则转出页会被判成收入。
        val decisiveSrc = clean.map { t ->
            var s = t
            OUT_EVIDENCE.forEach { w -> s = s.replace(w, "") }
            s
        }
        val decisive = decisiveSrc.any { t -> INCOME_DECISIVE.any { t.contains(it) } }
        val outgoing = OUT_EVIDENCE.any { w -> clean.any { it.contains(w) } }
        // 决定性收入词与流出证据并存时（如退款页提到「付款给」），收入优先。
        if (decisive) return DIR_INCOME
        if (outgoing) return DIR_EXPENSE
        val inc = INCOME_WEAK.count { w -> clean.any { it.contains(w) } }
        val exp = EXPENSE_STRONG.count { w -> clean.any { it.contains(w) } }
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
        // v2.15：广告话术里的钱包词不算标题证据。
        // 「免押租手机，自动扣款成功继续租用」这类营销行命中词表后，
        // 门户页被当账单页放进闸门链 —— 芝麻信用页误记账的触发路径之一。
        return texts.any { t -> isEvidenceLine(t) && words.any { w -> t.contains(w) } }
    }

    fun hasExpenseSignal(texts: List<String>): Boolean =
        texts.any { t -> EXPENSE_WORDS.any { w -> t.contains(w) } }

    /** 页面上有没有「账单结构」字段（付款时间 / 交易单号 / 账单分类 等） */
    fun hasBillStructure(texts: List<String>): Boolean =
        DETAIL_WORDS.any { w -> texts.any { t -> t.contains(w) } }

    private val ORDER_KEYS = listOf("订单号", "订单编号", "交易单号")

    /**
     * 订单列表页特有的标记词：页签（待付款/待发货…）与列表条目按钮（再次购买/查看物流…）。
     *
     * v2.12 修「淘宝我的订单页浏览历史订单，全部被记成新消费」：
     * 淘宝列表条目上不显示订单号（旧判据「3 金额 + 2 单号」挡不住），
     * 但每个条目的状态「交易成功」又命中 PAID_WORDS，把首页判定和支付状态判定一起带过了。
     * 这些标记词是列表页独有的——真正的支付结果页不会同时出现两个以上。
     */
    private val LIST_MARKERS = listOf(
        "我的订单", "全部订单", "订单列表", "待付款", "待发货", "待收货", "待评价",
        "待分享", "待使用", "再次购买", "查看物流", "确认收货", "删除订单",
        "催发货", "订单回收站", "延长收货", "查看更多订单",
        // 出行/票务类列表页的页签与条目按钮（携程/飞猪/去哪儿/12306/滴滴）
        "待出行", "待出游", "待入住", "待点评", "取消订单", "申请退款", "改签", "退票", "交易关闭"
    )

    /**
     * 支付结果页特有的词。列表页只会有「交易成功」这类条目状态，不会有这些词；
     * 反过来，结果页可能带「查看全部订单」等入口，为防误杀，出现结果词时标记词判定不启用。
     */
    private val RESULT_WORDS = listOf(
        "支付成功", "付款成功", "支付完成", "付款完成", "充值成功",
        "预订成功", "购票成功"
    )

    // ---------------- 门户/营销页判定（v2.15） ----------------

    /**
     * 营销门户页特征词：芝麻信用/会员中心/活动会场/频道首页这类页面。
     *
     * v2.15 事故：支付宝「芝麻信用」页被整页记账（-¥37.26 商户「挪了个箭」）。
     * 该页是典型的营销门户：信用分、额度、特权、领券、勋章、免押租机广告……
     * 没有任何账单结构字段，但广告模块里的价签（¥37.26）+ 扣款类广告词
     * 把「标题/支付状态/金额/方向」四道闸门全部凑齐了。
     */
    private val PORTAL_WORDS = listOf(
        "芝麻分", "信用分", "信用名片", "可用额度", "可用特权", "可享优惠",
        "领券", "优惠券", "去看看", "免押", "勋章", "签到", "特权", "额度",
        "秒杀", "限时", "爆款", "种草", "上新", "大促", "清仓", "特卖", "抽奖",
        "返现", "立减", "满减", "体验价", "试用", "免费领", "去领取", "立即领",
        "点击领取", "做任务", "包邮", "月租", "日租", "租机", "租金", "押金",
        "热卖", "热销", "抢购", "新品", "领红包", "积分兑换", "领取", "会员"
    )

    /**
     * 结果页/账单页的出口词：有这些词说明这是某笔交易的落地页，不是营销会场。
     * 用于给门户判定兜底 —— 真实支付结果页底部常挂营销模块，不能被门户词误杀。
     */
    private val RESULT_EXIT = listOf(
        "查看账单", "再付一笔", "再转一笔", "继续支付", "交易详情", "付款详情"
    )

    /**
     * 是否是「营销门户/活动会场」页（不是某笔账单）。
     *
     * 判据（同时满足）：
     *  ① 营销词命中 ≥4 个 —— 真账单页顶多底部挂一两个广告模块，凑不齐 4 个；
     *  ② 页面没有任何账单结构字段（时间/单号/分类，[DETAIL_WORDS]）；
     *  ③ 页面没有强标签金额（实付款/支付金额/配对标签）——
     *     门户页的数字都是额度、分数、价签，不会写「实付款」；
     *  ④ 没有结果页出口词（查看账单/再付一笔…）——防误杀带广告的支付结果页。
     */
    fun looksLikePortal(texts: List<String>): Boolean {
        val hits = PORTAL_WORDS.count { w -> texts.any { it.contains(w) } }
        if (hits < 4) return false
        if (DETAIL_WORDS.any { w -> texts.any { it.contains(w) } }) return false
        if (texts.any { STRONG_LABELED.containsMatchIn(it) || STRONG_INLINE.containsMatchIn(it) }) return false
        if (texts.any { t -> STRONG_LABELS.contains(t.trim().trimEnd('：', ':').trim()) }) return false
        if (RESULT_EXIT.any { w -> texts.any { it.contains(w) } }) return false
        return true
    }

    /**
     * 账单流水列表页专属词（微信「交易记录」、支付宝账单列表、零钱明细）。
     * 一屏 4 个以上金额节点 + 命中其一 → 是流水列表，不是某一笔账单。
     */
    private val BILL_LIST_WORDS = listOf(
        "交易记录", "收支明细", "账单明细", "零钱明细", "账单列表"
    )

    /**
     * 是否像「订单列表」而不是「某一笔的账单」。
     *
     * 判据（满足其一即可）：
     *  ① 一屏 3 个以上金额节点 + 2 个以上单号字段 —— 一笔账单只有一个金额、一个单号；
     *  ② v2.12：3 个以上列表标记词（页签/条目按钮），覆盖淘宝这类条目不带单号的列表页；
     *  ③ v2.12：2 个以上标记词 + 3 个以上金额节点；
     *  ④ v2.12：4 个以上金额节点 + 账单流水页专属词（微信交易记录/支付宝收支明细）。
     * 标记词判定只在页面没有结果词时启用 —— 支付结果页带「支付成功/付款成功」，
     * 且可能带「查看全部订单」入口，不能被标记词误杀。
     * 这挡住 12306、淘宝/京东/携程等「我的订单」列表页：
     * 每行都是「已支付 + 金额（+单号）」，单看任何一行都像账单，但它们讲的不是同一笔。
     */
    fun looksLikeList(texts: List<String>): Boolean {
        val amtNodes = texts.count { hasAmountIn(it) }
        if (amtNodes < 2) return false
        val orderNodes = texts.count { t -> ORDER_KEYS.any { t.contains(it) } }
        if (amtNodes >= 3 && orderNodes >= 2) return true
        if (RESULT_WORDS.any { w -> texts.any { it.contains(w) } }) return false
        val markers = LIST_MARKERS.count { w -> texts.any { it.contains(w) } }
        if (markers >= 3) return true
        if (markers >= 2 && amtNodes >= 3) return true
        if (amtNodes >= 4 && BILL_LIST_WORDS.any { w -> texts.any { it.contains(w) } }) return true
        return false
    }

    // ---------------- 消息流/会话页判定（v2.14） ----------------

    /**
     * 聊天时间线分割线（微信支付会话/聊天窗口特有）：
     * 「昨天 22:55」「今天」「10月29日 20:30」「周一」这类独立成行的小字。
     * 账单页/支付结果页的时间都带字段标签且通常含年份（付款时间 2026年10月29日 22:55:30），
     * 不会以这种形态独立出现。
     */
    private val STREAM_SEPARATOR = Regex(
        "^(今天|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天])(\\s+\\d{1,2}:\\d{2})?$" +
            "|^\\d{1,2}月\\d{1,2}日(\\s+\\d{1,2}:\\d{2})?$"
    )

    /**
     * 交易结果块标题按语义分桶（同义词算同一桶，防止「支付成功+付款成功」这类单页被误杀）。
     * 一屏命中 ≥2 个桶 = 多条交易消息混排。
     */
    private val RESULT_BLOCK_BUCKETS = listOf(
        listOf("支付成功", "付款成功", "支付完成", "付款完成"),
        listOf("转账成功", "已转账"),
        listOf("收款成功", "收款到账", "已收钱"),
        listOf("退款到账", "退款成功", "已退款"),
        listOf("充值成功", "充值完成"),
        listOf("缴费成功"),
        listOf("下单成功", "购买成功")
    )

    /**
     * 是否是「消息流/会话页」——一屏混着多条交易消息（微信支付会话、聊天窗口）。
     *
     * v2.14 实测事故：微信支付会话里同时躺着「支付成功，对方已收款 ¥1.30」（转出消息）
     * 和「退款到账通知 ¥69.90」（拼多多退款消息），识别把上块的金额（1.30）配上
     * 下块的商户（拼多多平台商户）和方向（退款到账→收入），拼出「收入 +1.30 拼多多平台商户」
     * 这种两笔混血记录。金额/商户/方向分属不同消息块，结构上配不成对，只能整页不记。
     *
     * 判据（满足其一）：
     *  ① 出现聊天时间线分割线（昨天 22:55 / 10月29日 20:30）—— 浏览会话的铁证；
     *  ② 一屏命中 ≥2 个交易结果块语义桶 —— 多条消息混排。
     *
     * 浏览/翻聊天记录一律不记账；支付那一刻由通知通道兜底，
     * 单笔支付结果页、单笔账单详情页不受影响（各只命中 1 个桶、无时间线分割线）。
     */
    fun looksLikeMessageStream(texts: List<String>): Boolean {
        if (texts.any { STREAM_SEPARATOR.matches(it.trim()) }) return true
        val buckets = RESULT_BLOCK_BUCKETS.count { b -> b.any { w -> texts.any { it.contains(w) } } }
        return buckets >= 2
    }

    /**
     * 页面上解析出的最新时间；null 表示页面上没有任何可解析的时间。
     *
     * v2.12「只认刚刚发生的支付」的依据：
     * 历史订单详情页与支付成功页结构完全相同（交易成功 + 实付款 + 订单编号），
     * 结构闸门分不出来；唯一可靠的区别是时间 ——
     * 刚付完的页面，付款/交易时间是此刻；浏览历史订单时，页面上的时间是几天前。
     * 取「最新」而非「第一个」时间：补付款的订单页同时有创建时间（几天前）和付款时间（此刻），
     * 取最新才不会把刚付完的误杀。
     */
    fun latestParsedTime(texts: List<String>): Long? {
        var max: Long? = null
        for (t in texts) {
            val s = t.trim()
            if (s.isEmpty() || s.length > 40) continue
            val m = TIME.find(s) ?: continue
            val parsed = parseTime(m.groupValues[1]) ?: continue
            if (max == null || parsed > max) max = parsed
        }
        return max
    }

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
     * 字段值里出现这些词说明取到的是字段名延续/编号，不是真正的值。
     *
     * v2.13 修「微信账单详情识别结果错误」：商户键「商户」前缀匹配上了
     * 「商户单号 4200002026102912345678」，把「单号 42000020…」当成商户名写进了备注。
     */
    private val NOISE_VALUE = listOf(
        "单号", "编号", "号码", "批次", "流水", "时间", "日期",
        // v2.14：「商品详情」是字段标签本身，商品键「商品」前缀匹配上它会把「详情」当商品名
        "详情"
        // 注意：不放「订单」——「美团-外卖订单」是正常商品名，放进来会把真值误杀
    )

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
                if (rest.isEmpty() || rest.length !in 1..30) continue
                if (hasAmountIn(rest) || TIME.find(rest) != null) continue
                // v2.13：值里带单号/时间等字样的，是字段名延续（如「商户单号 42000020…」），不是真值
                if (NOISE_VALUE.any { rest.contains(it) }) continue
                return rest
            }
            for (i in texts.indices) {
                if (texts[i].trim() != k) continue
                val next = texts.getOrNull(i + 1)?.trim() ?: continue
                if (next.isEmpty() || next.length > 30) continue
                if (hasAmountIn(next) || EXPENSE_WORDS.any { next.contains(it) }) continue
                if (NOISE_VALUE.any { next.contains(it) }) continue
                return next
            }
        }
        return null
    }

    // ---------------- 钱包账单详情（v2.13） ----------------

    /**
     * 账单详情本质上就是「事后核对页」：支付成功页几秒就关，用户是靠它回看每一笔的。
     * v2.12 的 10 分钟历史闸门把这类页面一刀切拦掉，是「微信账单详情不弹窗」的另一根因。
     * 但电商「订单详情」必须继续拦 —— 那是 v2.12 刚修掉的「浏览历史订单误记账」。
     * 所以只对支付工具（支付宝/微信/QQ）的「单笔账单详情」放行，判据极严：
     * 页面标题必须是账单/交易/转账详情，列表页（交易记录/账单）不会是这个标题。
     */
    private val WALLET_PKGS = setOf(
        "com.eg.android.AlipayGphone", "com.tencent.mm", "com.tencent.mobileqq"
    )
    private val BILL_DETAIL_TITLES = listOf("账单详情", "交易详情", "转账详情")

    /**
     * 单笔钱包账单详情页（支付宝/微信/QQ）。
     * 命中时可豁免「10 分钟历史」闸门：记录的入账时间用页面上的付款时间，
     * 配合更宽的同额查重窗口（[MERGE_WINDOW_DETAIL_MS]），不会与支付成功页那次识别重复。
     */
    fun isWalletBillDetail(texts: List<String>, app: App?): Boolean {
        val pkg = app?.pkg ?: return false
        if (pkg !in WALLET_PKGS) return false
        return BILL_DETAIL_TITLES.any { w -> texts.any { it.contains(w) } }
    }

    /**
     * 账单详情能解析出页面付款时间时的查重窗口（30 分钟）：
     * 支付成功页那次识别的入账时间 ≈ 付款时间，同一笔在详情页再被打开时
     * 按页面时间查重能对上，不再落第二条；真的漏记的历史账单则照常补录。
     */
    const val MERGE_WINDOW_DETAIL_MS = 30 * 60_000L
}
