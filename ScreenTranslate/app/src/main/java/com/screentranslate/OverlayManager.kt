package com.screentranslate

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.screentranslate.model.TextBlock
import kotlin.math.abs
import kotlin.math.cos

/**
 * 翻译结果覆盖层管理器。
 *
 * 使用 WindowManager 在屏幕上原文字的相同位置、相近字号处显示翻译后的中文，
 * 并用不透明背景遮挡原文字，实现"替换屏幕文字"的效果。
 *
 * 对倾斜文字，根据旋转角度校正字号估算（AABB 高度包含旋转分量），
 * 并以边界框中心为支点旋转覆盖层，与原文倾斜方向一致。
 */
class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 屏幕尺寸，用于覆盖层边界裁剪修正 */
    private val screenWidth: Int
    private val screenHeight: Int

    init {
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    /** 当前已添加到屏幕上的覆盖 TextView 列表 */
    private val overlayViews = mutableListOf<View>()

    /**
     * 在屏幕上显示一组翻译结果。
     *
     * @param blocks OCR 识别出的文字块（含原位置）
     * @param translations 与 blocks 一一对应的翻译结果
     */
    fun showTranslations(blocks: List<TextBlock>, translations: List<String>) {
        clear()
        var added = 0
        for (i in blocks.indices) {
            if (added >= MAX_OVERLAY_VIEWS) break
            val block = blocks[i]
            val translated = translations.getOrNull(i)?.takeIf { it.isNotBlank() } ?: continue
            if (addOverlayText(block, translated)) {
                added++
            }
        }
        Log.d(TAG, "显示 $added/${blocks.size} 个翻译覆盖层")
    }

    /**
     * 为单个文字块添加覆盖 TextView。
     * @return 是否成功添加
     */
    private fun addOverlayText(block: TextBlock, translated: String): Boolean {
        val box = block.boundingBox
        if (box.width() <= 0 || box.height() <= 0) return false

        val isRotated = abs(block.angle) > 1.5f
        val pad = if (isRotated) 4 else 2

        // 倾斜文字的 AABB 高度 = 实际文字高度 * |cos(θ)| + 文字宽度 * |sin(θ)|。
        // 这里只按高度分量反推，避免字号过大；对近垂直文字设下限防止字号过小。
        val actualHeight = if (isRotated) {
            val cosAngle = abs(cos(Math.toRadians(block.angle.toDouble()))).coerceIn(0.3, 1.0)
            (box.height() * cosAngle).toInt()
        } else {
            box.height()
        }

        val textView = TextView(context).apply {
            text = translated
            setTextColor(0xFF111827.toInt())
            // 用不透明背景遮挡原文字，加细边框便于用户识别覆盖区域
            setBackgroundColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                setStroke(1, 0xFFE5E7EB.toInt())
            }
            gravity = Gravity.CENTER
            setPadding(pad, 0, pad, 0)
            // 根据原文字高度估算字号（px 单位），倾斜文字用校正后的高度。
            // 最小/最大值基于 density * fontScale，尊重系统字体缩放设置，避免在高 dpi 或大字模式下不可读。
            val metrics = context.resources.displayMetrics
            val fontScale = context.resources.configuration.fontScale
            val scaledDensity = metrics.density * fontScale
            val minPx = 12f * scaledDensity
            val maxPx = 80f * scaledDensity
            val fontSizePx = (actualHeight * 0.85f).coerceIn(minPx, maxPx)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
            typeface = Typeface.DEFAULT
            maxLines = 1
            // 匹配原文倾斜角度：以边界框中心为支点旋转
            pivotX = box.width() / 2f
            pivotY = box.height() / 2f
            rotation = -block.angle
        }

        // 覆盖层尺寸：倾斜时适当放大以确保遮挡
        val w = box.width() + pad * 2
        val h = box.height() + pad

        val params = WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 定位到原文字区域，偏移 pad 以居中
            x = (box.left - pad).coerceIn(0, (screenWidth - w).coerceAtLeast(0))
            y = (box.top - pad / 2).coerceIn(0, (screenHeight - h).coerceAtLeast(0))
        }

        return try {
            windowManager.addView(textView, params)
            overlayViews.add(textView)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 移除所有翻译覆盖层。
     */
    fun clear() {
        for (view in overlayViews) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayViews.clear()
    }

    /**
     * 当前是否有覆盖层显示。
     */
    fun hasOverlays(): Boolean = overlayViews.isNotEmpty()

    companion object {
        private const val TAG = "OverlayManager"
        /** 单次最多添加的覆盖视图数量，避免大量 TextView 导致卡顿 */
        private const val MAX_OVERLAY_VIEWS = 100
    }
}
