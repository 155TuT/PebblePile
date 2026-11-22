package com.pebblepile.android.algorithm

import org.junit.Assert.*
import org.junit.Test

class RankerReviewTest {
    private val scene = DefaultDietScenePackage.scenePackage
    private val ranker = CandidateRanker(scene)
    private val weekday = QueryConstraint("wed", overtime = true, weekend = false, budgetCny = 20)
    private val weekend = QueryConstraint("sun", overtime = false, weekend = true, budgetCny = 20, cookMinutes = 40)
    private val soup = scene.menu.single { it.id == "noodle-soup" }
    private val pasta = scene.menu.single { it.id == "tomato-egg-noodle" }
    private val overtime = EvidenceRef(EvidenceSource("overtime", 1, "r1"), CodePointSpan(0, 1), "time_cost")
    private val other = overtime.copy(source = EvidenceSource("other", 1, "r2"))
    private val preference = PreferenceCandidate(
        preferenceId = "weekday-rule", preferenceKey = "weekday_overtime_convenience",
        direction = "convenience", scope = ConditionScope(overtime = true), status = PreferenceStatus.INFERRED,
        support = listOf(overtime, other), counterExamples = emptyList(), supportEventKeys = listOf("one", "two"),
    )

    @Test fun invalidSceneConfigurationFailsBeforeRanking() {
        assertThrows(IllegalArgumentException::class.java) { soup.copy(priceCny = -1) }
        assertThrows(IllegalArgumentException::class.java) { soup.copy(prepMinutes = -1) }
        assertThrows(IllegalArgumentException::class.java) { soup.copy(convenienceScore = 11) }
        assertThrows(IllegalArgumentException::class.java) { scene.copy(menu = listOf(soup, soup.copy(name = "另一份面"))) }
    }

    @Test fun weakAndUncorroboratedPreferencesStayOutOfScoresAndCitations() {
        val neutral = ranker.rank(weekday, emptyList(), scene.menu, emptyList())
        for (invalid in listOf(
            preference.copy(status = PreferenceStatus.WEAK),
            preference.copy(status = PreferenceStatus.EXCLUDED),
            preference.copy(supportEventKeys = listOf("one", "one")),
        )) {
            assertEquals(neutral, ranker.rank(weekday, listOf(invalid), scene.menu, emptyList()))
        }
    }

    @Test fun explicitDirectionOverridesAnOpposingInferenceAndTheWeekendException() {
        val weekendEvidence = other.copy(role = "cleanup_effort")
        val observed = ExtractedEvent("weekend-experience", ObservationType.SELF_ACTUAL, listOf("周末"),
            DietFacts(overtime = false, weekend = true, exceptionFor = preference.preferenceKey),
            listOf(weekendEvidence), "愿意做饭")
        val comparison = preference.copy(scope = ConditionScope(), counterExamples = listOf(weekendEvidence))
        val personal = comparison.copy(preferenceId = "explicit", status = PreferenceStatus.EXPLICIT,
            direction = "leisurely", support = listOf(overtime), counterExamples = emptyList())
        val result = ranker.rank(weekday, listOf(personal, comparison), listOf(soup, pasta), emptyList())
        assertEquals(listOf("explicit"), result.appliedPreferences)
        assertEquals(pasta.id, result.items.first().candidateId)
        assertEquals(listOf(overtime), result.items.first().evidence)
        val quick = personal.copy(direction = "convenience")
        val weekendResult = ranker.rank(weekend, listOf(comparison, quick), listOf(soup, pasta), listOf(observed))
        assertEquals(soup.id, weekendResult.items.first().candidateId)
        assertTrue(weekendResult.items.all { "优先省事" in it.matchedConditions && weekendEvidence !in it.evidence })
    }

