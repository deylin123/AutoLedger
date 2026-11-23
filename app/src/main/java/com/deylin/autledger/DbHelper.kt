package com.deylin.autledger

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/** 一条账单 */
data class Rec(
    var id: Long = 0L,
    var amount: Double = 0.0,
    var type: Int = 0,              // 0 支出, 1 收入
    var cat: String = "其它",
    var note: String = "",
    var ts: Long = 0L,
    var book: String = "日常账本",
    var status: Int = 1,            // 0 待确认, 1 已确认
    var src: String = "手工",
    var remark: String = ""         // v2.30：用户手写备注，与 note（自动识别商户/商品）分离
)

/** 分类定义 */
data class CatDef(val id: Long, val name: String, val emoji: String, val type: Int)

class DbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "autoledger.db", null, 9) {

    companion object {
        val COLS = arrayOf("id", "amount", "type", "cat", "note", "ts", "book", "status", "src", "remark")

        /**
         * v2.28：商户名是否值得记忆/匹配。
         * 自动回落的兜底名（"微信消费""支付宝收入"）不是真商户，不学也不配，
         * 否则一条记忆会把全部同名兜底账带偏到同一个类别。
         * 备注形如「商户·商品」时只取商户段做键。
         */
        /**
         * v2.29：平台/渠道通用名黑名单。
         * 这些词在微信/支付宝账单里几乎每笔都一样（「微信支付」「财付通」「零钱」…），
         * 拿它们当商户 key，一条误改就会把全部账单带到同一个类别 —— 这正是
         * 「两笔不同消费都自动识别成彩票」的成因。
         */
        private val GENERIC_MERCHANT_EXACT = listOf(
            "微信支付", "微信", "财付通", "零钱", "零钱通", "余额", "余额宝", "银行卡",
            "支付宝", "支付", "付款", "收款", "转账", "红包", "退款", "消费", "收入",
            "二维码", "扫码", "扫一扫", "收付款", "账单", "交易", "订单", "商户", "商家",
            "未知", "其它", "其他", "暂无", "无", "待确认", "自动记账", "收款方", "付款方"
        )
        /** 这些词是渠道/动作名，只要商户名里含就说明不是具体商户（"XX支付平台"之类） */
        private val GENERIC_MERCHANT_CONTAINS = listOf(
            "微信支付", "财付通", "支付宝", "零钱通", "扫二维码", "扫码付款",
            "收付款", "账单详情", "交易记录", "支付方式", "付款方式"
        )

        /** 商户名是否值得记忆/匹配：必须是具体商户，不能是渠道名、兜底名或机器串 */
        fun isLearnableMerchant(raw: String): Boolean {
            val m = raw.trim().substringBefore('·').trim()
            if (m.length < 2 || m.length > 24) return false
            if (m.endsWith("消费") || m.endsWith("收入")) return false
            if (GENERIC_MERCHANT_EXACT.any { m == it }) return false
            if (GENERIC_MERCHANT_CONTAINS.any { m.contains(it) }) return false
            if (m.all { !it.isLetterOrDigit() }) return false
            if (m.all { it.isDigit() }) return false
            if (m.length >= 12 && m.all { it.isLetterOrDigit() }) return false
            return true
        }

        fun monthStart(y: Int, m: Int): Long {
            val c = Calendar.getInstance()
            c.clear()
            c.set(y, m, 1, 0, 0, 0)
            return c.timeInMillis
        }

        fun monthEnd(y: Int, m: Int): Long =
            monthStart(if (m == 11) y + 1 else y, if (m == 11) 0 else m + 1)
    }

    override fun onCreate(db: SQLiteDatabase) {
        makeTables(db)
        seed(db)
    }

