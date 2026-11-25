package com.deylin.autledger

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors

/**
 * v2.35：微信自绘结果页「截屏 OCR」取数。
 *
 * 为什么需要这一层（真机日志 + 用户截图实证）：
 *  微信「收款成功页」「付款现场页」的文字**不进无障碍树** —— 诊断日志那段时间
 *  每帧只读到「正在加载」1 条文本；用户也确认这类页没有通知栏消息。
 *  于是页面通道（读不到字）+ 通知通道（没消息）= 双通道无输入，
 *  v2.32/v2.33 加在「文字进树之后」的判据是死代码，再改也不会弹。
 *
 *  唯一能拿到这类页文字的路径是**读屏幕像素**：
 *   无障碍 takeScreenshot（API 30+，一次授权后静默截屏、无逐次确认弹窗）
 *   → ML Kit 离线中文 OCR → 文本行 → 送进现有 scan()。
 *
 * 本模块只负责「把字读出来」，不做任何收支判定/入库/弹卡 —— 那些全部复用
 * 服务层已由真机验证的 scan()/attempt() 链路，核心识别逻辑零改动。
 *
 * 安全约束：
 *  · 只对微信（com.tencent.mm）截屏；
 *  · Build < API30 直接降级（不截屏，走原有无障碍链路）；
 *  · 强节流：两次 OCR 至少相隔 [OCR_MIN_INTERVAL_MS]，同一页指纹不重复；
 *  · 结果必须经服务层的目标页形态闸，普通聊天/账单页不入库。
 */
object OcrCapture {

    private const val TAG = "OcrCapture"
    /** 强节流：两次截屏 OCR 的最小间隔，避免持续截屏耗电并压住重复触发 */
    private const val OCR_MIN_INTERVAL_MS = 4000L

    /**
     * v2.39：「微信支付」标题页的最小间隔。
     *
     * 真机日志（2026-11-24 17:23）证明：转账**输入页**与**结果页**的无障碍文本完全相同，
     * 都只剩窗口标题「微信支付」1 条（页面内容全部自绘，不进树）。
     * 于是两页算出同一个指纹，被「同指纹 12 秒内不再截屏」整段盖住，
     * 而页面节拍器总长只有 12.6 秒 —— 结果页的整个重试窗口被抑制吃掉，
     * 必须等用户手动截屏（产生 com.miui.screenshot 窗口事件）才解除。
     * 所以这一类页面不能参与同指纹抑制，改用更短间隔重试。
     */
    private const val PAYTITLE_MIN_INTERVAL_MS = 1200L

    /**
     * 连续快速重试的上限与冷却。
     *
     * v2.44 修正：原来「20 次用尽 → 冷却 30 秒」是**结果页不弹的直接原因**。
     * 转账输入页（输金额、选人、输密码）是同一个「微信支付」标题页，会持续消耗次数：
     * 用户在那页停留 40~50 秒，20 次预算刚好在**结果页出现的前一刻**用完，
     * 于是结果页整段落在 30 秒冷却里 —— 一次都不截，用户只能等点掉结果页
     * 回聊天页后被 amount 支路补截（这就是「点确定后才弹」）。
     * 现在预算放宽到 60 次（≈72 秒），冷却缩到 6 秒，宁可多截几次也不漏结果页。
     */
    private const val PAYTITLE_MAX_RUN = 60
    private const val PAYTITLE_COOLDOWN_MS = 6_000L

