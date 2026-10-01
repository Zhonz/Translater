package com.screentranslate.util

import android.content.Context
import android.content.SharedPreferences
import com.screentranslate.model.AppConfig

/**
 * 使用 SharedPreferences 持久化应用配置（AI 服务商信息、术语注入开关等）。
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
            apiUrl = prefs.getString(KEY_API_URL, "https://api.openai.com/v1/chat/completions")
                ?: "https://api.openai.com/v1/chat/completions",
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            model = prefs.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini",
            prompt = prefs.getString(KEY_PROMPT, AppConfig.DEFAULT_PROMPT)
                ?: AppConfig.DEFAULT_PROMPT
        )
    }

    /** 是否启用 Project Moon 术语注入（边狱巴士/脑叶公司/废墟图书馆统一译名） */
    fun isProjectMoonTermsEnabled(): Boolean =
        prefs.getBoolean(KEY_USE_PM_TERMS, true)

    fun setProjectMoonTermsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_PM_TERMS, enabled).apply()
    }

    /** 悬浮窗服务是否正在运行（由服务在 onCreate/onDestroy 时维护） */
    fun isServiceRunning(): Boolean = prefs.getBoolean(KEY_SERVICE_RUNNING, false)

    fun setServiceRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
    }

    companion object {
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_USE_PM_TERMS = "use_pm_terms"
        private const val KEY_SERVICE_RUNNING = "service_running"
    }
}
