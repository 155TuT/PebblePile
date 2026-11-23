package com.pebblepile.android.algorithm

import com.pebblepile.android.model.CapturedSource
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

internal val dietWords = Regex("晚饭|晚餐|今晚吃|做饭|饮食|用餐|饭菜")
internal val preferenceWords = Regex("优先|省事|省时|快手|慢做饭|慢慢做|愿意做饭|愿意洗锅")
private val dinner = Regex("晚饭|晚餐|今晚|晚上|晚膳")
private val choice = Regex("吃(?:点|些)?什么|怎么(?:吃|选)|有什么(?:推荐|建议)|(?:推荐|建议)(?:我|一下|一份|一道|几道)?(?:吃|点|选|晚饭|晚餐)|(?:该|应该|可以)吃哪")
private val rememberedQuestion = Regex("问过|问了|问我|想过|问的是|(?:昨天|昨晚|当时|那天).{0,20}(?:不知道|在想|纠结)|(?:后来|最后)(?:我)?(?:还是)?(?:吃了|点了|选了|去了)")
private val correction = Regex("^\\s*(?:以后|今后|下次只在|只在|仅在)")
private val number = DietVocabulary.NUMBER
private val spendingLimit = Regex("(?:预算(?:是|为|只有)?\\s*(?:不超过|最多|上限)?|(?:最多|至多|不超过|控制在|上限)\\s*)\\s*($number)\\s*[元块]")
private const val timePrefix = "(?:愿意花|可以花|只有|只剩|最多|不超过|可用|限时|控制在|有)"
private val minuteLimit = Regex("""$timePrefix\s*($number)\s*分钟""")
private val hourLimit = Regex("""$timePrefix\s*(?:(半)|($number)\s*个?(半)?)\s*(?:小时|钟头)""")
private val statedLimit = Regex("""$timePrefix[^，。；！？,;\s]{0,12}(?:分钟|小时|钟头)""")
private val conditionalGoal = Regex("只在|仅在|如果|的时候|加班时|休息时")
private val noOvertime = Regex("(?:没有|不需要|不用|无需|无须|没|不)加班|(?:今天|今晚|周末|周[一二三四五六日])休息")

internal fun isDinnerQuestion(text: String): Boolean {
    if (!dinner.containsMatchIn(text) || DietVocabulary.reportedSubject(text) != null || correction.containsMatchIn(text)) {
        return false
    }
    return text.split('。', '！', '？', '?', '；', '\n').any { sentence ->
        choice.containsMatchIn(sentence) && !rememberedQuestion.containsMatchIn(sentence)
    }
}

/** Resolve only constraints the user supplied. Relative dates are anchored to the capture, including on replay. */
internal fun dinnerConstraint(event: ExtractedEvent, source: CapturedSource, goal: String?): QueryConstraint? {
    val current = goal.orEmpty().takeUnless { preferenceWords.containsMatchIn(it) && conditionalGoal.containsMatchIn(it) }.orEmpty()
    val text = source.text
    val weekday = DietVocabulary.weekdayOf(current) ?: event.weekday ?: relativeDay(current, source.capturedAt)
        ?: relativeDay(text, source.capturedAt) ?: return null
    val overtime = overtimeIn(current) ?: overtimeIn(text) ?: return null
    val budget = amount(spendingLimit, current) ?: amount(spendingLimit, text) ?: event.facts.budgetCny
    val minutes = minutesIn(current) ?: minutesIn(text) ?: event.facts.willingCookMinutes
    // An unrecognised explicit upper bound needs clarification before it can filter the menu.
    if (("预算" in current && amount(spendingLimit, current) == null) ||
        ("预算" in text && budget == null) ||
        (statedLimit.containsMatchIn(current) && minutesIn(current) == null) ||
        (statedLimit.containsMatchIn(text) && minutesIn(text) == null)
    ) return null
    return QueryConstraint(weekday, overtime, weekday == "sat" || weekday == "sun", budget, minutes)
}

private fun minutesIn(text: String): Int? {
    amount(minuteLimit, text)?.let { return it }
    val hours = hourLimit.find(text) ?: return null
    if (hours.groups[1] != null) return 30
    val fullHours = DietVocabulary.parseChineseNumber(hours.groupValues[2]) ?: return null
    val total = fullHours * 60L + if (hours.groups[3] == null) 0 else 30
    return total.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

private fun amount(pattern: Regex, text: String): Int? =
    pattern.find(text)?.groupValues?.get(1)?.let(DietVocabulary::parseChineseNumber)

private fun overtimeIn(text: String): Boolean? = when {
    noOvertime.containsMatchIn(text) -> false
    "加班" in text -> true
    else -> null
}

private fun relativeDay(text: String, capturedAt: String): String? {
    val offset = when {
        "明天" in text || "明晚" in text -> 1L
        "今天" in text || "今晚" in text -> 0L
        else -> return null
    }
    val date = try {
        LocalDate.parse(capturedAt.take(10))
    } catch (_: DateTimeParseException) {
        return null
    }
    return date.plusDays(offset).dayOfWeek.name.take(3).lowercase(Locale.ROOT)
}
