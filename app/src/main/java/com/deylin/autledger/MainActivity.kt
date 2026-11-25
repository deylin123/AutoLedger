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

    /** v2.28：图表排行点进来的分类筛选状态（null = 不筛选）；点「明细」tab 即清除 */
    var filterCat: String? = null
    var filterType: Int = 0

    /**
     * v2.31：明细页流水列表的滚动位置。
     *
     * refresh() 会把整个明细页拆掉重建，新建的 ScrollView 默认 scrollY=0；
     * 流水按日期倒序排列，顶部就是最新一天。于是「改完 11 月 18 日那笔 → 保存」
     * 会跳回 11 月 22 日，用户得重新往下翻。这里把用户实际停的位置记下来，
     * 重建后滚回去。
     */
    var detailScrollY: Int = 0

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
                    0 -> {
                        // 直接点「明细」= 清除图表带来的分类筛选
                        filterCat = null
                        detailScrollY = 0
                        show(0)
                    }
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

    /**
     * v2.31：区分两种刷新。
     * @param keepScroll true  = 数据变更后的原地刷新（编辑/改分类/写备注/删除/批量改日期），
     *                           保留用户当前浏览位置；
     *                   false = 视图条件变化（切月份/账本/筛选/tab），从顶部开始。
     */
    fun refresh(keepScroll: Boolean = false) {
        if (!keepScroll) detailScrollY = 0
        show(tab)
    }

    override fun onResume() {
        super.onResume()
        if (this::content.isInitialized && content.childCount > 0) show(tab)
        refreshWarn()
    }

    // ---------- 自动弹窗就绪检查 ----------

    fun accOn(): Boolean {
        // v2.38：双信号。
        // 真机日志（2026-11-24）证明 MIUI 上 `enabled_accessibility_services` 读不到本应用包名，
        // 而服务其实一直在跑（日志里既有「无障碍服务已连接」，又有每两分钟的「服务在线」心跳），
        // 于是权限条长期误报「无障碍未开」，用户被引去反复开关无障碍 —— 排查方向直接跑偏。
        // 服务实例存在是比系统设置串更硬的事实：实例只在 onServiceConnected 时登记、onDestroy 时清除。
        if (BillAccessibilityService.current() != null) return true
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
        val ocrNeed = ocrNeedPermission()
        // v2.38：悬浮卡片是否被系统拦下，按「实际建窗结果」判断。
        // 真机日志（2026-11-24）里 Activity 查 canDrawOverlays 与 OverlayUi 建窗的结论相反，
        // 结果就是权限条一句话都不说，而卡片一次都没出现过。
        val cardBad = cardBlocked() && miss.none { it.startsWith("悬浮窗") }
        if (miss.isEmpty() && !ocrNeed && !cardBad) {
            warnBar.visibility = View.GONE
            return
        }
        warnBar.visibility = View.VISIBLE
        warnBar.setBackgroundColor(Ui.ORANGE_LIGHT)
        val lines = ArrayList<String>()
        if (miss.isNotEmpty()) lines.add(
            "自动弹窗还差 ${miss.size} 项权限未开，缺任意一项都不会在别的App上面弹窗：" +
                miss.joinToString("、")
        )
        if (cardBad) lines.add(
            "悬浮卡片被系统拦住了（通知仍可确认记账）：点此开启「显示在其他应用上层 / 后台弹出界面」"
        )
        if (lines.isEmpty()) {
            // v2.37：截屏是「微信转账结果页」这条补充通道的前提，不影响其它 App 的弹窗，
            // 所以单独提示，不混进上面那句「缺一项都不弹」里，避免吓人。
            lines.add("微信转账结果页的识别还需要「屏幕截取」授权（只需一次），点此测试")
        }
        for ((i, s) in lines.withIndex()) {
            val t = Ui.tv(this, s, 11.5f, Ui.ORANGE_DEEP)
            t.setPadding(
                Ui.dp(this, 14f),
                Ui.dp(this, if (i == 0) 9f else 2f),
                Ui.dp(this, 14f),
                Ui.dp(this, 3f)
            )
            warnBar.addView(t, Ui.lp(Ui.MP, Ui.WC))
        }
        val t2 = Ui.tv(this, "点此逐项开启 →", 11.5f, Ui.ORANGE_DEEP, true)
        t2.setPadding(Ui.dp(this, 14f), Ui.dp(this, 2f), Ui.dp(this, 14f), Ui.dp(this, 10f))
        warnBar.addView(t2, Ui.lp(Ui.MP, Ui.WC))
        warnBar.setOnClickListener { showPermDialog() }
    }

    /** v2.38：卡片通道的真实结局（ok / no_perm / addview:Xxx / zero），由 OverlayUi 落盘 */
    fun cardState(): String =
        getSharedPreferences("cfg", MODE_PRIVATE).getString("card_state", "") ?: ""

    fun cardBlocked(): Boolean {
        val s = cardState()
        return s == "no_perm" || s.startsWith("addview:") || s == "zero"
    }

    /**
     * v2.37：截屏 OCR 通道状态。
     * ok=已就绪；fail:code=系统拒绝了截屏；unsupported=系统版本不支持；空=从未跑过。
     */
    fun ocrState(): String =
        getSharedPreferences("cfg", MODE_PRIVATE).getString("ocr_state", "") ?: ""

    /** 微信转账结果页识别是否还缺截屏授权（不影响其它 App 的弹窗识别） */
    fun ocrNeedPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val s = ocrState()
        return s != "ok" && s != "unsupported"
    }

    /**
     * v2.37：测试屏幕截取。
     *
     * 微信转账结果页是微信自绘的，文字不进无障碍树，只能截屏 + OCR 才能第一时间记账。
     * 而 takeScreenshot 首次必须由用户点系统授权框 —— 之前这个环节完全黑盒，
     * 用户只看到「转账还是不弹」。现在把它做成一个用户能自己点、能自己看结果的按钮。
     */
    fun probeOcr() {
        val svc = BillAccessibilityService.current()
        if (svc == null) {
            AlertDialog.Builder(this)
                .setTitle("请先开启无障碍")
                .setMessage(
                    "屏幕截取依赖无障碍服务，现在无障碍还没连上。\n\n" +
                        "请先开启「无障碍（账单页识别）」，回到这里再点一次「屏幕截取」测试。"
                )
                .setPositiveButton("去开启") { _, _ -> openAccessibility() }
                .setNegativeButton("知道了", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("测试屏幕截取")
            .setMessage(
                "微信转账的结果页是微信自己绘制的，文字读不进系统，只能截屏识别文字来记账。\n\n" +
                    "点「开始测试」后系统会弹一次「允许截取屏幕内容」，请点允许。\n" +
                    "本 App 只对微信截屏，其它 App 一律不截。"
            )
            .setPositiveButton("开始测试") { _, _ -> svc.probeScreenshot() }
            .setNegativeButton("稍后", null)
            .show()
    }

    fun showPermDialog() {
        val items = ArrayList(missingPerms())
        // v2.38：权限查询说「已开」但实际建不出窗时，仍然要能从这里跳去开权限
        // v2.40：拆成两项。小米/红米要开两道，「后台弹出界面」在「其他权限」里，
        // 很多人只开了「显示悬浮窗」，卡片依旧不出现（addView 被吞、宽为 0）。
        if (cardBlocked()) {
            if (items.none { it.startsWith("悬浮窗") })
                items.add("悬浮窗（显示在其他应用上层）")
            if (items.none { it.startsWith("后台弹出") })
                items.add("后台弹出界面（小米/红米必开，在「其他权限」里）")
        }
        // v2.37：屏幕截取单独列一项，只影响微信转账结果页这条补充通道
        if (ocrNeedPermission()) items.add("屏幕截取（微信转账结果页识别）")
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("开启自动弹窗所需权限")
            .setItems(items.toTypedArray()) { _, w ->
                val m = items[w]
                when {
                    m.startsWith("无障碍") -> openAccessibility()
                    m.startsWith("悬浮窗") -> openOverlay()
                    m.startsWith("后台弹出") -> openAppDetails()
                    m.startsWith("屏幕截取") -> probeOcr()
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

    /**
     * v2.40：HyperOS / MIUI 上 ACTION_MANAGE_OVERLAY_PERMISSION 常常没有对应页面，
     * 旧实现只有一条 Intent + 空 catch —— 异常被吞掉，用户看到的就是
     * 「点了那一行字什么也没发生，弹窗里只剩一个『稍后』」。
     * 真机反馈（2026-11-25）正是如此。改为逐级降级，并在全部失败时给出手动路径。
     */
    fun openOverlay() {
        val pkgUri = Uri.parse("package:$packageName")
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            // 应用详情页：HyperOS 的「权限管理 / 其他权限」都在这一层进去
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        )
        for (it0 in candidates) {
            try {
                if (it0.resolveActivity(packageManager) == null) continue
                it0.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it0)
                return
            } catch (_: Exception) {
            }
        }
        toast("没跳转成功。请手动：设置 → 应用设置 → 应用管理 → AutoLedger → 权限管理 → 显示悬浮窗")
    }

    /** 后台弹出界面（小米/红米专有）没有公开 Action，只能进应用详情页再逐层点 */
    fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            toast("请手动：设置 → 应用设置 → 应用管理 → AutoLedger → 权限管理 → 其他权限 → 后台弹出界面")
        }
    }

    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
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
