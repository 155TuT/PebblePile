package com.pebblepile.android.algorithm

interface EventExtractor {
    val version: String
    fun extract(source: EvidenceSource, text: String): ExtractedEvent
}

interface PreferenceUpdate {
    val version: String
    fun update(
        events: List<ExtractedEvent>,
        revisions: ManualRevisions,
        snapshot: PreferenceSnapshot? = null,
    ): List<PreferenceCandidate>
}

interface DinnerRanker {
    val version: String
    fun rank(
        constraint: QueryConstraint,
        preferences: List<PreferenceCandidate>,
        candidates: List<MenuItem>,
        history: List<ExtractedEvent>,
    ): RankResult
}
