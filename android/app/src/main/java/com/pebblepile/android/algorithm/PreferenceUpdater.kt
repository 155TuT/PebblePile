package com.pebblepile.android.algorithm

/**
 * #9 更新函数：`update(有效证据, 人工修订, 当前快照)`。
 *
 * 归并规则：
 * - 同一真实经历的重复分享按 [ExtractedEvent.eventKey] 归并，支持次数只计一次；
 * - 转述（[ObservationType.REPORTED]）与他人材料不计入本人偏好；
 * - 推断偏好至少由 [ScenePackage.minIndependentSupports] 次独立实际经历支持，否则为待核对弱候选；
 * - 本人明确设置（EXPLICIT）优先于推断；人工排除持续生效，重新整理不复现。
 */
class PreferenceUpdater(private val scene: ScenePackage) {

    fun update(
        events: List<ExtractedEvent>,
        revisions: ManualRevisions,
        snapshot: PreferenceSnapshot? = null,
    ): List<PreferenceCandidate> {
        // 1. 按归并键去重：重复分享保留多条来源，但事件只计一次。
        val merged = events.groupBy { it.eventKey }
            .map { (_, group) -> group.first() }

        val eligible = merged.filter { event ->
            event.observationType == ObservationType.SELF_ACTUAL &&
                event.tags.none { it in revisions.excludedTags }
        }

        // 2. 推断偏好：工作日加班 + 省事特征。
        val supportEvents = eligible.filter { event ->
            event.facts.overtime == true &&
                event.facts.weekend != true &&
                event.facts.convenienceMentioned
        }
        val counterEvents = eligible.filter { event -> event.facts.exceptionFor != null }

        val supportEvidence = supportEvents.flatMap { it.evidence.filter { ref -> ref.role in SUPPORT_ROLES } }
        val counterEvidence = counterEvents.flatMap { it.evidence }
        val supportKeys = supportEvents.map { it.eventKey }.distinct()

        // 支持事件都发生在工作日时，推断条件泛化为「工作日且加班」；周末不命中该推断。
        val supportWeekdays = supportEvents.mapNotNull { it.weekday }.toSet()
        val inferredScope = if (supportEvents.isNotEmpty() && supportWeekdays.all { it !in setOf("sat", "sun") }) {
            ConditionScope(weekdays = WORKDAYS, overtime = true)
        } else {
            ConditionScope(overtime = true)
        }

        val inferredStatus = when {
            supportKeys.size >= scene.minIndependentSupports -> PreferenceStatus.INFERRED
            supportKeys.isNotEmpty() -> PreferenceStatus.WEAK
            else -> PreferenceStatus.WEAK
        }

        val inferred = PreferenceCandidate(
            preferenceId = "pref-weekday-overtime-convenience",
            preferenceKey = "weekday_overtime_convenience",
            direction = "convenience",
            scope = inferredScope,
            status = inferredStatus,
            support = supportEvidence,
            counterExamples = counterEvidence,
            supportEventKeys = supportKeys,
            note = if (inferredStatus == PreferenceStatus.INFERRED) {
                "由 ${supportKeys.size} 次独立加班经历推断"
            } else "独立经历不足，待核对",
        )

        // 3. 本人明确设置，范围以纠正条件为准，优先于推断。
        val explicit = revisions.explicit.map { setting ->
            PreferenceCandidate(
                preferenceId = "pref-explicit-${setting.preferenceKey}",
                preferenceKey = setting.preferenceKey,
                direction = setting.direction,
                scope = setting.scope,
                status = PreferenceStatus.EXPLICIT,
                support = listOfNotNull(setting.evidence),
                counterExamples = emptyList(),
                supportEventKeys = emptyList(),
                note = "本人明确设置",
            )
        }

        // 4. 人工排除的候选保持 EXCLUDED；快照中其余候选沿用。
        val excludedIds = revisions.excludedTags.map { "pref-$it" }.toSet()
        val carried = snapshot?.preferences.orEmpty().filter { candidate ->
            candidate.preferenceId !in (explicit.map { it.preferenceId } + inferred.preferenceId)
        }.map { candidate ->
            if (candidate.preferenceId in excludedIds) candidate.copy(status = PreferenceStatus.EXCLUDED) else candidate
        }

        return explicit + listOf(inferred) + carried
    }

    private companion object {
        val SUPPORT_ROLES = setOf("overtime", "time_cost", "prep_effort", "cleanup_effort", "self_evaluation", "meal")
        val WORKDAYS = setOf("mon", "tue", "wed", "thu", "fri")
    }
}
