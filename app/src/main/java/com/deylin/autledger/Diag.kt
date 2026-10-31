package com.deylin.autledger

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抓取诊断日志：记录每次扫描与弹窗的结果，便于定位「为什么不弹窗」。
 *
 * v2.19 三项改动（目的一：让问题可观测）：
 *  ① 容量 40 → 300 条，时间戳带日期。旧版只留 40 条且只有 HH:mm:ss，
 *     实测七八笔之后关键记录已被挤掉、跨天也分不清先后 —— 用户反馈「哪几笔没弹」时无从查证。
 *  ② 支持导出：一键写入「下载/AutoLedger-诊断-*.txt」，不再只能对着屏幕截图。
 *  ③ 弹窗链路打点（见 [logCard]）：卡片是「调用了 / 通知已发 / 窗口已建 / 被什么挡住」，
 *     逐段留痕，专门用来区分「没识别到」和「识别到了但没弹出来」。
 */
object Diag {
    private const val PREF = "diag"
    private const val KEY = "diag_log"
    private const val MAX = 300

    fun log(ctx: Context, line: String) {
        try {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val old = sp.getString(KEY, "") ?: ""
            val lines = (old.split("\n").filter { it.isNotBlank() } + "$now $line").takeLast(MAX)
            sp.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (_: Exception) {
        }
    }

    /** 弹窗链路打点：前缀固定为「弹窗」，导出后可一眼筛出所有弹窗相关记录 */
    fun logCard(ctx: Context, line: String) = log(ctx, "弹窗 · $line")

    fun dump(ctx: Context, n: Int): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = sp.getString(KEY, "") ?: ""
        val lines = old.split("\n").filter { it.isNotBlank() }
        return if (lines.isEmpty()) {
            "（暂无抓取记录：说明无障碍服务没收到任何页面事件，请检查「账单页抓取」是否开启）"
        } else {
            "版本 ${verName(ctx)}｜共 ${lines.size} 条，显示最近 $n 条\n\n" + lines.takeLast(n).joinToString("\n")
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, "").apply()
    }

    private fun verName(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "${pi.versionName} ($code)"
    } catch (_: Exception) {
        "?"
    }

    /**
     * 导出全部日志到「下载」目录，返回文件路径（失败返回 null）。
     * Android 10+ 走 MediaStore，不需要存储权限；更老的系统退到应用外部私有目录。
     */
    fun export(ctx: Context): String? {
        return try {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val body = (sp.getString(KEY, "") ?: "").ifBlank { "（暂无记录）" }
            val text = "AutoLedger 抓取诊断\n版本 ${verName(ctx)}\n导出时间 " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()) +
                "\n" + "-".repeat(40) + "\n" + body + "\n"
            val name = "AutoLedger-诊断-" +
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date()) + ".txt"

            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
                "下载/$name"
            } else {
                val dir = ctx.getExternalFilesDir(null) ?: return null
                val f = File(dir, name)
                f.writeText(text, Charsets.UTF_8)
                f.absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }
}
