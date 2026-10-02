package com.screentranslate

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/**
 * 术语仓库。
 *
 * 负责术语库的存储、合并与导入导出。
 * 术语由内置库（assets/project_moon_terms.json）与用户自定义库合并而成，
 * 用户自定义术语优先级高于内置（同 key 时覆盖）。
 *
 * 自定义术语持久化在应用内部存储 terms_custom.json，格式与内置库一致：
 * ```json
 * { "languages": { "en": {...}, "kr": {...}, "jp": {...} } }
 * ```
 */
class TermRepository(private val context: Context) {

    /** 支持的语言代码 */
    val supportedLangs = listOf("en", "kr", "jp")

    /** 内置术语（只读，来自 assets） */
    private val builtIn: Map<String, Map<String, String>> by lazy { loadBuiltIn() }

    /** 用户自定义术语（可读写，存储在内部文件） */
    private var custom: Map<String, Map<String, String>> = loadCustom()

    /** 术语库版本号，每次写入自定义术语时递增，供 TermInjector 判断是否需要刷新缓存 */
    @Volatile
    var version: Long = 0L
        private set

    // ==================== 读取 ====================

    /** 获取合并后的术语（自定义覆盖内置） */
    fun getMergedTerms(lang: String): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        builtIn[lang]?.let { merged.putAll(it) }
        custom[lang]?.let { merged.putAll(it) }
        return merged
    }

    /** 获取指定语言的内置术语 */
    fun getBuiltInTerms(lang: String): Map<String, String> = builtIn[lang] ?: emptyMap()

    /** 获取指定语言的用户自定义术语 */
    fun getCustomTerms(lang: String): Map<String, String> = custom[lang] ?: emptyMap()

    /** 获取所有自定义术语总数 */
    fun getCustomCount(): Int = custom.values.sumOf { it.size }

    /** 获取指定语言自定义术语数 */
    fun getCustomCount(lang: String): Int = custom[lang]?.size ?: 0

    // ==================== 写入 ====================

    /**
     * 添加或更新一条术语。
     * @param lang 语言代码 en/kr/jp
     * @param source 原文
     * @param translation 译文
     */
    fun setTerm(lang: String, source: String, translation: String) {
        val mutable = custom.toMutableMap()
        val langMap = (mutable[lang] ?: emptyMap()).toMutableMap()
        langMap[source] = translation
        mutable[lang] = langMap
        custom = mutable
        saveCustom()
    }

    /** 删除一条术语 */
    fun removeTerm(lang: String, source: String) {
        val mutable = custom.toMutableMap()
        val langMap = (mutable[lang] ?: emptyMap()).toMutableMap()
        if (langMap.remove(source) != null) {
            mutable[lang] = langMap
            custom = mutable
            saveCustom()
        }
    }

    /** 批量设置某语言的自定义术语（覆盖该语言全部自定义条目） */
    fun setCustomTerms(lang: String, terms: Map<String, String>) {
        val mutable = custom.toMutableMap()
        mutable[lang] = terms
        custom = mutable
        saveCustom()
    }

    /** 批量合并术语（不覆盖已有自定义条目） */
    fun mergeCustomTerms(lang: String, terms: Map<String, String>) {
        val mutable = custom.toMutableMap()
        val langMap = (mutable[lang] ?: emptyMap()).toMutableMap()
        for ((k, v) in terms) {
            if (!langMap.containsKey(k)) langMap[k] = v
        }
        mutable[lang] = langMap
        custom = mutable
        saveCustom()
    }

    /** 清空某语言的自定义术语 */
    fun clearCustom(lang: String) {
        val mutable = custom.toMutableMap()
        mutable[lang] = emptyMap()
        custom = mutable
        saveCustom()
    }

    /** 清空全部自定义术语 */
    fun clearAllCustom() {
        custom = emptyMap()
        saveCustom()
    }

    // ==================== 导入导出 ====================

    /**
     * 从 JSON 字符串导入术语。
     * @param jsonStr 格式 { "languages": { "en": {...}, ... } }
     * @param overwrite true 覆盖已有自定义条目；false 仅合并新增
     * @return 导入的术语总数
     */
    fun importFromJson(jsonStr: String, overwrite: Boolean): Int {
        val imported = parseLanguages(jsonStr)
        if (imported.isEmpty()) return 0
        var count = 0
        val mutable = custom.toMutableMap()
        for (lang in supportedLangs) {
            val src = imported[lang] ?: continue
            if (src.isEmpty()) continue
            val langMap = if (overwrite) {
                src.toMutableMap()
            } else {
                val existing = (mutable[lang] ?: emptyMap()).toMutableMap()
                for ((k, v) in src) {
                    if (!existing.containsKey(k)) existing[k] = v
                }
                existing
            }
            count += src.size
            mutable[lang] = langMap
        }
        custom = mutable
        saveCustom()
        return count
    }

    /**
     * 导出当前自定义术语为 JSON 字符串。
     * @param includeBuiltIn true 包含内置术语（完整术语库）；false 仅导出自定义
     */
    fun exportToJson(includeBuiltIn: Boolean): String {
        val root = JSONObject()
        val langs = JSONObject()
        for (lang in supportedLangs) {
            val langObj = JSONObject()
            if (includeBuiltIn) {
                builtIn[lang]?.forEach { (k, v) -> langObj.put(k, v) }
            }
            custom[lang]?.forEach { (k, v) -> langObj.put(k, v) }
            langs.put(lang, langObj)
        }
        root.put("languages", langs)
        return root.toString(2)
    }

    // ==================== 持久化 ====================

    private fun loadBuiltIn(): Map<String, Map<String, String>> {
        val json = loadJsonFromAssets(context, "project_moon_terms.json")
        return parseLanguages(json.toString())
    }

    private fun loadCustom(): Map<String, Map<String, String>> {
        return try {
            val file = context.getFileStreamPath(CUSTOM_FILE)
            if (!file.exists()) return emptyMap()
            val content = file.readText(Charsets.UTF_8)
            parseLanguages(content)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    private fun saveCustom() {
        try {
            val root = JSONObject()
            val langs = JSONObject()
            for (lang in supportedLangs) {
                val langObj = JSONObject()
                custom[lang]?.forEach { (k, v) -> langObj.put(k, v) }
                langs.put(lang, langObj)
            }
            root.put("languages", langs)
            context.openFileOutput(CUSTOM_FILE, Context.MODE_PRIVATE).use {
                it.write(root.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        version++
    }

    private fun parseLanguages(jsonStr: String): Map<String, Map<String, String>> {
        return try {
            val root = JSONObject(jsonStr)
            val languages = root.optJSONObject("languages") ?: return emptyMap()
            val result = HashMap<String, Map<String, String>>()
            for (lang in supportedLangs) {
                result[lang] = toStringMap(languages.optJSONObject(lang))
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    private fun toStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = HashMap<String, String>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.optString(key, "")
        }
        return map
    }

    private fun loadJsonFromAssets(context: Context, fileName: String): JSONObject {
        return try {
            context.assets.open(fileName).use { input ->
                val size = input.available()
                val buffer = ByteArray(size)
                input.read(buffer)
                JSONObject(String(buffer, Charsets.UTF_8))
            }
        } catch (e: IOException) {
            e.printStackTrace()
            JSONObject()
        }
    }

    companion object {
        private const val CUSTOM_FILE = "terms_custom.json"
    }
}
