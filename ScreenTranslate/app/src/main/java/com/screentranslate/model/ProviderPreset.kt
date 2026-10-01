package com.screentranslate.model

/**
 * AI 翻译服务商预设。
 *
 * 内置常用 OpenAI 兼容接口的服务商，用户选择后自动填入 API 地址和推荐模型，
 * 只需填写 API Key 即可使用。也支持"自定义"选项手动配置。
 */
data class ProviderPreset(
    /** 显示名称 */
    val name: String,
    /** OpenAI 兼容的 chat completions 接口地址 */
    val apiUrl: String,
    /** 推荐模型名称 */
    val model: String,
    /** 可选的说明（如计费、官网） */
    val description: String = ""
) {
    companion object {
        /**
         * 内置服务商列表（均为 OpenAI 兼容接口）。
         * 第一项为"自定义"，允许用户手动填写任意地址和模型。
         */
        val BUILT_IN: List<ProviderPreset> = listOf(
            ProviderPreset(
                name = "自定义",
                apiUrl = "",
                model = "",
                description = "手动填写任意 OpenAI 兼容接口"
            ),
            ProviderPreset(
                name = "OpenAI",
                apiUrl = "https://api.openai.com/v1/chat/completions",
                model = "gpt-4o-mini",
                description = "官网: platform.openai.com"
            ),
            ProviderPreset(
                name = "DeepSeek 深度求索",
                apiUrl = "https://api.deepseek.com/v1/chat/completions",
                model = "deepseek-chat",
                description = "官网: platform.deepseek.com"
            ),
            ProviderPreset(
                name = "Qwen 通义千问 (阿里云)",
                apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                model = "qwen-plus",
                description = "官网: bailian.console.aliyun.com"
            ),
            ProviderPreset(
                name = "Kimi 月之暗面",
                apiUrl = "https://api.moonshot.cn/v1/chat/completions",
                model = "moonshot-v1-8k",
                description = "官网: platform.moonshot.cn"
            ),
            ProviderPreset(
                name = "智谱 GLM (清华)",
                apiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                model = "glm-4-flash",
                description = "官网: open.bigmodel.cn"
            ),
            ProviderPreset(
                name = "零一万物 Yi",
                apiUrl = "https://api.lingyiwanwu.com/v1/chat/completions",
                model = "yi-34b-chat",
                description = "官网: platform.lingyiwanwu.com"
            ),
            ProviderPreset(
                name = "MiniMax",
                apiUrl = "https://api.minimaxi.chat/v1/chat/completions",
                model = "MiniMax-M1",
                description = "官网: minimaxi.cn"
            ),
            ProviderPreset(
                name = "SiliconFlow 硅基流动",
                apiUrl = "https://api.siliconflow.cn/v1/chat/completions",
                model = "Qwen/Qwen2.5-7B-Instruct",
                description = "聚合多家模型，官网: siliconflow.cn"
            ),
            ProviderPreset(
                name = "OpenRouter",
                apiUrl = "https://openrouter.ai/api/v1/chat/completions",
                model = "openai/gpt-4o-mini",
                description = "聚合多家模型，官网: openrouter.ai"
            ),
            ProviderPreset(
                name = "Groq",
                apiUrl = "https://api.groq.com/openai/v1/chat/completions",
                model = "llama-3.1-8b-instant",
                description = "高速推理，官网: groq.com"
            ),
            ProviderPreset(
                name = "Together AI",
                apiUrl = "https://api.together.xyz/v1/chat/completions",
                model = "meta-llama/Llama-3.1-8B-Instruct-Turbo",
                description = "开源模型聚合，官网: together.ai"
            )
        )

        /** 根据已保存的 apiUrl 反查预设（用于回显当前选择的服务商） */
        fun findByApiUrl(apiUrl: String): ProviderPreset {
            return BUILT_IN.firstOrNull { it.apiUrl == apiUrl } ?: BUILT_IN[0]
        }
    }
}
