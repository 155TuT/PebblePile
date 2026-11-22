package com.pebblepile.android.algorithm

/** 同一来源的新版本替换旧版本，即使新版本已移除全部支持片段。 */
internal fun latestEventVersions(events: List<ExtractedEvent>): List<ExtractedEvent> {
    val newest = events.mapNotNull { it.source }.groupBy { it.sourceId }
        .mapValues { (_, sources) -> sources.maxOf { it.sourceVersion } }
    return events.filter { event ->
        val source = event.source
        source == null || source.sourceVersion == newest[source.sourceId]
    }.distinct()
}

internal fun ManualRevisions.excludes(event: ExtractedEvent, tags: Set<String>): Boolean {
    val scoped = excludedTagsByRecord[event.source?.recordId].orEmpty() + excludedTagsByRecord[event.eventKey].orEmpty()
    return tags.any { it in excludedTags || it in scoped }
}

class PreferenceUpdater(private val scene: ScenePackage) : PreferenceUpdate {
    override val version = "evidence-updater-0.1.0"

    override fun update(
        events: List<ExtractedEvent>,
        revisions: ManualRevisions,
        snapshot: PreferenceSnapshot?,
    ): List<PreferenceCandidate> {
        val actual = latestEventVersions(events).filter { it.observationType == ObservationType.SELF_ACTUAL }
        val grouped = actual.groupBy { it.eventKey }
        val support = grouped.filterValues { group ->
            group.none { revisions.excludes(it, SUPPORT_TAGS) }
        }.values.flatten().filter { event ->
            event.facts.overtime == true && event.facts.weekend == false && event.facts.convenienceMentioned &&
                "饮食" in event.tags && event.evidence.any { it.role in SUPPORT_ROLES }
        }
        val keys = support.map { it.eventKey }.distinct().sorted()
        val refs = support.flatMap { event -> event.evidence.filter { it.role in SUPPORT_ROLES } }.distinct()
        val counters = grouped.values.flatMap { group ->
            if (group.any { revisions.excludes(it, COUNTER_TAGS) }) emptyList() else group.mapNotNull { event ->
                if (event.facts.exceptionFor != KEY) return@mapNotNull null
                val role = when {
                    event.facts.willingToCleanUp == true -> "cleanup_effort"
                    event.facts.willingCookMinutes != null -> "time_cost"
                    else -> "cooking_preference"
                }
                event.evidence.firstOrNull { it.role == role }
            }
        }.distinct()
        val prior = snapshot?.preferences.orEmpty()
        val explicit = linkedMapOf<String, ExplicitPreference>()
        for (candidate in prior.filter { it.status == PreferenceStatus.EXPLICIT }) {
            explicit[candidate.preferenceKey] = ExplicitPreference(candidate.preferenceKey, candidate.direction,
                candidate.scope, candidate.explicitEvidence)
        }
        for (setting in revisions.explicit) explicit[setting.preferenceKey] = setting
        val updated = explicit.values.map { setting ->
            val matchesDirection = setting.preferenceKey == KEY && setting.direction == "convenience"
            val matchingHistory = if (matchesDirection) support.filter { event ->
                val scope = setting.scope
                val dayMatches = scope.weekdays.isEmpty() || event.weekday in scope.weekdays ||
                    (event.weekday == null && event.facts.weekend == false && scope.weekdays.containsAll(WORKDAYS))
                dayMatches && (scope.overtime == null || scope.overtime == event.facts.overtime) &&
                    (scope.weekend == null || scope.weekend == event.facts.weekend)
            } else emptyList()
            val historyRefs = matchingHistory.flatMap { it.evidence.filter { ref -> ref.role in SUPPORT_ROLES } }
            PreferenceCandidate(
                preferenceId = "pref-explicit-${setting.preferenceKey}",
                preferenceKey = setting.preferenceKey, direction = setting.direction, scope = setting.scope,
                status = PreferenceStatus.EXPLICIT,
                support = (listOfNotNull(setting.evidence) + historyRefs).distinct(),
                counterExamples = if (setting.preferenceKey == KEY) counters else emptyList(),
                supportEventKeys = matchingHistory.map { it.eventKey }.distinct().sorted(),
                note = "本人明确设置", explicitEvidence = setting.evidence,
            )
        }.toMutableList()
        val excluded = prior.filter { it.status == PreferenceStatus.EXCLUDED && it.preferenceKey !in explicit }
            .distinctBy { it.preferenceKey }.map { it.copy(support = emptyList(), counterExamples = emptyList(), supportEventKeys = emptyList()) }
        if (KEY !in explicit && excluded.none { it.preferenceKey == KEY }) {
            val status = if (keys.size >= scene.minIndependentSupports) PreferenceStatus.INFERRED else PreferenceStatus.WEAK
            val scope = ConditionScope(weekdays = WORKDAYS, overtime = true)
            updated += PreferenceCandidate(
                preferenceId = "pref-weekday-overtime-convenience", preferenceKey = KEY, direction = "convenience",
                scope = scope, status = status, support = refs, counterExamples = counters, supportEventKeys = keys,
                note = if (status == PreferenceStatus.INFERRED) "由 ${keys.size} 次独立加班经历推断" else "独立经历不足，待核对",
            )
        }
        return updated + excluded
    }

    private companion object {
        const val KEY = "weekday_overtime_convenience"
        val SUPPORT_ROLES = setOf("overtime", "time_cost", "prep_effort", "cleanup_effort", "self_evaluation", "meal")
        val SUPPORT_TAGS = setOf("饮食", "加班", "省事")
        val COUNTER_TAGS = setOf("饮食", "周末", "慢做饭")
        val WORKDAYS = setOf("mon", "tue", "wed", "thu", "fri")
    }
}
