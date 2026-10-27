package com.deylin.autledger

/** 预置分类：支出 40 类（与用户既有记账 App 的支出区一致）+ 收入类 */
object Cats {

    val EXPENSE = listOf(
        Pair("餐饮", "🍜"),
        Pair("电车充电", "🔌"),
        Pair("停车费", "🅿️"),
        Pair("高速费", "🛣️"),
        Pair("恋爱", "💗"),
        Pair("彩票", "🎰"),
        Pair("住房", "🏠"),
        Pair("宠物", "🐶"),
        Pair("柴米油盐", "🍚"),
        Pair("蔬菜", "🥬"),
        Pair("饮品", "🧋"),
        Pair("购物", "🛍️"),
        Pair("旅游", "✈️"),
        Pair("旅游2", "🏝️"),
        Pair("旅游3", "🏔️"),
        Pair("旅游4", "🚢"),
        Pair("电驴充电", "🛵"),
        Pair("汽车", "🚗"),
        Pair("交通", "🚇"),
        Pair("日用", "🧻"),
        Pair("零食", "🍬"),
        Pair("水果", "🍎"),
        Pair("服饰", "👕"),
        Pair("娱乐", "🎮"),
        Pair("美容", "💅"),
        Pair("通讯", "📱"),
        Pair("医疗", "💊"),
        Pair("学习", "📚"),
        Pair("红包", "🧧"),
        Pair("家庭", "👨‍👩‍👧"),
        Pair("快递", "📦"),
        Pair("社交", "🍻"),
        Pair("酒店", "🏨"),
        Pair("礼品", "🎁"),
        Pair("数码", "💻"),
        Pair("办公", "📎"),
        Pair("运动", "🏃"),
        Pair("保险费", "🛡️"),
        Pair("赡养", "👴"),
        Pair("其它", "📌")
    )

    val INCOME = listOf(
        Pair("工资", "💰"),
        Pair("奖金", "🏆"),
        Pair("兼职", "💼"),
        Pair("投资", "📈"),
        Pair("报销", "🧾"),
        Pair("退款", "↩️"),
        Pair("红包收入", "🧧"),
        Pair("其它收入", "📌")
    )

    val BOOKS = listOf("日常账本", "旅行账本", "工作账本")
}
