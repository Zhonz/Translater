package com.screentranslate

import android.content.Context

/**
 * 术语注入器。
 *
 * 基于 [TermRepository] 获取合并后的术语库（内置 + 用户自定义），
 * 翻译前扫描识别出的屏幕文字，找出命中的术语，拼入提示词，
 * 确保 AI 翻译时使用统一译名。
 *
 * 采用"按需注入"策略：只把实际出现在文本中的术语加入提示词，避免 token 浪费。
 */
class TermInjector(private val context: Context) {

    private val repository = TermRepository(context)

    /** 合并后的术语表 */
    private var enTerms: Map<String, String> = emptyMap()
    private var krTerms: Map<String, String> = emptyMap()
    private var jpTerms: Map<String, String> = emptyMap()

    /** 预排序的术语键（按长度降序） */
    private var enSortedKeys: List<String> = emptyList()
    private var krSortedKeys: List<String> = emptyList()
    private var jpSortedKeys: List<String> = emptyList()

    /** 上次加载时的版本号，用于检测术语库是否被修改 */
    private var loadedVersion = -1L

    /** 若术语库版本变化则重新加载缓存 */
    private fun ensureFresh() {
        if (repository.version == loadedVersion) return
        enTerms = repository.getMergedTerms("en")
        krTerms = repository.getMergedTerms("kr")
        jpTerms = repository.getMergedTerms("jp")
        enSortedKeys = enTerms.keys.sortedByDescending { it.length }
        krSortedKeys = krTerms.keys.sortedByDescending { it.length }
        jpSortedKeys = jpTerms.keys.sortedByDescending { it.length }
        loadedVersion = repository.version
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
     * @param texts 屏幕上识别出的文字列表
     * @return 命中的术语对列表（原文 -> 译文）
     */
    fun findMatchingTerms(texts: List<String>): List<Pair<String, String>> {
        ensureFresh()
        if (texts.isEmpty()) return emptyList()
        val joined = texts.joinToString(" ")

        val matched = LinkedHashMap<String, String>()
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

    private fun containsWord(text: String, word: String): Boolean {
        if (word.isEmpty()) return false
        val idx = text.indexOf(word, ignoreCase = true)
        if (idx < 0) return false
        val beforeOk = idx == 0 || !isWordChar(text[idx - 1])
        val afterIdx = idx + word.length
        val afterOk = afterIdx >= text.length || !isWordChar(text[afterIdx])
        return beforeOk && afterOk
    }

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
        private const val MAX_TERMS = 60
    }
}
