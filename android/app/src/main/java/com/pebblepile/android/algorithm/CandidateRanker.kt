package com.pebblepile.android.algorithm

/**
 * #9 排序函数：`rank(当前约束, 有效偏好, 候选清单)`。
 *
 * 流程：先按预算等显式条件硬筛选，再按命中的条件偏好计分；未命中范围的偏好
 * 不参与加权，推断偏好只作低风险背景。输出候选编号、采用的条件与证据编号。
 */
class CandidateRanker(private val scene: ScenePackage) {

    fun rank(
        constraint: QueryConstraint,
        preferences: List<PreferenceCandidate>,
        candidates: List<MenuItem> = scene.menu,
        historyMeals: List<String> = emptyList(),
    ): RankResult {
        val active = preferences.filter { it.status != PreferenceStatus.EXCLUDED }

        val matched = active.filter { it.scope.matches(constraint.weekday, constraint.overtime, constraint.weekend) }
        val unmatched = active.filterNot { it.scope.matches(constraint.weekday, constraint.overtime, constraint.weekend) }

        val explicitMatch = matched.filter { it.status == PreferenceStatus.EXPLICIT }
        val inferredMatch = matched.filter { it.status == PreferenceStatus.INFERRED || it.status == PreferenceStatus.WEAK }
        val convenienceHit = explicitMatch.any { it.direction == "convenience" } ||
            inferredMatch.any { it.direction == "convenience" }
        val convenienceExplicit = explicitMatch.any { it.direction == "convenience" }
        val weekendLeisurely = constraint.weekend && !constraint.overtime &&
            active.flatMap { it.counterExamples }.isNotEmpty()

        val filteredOut = mutableListOf<String>()
        val eligible = candidates.filter { item ->
            val withinBudget = constraint.budgetCny == null || item.priceCny <= constraint.budgetCny!!
            if (!withinBudget) filteredOut.add(item.id)
            withinBudget
        }

        val counterEvidence = if (weekendLeisurely) {
            active.flatMap { it.counterExamples }.distinct()
        } else {
            emptyList()
        }
        val allEvidence = matched.flatMap { it.support }.distinct() + counterEvidence

        val ranked = eligible.map { item ->
            val score = scoreItem(
                item = item,
                constraint = constraint,
                convenienceHit = convenienceHit,
                convenienceExplicit = convenienceExplicit,
                weekendLeisurely = weekendLeisurely,
                historyMeals = historyMeals,
            )
            val matchedConditions = buildList {
                if (convenienceHit) add("优先省事")
                if (weekendLeisurely) add("周末可慢慢做饭")
                constraint.budgetCny?.let { add("预算不超过 ${it} 元") }
            }
            val reason = when {
                weekendLeisurely -> "周末不加班，按愿意花时间做饭的经历比较"
                convenienceHit -> "按省事偏好与历史经历排序"
                else -> "未命中长期条件，按预算与准备时间中性排序"
            }
            RankedCandidate(
                candidateId = item.id,
                score = score,
                matchedConditions = matchedConditions,
                evidence = allEvidence,
                reason = reason,
            )
        }.sortedWith(
            compareByDescending<RankedCandidate> { it.score }
                .thenBy { itemPrice(candidates, it.candidateId) }
                .thenBy { itemPrep(candidates, it.candidateId) }
        )

        return RankResult(
            items = ranked,
            appliedPreferences = matched.map { it.preferenceId },
            unmatchedPreferences = unmatched.map { it.preferenceId },
            filteredOut = filteredOut,
        )
    }

    private fun scoreItem(
        item: MenuItem,
        constraint: QueryConstraint,
        convenienceHit: Boolean,
        convenienceExplicit: Boolean,
        weekendLeisurely: Boolean,
        historyMeals: List<String>,
    ): Double {
        var score: Double
        val evidenceMatch = item.matchedMeal != null && item.matchedMeal in historyMeals
        val budgetMargin = constraint.budgetCny?.let { (it - item.priceCny).toDouble() / it } ?: 0.0

        when {
            weekendLeisurely -> {
                // 周末慢做饭：反转省事分，时长接近可接受做饭时长、接受洗锅的靠前。
                score = (10 - item.convenienceScore).toDouble()
                val cookMinutes = constraint.cookMinutes ?: 40
                val timeFit = 1.0 - kotlin.math.abs(cookMinutes - item.prepMinutes).toDouble() / cookMinutes
                score += timeFit * 3.0
                if (evidenceMatch) score += 1.5
                score += budgetMargin
            }
            convenienceHit -> {
                score = item.convenienceScore.toDouble()
                if (convenienceExplicit) score += 1.0
                if (evidenceMatch) score += 1.5
                score += budgetMargin * 2.0
                score += (10 - item.prepMinutes).toDouble() / 10.0
            }
            else -> {
                // 中性排序：预算余量与准备时间，历史经历只作低风险背景。
                score = budgetMargin * 2.0 + (10 - item.prepMinutes).toDouble() / 10.0
                if (evidenceMatch) score += 0.5
            }
        }
        return score
    }

    private fun itemPrice(candidates: List<MenuItem>, id: String): Int =
        candidates.firstOrNull { it.id == id }?.priceCny ?: Int.MAX_VALUE

    private fun itemPrep(candidates: List<MenuItem>, id: String): Int =
        candidates.firstOrNull { it.id == id }?.prepMinutes ?: Int.MAX_VALUE
}
