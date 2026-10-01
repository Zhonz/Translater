package com.screentranslate.model

import android.graphics.Rect

/**
 * OCR 识别出的文字块，包含文字内容和在屏幕上的位置/边界框。
 */
data class TextBlock(
    /** 识别出的文字内容 */
    val text: String,
    /** 文字在屏幕上的边界框（绝对屏幕坐标） */
    val boundingBox: Rect,
    /** 文字高度（用于估算字号） */
    val textHeight: Int = boundingBox.height()
)
