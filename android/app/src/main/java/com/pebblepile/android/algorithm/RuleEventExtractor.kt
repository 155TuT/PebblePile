package com.pebblepile.android.algorithm

import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** 离线抽取场景标签、事实和原话位置；模型补充的标签也要回到本条来源。 */
class RuleEventExtractor(
    private val scene: ScenePackage,
    private val inference: InferencePort? = null,
) : EventExtractor {
    override val version = "rule-extractor-0.1.0"

    override fun extract(source: EvidenceSource, text: String): ExtractedEvent {
        val subject = DietVocabulary.reportedSubject(text)
        val type = when {
            subject != null -> ObservationType.REPORTED
            text.isBlank() || OTHER.containsMatchIn(text) -> ObservationType.OTHER
            PLAN.containsMatchIn(text) && !ACTUAL.containsMatchIn(text) -> ObservationType.SELF_PLAN
            else -> ObservationType.SELF_ACTUAL
        }
        val evidence = mutableListOf<EvidenceRef>()
        fun add(role: String, span: CodePointSpan?) {
            if (span != null) evidence += EvidenceRef(source, span, role)
        }
        fun matchedSpan(match: MatchResult?, group: Int = 0): CodePointSpan? {
            val range = match?.groups?.get(group)?.range ?: return null
            return DietVocabulary.spanAt(text, range.first, range.last + 1)
        }
        fun phrase(role: String, phrases: List<String>): String? {
            val found = phrases.firstOrNull(text::contains) ?: return null
            add(role, DietVocabulary.spanOf(text, found))
            return found
        }

        val weekday = DietVocabulary.weekdayOf(text)
        val weekend = when {
            weekday != null -> weekday in setOf("sat", "sun")
            "周末" in text -> true
            "工作日" in text -> false
            else -> null
        }
        val overtimePhrase = phrase("overtime", listOf("没有加班", "没加班", "不加班", "又加班了", "加班到八点半", "加班"))
        val overtime = overtimePhrase?.let { !it.startsWith("没") && !it.startsWith("不") }
        val budget = BUDGET.find(text)
        add("budget", matchedSpan(budget, 1))
        val price = PRICE.find(text)
        // 金额后先找场景包中的菜名，避免把“热汤面很省事”整句当成菜名。
        val names = scene.menu.flatMap { listOfNotNull(it.matchedMeal, it.name) }.distinct().sortedByDescending(String::length)
        val mealStart = price?.range?.last?.plus(1)
        val pricedMeal = mealStart?.let { start ->
            names.firstOrNull { text.startsWith(it, start) }
        }
        val meal = pricedMeal ?: names.mapNotNull { name ->
            text.indexOf(name).takeIf { it >= 0 }?.let { it to name }
        }.minByOrNull { it.first }?.second
        if (price != null && pricedMeal != null) {
            add("meal", DietVocabulary.spanAt(text, price.range.first, price.range.last + 1 + pricedMeal.length))
        } else {
            if (price != null) add("price", matchedSpan(price))
            add("meal", meal?.let { DietVocabulary.spanOf(text, it) })
        }
        val time = DietVocabulary.minuteNumberRegex.find(text)
        val minutes = time?.let { DietVocabulary.parseChineseNumber(it.groupValues[1]) }
        if (time != null) {
            val end = time.range.last + 1
            val suffix = if (text.startsWith("就吃上了", end)) "就吃上了" else ""
            add("time_cost", DietVocabulary.spanAt(text, time.range.first, end + suffix.length))
        }
        val prep = phrase("prep_effort", listOf("不用准备食材", "准备食材", "做菜"))
        val cleanup = phrase("cleanup_effort", listOf("也不用洗锅", "不用洗锅", "不用洗碗", "不愿意洗锅", "不愿意洗碗",
            "愿意洗锅", "愿意洗碗", "洗锅", "洗碗"))
        val willingClean = when {
            cleanup?.startsWith("不愿意") == true -> false
            cleanup?.startsWith("愿意") == true -> true
            else -> null
        }
        val convenient = listOf("省事", "省时", "不用洗锅", "不用准备食材", "十分钟就吃上了", "很快").any(text::contains) &&
            !NEGATIVE_CONVENIENCE.containsMatchIn(text)
        val evaluation = if (NEGATIVE_CONVENIENCE.containsMatchIn(text)) {
            val negative = NEGATIVE_CONVENIENCE.find(text)
            add("self_evaluation", matchedSpan(negative))
            negative?.value
        } else {
            val word = phrase("self_evaluation", listOf("省事", "放松", "满意", "省时"))
            if ("省事让我很满意" in text) "省事让我很满意" else word
        }
        val willingMinutes = minutes?.takeIf { WILLING_TIME.containsMatchIn(text) }
        if ("慢慢做饭" in text) add("cooking_preference", DietVocabulary.spanOf(text, "慢慢做饭"))
        val exception = weekend == true && (willingMinutes != null || willingClean == true || "慢慢做饭" in text)
        val facts = DietFacts(
            overtime = overtime, weekend = weekend,
            budgetCny = budget?.let { DietVocabulary.parseChineseNumber(it.groupValues[2]) },
            priceCny = price?.let { DietVocabulary.parseChineseNumber(it.groupValues[1]) },
            meal = meal, durationMinutes = minutes?.takeUnless { willingMinutes != null },
            prepNeeded = prep?.let { !it.startsWith("不用") },
            cleanupNeeded = cleanup?.let { "不用" !in it },
            convenienceMentioned = convenient, willingCookMinutes = willingMinutes, willingToCleanUp = willingClean,
            satisfaction = evaluation, exceptionFor = if (exception) "weekday_overtime_convenience" else null,
        )
        val tags = buildList {
            for (label in scene.labels) {
                val applies = when (label.canonical) {
                    "加班" -> overtime == true
                    "周末" -> weekend == true
                    "省事" -> convenient
                    "慢做饭" -> exception
                    else -> label.aliases.any(text::contains) || label.canonical in text
                }
                if (applies) add(label.canonical)
            }
            addAll(modelTags(source, text))
            if (isEmpty()) add("待整理")
        }.distinct()
        val key = source.eventId?.takeIf { it.isNotBlank() } ?: run {
            val normalized = text.trim().replace("\r\n", "\n").replace('\r', '\n')
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            "text:" + digest.joinToString("") { "%02x".format(it) }
        }
        return ExtractedEvent(key, type, tags, facts, evidence, text, subject, weekday, source)
    }

    private fun modelTags(source: EvidenceSource, text: String): List<String> {
        val model = try {
            inference?.suggestExtraction(source, text, scene)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val length = text.codePointCount(0, text.length)
        if (model.rawText != text || model.source != source || model.evidence.isEmpty() ||
            model.evidence.any { it.source != source || it.span.end > length || it.span.end == it.span.start }) return emptyList()
        val quotes = model.evidence.map { it.span.quoteIn(text) }
        return scene.labels.filter { label ->
            label.canonical in model.tags && quotes.any { quote ->
                label.canonical in quote || label.aliases.any { it.isNotBlank() && it in quote }
            }
        }.map { it.canonical }
    }

    private companion object {
        val BUDGET = Regex("""(?:预算|控制在)\s*((${DietVocabulary.NUMBER})\s*元(?:以内)?)""")
        val PRICE = Regex("""(?<![.0-9])(${DietVocabulary.NUMBER})\s*元的""")
        val WILLING_TIME = Regex("""(?:愿意|可以|打算)花?\s*${DietVocabulary.NUMBER}\s*分钟""")
        val OTHER = Regex("""^\s*(?:收藏|转发|摘录|菜谱|攻略|假如|假设|如果|AI[：:]|系统[：:])|[？?]\s*$""")
        val PLAN = Regex("""(?:明天|下次|下周|准备去|计划|打算|准备吃|想吃|想点|想试|准备做)""")
        val ACTUAL = Regex("""(?:我吃了|最后吃了|我点了|已经吃|刚吃完|吃上了|让我很满意|让我放松)""")
        val NEGATIVE_CONVENIENCE = Regex("""(?:不太|并不|一点也不|不)(?:省事|省时|方便)""")
    }
}