    /**
     * 退避：前 [PAYTITLE_FAST_RUN] 次按 1.2 秒快试（覆盖「点转账 → 结果页」这段几秒钟），
     * 之后拉长到 2.5 秒。结果页通常只停留 2~5 秒（用户看到就点确定），
     * 2.5 秒的间隔只够试一两次，来不及就要等下一拍，必须更密。
     */
    private const val PAYTITLE_FAST_RUN = 15
    private const val PAYTITLE_SLOW_INTERVAL_MS = 2500L

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "autoledger-ocr").apply { isDaemon = true }
    }

    /** 中文识别器（bundled 模型，离线可用，随包静态链接） */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    @Volatile
    private var lastOcrAt = 0L
    @Volatile
    private var lastFingerprint = ""
    @Volatile
    private var inFlight = false

    /** v2.39：paytitle 连续快速截屏计数与冷却截止时刻 */
    @Volatile
    private var payRun = 0
    @Volatile
    private var payCoolUntil = 0L

    /**
     * 请求一次截屏 OCR。
     * @param svc 无障碍服务（用于 takeScreenshot 与日志上下文）
     * @param handler 主线程 handler（结果回主线程再判定/入库/弹卡）
     * @param pkg 当前页面包名，非微信直接忽略
     * @param fingerprint 本页指纹，相同指纹在节流窗内不重复 OCR
     * @param onTexts OCR 成功回调：识别出的文本行（主线程执行）
     * @param payTitle 「结算页候选」档。v2.39 用于微信「微信支付」标题页（内容全自绘，
     *                 无障碍只剩窗口标题），v2.44 起支付宝结算页也走这一档。
     *                 这类页指纹无法区分「输入页/结果页」，故不参与同指纹抑制，改用更短间隔重试。
     * @param newPage v2.44：本拍是从「非支付标题页」刚切进支付标题页的那一刻。
     *                结果页一出现就立刻截一张，不等 1.2 秒间隔，也不用管冷却 ——
     *                用户看到结果页往往两三秒内就点「确定」走人，这几百毫秒决定弹不弹。
     */
    fun request(
        svc: AccessibilityService,
        handler: Handler,
        pkg: String,
        fingerprint: String,
        onTexts: (List<String>) -> Unit,
        force: Boolean = false,
        probe: Boolean = false,
        payTitle: Boolean = false,
        newPage: Boolean = false
    ) {
        if (pkg !in TARGET_PKGS) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Diag.log(svc, "OCR 降级：系统 ${Build.VERSION.SDK_INT} < 30，不支持无障碍截屏，沿用页面通道")
            markState(svc, "unsupported")
            return
        }
        val now = System.currentTimeMillis()
        // v2.44：刚切进结算页 → 立刻截，不受间隔与冷却约束（并发仍受 inFlight 保护）
        val urgent = newPage
        if (urgent) payCoolUntil = 0L
        // v2.39：paytitle 走独立节流 —— 它不参与同指纹抑制，否则结果页会被输入页的指纹盖住 12 秒
        val minGap = if (payTitle) payTitleGap() else OCR_MIN_INTERVAL_MS
        if (!force) {
            if (payTitle) {
                if (now < payCoolUntil) return
            } else if (fingerprint.isNotEmpty() && fingerprint == lastFingerprint &&
                now - lastOcrAt < OCR_MIN_INTERVAL_MS * 3
            ) {
                return
            }
            if (!urgent && now - lastOcrAt < minGap) return
            if (inFlight) return
        }
        synchronized(this) {
            // force（用户手动测试）只跳过时间节流与同页抑制，不跳过并发保护
            if (inFlight) return
            if (!force) {
                if (payTitle && now < payCoolUntil) return
                val g = if (payTitle) payTitleGap() else OCR_MIN_INTERVAL_MS
                if (!urgent && now - lastOcrAt < g) return
                if (!payTitle && fingerprint.isNotEmpty() && fingerprint == lastFingerprint &&
                    now - lastOcrAt < OCR_MIN_INTERVAL_MS * 3
                ) {
                    return
                }
            }
            inFlight = true
            lastOcrAt = now
            if (fingerprint.isNotEmpty()) lastFingerprint = fingerprint
            if (payTitle) {
                payRun++
                if (payRun >= PAYTITLE_MAX_RUN) {
                    payCoolUntil = now + PAYTITLE_COOLDOWN_MS
                    payRun = 0
                    Diag.log(
                        svc,
                        "OCR 连续 ${PAYTITLE_MAX_RUN} 次截屏仍未命中，冷却 ${PAYTITLE_COOLDOWN_MS / 1000} 秒" +
                            "（防自绘页长期停留时反复截屏）"
                    )
                }
            } else {
                // 页面形态变了（不再是支付标题页），计数归零，后续若再进入也可正常重试
                payRun = 0
            }
        }

        try {
            val dm = svc.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display == null) {
                Diag.log(svc, "OCR 未取到默认显示，放弃本次截屏")
                inFlight = false
                return
            }
            svc.takeScreenshot(
                display.displayId,
                exec,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        // v2.37：截到图就说明「截取屏幕内容」权限已授予 —— 落成用户可见状态
                        markState(svc, "ok")
                        var bitmap: Bitmap? = null
                        try {
                            val hw = result.hardwareBuffer ?: run {
                                Diag.log(svc, "OCR 截屏返回空 HardwareBuffer")
                                release()
                                return
                            }
                            val cs = result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                            val wrapped = Bitmap.wrapHardwareBuffer(hw, cs)
                            if (wrapped == null) {
                                Diag.log(svc, "OCR 包装 HardwareBuffer 失败")
                                hw.close()
                                release()
                                return
                            }
                            // 复制为 ARGB_8888，避免 HardwareBuffer 关闭后像素失效
                            bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped.recycle()
                            hw.close()
                            val bmp = bitmap ?: run {
                                release()
                                return
                            }
                            // 大图降采样，降低 OCR 失败率并省内存
                            val scaled = scaleDown(bmp, 1440)
                            if (scaled !== bmp) bmp.recycle()
                            val image = InputImage.fromBitmap(scaled, 0)
                            recognizer.process(image)
                                .addOnSuccessListener { vision ->
                                    val lines = vision.textBlocks
                                        .flatMap { it.lines }
                                        .map { it.text.trim() }
                                        .filter { it.isNotEmpty() }
                                    Diag.log(svc, "OCR 识别到 ${lines.size} 行：${lines.take(16).joinToString("/").take(180)}")
                                    handler.post {
                                        try { onTexts(lines) } finally {
                                            scaled.recycle()
                                            release()
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Diag.log(svc, "OCR 识别失败：${e.javaClass.simpleName}")
                                    scaled.recycle()
                                    release()
                                }
                        } catch (e: Exception) {
                            Diag.log(svc, "OCR 处理异常：${e.javaClass.simpleName}")
                            bitmap?.recycle()
                            release()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val why = errText(errorCode)
                        markState(svc, "fail:$errorCode")
                        Diag.log(
                            svc,
                            "OCR 截屏失败 code=$errorCode（$why）" +
                                if (errorCode == 1 || errorCode == 2)
                                    " —— 常见原因是系统未授予「截取屏幕内容」，" +
                                        "请回本 App 主界面点「测试屏幕截取」并允许授权"
                                else ""
                        )
                        if (probe) toast(svc, "屏幕截取失败：$why")
                        release()
                    }
                }
            )
        } catch (e: Exception) {
            Diag.log(svc, "OCR 请求异常：${e.javaClass.simpleName}")
            inFlight = false
        }
    }

    private fun release() {
        inFlight = false
    }

    /** v2.39：paytitle 的当前重试间隔（前 15 次快试 1.2 秒，之后退避到 2.5 秒） */
    private fun payTitleGap(): Long =
        if (payRun >= PAYTITLE_FAST_RUN) PAYTITLE_SLOW_INTERVAL_MS else PAYTITLE_MIN_INTERVAL_MS

    /**
     * v2.44：OCR 命中目标页之后调用 —— 计数清零并静默 [HIT_QUIET_MS]。
     *
     * 间隔缩到 1.2 秒之后，如果结果页还停在屏上，会继续每 1.2 秒截一次屏，
     * 纯属耗电（同一笔已被查重挡掉）。命中即静默，等页面真换了再重新开始。
     */
    fun noteHit() {
        payRun = 0
        payCoolUntil = System.currentTimeMillis() + HIT_QUIET_MS
    }

    private const val HIT_QUIET_MS = 8_000L

    /**
     * v2.37：截屏通道的状态（ok / fail:code / unsupported）落进 cfg，
     * 主界面据此在权限条里提示「屏幕截取未授权」，避免整条链路黑盒。
     */
    private fun markState(ctx: Context, v: String) {
        try {
            ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString("ocr_state", v)
                .putLong("ocr_state_at", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun errText(code: Int): String = when (code) {
        1 -> "内部错误"
        2 -> "无障碍服务无权截屏"
        3 -> "两次截屏间隔过短"
        4 -> "显示无效"
        else -> "未知错误"
    }

    private fun toast(ctx: Context, s: String) {
        try {
            Handler(ctx.mainLooper).post {
                android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
        }
    }

    /** 等比缩小到最长边 <= maxDim，返回同对象（未缩小）或新 Bitmap */
    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    /**
     * v2.44：截屏范围扩到支付宝。
     *
     * 用户实测：淘宝下单 → 跳支付宝付款 → 付完不弹卡，必须自己进账单点开这笔才弹。
     * 原因是收银台/结果页是 H5，金额常以图片或自绘形式出现：无障碍读到「支付成功」
     * 却读不到金额，attempt 的金额闸直接拦下；而这一屏只停留一两秒，返回淘宝就没了。
     * 只对这两个 App 截屏，桌面/输入法/购物 App 一律不碰。
     */
    private val TARGET_PKGS = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
}
