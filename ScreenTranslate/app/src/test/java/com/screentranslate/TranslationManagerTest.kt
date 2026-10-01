package com.screentranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * TranslationManager 纯逻辑单元测试。
 * 覆盖 parseJsonArraySafely 对各种 AI 返回格式的鲁棒解析。
 */
class TranslationManagerTest {

    private lateinit var manager: TranslationManager

    @Before
    fun setUp() {
        manager = TranslationManager()
    }

    @Test
    fun `parse plain JSON array`() {
        val input = """["你好", "世界"]"""
        val arr = manager.parseJsonArraySafely(input)
        assertNotNull(arr)
        assertEquals(2, arr!!.length())
        assertEquals("你好", arr.getString(0))
        assertEquals("世界", arr.getString(1))
    }

    @Test
    fun `parse JSON array wrapped in json code fence`() {
        val input = """```json
["你好", "世界"]
```"""
        val arr = manager.parseJsonArraySafely(input)
        assertNotNull(arr)
        assertEquals(2, arr!!.length())
        assertEquals("你好", arr.getString(0))
    }

    @Test
    fun `parse JSON array wrapped in plain code fence`() {
        val input = """```
["翻译1", "翻译2", "翻译3"]
```"""
        val arr = manager.parseJsonArraySafely(input)
        assertNotNull(arr)
        assertEquals(3, arr!!.length())
        assertEquals("翻译3", arr.getString(2))
    }

    @Test
    fun `parse JSON array with surrounding explanatory text`() {
        val input = """以下是翻译结果：
["你好", "世界"]
希望对你有帮助。"""
        val arr = manager.parseJsonArraySafely(input)
        assertNotNull(arr)
        assertEquals(2, arr!!.length())
    }

    @Test
    fun `parse JSON array nested in object result field`() {
        val input = """{"result": ["翻译A", "翻译B"]}"""
        val arr = manager.parseJsonArraySafely(input)
        assertNotNull(arr)
        assertEquals(2, arr!!.length())
        assertEquals("翻译A", arr.getString(0))
    }

    @Test
    fun `parse JSON array nested in object with unknown field`() {
        val input = """{"translations": ["一", "二", "三"]}"""
        val arr = manager.parseJsonArraySafely(input)
        assertNotNull(arr)
        assertEquals(3, arr!!.length())
    }

    @Test
    fun `parse JSON array with nested brackets in strings`() {
        val input = """["他说 [你好]", "世界 [地球]"]"""
        val arr = manager.parseJsonArraySafely(input)
        assertNotNull(arr)
        assertEquals(2, arr!!.length())
        assertEquals("他说 [你好]", arr.getString(0))
    }

    @Test
    fun `parse empty array`() {
        val arr = manager.parseJsonArraySafely("[]")
        assertNotNull(arr)
        assertEquals(0, arr!!.length())
    }

    @Test
    fun `return null for invalid JSON`() {
        val arr = manager.parseJsonArraySafely("this is not json at all")
        assertNull(arr)
    }

    @Test
    fun `return null for empty content`() {
        assertNull(manager.parseJsonArraySafely(""))
        assertNull(manager.parseJsonArraySafely("   "))
    }

    @Test
    fun `findMatchingBracket handles nested arrays`() {
        val s = """[["a","b"],["c"]]"""
        val idx = manager.findMatchingBracket(s, 0)
        // 最后一个 ] 的索引
        assertEquals(s.length - 1, idx)
    }

    @Test
    fun `findMatchingBracket handles brackets inside strings`() {
        val s = """["a]b", "c"]"""
        val idx = manager.findMatchingBracket(s, 0)
        assertEquals(s.length - 1, idx)
    }

    // ==================== deriveModelsUrl ====================

    @Test
    fun `deriveModelsUrl replaces chat completions with models`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            manager.deriveModelsUrl("https://api.openai.com/v1/chat/completions")
        )
    }

    @Test
    fun `deriveModelsUrl handles trailing slash`() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            manager.deriveModelsUrl("https://api.deepseek.com/v1/chat/completions/")
        )
    }

    @Test
    fun `deriveModelsUrl handles completions suffix`() {
        assertEquals(
            "https://example.com/v1/models",
            manager.deriveModelsUrl("https://example.com/v1/completions")
        )
    }

    @Test
    fun `deriveModelsUrl returns null for non matching url`() {
        assertNull(manager.deriveModelsUrl("https://example.com/v1/embeddings"))
        assertNull(manager.deriveModelsUrl(""))
    }

    // ==================== parseModelsResponse ====================

    @Test
    fun `parseModelsResponse parses openai standard format`() {
        val body = """{"data":[{"id":"gpt-4o-mini"},{"id":"gpt-4o"}]}"""
        assertEquals(listOf("gpt-4o-mini", "gpt-4o"), manager.parseModelsResponse(body))
    }

    @Test
    fun `parseModelsResponse parses direct array format`() {
        val body = """["gpt-4o-mini","gpt-4o"]"""
        assertEquals(listOf("gpt-4o-mini", "gpt-4o"), manager.parseModelsResponse(body))
    }

    @Test
    fun `parseModelsResponse parses objects array format`() {
        val body = """[{"id":"model-a"},{"id":"model-b"}]"""
        assertEquals(listOf("model-a", "model-b"), manager.parseModelsResponse(body))
    }

    @Test
    fun `parseModelsResponse skips empty ids`() {
        val body = """{"data":[{"id":"gpt-4o"},{"id":""},{"id":"  "}]}"""
        assertEquals(listOf("gpt-4o"), manager.parseModelsResponse(body))
    }

    @Test
    fun `parseModelsResponse returns empty for invalid json`() {
        assertEquals(emptyList<String>(), manager.parseModelsResponse("not json"))
        assertEquals(emptyList<String>(), manager.parseModelsResponse(""))
    }
}
