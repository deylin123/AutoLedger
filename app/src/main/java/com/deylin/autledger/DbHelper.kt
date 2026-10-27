package com.deylin.autledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class Rec(
    val id: Long,
    val amount: Double,
    val merchant: String,
    val category: String,
    val ts: Long,
    val status: Int // 0=待确认 1=已确认 2=已忽略
)

class DbHelper(context: Context) : SQLiteOpenHelper(context, "ledger.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE records(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "amount REAL NOT NULL, " +
                "merchant TEXT, " +
                "category TEXT, " +
                "note TEXT, " +
                "ts INTEGER, " +
                "status INTEGER DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(amount: Double, merchant: String, ts: Long, status: Int): Long {
        val cv = ContentValues().apply {
            put("amount", amount)
            put("merchant", merchant)
            put("category", "待确认")
            put("ts", ts)
            put("status", status)
        }
        return writableDatabase.insert("records", null, cv)
    }

    /** 抓屏链路：点确认后才入库（直接已确认） */
    fun insertFinal(amount: Double, merchant: String, category: String, ts: Long): Long {
        val cv = ContentValues().apply {
            put("amount", amount)
            put("merchant", merchant)
            put("category", category)
            put("ts", ts)
            put("status", 1)
        }
        return writableDatabase.insert("records", null, cv)
    }

    /** 防重：同金额+同商户+2分钟内视为同一笔 */
    fun existsSimilar(amount: Double, merchant: String, ts: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM records WHERE amount=? AND merchant=? AND status<2 AND abs(ts-?)<120000 LIMIT 1",
            arrayOf(amount.toString(), merchant, ts.toString())
        ).use { return it.moveToFirst() }
    }

    fun get(id: Long): Rec? =
        readableDatabase.rawQuery(
            "SELECT id,amount,merchant,category,ts,status FROM records WHERE id=?",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst())
                Rec(c.getLong(0), c.getDouble(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5))
            else null
        }

    fun confirm(id: Long, category: String) {
        writableDatabase.execSQL(
            "UPDATE records SET category=?, status=1 WHERE id=?",
            arrayOf(category, id)
        )
    }

    fun setStatus(id: Long, st: Int) {
        writableDatabase.execSQL("UPDATE records SET status=? WHERE id=?", arrayOf(st, id))
    }

    fun recent(n: Int): List<Rec> {
        val out = ArrayList<Rec>()
        readableDatabase.rawQuery(
            "SELECT id,amount,merchant,category,ts,status FROM records ORDER BY ts DESC LIMIT $n", null
        ).use { c ->
            while (c.moveToNext())
                out.add(Rec(c.getLong(0), c.getDouble(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5)))
        }
        return out
    }

    fun monthTotal(): Double {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount),0) FROM records WHERE status=1 AND ts>=?",
            arrayOf(cal.timeInMillis.toString())
        ).use { c ->
            c.moveToFirst()
            return c.getDouble(0)
        }
    }
}
