package com.screentranslate

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.screentranslate.model.TextBlock

/**
 * 翻译结果覆盖层管理器。
 *
 * 使用 WindowManager 在屏幕上原文字的相同位置、相近字号处显示翻译后的中文，
 * 并用不透明背景遮挡原文字，实现"替换屏幕文字"的效果。
 */
class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

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
        for (i in blocks.indices) {
            val block = blocks[i]
            val translated = translations.getOrNull(i)?.takeIf { it.isNotBlank() } ?: continue
            addOverlayText(block, translated)
        }
    }

    /**
     * 为单个文字块添加覆盖 TextView。
     */
    private fun addOverlayText(block: TextBlock, translated: String) {
        val box = block.boundingBox
        if (box.width() <= 0 || box.height() <= 0) return

        val textView = TextView(context).apply {
            text = translated
            setTextColor(0xFF111827.toInt())
            // 用不透明背景遮挡原文字
            setBackgroundColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(2, 0, 2, 0)
            // 根据原文字高度估算字号（px 单位）。0.85 系数用于匹配实际字号与行高。
            val fontSizePx = (block.textHeight * 0.85f).coerceIn(8f, 80f)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
            typeface = Typeface.DEFAULT
            maxLines = 1
        }

        val params = WindowManager.LayoutParams(
            box.width(),
            box.height(),
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
            x = box.left
            y = box.top
        }

        try {
            windowManager.addView(textView, params)
            overlayViews.add(textView)
        } catch (e: Exception) {
            e.printStackTrace()
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
}
