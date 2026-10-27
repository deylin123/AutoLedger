package com.deylin.autledger

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 抓取诊断日志：记录每次扫描的结果，便于定位「为什么不弹窗」 */
object Diag {
    private const val PREF = "diag"
    private const val KEY = "diag_log"
    private const val MAX = 40

    fun log(ctx: Context, line: String) {
        try {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val now = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            val old = sp.getString(KEY, "") ?: ""
            val lines = (old.split("\n").filter { it.isNotBlank() } + "$now $line").takeLast(MAX)
            sp.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (_: Exception) {
        }
    }

    fun dump(ctx: Context, n: Int): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = sp.getString(KEY, "") ?: ""
        val lines = old.split("\n").filter { it.isNotBlank() }
        return if (lines.isEmpty()) "（暂无抓取记录：说明无障碍服务没收到任何页面事件，请检查「权限2」是否开启）"
        else lines.takeLast(n).joinToString("\n")
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, "").apply()
    }
}
