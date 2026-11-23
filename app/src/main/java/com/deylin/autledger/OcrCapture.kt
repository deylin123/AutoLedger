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

    /**
     * 请求一次截屏 OCR。
     * @param svc 无障碍服务（用于 takeScreenshot 与日志上下文）
     * @param handler 主线程 handler（结果回主线程再判定/入库/弹卡）
     * @param pkg 当前页面包名，非微信直接忽略
     * @param fingerprint 本页指纹，相同指纹在节流窗内不重复 OCR
     * @param onTexts OCR 成功回调：识别出的文本行（主线程执行）
     */
    fun request(
        svc: AccessibilityService,
        handler: Handler,
        pkg: String,
        fingerprint: String,
        onTexts: (List<String>) -> Unit
    ) {
        if (pkg != TARGET_PKG) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Diag.log(svc, "OCR 降级：系统 ${Build.VERSION.SDK_INT} < 30，不支持无障碍截屏，沿用页面通道")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastOcrAt < OCR_MIN_INTERVAL_MS) return
        if (inFlight) return
        if (fingerprint.isNotEmpty() && fingerprint == lastFingerprint &&
            now - lastOcrAt < OCR_MIN_INTERVAL_MS * 3) return
        synchronized(this) {
            if (inFlight) return
            if (now - lastOcrAt < OCR_MIN_INTERVAL_MS) return
            inFlight = true
            lastOcrAt = now
            if (fingerprint.isNotEmpty()) lastFingerprint = fingerprint
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
                        Diag.log(svc, "OCR 截屏失败 code=$errorCode")
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

    private const val TARGET_PKG = "com.tencent.mm"
}
