package com.screentranslate

import com.screentranslate.model.AppConfig
import kotlinx.coroutines.Dispatchers
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
 */
class TranslationManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 批量翻译文字列表。
     *
     * @param texts 待翻译的文字列表
     * @param config AI 服务商配置
     * @return 翻译后的文字列表（与输入顺序一一对应）；失败时返回空列表
     */
    suspend fun translate(texts: List<String>, config: AppConfig): List<String> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty() || config.apiKey.isBlank()) {
                return@withContext emptyList()
            }

            // 将文字列表打包为 JSON 数组发送给 AI，要求其返回同顺序的 JSON 数组
            val inputArray = JSONArray(texts)
            val systemPrompt = buildString {
                append(config.prompt)
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

            val request = Request.Builder()
                .url(config.apiUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext emptyList()
                    }
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val json = JSONObject(body)
                    val content = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()

                    // 解析返回的 JSON 数组（去除可能的代码块标记）
                    val cleaned = content
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val resultArray = JSONArray(cleaned)
                    val results = mutableListOf<String>()
                    for (i in 0 until resultArray.length()) {
                        results.add(resultArray.getString(i))
                    }

                    // 数量对齐：若返回数量与输入不一致，用空字符串补齐
                    while (results.size < texts.size) {
                        results.add("")
                    }
                    return@withContext results
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext emptyList()
            }
        }
}
