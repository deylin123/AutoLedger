package com.deylin.autledger

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 我的：账本、预算、分类管理、自动记账权限、数据导出、诊断 */
class PageMine(private val act: MainActivity) {

    private val c: Context = act

    fun build(): View {
        val root = Ui.col(c)
        root.setBackgroundColor(Ui.BG)

        val top = Ui.row(c)
        Ui.pad(top, 16, 14, 16, 6)
        top.addView(Ui.tv(c, "我的", 18f, Ui.TEXT, true))
        root.addView(top, Ui.lp(Ui.MP, Ui.WC))

        val sv = ScrollView(c)
        sv.isFillViewport = true
        val body = Ui.col(c)
        Ui.pad(body, 14, 4, 14, 30)

        // ---- 账本 ----
        val bookCard = card("账本")
        val chips = Ui.col(c)
        var line: LinearLayout? = null
        val books = act.db().books()
        for (i in books.indices) {
            if (i % 3 == 0) {
                line = Ui.row(c)
                chips.addView(line, Ui.lp(Ui.MP, Ui.WC))
            }
            val b = chipText(books[i], books[i] == act.book)
            b.setOnClickListener {
                act.book = books[i]
                act.refresh()
            }
            val p = Ui.lp(0, Ui.WC, 1f)
            p.setMargins(0, Ui.dp(c, 4f), Ui.dp(c, 6f), Ui.dp(c, 4f))
            line?.addView(b, p)
        }
        val filler = (3 - books.size % 3) % 3
        for (i in 0 until filler) {
            val sp = View(c)
            val p = Ui.lp(0, Ui.WC, 1f)
            p.setMargins(0, 0, Ui.dp(c, 6f), 0)
            line?.addView(sp, p)
        }
        bookCard.addView(chips)
        val addBook = chipText("＋ 新建账本", false)
        addBook.setOnClickListener { newBookDialog { act.refresh() } }
        val abp = Ui.lp(Ui.WC, Ui.WC)
        abp.topMargin = Ui.dp(c, 6f)
        bookCard.addView(addBook, abp)
        body.addView(bookCard, Ui.lp(Ui.MP, Ui.WC))

        // ---- 预算 ----
        val month = String.format(Locale.CHINA, "%04d-%02d", act.curY, act.curM + 1)
        val budget = act.db().budget(month)
        val budgetCard = card("预算（${act.curM + 1}月）")
        budgetCard.addView(
            rowItem(
                "本月总预算",
                if (budget > 0) "¥" + Ui.money(budget) else "未设置"
            ) { setBudgetDialog(month, budget) }
        )
        val bcp = Ui.lp(Ui.MP, Ui.WC)
        bcp.topMargin = Ui.dp(c, 10f)
        body.addView(budgetCard, bcp)

        // ---- 分类 ----
        val catCard = card("分类")
        catCard.addView(rowItem("分类管理", "增删自定义分类") { manageCats() })
        val ccp = Ui.lp(Ui.MP, Ui.WC)
        ccp.topMargin = Ui.dp(c, 10f)
        body.addView(catCard, ccp)

        // ---- 自动记账 ----
        val autoCard = card("自动记账")
        val pend = act.db().pendingCount()
        autoCard.addView(
            rowItem("待确认账单", if (pend > 0) "$pend 笔" else "无") {
                act.startActivity(Intent(act, PendingActivity::class.java))
            }
        )
        autoCard.addView(permRow("通知监听", "读取各 App 的支付成功通知", hasNotifListener()) { act.openNotifListener() })
        autoCard.addView(permRow("账单页抓取", "支付宝/微信/电商/外卖/出行等账单页", hasAccessibility()) { act.openAccessibility() })
        autoCard.addView(permRow("悬浮窗", "抓取后屏幕顶部弹记账表单", Settings.canDrawOverlays(act)) { act.openOverlay() })
        autoCard.addView(permRow("后台保活", "关闭省电限制，避免被系统清理", true) { act.openBattery() })
        autoCard.addView(rowItem("支持范围", "26 个消费类 App + 通用兜底", true) { supportGuide() })
        autoCard.addView(rowItem("小米 / 红米 必看", "4 处设置，缺一项就不弹", true) { miuiGuide() })
        autoCard.addView(rowItem("测试弹窗", "点一下验证悬浮窗能不能弹") { testOverlay() })
        val acp = Ui.lp(Ui.MP, Ui.WC)
        acp.topMargin = Ui.dp(c, 10f)
        body.addView(autoCard, acp)

        // ---- 数据 ----
        val dataCard = card("数据")
        dataCard.addView(rowItem("导出账单 CSV", "") { exportCsv() })
        dataCard.addView(rowItem("抓取诊断日志", "") { showDiag() })
        dataCard.addView(rowItem("关于", "版本 " + verName(), arrow = false) {})
        val dcp = Ui.lp(Ui.MP, Ui.WC)
        dcp.topMargin = Ui.dp(c, 10f)
        body.addView(dataCard, dcp)

        val tip = Ui.tv(c, "账单只存在本机，不上传任何服务器", 11f, Ui.SUB)
        tip.gravity = Gravity.CENTER
        tip.setPadding(0, Ui.dp(c, 16f), 0, 0)
        body.addView(tip, Ui.lp(Ui.MP, Ui.WC))

        sv.addView(body, Ui.lp(Ui.MP, Ui.WC))
        root.addView(sv, Ui.lp(Ui.MP, 0, 1f))
        return root
    }

