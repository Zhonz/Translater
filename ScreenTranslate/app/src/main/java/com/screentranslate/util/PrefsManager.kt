package com.screentranslate.util

import android.content.Context
import android.content.SharedPreferences
import com.screentranslate.model.AppConfig

/**
 * 使用 SharedPreferences 持久化应用配置（AI 服务商信息等）。
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("screen_translate_prefs", Context.MODE_PRIVATE)

    fun saveConfig(config: AppConfig) {
        prefs.edit().apply {
            putString(KEY_API_URL, config.apiUrl)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_MODEL, config.model)
            putString(KEY_PROMPT, config.prompt)
            apply()
        }
    }

    fun loadConfig(): AppConfig {
        return AppConfig(
            apiUrl = prefs.getString(KEY_API_URL, AppConfig.DEFAULT_PROMPT) ?: "https://api.openai.com/v1/chat/completions",
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            model = prefs.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini",
            prompt = prefs.getString(KEY_PROMPT, AppConfig.DEFAULT_PROMPT) ?: AppConfig.DEFAULT_PROMPT
        )
    }

    companion object {
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_PROMPT = "prompt"
    }
}
