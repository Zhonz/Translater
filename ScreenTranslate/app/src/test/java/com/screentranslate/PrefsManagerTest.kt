package com.screentranslate

import com.screentranslate.model.AppConfig
import com.screentranslate.util.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PrefsManager 配置校验逻辑单元测试。
 */
class PrefsManagerTest {

    private fun makeConfig(
        apiUrl: String = "https://api.openai.com/v1/chat/completions",
        apiKey: String = "sk-test-1234567890",
        model: String = "gpt-4o-mini",
        prompt: String = AppConfig.DEFAULT_PROMPT
    ) = AppConfig(apiUrl = apiUrl, apiKey = apiKey, model = model, prompt = prompt)

    @Test
    fun `valid config passes validation`() {
        assertNull(PrefsManager.validateConfig(makeConfig()))
    }

    @Test
    fun `blank api url is rejected`() {
        assertEquals("API 地址不能为空", PrefsManager.validateConfig(makeConfig(apiUrl = "")))
        assertEquals("API 地址不能为空", PrefsManager.validateConfig(makeConfig(apiUrl = "   ")))
    }

    @Test
    fun `invalid url format is rejected`() {
        assertEquals(
            "API 地址格式不正确（需以 http:// 或 https:// 开头）",
            PrefsManager.validateConfig(makeConfig(apiUrl = "ftp://example.com"))
        )
        assertEquals(
            "API 地址格式不正确（需以 http:// 或 https:// 开头）",
            PrefsManager.validateConfig(makeConfig(apiUrl = "example.com/v1/chat"))
        )
        assertEquals(
            "API 地址格式不正确（需以 http:// 或 https:// 开头）",
            PrefsManager.validateConfig(makeConfig(apiUrl = "http://"))
        )
    }

    @Test
    fun `http and https urls are accepted`() {
        assertNull(PrefsManager.validateConfig(makeConfig(apiUrl = "http://localhost:8080/v1")))
        assertNull(PrefsManager.validateConfig(makeConfig(apiUrl = "https://api.deepseek.com/v1/chat/completions")))
    }

    @Test
    fun `blank api key is rejected`() {
        assertEquals("API Key 不能为空", PrefsManager.validateConfig(makeConfig(apiKey = "")))
    }

    @Test
    fun `blank model is rejected`() {
        assertEquals("模型名称不能为空", PrefsManager.validateConfig(makeConfig(model = "")))
    }

    @Test
    fun `custom provider url with path is accepted`() {
        assertNull(
            PrefsManager.validateConfig(
                makeConfig(apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            )
        )
    }
}
