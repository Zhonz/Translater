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
 * 术语库懒加载（首次使用时才解析 JSON），避免服务启动时阻塞主线程。
 */
class TermInjector(private val context: Context) {

    /** 英->中 术语表（懒加载） */
    private val enTerms: Map<String, String> by lazy { loadLang("en") }
    /** 韩->中 术语表（懒加载） */
    private val krTerms: Map<String, String> by lazy { loadLang("kr") }
    /** 日->中 术语表（懒加载） */
    private val jpTerms: Map<String, String> by lazy { loadLang("jp") }

    /** 预排序的英文术语键（按长度降序），避免每次匹配都排序 */
    private val enSortedKeys by lazy { enTerms.keys.sortedByDescending { it.length } }
    private val krSortedKeys by lazy { krTerms.keys.sortedByDescending { it.length } }
    private val jpSortedKeys by lazy { jpTerms.keys.sortedByDescending { it.length } }

    @Volatile
    private var jsonCache: JSONObject? = null

    private fun getJson(): JSONObject {
        jsonCache?.let { return it }
        synchronized(this) {
            jsonCache?.let { return it }
            val json = loadJsonFromAssets(context, "project_moon_terms.json")
            jsonCache = json
            return json
        }
    }

    private fun loadLang(lang: String): Map<String, String> {
        val languages = getJson().optJSONObject("languages") ?: return emptyMap()
        return toStringMap(languages.optJSONObject(lang))
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
     * @return "en" / "kr" / "jp" / "zh" / "mixed"
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
     * 由于 Project Moon 游戏文本常混合英文名与韩/日文（如 "이상 Yi Sang"），
     * 此处同时检索英/韩/日三套术语表，按命中顺序合并，最多取 [MAX_TERMS] 条。
     *
     * 英文采用词边界匹配（\b），避免 "He" 误匹配 "Heathcliff"；
     * 韩日采用精确子串匹配（东亚文字无空格分词）。
     *
     * @param texts 屏幕上识别出的文字列表
     * @return 命中的术语对列表（原文 -> 译文）
     */
    fun findMatchingTerms(texts: List<String>): List<Pair<String, String>> {
        if (texts.isEmpty()) return emptyList()
        val joined = texts.joinToString(" ")

        val matched = LinkedHashMap<String, String>()

        // 按检测到的主导语言优先检索，再依次检索其余语言，确保混合文本不漏术语
        val searchOrder = buildSearchOrder(detectLanguage(joined))

        for ((termMap, sortedKeys, isEnglish) in searchOrder) {
            for (key in sortedKeys) {
                if (matched.size >= MAX_TERMS) break
                val found = if (isEnglish) {
                    containsWord(joined, key)
                } else {
                    joined.contains(key)
                }
                if (found) {
                    matched[key] = termMap[key] ?: ""
                }
            }
            if (matched.size >= MAX_TERMS) break
        }
        return matched.toList()
    }

    /**
     * 根据主导语言决定三套术语表的检索顺序（主导语言优先）。
     * 返回三元组列表：(术语表, 预排序键, 是否为英文/词边界匹配)
     */
    private fun buildSearchOrder(
        lang: String
    ): List<Triple<Map<String, String>, List<String>, Boolean>> {
        val en = Triple(enTerms, enSortedKeys, true)
        val kr = Triple(krTerms, krSortedKeys, false)
        val jp = Triple(jpTerms, jpSortedKeys, false)
        return when (lang) {
            "kr" -> listOf(kr, en, jp)
            "jp" -> listOf(jp, en, kr)
            else -> listOf(en, kr, jp)
        }
    }

    /**
     * 英文词边界匹配：检查 text 中是否包含作为独立单词的 word。
     * 处理术语中含特殊字符（如 E.G.O, Yi Sang）的情况。
     */
    private fun containsWord(text: String, word: String): Boolean {
        if (word.isEmpty()) return false
        val idx = text.indexOf(word, ignoreCase = true)
        if (idx < 0) return false
        // 检查前一个字符是否为单词分隔符
        val beforeOk = idx == 0 || !isWordChar(text[idx - 1])
        val afterIdx = idx + word.length
        val afterOk = afterIdx >= text.length || !isWordChar(text[afterIdx])
        return beforeOk && afterOk
    }

    /** 判断字符是否为"单词字符"（字母、数字、CJK） */
    private fun isWordChar(c: Char): Boolean {
        return c.isLetterOrDigit() || c.code in 0x4E00..0x9FFF ||
            c.code in 0xAC00..0xD7AF || c.code in 0x3040..0x30FF
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
