package com.screentranslate.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.screentranslate.model.AppConfig

/**
 * 应用配置持久化。
 *
 * 安全策略：
 * - API Key 等敏感字段使用 [EncryptedSharedPreferences]（AES256 + Android Keystore）加密存储；
 * - 非敏感字段（API 地址、模型、提示词、开关标志）使用普通 SharedPreferences；
 * - 若加密存储初始化失败（少数设备 Keystore 异常），自动回退到普通存储并记录警告，
 *   保证功能不中断。
 *
 * 稳定性策略：
 * - 敏感字段使用 commit() 同步写入，便于检测写入失败；
 * - 读取时对缺失/损坏字段做容错回退到默认值；
 * - 提供旧版本明文配置的自动迁移。
 */
class PrefsManager(context: Context) {

    private val appContext = context.applicationContext

    /** 普通 SharedPreferences：存储非敏感配置 */
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 加密 SharedPreferences：存储 API Key（可能为 null，初始化失败时回退到普通存储） */
    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences 初始化失败，回退到普通存储: ${e.message}")
        null
    }

    init {
        // 启动时执行一次旧版明文配置迁移
        migratePlaintextApiKeyIfNeeded()
    }

    // ==================== 配置读写 ====================

    /**
     * 保存配置。
     *
     * @return true 表示全部写入成功；false 表示敏感字段写入失败（此时非敏感字段可能已写入）
     */
    fun saveConfig(config: AppConfig): Boolean {
        // 非敏感字段：异步写入
        prefs.edit().apply {
            putString(KEY_API_URL, config.apiUrl)
            putString(KEY_MODEL, config.model)
            putString(KEY_PROMPT, config.prompt)
            apply()
        }
        // 敏感字段：同步写入，检测失败
        return saveApiKey(config.apiKey)
    }

    fun loadConfig(): AppConfig {
        return AppConfig(
            apiUrl = prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL,
            apiKey = loadApiKey(),
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            prompt = prefs.getString(KEY_PROMPT, AppConfig.DEFAULT_PROMPT)
                ?: AppConfig.DEFAULT_PROMPT
        )
    }

    /** 清除已保存的 API Key（保留其他配置） */
    fun clearApiKey() {
        saveApiKey("")
    }

    /** 清除全部配置（恢复默认） */
    fun clearAllConfig() {
        prefs.edit().clear().apply()
        securePrefs?.edit()?.clear()?.apply()
    }

    // ==================== 检测模型缓存 ====================

    /**
     * 保存上一次检测到的可用模型列表（按当前服务商 API 地址区分）。
     * 使用 JSON 数组持久化，便于下次直接展示而无需重新请求接口。
     */
    fun saveDetectedModels(apiUrl: String, models: List<String>) {
        try {
            val key = KEY_DETECTED_MODELS_PREFIX + apiUrl
            val json = org.json.JSONArray(models).toString()
            prefs.edit().putString(key, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存模型列表失败: ${e.message}")
        }
    }

    /** 读取指定服务商上次检测到的模型列表；无缓存时返回空列表 */
    fun loadDetectedModels(apiUrl: String): List<String> {
        return try {
            val key = KEY_DETECTED_MODELS_PREFIX + apiUrl
            val json = prefs.getString(key, null) ?: return emptyList()
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ==================== 敏感字段读写 ====================

    private fun saveApiKey(apiKey: String): Boolean {
        val store = securePrefs ?: prefs
        return try {
            store.edit().putString(KEY_API_KEY, apiKey).commit()
        } catch (e: Exception) {
            Log.e(TAG, "保存 API Key 失败: ${e.message}")
            false
        }
    }

    private fun loadApiKey(): String {
        val store = securePrefs ?: prefs
        return try {
            store.getString(KEY_API_KEY, "") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "读取 API Key 失败: ${e.message}")
            ""
        }
    }

    /** 旧版本将 API Key 明文存在普通 prefs 中，迁移到加密存储后删除原明文 */
    private fun migratePlaintextApiKeyIfNeeded() {
        val legacyKey = try {
            prefs.getString(KEY_API_KEY, null)
        } catch (_: Exception) {
            null
        }
        if (!legacyKey.isNullOrEmpty() && securePrefs != null) {
            if (saveApiKey(legacyKey)) {
                prefs.edit().remove(KEY_API_KEY).apply()
                Log.i(TAG, "API Key 已从明文迁移到加密存储")
            }
        }
    }

    // ==================== 校验 ====================

    companion object {
        private const val TAG = "PrefsManager"

        private const val PREFS_NAME = "screen_translate_prefs"
        private const val SECURE_PREFS_NAME = "screen_translate_secure"

        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_USE_PM_TERMS = "use_pm_terms"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_DETECTED_MODELS_PREFIX = "detected_models_"

        private const val DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4o-mini"

        /**
         * 校验配置合法性。
         *
         * @return 错误信息；null 表示校验通过
         */
        fun validateConfig(config: AppConfig): String? {
            if (config.apiUrl.isBlank()) return "API 地址不能为空"
            if (!URL_REGEX.matches(config.apiUrl)) return "API 地址格式不正确（需以 http:// 或 https:// 开头）"
            if (config.apiKey.isBlank()) return "API Key 不能为空"
            if (config.model.isBlank()) return "模型名称不能为空"
            return null
        }

        private val URL_REGEX = Regex("^https?://[^\\s/$.?#].[^\\s]*$", RegexOption.IGNORE_CASE)
    }

    // ==================== 其他配置（非敏感，保持原逻辑） ====================

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
}
