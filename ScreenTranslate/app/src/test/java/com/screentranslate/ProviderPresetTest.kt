package com.screentranslate

import com.screentranslate.model.ProviderPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProviderPreset 单元测试。
 */
class ProviderPresetTest {

    @Test
    fun `findByApiUrl returns matching preset`() {
        val deepseek = ProviderPreset.findByApiUrl("https://api.deepseek.com/v1/chat/completions")
        assertEquals("DeepSeek 深度求索", deepseek.name)
        assertEquals("deepseek-chat", deepseek.model)
    }

    @Test
    fun `findByApiUrl returns custom for unknown url`() {
        val result = ProviderPreset.findByApiUrl("https://unknown.example.com/v1/chat/completions")
        assertEquals("自定义", result.name)
    }

    @Test
    fun `builtin providers have non-empty names`() {
        assertTrue(ProviderPreset.BUILT_IN.isNotEmpty())
        ProviderPreset.BUILT_IN.forEach {
            assertTrue(it.name.isNotBlank())
        }
    }

    @Test
    fun `custom preset is first in list`() {
        assertEquals("自定义", ProviderPreset.BUILT_IN.first().name)
        assertEquals("", ProviderPreset.BUILT_IN.first().apiUrl)
    }

    @Test
    fun `non-custom providers have api url and model`() {
        ProviderPreset.BUILT_IN.drop(1).forEach {
            assertTrue("${it.name} should have apiUrl", it.apiUrl.isNotBlank())
            assertTrue("${it.name} should have model", it.model.isNotBlank())
        }
    }
}
