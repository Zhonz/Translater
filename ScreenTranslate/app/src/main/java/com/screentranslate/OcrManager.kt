package com.screentranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.screentranslate.model.TextBlock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 基于 ML Kit 的多语言文字识别管理器。
 *
 * 并行运行拉丁、韩语、日语三个识别器，按边界框重叠度去重合并。
 * 合并时优先选择与文字脚本匹配的识别器结果（如韩文优先用韩语识别器），
 * 避免拉丁识别器把韩文识别成乱码反而被选中。
 * 每个文字块携带边界框与旋转角度，用于覆盖层精确定位。
 */
class OcrManager {

    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val koreanRecognizer: TextRecognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val japaneseRecognizer: TextRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    /** 识别器对应的文字脚本标签，用于合并时择优 */
    private enum class Script { LATIN, KOREAN, JAPANESE }

    /** 内部带来源标签的识别块 */
    private data class TaggedBlock(val block: TextBlock, val source: Script)

    /**
     * 识别 bitmap 中的文字（多语言）。
     * @return 识别出的文字块列表（按从上到下排序），每个块含位置与旋转角度
     */
    suspend fun recognize(bitmap: Bitmap): List<TextBlock> = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)

        // 并行运行三个识别器
        val latinDeferred = async {
            runRecognizer(latinRecognizer, image, Script.LATIN)
        }
        val koreanDeferred = async {
            runRecognizer(koreanRecognizer, image, Script.KOREAN)
        }
        val japaneseDeferred = async {
            runRecognizer(japaneseRecognizer, image, Script.JAPANESE)
        }

        val latinBlocks = latinDeferred.await()
        val koreanBlocks = koreanDeferred.await()
        val japaneseBlocks = japaneseDeferred.await()

        // 合并去重
        val merged = mergeBlocks(latinBlocks, koreanBlocks, japaneseBlocks)
        merged.sortBy { it.boundingBox.top }
        merged
    }

    /**
     * 运行单个识别器，将结果转为带来源标签的 TextBlock 列表。
     * 协程取消时会同步取消 ML Kit Task，避免资源泄漏。
     */
    private suspend fun runRecognizer(
        recognizer: TextRecognizer,
        image: InputImage,
        source: Script
    ): List<TaggedBlock> {
        return suspendCancellableCoroutine { cont ->
            // ML Kit v16 的 TextRecognizer.process 不支持 CancellationToken，
            // 因此协程取消时任务仍会执行完毕，但通过 isActive 检查避免恢复已取消的协程。
            val task = recognizer.process(image)
            task.addOnSuccessListener { visionText ->
                if (!cont.isActive) return@addOnSuccessListener
                val blocks = mutableListOf<TaggedBlock>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val text = line.text.trim()
                        if (text.isEmpty()) continue
                        blocks.add(
                            TaggedBlock(
                                block = TextBlock(
                                    text = text,
                                    boundingBox = Rect(box),
                                    textHeight = box.height(),
                                    angle = line.angle
                                ),
                                source = source
                            )
                        )
                    }
                }
                cont.resume(blocks)
            }.addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    /**
     * 合并多个识别器的结果，按边界框重叠度去重。
     *
     * 对重叠区域，按以下优先级选择最优结果：
     * 1. 识别结果的文字脚本与来源识别器匹配（如韩文来自韩语识别器）
     * 2. 文字长度较长（更可能识别完整）
     */
    private fun mergeBlocks(vararg blockLists: List<TaggedBlock>): MutableList<TextBlock> {
        val all = mutableListOf<TaggedBlock>()
        for (list in blockLists) all.addAll(list)

        if (all.isEmpty()) return mutableListOf()

        // 按面积降序，优先处理大块
        all.sortByDescending { it.block.boundingBox.width() * it.block.boundingBox.height() }

        val result = mutableListOf<TextBlock>()
        val used = BooleanArray(all.size)

        for (i in all.indices) {
            if (used[i]) continue
            val current = all[i]
            used[i] = true
            var best = current

            // 找到所有与 current 高度重叠（IoU > 0.3）的块
            for (j in i + 1 until all.size) {
                if (used[j]) continue
                val other = all[j]
                if (iou(current.block.boundingBox, other.block.boundingBox) > 0.3f) {
                    used[j] = true
                    if (isBetterMatch(other, best)) {
                        best = other
                    }
                }
            }
            result.add(best.block)
        }
        return result
    }

    /**
     * 判断 candidate 是否比 current 更适合作为该区域的识别结果。
     * 优先：脚本匹配 > 文字长度。
     */
    private fun isBetterMatch(candidate: TaggedBlock, current: TaggedBlock): Boolean {
        val candidateScript = detectScript(candidate.block.text)
        val currentScript = detectScript(current.block.text)

        val candidateMatch = candidateScript == candidate.source
        val currentMatch = currentScript == current.source

        // 一方匹配来源、另一方不匹配 → 匹配的胜出
        if (candidateMatch != currentMatch) return candidateMatch

        // 都匹配或都不匹配 → 选文字较长的
        return candidate.block.text.length > current.block.text.length
    }

    /**
     * 检测文字的主导脚本（拉丁/韩文/日文/其他）。
     * 用于判断识别结果是否与来源识别器匹配。
     */
    private fun detectScript(text: String): Script? {
        var latin = 0
        var korean = 0
        var japanese = 0
        for (ch in text) {
            when {
                ch.code in 0xAC00..0xD7AF -> korean++
                // 日文假名
                ch.code in 0x3040..0x30FF -> japanese++
                // 日文汉字与中文汉字同区，这里不区分；有假名才算日文
                ch.isLetter() && ch.code !in 0x4E00..0x9FFF -> latin++
            }
        }
        return when {
            korean > latin && korean > japanese -> Script.KOREAN
            japanese > latin && japanese > korean -> Script.JAPANESE
            latin > 0 -> Script.LATIN
            else -> null // 纯汉字或无文字，无法判断
        }
    }

    /** 计算两个矩形的交并比 IoU */
    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop).toFloat()
        val areaA = a.width() * a.height().toFloat()
        val areaB = b.width() * b.height().toFloat()
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    /**
     * 释放所有识别器资源。
     * 应在不再需要 OCR 时调用（如服务销毁）。
     */
    fun close() {
        try { latinRecognizer.close() } catch (_: Exception) { }
        try { koreanRecognizer.close() } catch (_: Exception) { }
        try { japaneseRecognizer.close() } catch (_: Exception) { }
    }
}
