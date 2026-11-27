package com.pebblepile.android.algorithm

/** 先按用户给出的预算与可用时间筛选，再用当前场景的偏好和经历排序。 */
class CandidateRanker(private val scene: ScenePackage) : DinnerRanker {
    override val version = "condition-score-ranker-0.1.0"

    override fun rank(
        constraint: QueryConstraint,
        preferences: List<PreferenceCandidate>,
        candidates: List<MenuItem>,
        history: List<ExtractedEvent>,
    ): RankResult {
        val effective = preferences.groupBy { it.preferenceKey }.values.mapNotNull { versions ->
            val current = versions.lastOrNull { it.status == PreferenceStatus.EXPLICIT || it.status == PreferenceStatus.EXCLUDED }
                ?: versions.lastOrNull { it.status == PreferenceStatus.INFERRED }
                ?: versions.last()
            current.takeUnless { it.status == PreferenceStatus.EXCLUDED }
        }
        val available = effective.filter {
            it.direction in directions && (it.status == PreferenceStatus.EXPLICIT ||
                (it.status == PreferenceStatus.INFERRED && it.support.isNotEmpty() &&
                    it.supportEventKeys.distinct().size >= scene.minIndependentSupports))
        }
        val (matched, unmatched) = available.partition {
            it.scope.matches(constraint.weekday, constraint.overtime, constraint.weekend)
        }
        val chosen = matched.lastOrNull { it.status == PreferenceStatus.EXPLICIT }
            ?: matched.maxByOrNull { it.supportEventKeys.distinct().size }
        val latest = latestEventVersions(history)
        val knownSources = latest.mapNotNull { it.source?.sourceId }.toSet()
        val personalHistory = latest.filter { it.observationType == ObservationType.SELF_ACTUAL }
        val contextualSources = personalHistory.filter { event ->
            (event.facts.overtime == null || event.facts.overtime == constraint.overtime) &&
                (event.facts.weekend == null || event.facts.weekend == constraint.weekend) &&
                (chosen == null || chosen.scope.weekdays.isEmpty() || event.weekday in chosen.scope.weekdays)
        }.flatMap { it.evidence }.map { it.source }.toSet()
        val weekendHistory = if (chosen == null && constraint.weekend && !constraint.overtime && constraint.cookMinutes != null) {
            val weekendSources = personalHistory.filter {
                it.facts.weekend == true && it.facts.overtime == false &&
                    it.facts.exceptionFor == "weekday_overtime_convenience"
            }.flatMap { it.evidence }.map { it.source }.toSet()
            effective.filter { it.preferenceKey == "weekday_overtime_convenience" }.flatMap { it.counterExamples }
                .filter { it.source in weekendSources }.distinct()
        } else emptyList()
        val support = chosen?.support.orEmpty().filter { ref ->
            ref == chosen?.explicitEvidence || ref.source.sourceId !in knownSources || ref.source in contextualSources
        }
        val evidence = support.distinct() + weekendHistory
        val sourceIds = evidence.map { it.source }.toSet()
        val mealEvidence = personalHistory.filter { it.facts.meal != null }.groupBy { it.facts.meal }.mapValues { (_, events) ->
            events.flatMap { it.evidence }.filter { it.role == "meal" && it.source in sourceIds }.distinct()
        }
        val direction = chosen?.direction ?: "leisurely".takeIf { weekendHistory.isNotEmpty() }
        val (eligible, filtered) = candidates.partition { item ->
            (constraint.budgetCny == null || item.priceCny <= constraint.budgetCny) &&
                (constraint.cookMinutes == null || item.prepMinutes <= constraint.cookMinutes)
        }
        val ranked = eligible.map { item ->
            val priorMeals = item.matchedMeal?.let { mealEvidence[it] }.orEmpty()
            val budgetMargin = constraint.budgetCny?.takeIf { it > 0 }?.let {
                (it.toDouble() - item.priceCny) / it
            } ?: 0.0
            val speed = (10.0 - item.prepMinutes) / 10.0
            val score = when (direction) {
                "convenience" -> item.convenienceScore + speed + 2.0 * budgetMargin
                "leisurely" -> {
                    val availableMinutes = constraint.cookMinutes
                    val timeFit = if (availableMinutes != null && availableMinutes > 0) {
                        item.prepMinutes.toDouble() / availableMinutes
                    } else 0.0
                    (10.0 - item.convenienceScore) + 3.0 * timeFit + budgetMargin
                }
                else -> 2.0 * budgetMargin + speed
            } + if (priorMeals.isNotEmpty()) 1.5 else 0.0
            val conditions = buildList {
                constraint.budgetCny?.let { add("预算不超过 ${it} 元") }
                constraint.cookMinutes?.let { add("准备时间不超过 ${it} 分钟") }
                when {
                    chosen?.direction == "convenience" -> add("优先省事")
                    chosen?.direction == "leisurely" -> add("愿意慢慢做饭")
                    weekendHistory.isNotEmpty() -> add("周末不加班，可慢慢做饭")
                }
            }
            val basis = when {
                weekendHistory.isNotEmpty() -> "本次周末不加班，可参考愿意慢慢做饭的经历"
                chosen?.status == PreferenceStatus.EXPLICIT -> "按本人明确设置的${conditions.last()}"
                chosen != null -> "按 ${chosen.supportEventKeys.distinct().size} 次独立经历支持的${conditions.last()}"
                else -> "按价格与准备时间比较"
            }
            val reason = buildString {
                append("${item.name} ${item.priceCny} 元，准备 ${item.prepMinutes} 分钟。$basis。")
                if (priorMeals.isNotEmpty()) append("本人在相关经历中吃过这道菜。")
            }
            val cited = (evidence.filterNot { it.role == "meal" } + priorMeals).distinct()
            item to RankedCandidate(item.id, score, conditions, cited, reason)
        }.sortedWith(compareByDescending<Pair<MenuItem, RankedCandidate>> { it.second.score }
            .thenBy { it.first.priceCny }.thenBy { it.first.prepMinutes }.thenBy { it.first.id })
        return RankResult(
            items = ranked.map { it.second },
            appliedPreferences = listOfNotNull(chosen?.preferenceId),
            unmatchedPreferences = unmatched.map { it.preferenceId },
            filteredOut = filtered.map { it.id },
        )
    }

    private companion object {
        val directions = setOf("convenience", "leisurely")
    }
}
