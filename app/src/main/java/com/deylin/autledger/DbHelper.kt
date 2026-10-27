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
    var src: String = "手工"
)

/** 分类定义 */
data class CatDef(val id: Long, val name: String, val emoji: String, val type: Int)

class DbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "autoledger.db", null, 6) {

    companion object {
        val COLS = arrayOf("id", "amount", "type", "cat", "note", "ts", "book", "status", "src")

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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        dropAll(db)
        makeTables(db)
        seed(db)
    }

    private fun dropAll(db: SQLiteDatabase) {
        for (t in arrayOf("records", "cats", "books", "budgets")) {
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
                "src TEXT NOT NULL DEFAULT '手工')"
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
        return writableDatabase.insert("records", null, cv)
    }

    fun updateRecord(id: Long, amount: Double, cat: String, note: String, ts: Long, status: Int) {
        writableDatabase.execSQL(
            "UPDATE records SET amount=?,cat=?,note=?,ts=?,status=? WHERE id=?",
            arrayOf(amount, cat, note, ts, status, id)
        )
    }

    fun delRecord(id: Long) {
        writableDatabase.execSQL("DELETE FROM records WHERE id=?", arrayOf(id))
    }

    fun get(id: Long): Rec? {
        readableDatabase.query("records", COLS, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) return read(c)
        }
        return null
    }

    fun list(from: Long, to: Long, book: String, type: Int, onlyConfirmed: Boolean): List<Rec> {
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
        src = c.getString(8) ?: "手工"
    )
}