    // ---------- 版本与弹窗自检 ----------

    private fun verName(): String = try {
        val pi = act.packageManager.getPackageInfo(act.packageName, 0)
        val name = pi.versionName ?: "?"
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "$name (build $code)"
    } catch (_: Exception) {
        "未知"
    }

    /** 支持范围说明：让用户知道哪些 App 已覆盖、哪些抓不到，不用猜 */
    private fun supportGuide() {
        val names = PayApps.APPS.joinToString("、") { it.name }
        val msg = "【已单独适配】\n$names\n\n" +
            "【通用兜底】\n不在上面名单里的 App，只要页面出现「账单详情 / 交易详情 / 订单详情 / 支付凭证」" +
            "这类结构词，且有金额和收支特征，也会识别。首次遇到会记一条诊断日志（同一 App 只记一次），" +
            "方便后续补进名单。\n\n" +
            "【收支方向】\n页面出现「已收款 / 收款到账 / 已到账 / 退款成功」等，记为收入；" +
            "出现「支出 / 实付 / 支付成功 / 转账给」等，记为支出。方向判不出来的一律不记，" +
            "避免把收入记成支出污染账本。\n\n" +
            "【通知兜底】\n任何 App 只要推了「支付成功 / 付款成功 / 已支付 / 扣款成功 / 收款到账」类通知且带金额，" +
            "都会被记录 —— 这条覆盖了大量页面读不到文字的自绘 / 网页型 App。\n\n" +
            "【抓不到的】\n① 现金、银行卡刷卡、云闪付、Apple Pay（支付动作没经过这些 App）；\n" +
            "② 完全自绘且不发通知的页面；\n" +
            "③ 手动关掉通知或无障碍权限时。\n\n" +
            "抓不到可以手记，两者进同一个账本。"
        AlertDialog.Builder(act)
            .setTitle("自动识别支持范围")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 小米/红米的额外拦截说明：只开「悬浮窗」不够，必须再开「后台弹出界面」 */
    private fun miuiGuide() {
        val msg = "小米/红米（MIUI、HyperOS）除「悬浮窗」外，还有一个独立权限会拦截后台弹窗。任一项没开，\n" +
            "在任意 App 里消费都不会弹卡片。\n\n" +
            "① 设置 → 应用设置 → 应用管理 → 自动记账 → 权限管理 → 其它权限 →「后台弹出界面」→ 始终允许\n" +
            "② 同一页 →「显示悬浮窗」→ 允许\n" +
            "③ 应用设置 → 应用管理 → 自动记账 → 自启动 → 打开；并在最近任务列表里下拉本App锁定\n" +
            "④ 应用设置 → 应用管理 → 自动记账 → 省电策略 → 无限制\n\n" +
            "关键：①②是两个不同权限，只开②不够。\n\n" +
            "另外，v2.3 起通知栏提醒已改为无条件发送 —— 通知不受上述权限限制，\n" +
            "即使卡片被拦，下拉通知栏也能看到并点进去确认，账不会丢。"
        AlertDialog.Builder(act)
            .setTitle("小米 / 红米 必看设置")
            .setMessage(msg)
            .setPositiveButton("打开本应用设置") { _, _ ->
                try {
                    act.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + act.packageName)
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(act, "请手动进入系统设置", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    /** 不依赖抓取，直接验证悬浮窗是否真的能弹出来 */
    private fun testOverlay() {
        if (!Settings.canDrawOverlays(act)) {
            Toast.makeText(c, "悬浮窗权限未开启，无法弹窗", Toast.LENGTH_SHORT).show()
            act.openOverlay()
            return
        }
        try {
            val wm = act.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val box = Ui.card(c, 16f, 14f)
            box.setBackground(Ui.round(c, Ui.CARD, 18f, 1f, Ui.LINE))
            box.addView(Ui.tv(c, "弹窗测试成功", 15f, Ui.TEXT, true), Ui.lp(Ui.WC, Ui.WC))
            box.addView(
                Ui.tv(c, "看到这个卡片说明悬浮窗可用，抓取后就能自动弹", 12f, Ui.SUB),
                Ui.lp(Ui.WC, Ui.WC)
            )
            val holder = android.widget.FrameLayout(c)
            holder.setPadding(Ui.dp(c, 16f), 0, Ui.dp(c, 16f), 0)
            holder.addView(
                box,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            val p = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.BOTTOM
            p.y = Ui.dp(c, 120f)
            wm.addView(holder, p)
            Toast.makeText(c, "已弹出，3 秒后自动消失", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    wm.removeView(holder)
                } catch (_: Exception) {
                }
            }, 3500)
        } catch (e: Exception) {
            Toast.makeText(c, "弹窗失败：" + e.javaClass.simpleName, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 组件 ----------

    private fun card(title: String): LinearLayout {
        val cd = Ui.card(c, 14f, 8f)
        val t = Ui.tv(c, title, 12f, Ui.SUB, true)
        t.setPadding(0, Ui.dp(c, 4f), 0, Ui.dp(c, 6f))
        cd.addView(t)
        return cd
    }

    private fun chipText(text: String, selected: Boolean): TextView {
        val t = Ui.tv(c, text, 12.5f, if (selected) 0xFFFFFFFF.toInt() else Ui.TEXT, selected)
        t.gravity = Gravity.CENTER
        t.setBackground(Ui.round(c, if (selected) Ui.ORANGE else 0xFFF7F1E8.toInt(), 14f))
        t.setPadding(0, Ui.dp(c, 9f), 0, Ui.dp(c, 9f))
        return t
    }

    private fun rowItem(title: String, value: String, arrow: Boolean = true, onClick: () -> Unit): View {
        val row = Ui.row(c)
        Ui.pad(row, 0, 12, 0, 12)
        row.addView(Ui.tv(c, title, 14f, Ui.TEXT), Ui.lp(0, Ui.WC, 1f))
        if (value.isNotEmpty()) row.addView(Ui.tv(c, value, 12f, Ui.SUB))
        if (arrow) {
            val a = Ui.tv(c, "›", 16f, Ui.SUB)
            a.setPadding(Ui.dp(c, 6f), 0, 0, 0)
            row.addView(a)
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun permRow(title: String, desc: String, ok: Boolean, onClick: () -> Unit): View {
        val row = Ui.row(c)
        Ui.pad(row, 0, 11, 0, 11)
        val col = Ui.col(c)
        col.addView(Ui.tv(c, title, 14f, Ui.TEXT))
        val d = Ui.tv(c, desc, 11f, Ui.SUB)
        d.setPadding(0, Ui.dp(c, 3f), 0, 0)
        col.addView(d)
        row.addView(col, Ui.lp(0, Ui.WC, 1f))
        val st = Ui.tv(c, if (ok) "已开启" else "去开启", 12f, if (ok) Ui.GREEN else Ui.ORANGE, true)
        row.addView(st)
        row.setOnClickListener { onClick() }
        return row
    }

    // ---------- 权限状态 ----------

    private fun hasNotifListener(): Boolean {
        val s = Settings.Secure.getString(act.contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(act.packageName)
    }

    private fun hasAccessibility(): Boolean {
        val s = Settings.Secure.getString(act.contentResolver, "enabled_accessibility_services") ?: return false
        return s.contains(act.packageName)
    }

    // ---------- 对话框 ----------

    fun newBookDialog(onDone: () -> Unit) {
        val et = EditText(c)
        et.setHint("例如：旅行账本")
        et.inputType = InputType.TYPE_CLASS_TEXT
        AlertDialog.Builder(act)
            .setTitle("新建账本")
            .setView(et)
            .setPositiveButton("创建") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    act.db().addBook(name)
                    act.book = name
                    onDone()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setBudgetDialog(month: String, cur: Double) {
        val et = EditText(c)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        if (cur > 0) et.setText(Ui.money(cur))
        et.setHint("输入 0 表示取消预算")
        AlertDialog.Builder(act)
            .setTitle("设置本月总预算")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val v = et.text.toString().trim().toDoubleOrNull() ?: 0.0
                act.db().setBudget(month, v)
                Toast.makeText(act, if (v > 0) "预算已设置" else "已取消预算", Toast.LENGTH_SHORT).show()
                act.refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun manageCats() {
        AlertDialog.Builder(act)
            .setTitle("分类管理")
            .setItems(arrayOf("支出分类", "收入分类")) { _, w -> manageCatType(w) }
            .show()
    }

    private fun manageCatType(t: Int) {
        val list = act.db().cats(t)
        val names = ArrayList<String>()
        for (cd in list) names.add(cd.name)
        AlertDialog.Builder(act)
            .setTitle(if (t == 0) "支出分类" else "收入分类")
            .setItems(names.toTypedArray()) { _, w ->
                AlertDialog.Builder(act)
                    .setTitle("删除「${names[w]}」？")
                    .setMessage("已记录的账单不会删除，只是该分类不再出现在选择列表")
                    .setPositiveButton("删除") { _, _ ->
                        act.db().delCat(list[w].id)
                        act.refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setPositiveButton("＋ 新增") { _, _ -> addCatDialog(t) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun addCatDialog(t: Int) {
        val box = Ui.col(c)
        Ui.pad(box, 16, 8, 16, 0)
        val name = EditText(c)
        name.setHint("分类名称")
        name.inputType = InputType.TYPE_CLASS_TEXT
        box.addView(name)
        val emoji = EditText(c)
        emoji.setHint("图标（可填一个 emoji，如 🍔）")
        emoji.inputType = InputType.TYPE_CLASS_TEXT
        box.addView(emoji)
        AlertDialog.Builder(act)
            .setTitle("新增分类")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                val e = emoji.text.toString().trim()
                act.db().addCat(n, if (e.isEmpty()) "📌" else e, t)
                act.refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportCsv() {
        try {
            val recs = act.db().list(0L, System.currentTimeMillis() + 86400000L, "", -1, true)
            val sb = StringBuilder()
            sb.append("日期,类型,分类,金额,备注,账本,来源\r\n")
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            for (r in recs) {
                sb.append(f.format(Date(r.ts))).append(',')
                sb.append(if (r.type == 0) "支出" else "收入").append(',')
                sb.append(r.cat).append(',')
                sb.append(Ui.money(r.amount)).append(',')
                sb.append(r.note.replace(",", " ")).append(',')
                sb.append(r.book).append(',')
                sb.append(r.src).append("\r\n")
            }
            val dir = act.getExternalFilesDir(null) ?: act.filesDir
            val file = File(dir, "账单导出_" + System.currentTimeMillis() + ".csv")
            file.writeText("\uFEFF" + sb.toString(), Charsets.UTF_8)
            AlertDialog.Builder(act)
                .setTitle("导出完成（${recs.size} 笔）")
                .setMessage("文件位置：\n" + file.absolutePath)
                .setPositiveButton("知道了", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(act, "导出失败：" + e.javaClass.simpleName, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDiag() {
        val txt = TextView(c)
        txt.text = Diag.dump(act, 30)
        txt.setTextSize(11f)
        txt.setTextColor(Ui.TEXT)
        txt.setPadding(Ui.dp(c, 14f), Ui.dp(c, 10f), Ui.dp(c, 14f), Ui.dp(c, 10f))
        val sv = ScrollView(c)
        sv.addView(txt)
        AlertDialog.Builder(act)
            .setTitle("抓取诊断（最近 30 条）")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }
}
