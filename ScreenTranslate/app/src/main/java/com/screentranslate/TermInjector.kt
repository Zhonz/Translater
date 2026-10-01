package com.screentranslate

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/**
 * Project Moon 术语注入器。
 *
 * 内置边狱巴士、脑叶公司、废墟图书馆的多语言（英/韩/日 -> 简中）术语库。
 * 翻译前扫描识别出的屏幕文字，找出命中的术语，拼入提示词，
 * 确保 AI 翻译时使用社区统一译名（如 Yi Sang -> 李箱、Lobotomy Corporation -> 脑叶公司）。
 *
 * 采用"按需注入"策略：只把实际出现在文本中的术语加入提示词，避免 token 浪费。
 */
class TermInjector(context: Context) {

    /** 英->中 术语表 */
    private val enTerms: Map<String, String>
    /** 韩->中 术语表 */
    private val krTerms: Map<String, String>
    /** 日->中 术语表 */
    private val jpTerms: Map<String, String>

    init {
        val json = loadJsonFromAssets(context, "project_moon_terms.json")
        val languages = json.optJSONObject("languages") ?: JSONObject()
        enTerms = toStringMap(languages.optJSONObject("en"))
        krTerms = toStringMap(languages.optJSONObject("kr"))
        jpTerms = toStringMap(languages.optJSONObject("jp"))
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

    /**
     * 检测一段文本的主导语言。
     * @return "en" / "kr" / "jp" / "mixed" / "unknown"
     */
    fun detectLanguage(text: String): String {
        var kr = 0
        var jp = 0
        var cn = 0
        var en = 0
        for (ch in text) {
            when {
                ch.code in 0xAC00..0xD7AF -> kr++
                ch.code in 0x3040..0x30FF -> jp++
                ch.code in 0x4E00..0x9FFF -> cn++
                ch.code in 0x61..0x7A || ch.code in 0x41..0x5A -> en++
            }
        }
        return when {
            kr > jp && kr > en -> "kr"
            jp > kr && jp > en -> "jp"
            en >= kr && en >= jp && en > 0 -> "en"
            cn > 0 && en == 0 && kr == 0 && jp == 0 -> "zh"
            else -> "mixed"
        }
    }

    /**
     * 从术语表中找出在文本中出现的术语。
     *
     * @param texts 屏幕上识别出的文字列表
     * @return 命中的术语对列表（原文 -> 译文），按原文长度降序（长词优先）
     */
    fun findMatchingTerms(texts: List<String>): List<Pair<String, String>> {
        if (texts.isEmpty()) return emptyList()
        val joined = texts.joinToString(" ")

        val lang = detectLanguage(joined)
        val termMap: Map<String, String> = when (lang) {
            "kr" -> krTerms
            "jp" -> jpTerms
            else -> enTerms // 默认英文
        }

        val matched = LinkedHashMap<String, String>()
        // 长词优先匹配，避免短词遮蔽长词
        val sortedKeys = termMap.keys.sortedByDescending { it.length }
        for (key in sortedKeys) {
            if (matched.size >= MAX_TERMS) break
            // 英文做大小写不敏感匹配；韩日做精确包含匹配
            val found = if (lang == "en") {
                joined.contains(key, ignoreCase = true)
            } else {
                joined.contains(key)
            }
            if (found) {
                matched[key] = termMap[key] ?: ""
            }
        }
        return matched.toList()
    }

    /**
     * 将命中术语格式化为提示词片段。
     */
    fun buildTermContext(texts: List<String>): String {
        val matched = findMatchingTerms(texts)
        if (matched.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是《边狱巴士》《脑叶公司》《废墟图书馆》的官方/社区统一译名对照表，")
        sb.append("翻译时必须严格使用以下译名，不得自行音译或意译：\n")
        for ((src, dst) in matched) {
            sb.append("- ").append(src).append(" -> ").append(dst).append("\n")
        }
        return sb.toString()
    }

    companion object {
        /** 单次最多注入的术语数量，避免提示词过长 */
        private const val MAX_TERMS = 60
    }
}