    @Test fun budgetAndTimeAreHardLimitsIncludingZeroAndAllTiesAreStable() {
        val free = MenuItem("a", "即食", 0, 0, false, 8)
        val same = free.copy(id = "b")
        val slow = free.copy(id = "slow", prepMinutes = 1)
        val priced = free.copy(id = "priced", priceCny = 1)
        val constraint = weekend.copy(budgetCny = 0, cookMinutes = 0)
        val setting = preference.copy(status = PreferenceStatus.EXPLICIT, scope = ConditionScope(), direction = "leisurely")
        val result = ranker.rank(constraint, listOf(setting), listOf(same, slow, priced, free), emptyList())
        assertEquals(listOf("a", "b"), result.items.map { it.candidateId })
        assertEquals(listOf("slow", "priced"), result.filteredOut)
        assertTrue(result.items.all { it.score.isFinite() })
        assertEquals(result.items, ranker.rank(constraint, listOf(setting), listOf(free, same), emptyList()).items)
    }

    @Test fun onlyPersonalMealsFromAnAppliedSourceCanAddScoreAndMealCitations() {
        val meal = overtime.copy(role = "meal")
        val original = ExtractedEvent("one", ObservationType.SELF_ACTUAL, listOf("加班"), DietFacts(meal = soup.matchedMeal),
            listOf(overtime, meal), "热汤面")
        val menu = listOf(soup, pasta)
        val from = listOf(preference)
        val baseline = ranker.rank(weekday, from, menu, emptyList()).items
        for (ignored in listOf(original.copy(observationType = ObservationType.REPORTED),
            original.copy(observationType = ObservationType.SELF_PLAN),
            original.copy(evidence = listOf(meal.copy(source = EvidenceSource("unrelated", 1, "r3")))))) {
            val items = ranker.rank(weekday, from, menu, listOf(ignored)).items
            assertEquals(baseline.map { it.score }, items.map { it.score })
            assertTrue(items.none { it.evidence.any { ref -> ref.role == "meal" } })
        }
        val ranked = ranker.rank(weekday, from, menu, listOf(original)).items.associateBy { it.candidateId }
        assertTrue(ranked.getValue(soup.id).score > baseline.single { it.candidateId == soup.id }.score)
        assertTrue(meal in ranked.getValue(soup.id).evidence)
        assertFalse(meal in ranked.getValue(pasta.id).evidence)
    }

    @Test fun weekendSettingCitesTheCorrectionAndLeavesWorkdayEvidenceWithItsOwnContext() {
        val correction = other.copy(source = EvidenceSource("correction", 1, "r3"), role = "setting")
        val applied = preference.copy(status = PreferenceStatus.EXPLICIT, scope = ConditionScope(weekend = true),
            support = listOf(overtime, correction), explicitEvidence = correction)
        val workday = ExtractedEvent("one", ObservationType.SELF_ACTUAL, listOf("加班"),
            DietFacts(overtime = true, weekend = false, meal = soup.matchedMeal), listOf(overtime), "加班吃面",
            weekday = "wed")
        val choice = ranker.rank(weekend, listOf(applied), listOf(soup), listOf(workday)).items.single()
        assertEquals(listOf(correction), choice.evidence)
        assertFalse(choice.reason.contains("吃过这道菜"))
    }

    @Test fun correctionsKeepNamedWeekdaysAndLeaveUnknownConditionsForReview() {
        assertEquals(ConditionScope(setOf("mon", "tue", "wed"), overtime = false),
            DietVocabulary.parseCorrectionScope("只在星期一到星期三没有加班时，优先省事"))
        assertEquals(ConditionScope(weekend = true), DietVocabulary.parseCorrectionScope("只在周末时，慢慢做饭"))
        assertNull(DietVocabulary.parseCorrectionScope("只在下雨时，优先省事"))
        assertNull(DietVocabulary.parseCorrectionScope("只在周三下雨时，优先省事"))
        assertEquals("leisurely", DietVocabulary.correctionDirection("不要优先推荐省事，想慢慢做饭"))
        assertNull(DietVocabulary.correctionDirection("晚饭想吃面"))
    }
}
