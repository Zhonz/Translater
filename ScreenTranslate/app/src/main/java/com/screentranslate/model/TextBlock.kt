package com.screentranslate.model

import android.graphics.Rect

/**
 * OCR 识别出的文字块，包含文字内容、在屏幕上的位置/边界框、以及旋转角度。
 *
 * @param angle 文字行相对于水平方向的旋转角度（度），用于覆盖层旋转匹配原文倾斜。
 */
data class TextBlock(
    /** 识别出的文字内容 */
    val text: String,
    /** 文字在屏幕上的边界框（绝对屏幕坐标，已对齐水平/垂直轴） */
    val boundingBox: Rect,
    /** 文字高度（用于估算字号） */
    val textHeight: Int = boundingBox.height(),
    /** 文字行的旋转角度（度），正值为逆时针倾斜 */
    val angle: Float = 0f
)
