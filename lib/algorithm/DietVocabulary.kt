package com.pebblepile.android.algorithm

/** 中文金额、星期、转述句式与码点区间的本地规则工具，仅依赖标准库。 */
internal object DietVocabulary {

    private val digits = mapOf(
        "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9,
    )

    const val NUMBER = """(?:[0-9]+|[零〇一二两三四五六七八九十百千]+)"""

    fun parseChineseNumber(raw: String): Int? {
        if (raw.firstOrNull()?.isDigit() == true) return raw.toIntOrNull()
        var total = 0
        var digit = 0
        for (c in raw) {
            when (c) {
                '零', '〇' -> digit = 0
                '十', '百', '千' -> {
                    val place = when (c) { '十' -> 10; '百' -> 100; else -> 1000 }
                    total += (if (digit == 0) 1 else digit) * place
                    digit = 0
                }
                else -> digit = digits[c.toString()] ?: return null
            }
        }
        return total + digit
    }

    private val weekdayWords = mapOf(
        "周一" to "mon", "星期一" to "mon",
        "周二" to "tue", "星期二" to "tue",
        "周三" to "wed", "星期三" to "wed",
        "周四" to "thu", "星期四" to "thu",
        "周五" to "fri", "星期五" to "fri",
        "周六" to "sat", "星期六" to "sat",
        "周日" to "sun", "星期日" to "sun", "周天" to "sun", "星期天" to "sun",
    )

    fun weekdayOf(text: String): String? = weekdayWords.entries.firstOrNull { text.contains(it.key) }?.value

    fun weekdayLabel(day: String): String = weekdayWords.entries.firstOrNull { it.value == day }?.key ?: day

    /** 转述前缀，如「室友说：」。返回经历者主体；本人陈述返回 null。 */
    fun reportedSubject(text: String): String? {
        val match = Regex("""^\s*(?:我的)?(室友|朋友|同学|同事|家人|他|她)(?=说|讲|告诉我|昨天|今天|周|加班|吃|想)""").find(text) ?: return null
        return match.groupValues[1]
    }

    /** 在原文中定位短语的码点半开区间；找不到返回 null。 */
    fun spanOf(text: String, phrase: String): CodePointSpan? {
        val utf16Index = text.indexOf(phrase).takeIf { it >= 0 } ?: return null
        return spanAt(text, utf16Index, utf16Index + phrase.length)
    }

    fun spanAt(text: String, start: Int, end: Int): CodePointSpan {
        return CodePointSpan(text.codePointCount(0, start), text.codePointCount(0, end))
    }

    /** 按 role 从原文取首个匹配短语的区间；短语表按优先级排列。 */
    fun firstSpan(text: String, phrases: List<String>): CodePointSpan? =
        phrases.firstNotNullOfOrNull { spanOf(text, it) }

    /** 从「四十分钟」这类片段中取时长数词。 */
    val minuteNumberRegex = Regex("""(?<![.0-9])($NUMBER)\s*分钟""")

    private val workdays = setOf("mon", "tue", "wed", "thu", "fri")

    /**
     * 解析本人长期纠正条件，如「以后只在工作日加班时，优先给我推荐省事的晚饭」。
     * 返回条件范围与倾向；未交代的维度留空，表示不限定。
     */
    fun parseCorrectionScope(text: String): ConditionScope? {
        val normalized = text.replace("星期", "周").replace("礼拜", "周").replace("周天", "周日")
            .replace(Regex("""非周末|不是周末|周末以外"""), "工作日")
            .replace(Regex("""非工作日|不是工作日|工作日以外"""), "周末")
        val clause = Regex("""(?:只在|仅在|如果|当|在)(.+?)(?:的时候|时|[，,。]|就|才|优先|推荐|$)""")
            .find(normalized)?.groupValues?.get(1)
            ?: Regex("""^(.+?)(?:的时候|(?<!省)时(?=[，,]|优先|想|我|要|推荐|选择))""")
                .find(normalized)?.groupValues?.get(1)
        val terms = clause ?: normalized
        val dayWords = "一二三四五六日"
        val dayNames = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val noneOvertime = Regex("""(?:没有|不需要|不用|没|不|无需|无须|无)加班""")
        val range = Regex("""周([一二三四五六日])\s*(?:至|到|[-~～])\s*周?([一二三四五六日])""")
        val named = Regex("""周([一二三四五六日])""")
        val namedDays = mutableSetOf<String>()
        var remaining = terms.replace(range) { match ->
            val from = dayWords.indexOf(match.groupValues[1])
            val to = dayWords.indexOf(match.groupValues[2])
            for (step in 0..((to - from + 7) % 7)) namedDays += dayNames[(from + step) % 7]
            ""
        }.replace(named) { match ->
            namedDays += dayNames[dayWords.indexOf(match.groupValues[1])]
            ""
        }
        val workdaysOnly = "工作日" in remaining
        val weekendsOnly = "周末" in remaining
        val noOvertime = noneOvertime.containsMatchIn(remaining)
        remaining = remaining.replace(noneOvertime, "")
        val overtime = "加班" in remaining
        if (overtime && noOvertime) return null
        if (Regex("""除了|除外|以外|除去|不是周|非周""").containsMatchIn(terms)) return null
        // A conjunction can express weekdays and overtime. A disjunction across these dimensions cannot.
        if ("或" in remaining && (overtime || noOvertime)) return null
        if (clause != null) {
            val unrecognized = remaining.replace(Regex("""以后|工作日|周末|加班|并且|而且|并|且|和|与|或者|或|[、\s]"""), "")
            if (unrecognized.isNotEmpty()) return null
        }
        val weekdays = when {
            namedDays.isNotEmpty() -> namedDays + (if (workdaysOnly) workdays else emptySet()) +
                (if (weekendsOnly) setOf("sat", "sun") else emptySet())
            workdaysOnly && !weekendsOnly -> workdays
            else -> emptySet()
        }
        val weekend = true.takeIf { weekendsOnly && !workdaysOnly && namedDays.isEmpty() }
        val requiredOvertime = when {
            overtime -> true
            noOvertime -> false
            else -> null
        }
        return ConditionScope(weekdays, requiredOvertime, weekend)
    }

    /** 仅将明确表达的单一倾向写入偏好。 */
    fun correctionDirection(text: String): String? {
        val negativePrefix = Regex("""(?:不想|不要|不再|不需要|不用|别|不喜欢|不追求|不求|不必|不)(?:再|优先|推荐|选择|考虑|追求|太|图)*$""")
        fun requested(words: Regex) = words.findAll(text).any { match ->
            !negativePrefix.containsMatchIn(text.substring(0, match.range.first))
        }
        val quick = requested(Regex("""省事|省时|方便|快手|快捷|快一点|快点"""))
        val leisurely = requested(Regex("""慢慢做饭|慢做饭|慢慢做|花时间做饭|愿意做饭|愿意洗锅"""))
        return when {
            quick && !leisurely -> "convenience"
            leisurely && !quick -> "leisurely"
            else -> null
        }
    }
}
