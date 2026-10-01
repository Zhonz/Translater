package com.screentranslate.model

/**
 * AI 翻译服务商配置。
 */
data class AppConfig(
    /** OpenAI 兼容的 chat completions 接口地址 */
    val apiUrl: String = "https://api.openai.com/v1/chat/completions",
    /** API Key */
    val apiKey: String = "",
    /** 模型名称 */
    val model: String = "gpt-4o-mini",
    /** 系统提示词 */
    val prompt: String = DEFAULT_PROMPT
) {
    companion object {
        const val DEFAULT_PROMPT =
            "你是一个专业的翻译助手。请将用户提供的文本翻译成简体中文，只输出翻译结果，不要添加任何解释。如果原文已经是中文，则原样输出。"
    }
}
