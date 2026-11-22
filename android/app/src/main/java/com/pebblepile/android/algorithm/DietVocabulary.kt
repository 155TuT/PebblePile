package com.pebblepile.android.algorithm

/** 中文金额、星期、转述句式与码点区间的本地规则工具，仅依赖标准库。 */
internal object DietVocabulary {

    private val digits = mapOf(
        "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9,
    )

    /** 解析「十、十六、十八、二十、四十」等中文金额/时长数词。 */
    fun parseChineseNumber(raw: String): Int? {
        if (!raw.contains("十")) return digits[raw]
        val parts = raw.split("十")
        val tens = if (parts[0].isEmpty()) 1 else digits[parts[0]] ?: return null
        val ones = if (parts.size > 1 && parts[1].isNotEmpty()) digits[parts[1]] ?: return null else 0
        return tens * 10 + ones
    }

    private val weekdayWords = mapOf(
        "周一" to "mon", "星期一" to "mon",
        "周二" to "tue", "星期二" to "tue",
        "周三" to "wed", "星期三" to "wed",
        "周四" to "thu", "星期四" to "thu",
        "周五" to "fri", "星期五" to "fri",
        "周六" to "sat", "星期六" to "sat",
        "周日" to "sun", "星期日" to "sun", "周末" to "sun",
    )

    fun weekdayOf(text: String): String? = weekdayWords.entries.firstOrNull { text.contains(it.key) }?.value

    /** 转述前缀，如「室友说：」。返回经历者主体；本人陈述返回 null。 */
    fun reportedSubject(text: String): String? {
        val match = Regex("""^(室友|朋友|同学|同事|家人|他|她)说[:：]""").find(text) ?: return null
        return match.groupValues[1]
    }

    /** 在原文中定位短语的码点半开区间；找不到返回 null。 */
    fun spanOf(text: String, phrase: String): CodePointSpan? {
        val utf16Index = text.indexOf(phrase).takeIf { it >= 0 } ?: return null
        val start = text.codePointCount(0, utf16Index)
        val end = start + phrase.codePointCount(0, phrase.length)
        return CodePointSpan(start, end)
    }

    /** 按 role 从原文取首个匹配短语的区间；短语表按优先级排列。 */
    fun firstSpan(text: String, phrases: List<String>): CodePointSpan? =
        phrases.firstNotNullOfOrNull { spanOf(text, it) }

    /** 从「四十分钟」这类片段中取时长数词。 */
    val minuteNumberRegex = Regex("""([一二两三四五六七八九十]+)分钟""")

    private val workdays = setOf("mon", "tue", "wed", "thu", "fri")

    /**
     * 解析本人长期纠正条件，如「以后只在工作日加班时，优先给我推荐省事的晚饭」。
     * 返回条件范围与倾向；未交代的维度留空，表示不限定。
     */
    fun parseCorrectionScope(text: String): ConditionScope {
        val weekdays = if (text.contains("工作日")) workdays else emptySet()
        val overtime = when {
            text.contains("不加班") -> false
            text.contains("加班") -> true
            else -> null
        }
        val weekend = when {
            text.contains("周末") -> true
            else -> null
        }
        return ConditionScope(weekdays = weekdays, overtime = overtime, weekend = weekend)
    }

    /** 纠正条件的倾向：省事优先或慢慢做饭。 */
    fun correctionDirection(text: String): String =
        if (listOf("省事", "省时", "方便", "快").any { text.contains(it) }) "convenience" else "leisurely"
}
