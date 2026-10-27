package com.deylin.autledger

// 自动分类规则：按关键词命中，顺序即优先级，可手动改
object Category {

    private val rules = linkedMapOf(
        "交通" to listOf("滴滴", "出行", "充电", "加油", "停车", "地铁", "公交", "打车", "代驾",
            "单车", "骑行", "火车", "高铁", "机票", "12306", "高速", "ETC", "车费", "网约车"),
        "餐饮" to listOf("美团", "饿了么", "外卖", "餐", "食", "奶茶", "咖啡", "肯德基", "麦当劳",
            "小吃", "烧烤", "火锅", "饮", "面馆", "快餐"),
        "购物" to listOf("淘宝", "天猫", "京东", "拼多多", "抖音商城", "唯品会", "得物", "商城", "百货"),
        "居住" to listOf("房租", "物业", "水费", "电费", "燃气", "宽带"),
        "日用" to listOf("超市", "便利店", "话费", "充值", "生活缴费", "盒马", "山姆", "日用"),
        "娱乐" to listOf("电影", "游戏", "视频会员", "音乐", "爱奇艺", "腾讯视频", "KTV", "景区", "演出"),
        "医疗" to listOf("医院", "药店", "诊所", "体检", "健康", "挂号"),
        "人情" to listOf("红包", "礼物", "转账", "代付")
    )

    fun of(vararg texts: String): String {
        val joined = texts.filter { it.isNotBlank() }.joinToString(" ")
        for ((cat, kws) in rules) {
            if (kws.any { joined.contains(it) }) return cat
        }
        return "其他"
    }
}