    /**
     * v2.28：改为**增量升级**。
     *
     * 旧版 onUpgrade 无条件 DROP 全部表再重建 —— 用户覆盖安装新版后，
     * 所有记账数据会被直接清空。这个雷不能留：以后新增表/列一律走
     * 「CREATE TABLE IF NOT EXISTS」补建（makeTables 全部如此），已有数据原样保留。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 7) {
            // v7：新增商户分类记忆表 merchant_cats；缺哪张补哪张，不动已有数据
            makeTables(db)
            seed(db)
        }
        if (oldVersion < 8) {
            // v2.29：v7 的记忆表把「微信支付」这类渠道通用名当商户，
            // 一条误改就把全部账单带成同一类别（用户实测两笔都变「彩票」）。
            // 升级时**只清空 merchant_cats**（这是可重建的偏好数据，不是账单），
            // records 一条都不动。旧表结构缺 type 列，一并重建。
            db.execSQL("DROP TABLE IF EXISTS merchant_cats")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS merchant_cats(" +
                    "merchant TEXT NOT NULL," +
                    "type INTEGER NOT NULL DEFAULT 0," +
                    "cat TEXT NOT NULL," +
                    "hits INTEGER NOT NULL DEFAULT 1," +
                    "updated_at INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(merchant,type))"
            )
        }
        if (oldVersion < 9) {
            // v2.30：新增独立「用户备注」列 remark。
            // 只 ADD COLUMN，不 DROP、不重建 —— 历史记录一条不动（v2.28 的教训）。
            // 为什么不能复用 note：note 是自动识别的商户/商品名，同时是商户分类记忆 key
            // 与查重 key；用户手写备注塞进去会污染分类记忆（上一轮刚修的「彩票串类」会复发）。
            if (!hasColumn(db, "records", "remark")) {
                db.execSQL("ALTER TABLE records ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
            }
        }
    }

    /** v2.30：安全加列 —— 防止 oldVersion 标记不准时重复 ADD COLUMN 直接崩溃 */
    private fun hasColumn(db: SQLiteDatabase, table: String, col: String): Boolean {
        db.rawQuery("PRAGMA table_info(" + table + ")", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) if (idx >= 0 && c.getString(idx) == col) return true
        }
        return false
    }

    private fun dropAll(db: SQLiteDatabase) {
        for (t in arrayOf("records", "cats", "books", "budgets", "merchant_cats", "merchant_memory")) {
            db.execSQL("DROP TABLE IF EXISTS $t")
        }
    }

    private fun makeTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS records(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "amount REAL NOT NULL," +
                "type INTEGER NOT NULL DEFAULT 0," +
                "cat TEXT NOT NULL DEFAULT '其它'," +
                "note TEXT NOT NULL DEFAULT ''," +
                "ts INTEGER NOT NULL," +
                "book TEXT NOT NULL DEFAULT '日常账本'," +
                "status INTEGER NOT NULL DEFAULT 1," +
                "src TEXT NOT NULL DEFAULT '手工'," +
                "remark TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cats(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "emoji TEXT NOT NULL DEFAULT '📌'," +
                "type INTEGER NOT NULL DEFAULT 0," +
                "sort INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS books(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS budgets(month TEXT NOT NULL,cat TEXT NOT NULL DEFAULT ''," +
                "amount REAL NOT NULL,PRIMARY KEY(month,cat))"
        )
        // v2.29：商户分类记忆表。key = 商户 + 收支方向；hits 记「累计调整到该类别的次数」
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS merchant_cats(" +
                "merchant TEXT NOT NULL," +
                "type INTEGER NOT NULL DEFAULT 0," +
                "cat TEXT NOT NULL," +
                "hits INTEGER NOT NULL DEFAULT 1," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(merchant,type))"
        )
    }

    private fun seed(db: SQLiteDatabase) {
        var n = 0
        db.rawQuery("SELECT COUNT(*) FROM cats", null).use { if (it.moveToFirst()) n = it.getInt(0) }
        if (n == 0) {
            var i = 0
            for (c in Cats.EXPENSE) {
                db.execSQL("INSERT INTO cats(name,emoji,type,sort) VALUES(?,?,0,?)", arrayOf(c.first, c.second, i++))
            }
            i = 0
            for (c in Cats.INCOME) {
                db.execSQL("INSERT INTO cats(name,emoji,type,sort) VALUES(?,?,1,?)", arrayOf(c.first, c.second, i++))
            }
        }
        var b = 0
        db.rawQuery("SELECT COUNT(*) FROM books", null).use { if (it.moveToFirst()) b = it.getInt(0) }
        if (b == 0) {
            for (name in Cats.BOOKS) db.execSQL("INSERT INTO books(name) VALUES(?)", arrayOf(name))
        }
    }

    // ---------- 分类 ----------

    fun cats(type: Int): List<CatDef> {
        val out = ArrayList<CatDef>()
        readableDatabase.rawQuery(
            "SELECT id,name,emoji,type FROM cats WHERE type=? ORDER BY sort,id",
            arrayOf(type.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(CatDef(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "📌", c.getInt(3)))
        }
        return out
    }

    fun addCat(name: String, emoji: String, type: Int) {
        var max = 0
        readableDatabase.rawQuery("SELECT IFNULL(MAX(sort),0) FROM cats WHERE type=?", arrayOf(type.toString()))
            .use { if (it.moveToFirst()) max = it.getInt(0) }
        writableDatabase.execSQL(
            "INSERT INTO cats(name,emoji,type,sort) VALUES(?,?,?,?)",
            arrayOf(name, emoji, type, max + 1)
        )
    }

    fun delCat(id: Long) {
        writableDatabase.execSQL("DELETE FROM cats WHERE id=?", arrayOf(id))
    }

    fun emojiOf(name: String): String {
        readableDatabase.rawQuery("SELECT emoji FROM cats WHERE name=? LIMIT 1", arrayOf(name)).use {
            if (it.moveToFirst()) return it.getString(0) ?: "📌"
        }
        return "📌"
    }

    // ---------- 账本 ----------

    fun books(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT name FROM books ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) ?: "")
        }
        if (out.isEmpty()) out.add("日常账本")
        return out
    }

    fun addBook(name: String) {
        writableDatabase.execSQL("INSERT INTO books(name) VALUES(?)", arrayOf(name))
    }

    // ---------- 账单 ----------

    fun addRecord(r: Rec): Long {
        val cv = ContentValues()
        cv.put("amount", r.amount)
        cv.put("type", r.type)
        cv.put("cat", r.cat)
        cv.put("note", r.note)
        cv.put("ts", r.ts)
        cv.put("book", r.book)
        cv.put("status", r.status)
        cv.put("src", r.src)
        cv.put("remark", r.remark)
        return writableDatabase.insert("records", null, cv)
    }

    fun updateRecord(id: Long, amount: Double, cat: String, note: String, ts: Long, status: Int) {
        writableDatabase.execSQL(
            "UPDATE records SET amount=?,cat=?,note=?,ts=?,status=? WHERE id=?",
            arrayOf(amount, cat, note, ts, status, id)
        )
    }

    /**
     * 合并同一笔时用：连收支方向（type）与分类一起覆盖。
     * 先前那条可能是把收入误判成了支出，只改金额/备注会把错误方向留下。
     */
    fun updateRecordFull(
        id: Long, amount: Double, type: Int, cat: String, note: String, ts: Long, status: Int
    ) {
        writableDatabase.execSQL(
            "UPDATE records SET amount=?,type=?,cat=?,note=?,ts=?,status=? WHERE id=?",
            arrayOf(amount, type, cat, note, ts, status, id)
        )
    }

    fun delRecord(id: Long) {
        writableDatabase.execSQL("DELETE FROM records WHERE id=?", arrayOf(id))
    }

    /**
     * v2.30：单独写「用户备注」，不碰 note / cat / ts / status。
     * 由悬浮卡片备注栏、备注输入页、待确认页、编辑页共用。
     * 与 updateRecord/updateRecordFull 的关键区别：那两个按字段覆盖整行，
     * 若用户先写备注再确认就会被冲掉 —— 故它们的 SQL 刻意不含 remark 列，天然保留。
     */
    fun setRemark(id: Long, text: String) {
        writableDatabase.execSQL(
            "UPDATE records SET remark=? WHERE id=?",
            arrayOf(text.trim(), id.toString())
        )
    }

    fun remarkOf(id: Long): String {
        readableDatabase.rawQuery("SELECT remark FROM records WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) return it.getString(0) ?: ""
        }
        return ""
    }

    fun get(id: Long): Rec? {
        readableDatabase.query("records", COLS, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) return read(c)
        }
        return null
    }

    fun list(from: Long, to: Long, book: String, type: Int, onlyConfirmed: Boolean, cat: String? = null): List<Rec> {
        val sb = StringBuilder("ts>=? AND ts<?")
        val args = arrayListOf(from.toString(), to.toString())
        if (book.isNotEmpty()) {
            sb.append(" AND book=?")
            args.add(book)
        }
        if (type >= 0) {
            sb.append(" AND type=?")
            args.add(type.toString())
        }
        // v2.28：类别筛选（图表排行点进来用），null = 不过滤
        if (!cat.isNullOrEmpty()) {
            sb.append(" AND cat=?")
            args.add(cat)
        }
        if (onlyConfirmed) sb.append(" AND status=1")
        val out = ArrayList<Rec>()
        readableDatabase.query("records", COLS, sb.toString(), args.toTypedArray(), null, null, "ts DESC,id DESC")
            .use { c ->
                while (c.moveToNext()) out.add(read(c))
            }
        return out
    }

    fun sum(from: Long, to: Long, book: String, type: Int): Double {
        readableDatabase.rawQuery(
            "SELECT IFNULL(SUM(amount),0) FROM records WHERE ts>=? AND ts<? AND type=? AND status=1 AND book=?",
            arrayOf(from.toString(), to.toString(), type.toString(), book)
        ).use { if (it.moveToFirst()) return it.getDouble(0) }
        return 0.0
    }

    /** 分类汇总，按金额从大到小 */
    fun catSum(from: Long, to: Long, book: String, type: Int): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        readableDatabase.rawQuery(
            "SELECT cat,SUM(amount) s FROM records WHERE ts>=? AND ts<? AND type=? AND status=1 AND book=? " +
                "GROUP BY cat ORDER BY s DESC",
            arrayOf(from.toString(), to.toString(), type.toString(), book)
        ).use { c ->
            while (c.moveToNext()) out.add(Pair(c.getString(0) ?: "其它", c.getDouble(1)))
        }
        return out
    }

    // ---------- 待确认 ----------

    fun pending(): List<Rec> {
        val out = ArrayList<Rec>()
        readableDatabase.query("records", COLS, "status=0", null, null, null, "ts DESC,id DESC")
            .use { c ->
                while (c.moveToNext()) out.add(read(c))
            }
        return out
    }

    fun pendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE status=0", null).use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    /** 全库笔数，用于待确认页自检：区分「数据没落库」与「渲染没出来」 */
    fun totalCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM records", null).use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    fun existsSimilar(amount: Double, note: String, ts: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM records WHERE amount=? AND note=? AND abs(ts-?)<120000 LIMIT 1",
            arrayOf(amount.toString(), note, ts.toString())
        ).use { return it.moveToFirst() }
    }

    /**
     * 只按金额 + 时间窗判重，用于「页面抓取」与「通知监听」两条通道之间互相去重：
     * 同一笔消费会同时被账单页和支付通知命中，两边提取出的商户名往往不同，
     * 用 [existsSimilar] 比不中，会入库两条。窗口取 20 秒。
     */
    fun existsAmountWithin(amount: Double, ts: Long, windowMs: Long): Boolean {
        // v2.34：CAST 必须加。rawQuery 的 selectionArgs 全是 String，
        // 旧写法 abs(ts-?)<? 中左边是表达式（无列 affinity，SQLite 判为 integer），
        // 右边是 TEXT '20000' —— SQLite 类型序 INTEGER < TEXT 恒为真，
        // 导致时间窗从未生效：任意相隔几小时的同额记录都被当成同一笔候选。
        readableDatabase.rawQuery(
            "SELECT 1 FROM records WHERE amount=? AND abs(ts-?)<CAST(? AS INTEGER) LIMIT 1",
            arrayOf(amount.toString(), ts.toString(), windowMs.toString())
        ).use { return it.moveToFirst() }
    }

    /**
     * 取出时间窗内「同金额」的最近一条记录，用于把同一笔支付的多次识别收敛成一条。
     *
     * 为什么按金额而不按商户名：同一笔在收银台、支付结果页、账单详情里提取到的商户名经常不一样，
     * 按商户名比对必然漏判。为什么必须带时间窗：两笔真实的同金额消费（先后买两杯咖啡）不能被吞掉。
     */
    fun recentSameAmount(amount: Double, ts: Long, windowMs: Long): Rec? {
        val cols = COLS.joinToString(",")
        // v2.34：同样必须 CAST(? AS INTEGER)，否则窗口形同虚设（详见 existsAmountWithin 注释）。
        readableDatabase.rawQuery(
            "SELECT $cols FROM records WHERE amount=? AND abs(ts-?)<CAST(? AS INTEGER) ORDER BY abs(ts-?) ASC LIMIT 1",
            arrayOf(amount.toString(), ts.toString(), windowMs.toString(), ts.toString())
        ).use { c -> if (c.moveToFirst()) return read(c) }
        return null
    }

    /**
     * v2.34：账单详情页的兜底查重 —— 解决「昨天支付现场页记的账，今天打开账单详情又记一遍」。
     *
     * 为什么 recentSameAmount 的严格窗口不够：
     *  昨天支付现场页没有绝对时间，入库 ts = 识别时刻（如 14:26）；
     *  今天账单详情的 pageTs = 真实付款时间（如 12:04）。两者相差几小时，
     *  20 秒严窗必然落空，于是同一笔被当成新账入库（用户实测 ¥28 重复）。
     *
     * 判据（四条同时满足，缺一不可）：
     *  ① amount / type 完全相同 —— 同额收入不并支出；
     *  ② ts >= fromTs（fromTs 传页面付款时间）—— 识别时刻不可能早于真实付款时刻；
     *  ③ ts 落在 fromTs 所在的那一天 —— 挡住 v2.25「4号/7号同额串单」；
     *  ④ ORDER BY ts ASC 取最早一条 —— 付款后第一次识别才是这笔本身，
     *     后续同日同额的其它消费不会被这一笔吞掉（v2.17「连续同额只弹2笔」的老坑）。
     *
     * 注意：这里 ts>=? / ts<? 是「列引用 vs 参数」，列有 INTEGER affinity，String 参数会被
     * 自动转成数字，不需要 CAST（已用 sqlite3 实证）；踩坑的只有 abs(ts-?) 这种表达式。
     */
    fun firstSameAmountAfter(amount: Double, type: Int, fromTs: Long): Rec? {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = fromTs
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val nextDayStart = dayStart + 24L * 60 * 60 * 1000
        val cols = COLS.joinToString(",")
        readableDatabase.rawQuery(
            "SELECT $cols FROM records WHERE amount=? AND type=? AND ts>=? AND ts<? ORDER BY ts ASC LIMIT 1",
            arrayOf(amount.toString(), type.toString(), fromTs.toString(), nextDayStart.toString())
        ).use { c -> if (c.moveToFirst()) return read(c) }
        return null
    }

    // ---------- 商户分类记忆（v2.28） ----------

    /**
     * v2.29：记住（或累计）某商户的惯用分类。
     * 用户原话是「某个商户**多次调整**它的类别」才该记忆 —— 改一次就永久记住太激进，
     * 一次误触（比如分类列表里「彩票」排第 6 位）就会污染全部同渠道账单。
     * 这里累计 hits，达到 2 次才真正作为默认值生效（见 [merchantCat]）。
     * 换了类别则重新计数，避免旧的误触一直压着新习惯。
     */
    fun rememberMerchantCat(merchant: String, cat: String, type: Int) {
        if (!isLearnableMerchant(merchant) || cat.isBlank()) return
        val m = merchant.trim().substringBefore('·').trim()
        val db = writableDatabase
        var old: String? = null
        var hits = 0
        db.rawQuery(
            "SELECT cat,hits FROM merchant_cats WHERE merchant=? AND type=?",
            arrayOf(m, type.toString())
        ).use { if (it.moveToFirst()) { old = it.getString(0); hits = it.getInt(1) } }
        val n = if (old == cat) hits + 1 else 1
        db.execSQL(
            "INSERT OR REPLACE INTO merchant_cats(merchant,type,cat,hits,updated_at) VALUES(?,?,?,?,?)",
            arrayOf(m, type.toString(), cat, n.toString(), System.currentTimeMillis().toString())
        )
    }

    /**
     * 查某商户的惯用分类。必须同时满足：
     * ① 商户名可学习；② 收支方向一致；③ 累计 ≥2 次调整（防单次误触）；
     * ④ 该分类仍然存在（用户删过分类就不能再套用）。任一不满足返回 null。
     */
    fun merchantCat(merchant: String, type: Int): String? {
        if (!isLearnableMerchant(merchant)) return null
        val m = merchant.trim().substringBefore('·').trim()
        var cat: String? = null
        readableDatabase.rawQuery(
            "SELECT cat,hits FROM merchant_cats WHERE merchant=? AND type=? LIMIT 1",
            arrayOf(m, type.toString())
        ).use { if (it.moveToFirst() && it.getInt(1) >= 2) cat = it.getString(0) }
        val c = cat ?: return null
        // 分类已被删除 → 记忆失效，顺手清掉
        readableDatabase.rawQuery(
            "SELECT 1 FROM cats WHERE name=? AND type=? LIMIT 1", arrayOf(c, type.toString())
        ).use { if (!it.moveToFirst()) {
            writableDatabase.execSQL(
                "DELETE FROM merchant_cats WHERE merchant=? AND type=?", arrayOf(m, type.toString())
            )
            return null
        } }
        return c
    }

    /** v2.29：清除全部商户分类记忆（「我的」页入口）。账单记录一条不动。 */
    fun clearMerchantCats() {
        writableDatabase.execSQL("DELETE FROM merchant_cats")
    }

    /** 已生效（hits>=2）的记忆条数，用于「我的」页显示 */
    fun merchantCatCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM merchant_cats WHERE hits>=2", null).use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    /**
     * v2.29：批量把若干笔记录改到指定消费日期（保留原来的时:分:秒）。
     * 旧版「悬浮卡确认」把 ts 覆写成确认时刻，且没有备份原始消费时间，
     * 程序无法凭空还原 —— 但用户完全不必删除重录，用这个接口一次改一批即可。
     */
    fun redateRecords(ids: List<Long>, dayTs: Long): Int {
        if (ids.isEmpty()) return 0
        val target = Calendar.getInstance()
        target.timeInMillis = dayTs
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for (id in ids) {
                var old = 0L
                db.rawQuery("SELECT ts FROM records WHERE id=?", arrayOf(id.toString())).use {
                    if (it.moveToFirst()) old = it.getLong(0)
                }
                if (old <= 0L) continue
                val c = Calendar.getInstance()
                c.timeInMillis = old
                c.set(Calendar.YEAR, target.get(Calendar.YEAR))
                c.set(Calendar.MONTH, target.get(Calendar.MONTH))
                c.set(Calendar.DAY_OF_MONTH, target.get(Calendar.DAY_OF_MONTH))
                db.execSQL("UPDATE records SET ts=? WHERE id=?",
                    arrayOf(c.timeInMillis.toString(), id.toString()))
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    // ---------- 预算 ----------

    fun budget(month: String): Double {
        readableDatabase.rawQuery(
            "SELECT amount FROM budgets WHERE month=? AND cat='' LIMIT 1",
            arrayOf(month)
        ).use { if (it.moveToFirst()) return it.getDouble(0) }
        return 0.0
    }

    fun setBudget(month: String, amount: Double) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO budgets(month,cat,amount) VALUES(?,'',?)",
            arrayOf(month, amount)
        )
    }

    private fun read(c: Cursor): Rec = Rec(
        id = c.getLong(0),
        amount = c.getDouble(1),
        type = c.getInt(2),
        cat = c.getString(3) ?: "其它",
        note = c.getString(4) ?: "",
        ts = c.getLong(5),
        book = c.getString(6) ?: "日常账本",
        status = c.getInt(7),
        src = c.getString(8) ?: "手工",
        remark = if (c.columnCount > 9) (c.getString(9) ?: "") else ""
    )
}
