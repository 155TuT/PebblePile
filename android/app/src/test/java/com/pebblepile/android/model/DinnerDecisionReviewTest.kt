package com.pebblepile.android.model

import com.pebblepile.android.algorithm.QueryConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DinnerDecisionReviewTest {
    private fun decision(id: String, redacted: Boolean = false) = DinnerDecision(
        id = id,
        jobId = "job-$id",
        inputRevision = 1,
        preferenceRevision = 0,
        snapshotId = if (redacted) null else "snapshot-$id",
        manifest = null,
        constraint = QueryConstraint("周四", overtime = true, weekend = false, budgetCny = 20),
        suggestions = listOf(
            SuggestionItem("$id:gaifan", "盖饭", "工作日加班时优先省事，预算二十元内。", emptyList()),
            SuggestionItem("$id:tangmian", "热汤面", "省时也省事。", emptyList()),
        ),
    )

    private fun record(
        decisions: List<DinnerDecision>,
        superseded: String? = null,
        outcomes: List<OutcomeItem> = emptyList(),
    ) = PebbleRecord(
        id = "record-1",
        rawText = "今天周四加班，晚饭想省事一点，预算二十元。",
        capturedAt = "2026-11-19T18:30",
        captureMethod = "app",
        dinnerDecisions = decisions,
        supersededDinnerDecisionId = superseded,
        outcomes = outcomes,
    )

    @Test
    fun currentDecisionIsHiddenAfterTheConditionChanges() {
        val first = decision("diet-1")
        val second = decision("diet-2")
        val record = record(listOf(first, second), superseded = second.id)
        assertNull(record.currentDinnerDecision)
    }

    @Test
    fun outcomeKeepsItsOriginalDecisionAfterCorrection() {
        val first = decision("diet-1")
        val second = decision("diet-2")
        val outcome = OutcomeItem("outcome-1", "热汤面", "速度够快", "2026-11-19T20:00", first.id)
        val record = record(listOf(first, second), superseded = second.id, outcomes = listOf(outcome))
        assertEquals(first.id, record.decisionForOutcome(outcome)?.id)
    }

    @Test
    fun redactedDecisionIsNotShownAsCurrentSuggestion() {
        val record = record(listOf(decision("diet-1"), decision("diet-2", redacted = true)))
        assertNull(record.currentDinnerDecision)
    }

    @Test
    fun primarySuggestionUsesThePublishedRanking() {
        val decision = decision("diet-1")
        assertEquals("盖饭", decision.primarySuggestion()?.title)
        assertEquals(listOf("盖饭", "热汤面"), decision.suggestions.map { it.title })
    }

    @Test
    fun constraintSummaryReadsLikeTheQuery() {
        val constraint = QueryConstraint("sat", overtime = false, weekend = true, budgetCny = 20, cookMinutes = 40)
        assertEquals("周六 · 周末 · 不加班 · 预算 20 元 · 可花 40 分钟", constraint.displaySummary())
    }
}
