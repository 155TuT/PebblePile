package com.pebblepile.android.algorithm

/**
 * #9 抽取函数：`extract(来源版本, 原文, 场景包)`。
 *
 * 本版默认走本地词表、别名与句式规则；[InferencePort] 返回的模型候选只作标签补充，
 * 可直接计算的事实字段仍以本地规则为准，保证离线可复现。
 */
class RuleEventExtractor(
    private val scene: ScenePackage,
    private val inference: InferencePort? = null,
) {
    fun extract(source: EvidenceSource, text: String): ExtractedEvent {
        val reportedSubject = DietVocabulary.reportedSubject(text)
        val observationType = if (reportedSubject != null) ObservationType.REPORTED else ObservationType.SELF_ACTUAL

        val evidence = mutableListOf<EvidenceRef>()
        fun addEvidence(role: String, span: CodePointSpan?) {
            if (span != null && evidence.none { it.role == role && it.span == span }) {
                evidence.add(EvidenceRef(source, span, role))
            }
        }

        val weekday = DietVocabulary.weekdayOf(text)
        val weekend = weekday == "sat" || weekday == "sun"

        // 加班条件
        val overtimeNegated = Regex("""没有加班|没加班|不加班""").containsMatchIn(text)
        val overtime = when {
            overtimeNegated -> false
            text.contains("加班") -> true
            else -> null
        }
        addEvidence("overtime", DietVocabulary.firstSpan(text, listOf("没有加班", "又加班了", "加班到八点半", "加班")))

        // 预算：「预算二十元」「控制在二十元以内」；区间只取金额段（含“以内”）。
        val budgetMatch = Regex("""(?:预算|控制在)([一二两三四五六七八九十]+)元(以内)?""").find(text)
        val budgetCny = budgetMatch?.let { DietVocabulary.parseChineseNumber(it.groupValues[1]) }
        val budgetPhrase = budgetMatch?.let { it.groupValues[1] + "元" + (it.groupValues.getOrNull(2) ?: "") }
        val budgetSpan = budgetPhrase?.let { DietVocabulary.spanOf(text, it) }
        addEvidence("budget", budgetSpan)

        // 实付价格与菜名：「十八元的热汤面」「十六元的盖饭」；必须带“的”，避免误吞预算句。
        val priceMatch = Regex("""([一二两三四五六七八九十]+)元的([一-龥]+)""").find(text)
        val priceCny = priceMatch?.let { DietVocabulary.parseChineseNumber(it.groupValues[1]) }
        var meal = priceMatch?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
        val priceSpan = priceMatch?.let { DietVocabulary.spanOf(text, it.value) }
        addEvidence("meal", priceSpan)

        // 菜单归一：原文直接提到的菜名
        val menuMeal = scene.menu.firstNotNullOfOrNull { item ->
            val matched = item.matchedMeal?.takeIf { text.contains(it) }
            matched?.let { item to it }
        }
        if (meal == null) meal = menuMeal?.second
        if (menuMeal != null && priceSpan == null) {
            addEvidence("meal", DietVocabulary.spanOf(text, menuMeal.second))
        }

        // 时长：「十分钟就吃上了」「四十分钟」
        val minuteMatch = DietVocabulary.minuteNumberRegex.find(text)
        val minutes = minuteMatch?.let { DietVocabulary.parseChineseNumber(it.groupValues[1]) }
        val timeSpan = DietVocabulary.firstSpan(text, listOf("十分钟就吃上了", "四十分钟"))
            ?: minuteMatch?.let { DietVocabulary.spanOf(text, it.value) }
        addEvidence("time_cost", timeSpan)

        // 备菜投入
        val prepNeeded = when {
            text.contains("不用准备食材") -> false
            text.contains("准备食材") || text.contains("做菜") -> true
            else -> null
        }
        addEvidence("prep_effort", DietVocabulary.firstSpan(text, listOf("不用准备食材", "准备食材")))

        // 清理投入
        val cleanupNeeded = when {
            text.contains("不用洗锅") || text.contains("不用洗") -> false
            text.contains("洗锅") || text.contains("洗碗") -> true
            else -> null
        }
        val willingToCleanUp = if (text.contains("愿意洗锅") || text.contains("愿意洗碗")) true else cleanupNeeded?.takeIf { it }
        addEvidence("cleanup_effort", DietVocabulary.firstSpan(text, listOf("也不用洗锅", "不用洗锅", "愿意洗锅", "洗锅")))

        // 省事特征
        val conveniencePhrases = listOf("省事", "省时", "不用洗锅", "不用准备食材", "十分钟就吃上了", "很快")
        val convenienceMentioned = conveniencePhrases.any { text.contains(it) }

        // 本人评价；引文区间取短词，事实文本保留完整感受。
        val satisfactionPhrase = listOf("省事让我很满意", "放松", "满意", "省事").firstOrNull { text.contains(it) }
        val satisfactionQuote = when {
            text.contains("省事让我很满意") -> "省事让我很满意"
            else -> satisfactionPhrase
        }
        val satisfactionSpan = DietVocabulary.firstSpan(text, listOf("省事", "放松"))
        addEvidence("self_evaluation", satisfactionSpan)

        // 周末例外：周末且愿意慢慢做饭、洗锅
        val exceptionFor = if (weekend && (cleanupNeeded == true || minutes != null)) {
            "weekday_overtime_convenience"
        } else null

        val facts = DietFacts(
            overtime = overtime,
            weekend = weekend,
            budgetCny = budgetCny,
            priceCny = priceCny,
            meal = meal,
            durationMinutes = minutes?.takeIf { overtime == true },
            prepNeeded = prepNeeded,
            cleanupNeeded = cleanupNeeded,
            convenienceMentioned = convenienceMentioned,
            willingCookMinutes = minutes?.takeIf { weekend == true },
            willingToCleanUp = willingToCleanUp,
            satisfaction = satisfactionQuote,
            exceptionFor = exceptionFor,
        )

        val tags = buildList {
            if (scene.labels.any { entry -> entry.aliases.any { text.contains(it) } }) add("饮食")
            if (overtime == true) add("加班")
            if (weekend) add("周末")
            if (convenienceMentioned) add("省事")
            if (exceptionFor != null) add("慢做饭")
            if (isEmpty()) add("待整理")
        }

        val modelEvent = inference?.suggestExtraction(source, text, scene)
        val mergedTags = (tags + (modelEvent?.tags ?: emptyList())).distinct()

        val eventKey = listOf(
            observationType.name,
            weekday ?: "na",
            overtime?.toString() ?: "na",
            meal ?: "na",
        ).joinToString("|")

        return ExtractedEvent(
            eventKey = eventKey,
            observationType = observationType,
            tags = mergedTags,
            facts = facts,
            evidence = evidence,
            rawText = text,
            subject = reportedSubject,
            weekday = weekday,
        )
    }
}
