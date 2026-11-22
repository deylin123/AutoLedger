package com.deylin.autledger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * v2.30：给某一笔账单写「用户备注」的独立输入页。
 *
 * 为什么不把 EditText 直接放进悬浮卡片：
 * 卡片窗口是 TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE，
 * 在 MIUI/HyperOS 上 overlay 内的输入框拿不到焦点、软键盘弹不出来。
 * 独立 Activity 有自己的窗口，输入可靠 —— 与「改分类」跳 PendingActivity 同一条已验证路径。
 *
 * 备注存 records.remark（独立列），与 note（自动识别商户/商品、商户分类记忆 key、查重 key）分离：
 * 用户写什么都不会污染识别与分类记忆，也不会被确认/改分类的整行覆盖冲掉。
 */
class RemarkActivity : Activity() {

    private var recId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remark)
        recId = intent.getLongExtra("recId", 0L)

        val db = DbHelper(this)
        val rec = if (recId > 0L) try { db.get(recId) } catch (_: Exception) { null } else null
        val head = findViewById<TextView>(R.id.tvRemarkHead)
        head.text = if (rec != null) {
            "备注 · " + (if (rec.type == 0) "-¥" else "+¥") + Ui.money(rec.amount) +
                "  " + (if (rec.note.isNotBlank()) rec.note else rec.cat)
        } else {
            "给这笔账单写备注"
        }

        val et = findViewById<EditText>(R.id.etRemark)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.maxLines = 6
        val old = if (recId > 0L) try { db.remarkOf(recId) } catch (_: Exception) { "" } else ""
        et.setText(old)
        et.setSelection(et.text.length)
        et.requestFocus()
        et.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        db.close()

        findViewById<View>(R.id.btnRemarkCancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRemarkSave).setOnClickListener {
            val text = et.text.toString().trim()
            if (recId > 0L) {
                val w = DbHelper(this)
                try {
                    w.setRemark(recId, text)
                } catch (_: Exception) {
                } finally {
                    w.close()
                }
                OverlayUi.refreshRemark(applicationContext, recId, text)
                Toast.makeText(
                    this,
                    if (text.isBlank()) "备注已清空" else "备注已保存",
                    Toast.LENGTH_SHORT
                ).show()
            }
            finish()
        }
    }

    /** singleTop：重复点备注时复用本页，只换记录 id，不叠栈 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recId = intent.getLongExtra("recId", 0L)
    }
}
