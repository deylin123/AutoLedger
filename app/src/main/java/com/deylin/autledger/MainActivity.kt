package com.deylin.autledger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

class MainActivity : Activity() {

    lateinit var content: FrameLayout
    lateinit var host: FrameLayout
    private lateinit var warnBar: LinearLayout
    private var tab = 0
    private val navs = ArrayList<TextView>()
    private var helper: DbHelper? = null

    var curY: Int = 0
    var curM: Int = 0

    var book: String = "日常账本"
        set(v) {
            field = v
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("book", v).apply()
        }

    fun db(): DbHelper {
        var h = helper
        if (h == null) {
            h = DbHelper(this)
            helper = h
        }
        return h
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = Calendar.getInstance()
        curY = now.get(Calendar.YEAR)
        curM = now.get(Calendar.MONTH)
        book = getSharedPreferences("cfg", MODE_PRIVATE).getString("book", "日常账本") ?: "日常账本"
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
            } catch (_: Exception) {
            }
        }

        val root = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Ui.BG)

        warnBar = Ui.col(this)
        warnBar.visibility = View.GONE
        col.addView(warnBar, Ui.lp(Ui.MP, Ui.WC))

        content = FrameLayout(this)
        col.addView(content, Ui.lp(Ui.MP, 0, 1f))
        col.addView(buildNav(), Ui.lp(Ui.MP, Ui.dp(this, 58f)))

        root.addView(col, FrameLayout.LayoutParams(Ui.MP, Ui.MP))
        host = FrameLayout(this)
        root.addView(host, FrameLayout.LayoutParams(Ui.MP, Ui.MP))
        setContentView(root)

        show(0)
    }

    private fun buildNav(): View {
        val bar = Ui.col(this)
        bar.setBackgroundColor(Ui.CARD)
        val line = Ui.divider(this)
        bar.addView(line, Ui.lp(Ui.MP, 1))
        val row = Ui.row(this)
        bar.addView(row, Ui.lp(Ui.MP, 0, 1f))

        val titles = arrayOf("明细", "图表", "＋", "我的")
        for (i in titles.indices) {
            val t = Ui.tv(this, titles[i], if (i == 2) 24f else 12f, if (i == 2) Ui.ORANGE else Ui.SUB, i == 2)
            t.gravity = Gravity.CENTER
            t.layoutParams = Ui.lp(0, Ui.MP, 1f)
            t.setOnClickListener {
                when (i) {
                    2 -> PageEntry(this).show(null)
                    3 -> show(2)
                    else -> show(i)
                }
            }
            row.addView(t)
            navs.add(t)
        }
        return bar
    }

    fun show(i: Int) {
        tab = i
        content.removeAllViews()
        val v: View = when (i) {
            0 -> PageDetail(this).build()
            1 -> PageChart(this).build()
            else -> PageMine(this).build()
        }
        content.addView(v, FrameLayout.LayoutParams(Ui.MP, Ui.MP))
        for (k in navs.indices) {
            if (k == 2) continue
            val selected = (k == 3 && i == 2) || (k != 3 && k == i)
            navs[k].setTextColor(if (selected) Ui.ORANGE else Ui.SUB)
        }
    }

    fun refresh() {
        show(tab)
    }

    override fun onResume() {
        super.onResume()
        if (this::content.isInitialized && content.childCount > 0) show(tab)
        refreshWarn()
    }

    // ---------- 自动弹窗就绪检查 ----------

    fun accOn(): Boolean {
        val s = try {
            Settings.Secure.getString(contentResolver, "enabled_accessibility_services")
        } catch (_: Exception) {
            null
        } ?: return false
        return s.contains(packageName)
    }

    fun missingPerms(): MutableList<String> {
        val out = ArrayList<String>()
        if (!accOn()) out.add("无障碍（账单页识别）")
        if (!Settings.canDrawOverlays(this)) out.add("悬浮窗（在别的App上面弹窗）")
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) out.add("通知（弹窗被拦时兜底提醒）")
        return out
    }

    fun refreshWarn() {
        if (!this::warnBar.isInitialized) return
        warnBar.removeAllViews()
        val miss = missingPerms()
        if (miss.isEmpty()) {
            warnBar.visibility = View.GONE
            return
        }
        warnBar.visibility = View.VISIBLE
        warnBar.setBackgroundColor(Ui.ORANGE_LIGHT)
        val t = Ui.tv(
            this,
            "自动弹窗还差 ${miss.size} 项权限未开，缺任意一项都不会在别的App上面弹窗：" +
                miss.joinToString("、"),
            11.5f, Ui.ORANGE_DEEP
        )
        t.setPadding(Ui.dp(this, 14f), Ui.dp(this, 9f), Ui.dp(this, 14f), Ui.dp(this, 4f))
        warnBar.addView(t, Ui.lp(Ui.MP, Ui.WC))
        val t2 = Ui.tv(this, "点此逐项开启 →", 11.5f, Ui.ORANGE_DEEP, true)
        t2.setPadding(Ui.dp(this, 14f), 0, Ui.dp(this, 14f), Ui.dp(this, 10f))
        warnBar.addView(t2, Ui.lp(Ui.MP, Ui.WC))
        warnBar.setOnClickListener { showPermDialog() }
    }

    fun showPermDialog() {
        val miss = missingPerms()
        if (miss.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("开启自动弹窗所需权限")
            .setItems(miss.toTypedArray()) { _, w ->
                val m = miss[w]
                when {
                    m.startsWith("无障碍") -> openAccessibility()
                    m.startsWith("悬浮窗") -> openOverlay()
                    else -> if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
                    }
                }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    // ---------- 权限入口 ----------

    fun openNotifListener() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
        }
    }

    fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
        }
    }

    fun openOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
        }
    }

    fun openBattery() {
        // v2.19：直接申请本应用豁免，而不是把用户丢到一堆 App 的列表里自己找
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }
}
