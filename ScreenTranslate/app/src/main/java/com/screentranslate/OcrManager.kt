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
 * 识别屏幕截图中的文字，并返回每个文字块的内容与边界框（屏幕坐标）。
 * 边界框用于在覆盖层中将翻译结果放置在与原文相同的位置。
 */
class OcrManager {

    // 拉丁文字识别器（覆盖英文等）。如需更多语言可在此扩展。
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * 识别 bitmap 中的文字。
     * @return 识别出的文字块列表（按从上到下、从左到右排序）
     */
    suspend fun recognize(bitmap: Bitmap): List<TextBlock> = suspendCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val blocks = mutableListOf<TextBlock>()
                for (block in visionText.textBlocks) {
                    val box = block.boundingBox ?: continue
                    val text = block.text.trim()
                    if (text.isNotEmpty()) {
                        blocks.add(
                            TextBlock(
                                text = text,
                                boundingBox = Rect(box),
                                textHeight = box.height()
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
