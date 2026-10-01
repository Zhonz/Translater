package com.screentranslate

import com.screentranslate.model.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 翻译管理器。
 *
 * 调用 OpenAI 兼容的 chat completions 接口，将识别到的文字批量翻译为中文。
 * 服务商地址、API Key、模型、系统提示词均可在配置中自定义。
 *
 * 对瞬时错误（网络异常、5xx）自动重试，提升持续翻译的稳定性。
 */
class TranslationManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 最近一次翻译失败的用户可读错误信息（如 API Key 无效、额度不足）。
     * 调用方可在 translate 返回空列表时读取此值向用户提示。
     * 成功翻译或瞬时错误（会重试）时为 null。
     */
    @Volatile
    var lastError: String? = null
        private set

    companion object {
        /** AI 可能用来包装数组的字段名 */
        private val arrayKeys = arrayOf("translations", "result", "results", "data", "output", "texts")

        /** 最大重试次数（不含首次） */
        private const val MAX_RETRIES = 2
        /** 重试退避基数（毫秒） */
        private const val RETRY_BASE_DELAY_MS = 1000L
    }

    /**
     * 批量翻译文字列表。
     *
     * @param texts 待翻译的文字列表
     * @param config AI 服务商配置
     * @param termContext 可选的术语上下文（由 TermInjector 生成），会拼入系统提示词
     * @return 翻译后的文字列表（与输入顺序一一对应）；失败时返回空列表
     */
    suspend fun translate(
        texts: List<String>,
        config: AppConfig,
        termContext: String = ""
    ): List<String> =
        withContext(Dispatchers.IO) {
            lastError = null
            if (texts.isEmpty() || config.apiKey.isBlank()) {
                lastError = "API Key 未配置"
                return@withContext emptyList()
            }

            // 将文字列表打包为 JSON 数组发送给 AI，要求其返回同顺序的 JSON 数组
            val inputArray = JSONArray(texts)
            val systemPrompt = buildString {
                append(config.prompt)
                if (termContext.isNotBlank()) {
                    append("\n\n")
                    append(termContext)
                }
                append("\n\n用户会发送一个 JSON 字符串数组，请将每个元素翻译成简体中文，")
                append("并仅返回一个 JSON 字符串数组，保持元素数量和顺序与输入完全一致，")
                append("不要输出任何额外的解释、代码块标记或前后缀文本。")
            }

            val requestBody = JSONObject().apply {
                put("model", config.model)
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", inputArray.toString())
                    })
                })
            }.toString()

            var lastResult: List<String>? = null
            var attempt = 0
            while (attempt <= MAX_RETRIES) {
                val result = tryTranslate(config, requestBody, texts.size)
                if (result != null) {
                    lastResult = result
                    break
                }
                attempt++
                if (attempt <= MAX_RETRIES) {
                    // 指数退避：1s, 2s
                    delay(RETRY_BASE_DELAY_MS * attempt)
                }
            }

            lastResult ?: emptyList()
        }

    /**
     * 执行一次翻译请求，成功返回结果列表，瞬时失败返回 null（触发重试）。
     */
    private fun tryTranslate(
        config: AppConfig,
        requestBody: String,
        expectedSize: Int
    ): List<String>? {
        val request = Request.Builder()
            .url(config.apiUrl)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                // 5xx 服务端错误 → 重试
                if (response.code in 500..599) {
                    return null
                }
                if (!response.isSuccessful) {
                    // 4xx 客户端错误（如 401 key 无效、429 限流）不重试，记录用户可读错误
                    val errorBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    lastError = when (response.code) {
                        401 -> "API Key 无效或未授权，请检查配置"
                        403 -> "API Key 无权限访问该模型"
                        429 -> "请求过于频繁或额度已用尽，请稍后再试"
                        else -> "翻译服务返回错误 ${response.code}"
                    }
                    // 记录诊断日志（不含 API Key）
                    android.util.Log.w(
                        "TranslationManager",
                        "HTTP ${response.code}: ${errorBody.take(200)}"
                    )
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim() ?: return emptyList()

                val resultArray = parseJsonArraySafely(content)
                val results = mutableListOf<String>()
                if (resultArray != null) {
                    for (i in 0 until resultArray.length()) {
                        results.add(resultArray.optString(i, ""))
                    }
                }

                // 数量对齐：若返回数量与输入不一致，用空字符串补齐
                while (results.size < expectedSize) {
                    results.add("")
                }
                results
            }
        } catch (e: java.net.SocketTimeoutException) {
            null // 超时 → 重试
        } catch (e: java.io.IOException) {
            null // 网络 IO 异常 → 重试
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList() // 其他异常不重试
        }
    }

    /** 用于剥离 ```...``` 代码块（含语言标签）的正则，支持跨行 */
    private val codeFenceRegex = Regex("```[a-zA-Z]*\\s*", RegexOption.MULTILINE)

    /**
     * 从 AI 返回内容中鲁棒地解析 JSON 数组。
     * 处理：代码块标记、前后多余文本、对象包装（如 {"result":[...]}）。
     */
    private fun parseJsonArraySafely(content: String): JSONArray? {
        // 用正则移除所有代码块标记（含 ```json、``` 等），比 removePrefix 更鲁棒
        var cleaned = codeFenceRegex.replace(content, "").trim()

        // 尝试直接解析为数组
        try {
            return JSONArray(cleaned)
        } catch (_: Exception) { }

        // 尝试从文本中提取第一个 JSON 数组
        val arrayStart = cleaned.indexOf('[')
        if (arrayStart >= 0) {
            // 找到匹配的闭合括号
            val end = findMatchingBracket(cleaned, arrayStart)
            if (end > arrayStart) {
                try {
                    return JSONArray(cleaned.substring(arrayStart, end + 1))
                } catch (_: Exception) { }
            }
        }

        // 尝试解析为对象后提取数组字段
        try {
            val obj = JSONObject(cleaned)
            for (key in arrayKeys) {
                if (obj.has(key)) {
                    val arr = obj.optJSONArray(key)
                    if (arr != null) return arr
                }
            }
            // 遍历对象找第一个数组字段
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = obj.optJSONArray(k)
                if (arr != null) return arr
            }
        } catch (_: Exception) { }

        return null
    }

    /** 找到与 start 位置 '[' 匹配的 ']' 的索引 */
    private fun findMatchingBracket(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    /**
     * 释放 OkHttp 资源（连接池、线程池）。
     */
    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
