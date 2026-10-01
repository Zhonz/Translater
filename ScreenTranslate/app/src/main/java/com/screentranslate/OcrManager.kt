package com.screentranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.screentranslate.model.TextBlock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 基于 ML Kit 的文字识别管理器。
 *
 * 识别屏幕截图中的文字，并返回每个文字块的内容、边界框（屏幕坐标）以及旋转角度。
 * 边界框与角度用于在覆盖层中将翻译结果放置在与原文相同的位置与倾斜角度。
 */
class OcrManager {

    // 拉丁文字识别器（覆盖英文等）。如需更多语言可在此扩展。
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * 识别 bitmap 中的文字。
     * @return 识别出的文字块列表（按从上到下排序），每个块含位置与旋转角度
     */
    suspend fun recognize(bitmap: Bitmap): List<TextBlock> = suspendCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val blocks = mutableListOf<TextBlock>()
                for (block in visionText.textBlocks) {
                    // 按行处理，每行单独作为一个 TextBlock，以获取准确的倾斜角度
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val text = line.text.trim()
                        if (text.isEmpty()) continue
                        // line.angle 为该行相对于水平的旋转角度（度）
                        val angle = line.angle
                        blocks.add(
                            TextBlock(
                                text = text,
                                boundingBox = Rect(box),
                                textHeight = box.height(),
                                angle = angle
                            )
                        )
                    }
                }
                // 按 y 坐标排序，便于阅读顺序
                blocks.sortBy { it.boundingBox.top }
                cont.resume(blocks)
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }
}
