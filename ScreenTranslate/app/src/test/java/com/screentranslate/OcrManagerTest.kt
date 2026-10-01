package com.screentranslate

import android.graphics.Rect
import com.screentranslate.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OcrManager 纯逻辑单元测试（使用 Robolectric 以支持 android.graphics.Rect）。
 * 覆盖脚本检测、IoU 计算、合并去重策略。
 */
@RunWith(RobolectricTestRunner::class)
class OcrManagerTest {

    private lateinit var ocr: OcrManager

    @Before
    fun setUp() {
        ocr = OcrManager()
    }

    // ==================== detectScript ====================

    @Test
    fun `detectScript returns LATIN for english text`() {
        assertEquals(OcrManager.Script.LATIN, ocr.detectScript("Hello World"))
    }

    @Test
    fun `detectScript returns KOREAN for korean text`() {
        assertEquals(OcrManager.Script.KOREAN, ocr.detectScript("안녕하세요"))
    }

    @Test
    fun `detectScript returns JAPANESE for japanese hiragana`() {
        assertEquals(OcrManager.Script.JAPANESE, ocr.detectScript("こんにちは"))
    }

    @Test
    fun `detectScript returns null for pure chinese characters`() {
        // 纯汉字无法判断属于哪种识别器（中/日汉字同区）
        assertNull(ocr.detectScript("你好世界"))
    }

    @Test
    fun `detectScript returns JAPANESE for mixed kanji and kana`() {
        assertEquals(OcrManager.Script.JAPANESE, ocr.detectScript("日本語のテスト"))
    }

    @Test
    fun `detectScript returns LATIN for mixed latin and chinese`() {
        assertEquals(OcrManager.Script.LATIN, ocr.detectScript("Hello 你好"))
    }

    // ==================== iou ====================

    @Test
    fun `iou is 1 for identical rects`() {
        val a = Rect(0, 0, 100, 50)
        assertEquals(1.0f, ocr.iou(a, a), 0.001f)
    }

    @Test
    fun `iou is 0 for non-overlapping rects`() {
        val a = Rect(0, 0, 100, 50)
        val b = Rect(200, 0, 300, 50)
        assertEquals(0.0f, ocr.iou(a, b), 0.001f)
    }

    @Test
    fun `iou computes correct overlap for half-overlapping rects`() {
        val a = Rect(0, 0, 100, 100)
        val b = Rect(50, 0, 150, 100)
        // intersection = 50*100 = 5000, union = 10000+10000-5000 = 15000
        assertEquals(5000f / 15000f, ocr.iou(a, b), 0.001f)
    }

    @Test
    fun `iou is 0 when one rect contains no area`() {
        val a = Rect(0, 0, 0, 0)
        val b = Rect(0, 0, 100, 100)
        assertEquals(0.0f, ocr.iou(a, b), 0.001f)
    }

    // ==================== mergeBlocks ====================

    @Test
    fun `mergeBlocks returns empty for empty input`() {
        assertTrue(ocr.mergeBlocks().isEmpty())
        assertTrue(ocr.mergeBlocks(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `mergeBlocks keeps non-overlapping blocks`() {
        val a = OcrManager.TaggedBlock(
            TextBlock("Hello", Rect(0, 0, 100, 30)),
            OcrManager.Script.LATIN
        )
        val b = OcrManager.TaggedBlock(
            TextBlock("World", Rect(0, 100, 100, 130)),
            OcrManager.Script.LATIN
        )
        val merged = ocr.mergeBlocks(listOf(a), listOf(b))
        assertEquals(2, merged.size)
    }

    @Test
    fun `mergeBlocks deduplicates overlapping blocks`() {
        // 同一区域由拉丁和韩语识别器都识别到
        val latin = OcrManager.TaggedBlock(
            TextBlock("abc", Rect(0, 0, 100, 30)),
            OcrManager.Script.LATIN
        )
        val korean = OcrManager.TaggedBlock(
            TextBlock("abc", Rect(0, 0, 100, 30)),
            OcrManager.Script.KOREAN
        )
        val merged = ocr.mergeBlocks(listOf(latin), listOf(korean))
        // 重叠区域只保留一个结果
        assertEquals(1, merged.size)
    }

    @Test
    fun `mergeBlocks prefers script-matching result for korean text`() {
        // 韩文文本：韩语识别器结果正确，拉丁识别器把它识别成乱码
        val latinWrong = OcrManager.TaggedBlock(
            TextBlock("012", Rect(0, 0, 100, 30)), // 拉丁识别器的错误结果
            OcrManager.Script.LATIN
        )
        val koreanCorrect = OcrManager.TaggedBlock(
            TextBlock("안녕", Rect(0, 0, 100, 30)), // 韩语识别器的正确结果
            OcrManager.Script.KOREAN
        )
        val merged = ocr.mergeBlocks(listOf(latinWrong), listOf(koreanCorrect))
        assertEquals(1, merged.size)
        assertEquals("안녕", merged[0].text)
    }

    // ==================== isBetterMatch ====================

    @Test
    fun `isBetterMatch prefers script-matching candidate`() {
        val current = OcrManager.TaggedBlock(
            TextBlock("안녕", Rect(0, 0, 100, 30)),
            OcrManager.Script.LATIN // 不匹配
        )
        val candidate = OcrManager.TaggedBlock(
            TextBlock("안녕", Rect(0, 0, 100, 30)),
            OcrManager.Script.KOREAN // 匹配
        )
        assertTrue(ocr.isBetterMatch(candidate, current))
    }

    @Test
    fun `isBetterMatch prefers longer text when both match`() {
        val current = OcrManager.TaggedBlock(
            TextBlock("Hi", Rect(0, 0, 100, 30)),
            OcrManager.Script.LATIN
        )
        val candidate = OcrManager.TaggedBlock(
            TextBlock("Hello World", Rect(0, 0, 100, 30)),
            OcrManager.Script.LATIN
        )
        assertTrue(ocr.isBetterMatch(candidate, current))
    }

    @Test
    fun `isBetterMatch returns false when current is better`() {
        val current = OcrManager.TaggedBlock(
            TextBlock("Hello World", Rect(0, 0, 100, 30)),
            OcrManager.Script.LATIN
        )
        val candidate = OcrManager.TaggedBlock(
            TextBlock("Hi", Rect(0, 0, 100, 30)),
            OcrManager.Script.LATIN
        )
        assertFalse(ocr.isBetterMatch(candidate, current))
    }
}
