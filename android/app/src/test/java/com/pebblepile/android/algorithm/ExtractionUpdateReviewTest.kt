package com.pebblepile.android.algorithm

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ExtractionUpdateReviewTest {
    private val scene = DefaultDietScenePackage.scenePackage
    private val extractor = RuleEventExtractor(scene)
    private val updater = PreferenceUpdater(scene)
    private val dinner = "周一加班，我吃了18元的热汤面，不用洗锅。"

    private fun extract(id: String, text: String = dinner, version: Long = 1, event: String = id) =
        extractor.extract(EvidenceSource(id, version, id, event), text)

    @Test fun identicalMealsOnIndependentDaysCountSeparatelyAndRepeatedShareKeepsBothSources() {
        val first = extract("first")
        val anotherWeek = extract("another-week")
        val share = extract("share", event = "first")
        val prefs = updater.update(listOf(share, first, anotherWeek, share), ManualRevisions()).single()
        assertEquals(PreferenceStatus.INFERRED, prefs.status)
        assertEquals(setOf("first", "another-week"), prefs.supportEventKeys.toSet())
        assertEquals(setOf("first", "another-week", "share"), prefs.support.map { it.source.sourceId }.toSet())
        assertEquals(prefs.support.distinct(), prefs.support)
    }

    @Test fun freshShareFallsBackToMatchingTextAndOneRecordCanContainDifferentDinners() {
        val home = extractor.extract(EvidenceSource("home", 1, "record"), dinner)
        val shared = extractor.extract(EvidenceSource("share", 1, "second-record"), "  $dinner\n")
        val next = extractor.extract(EvidenceSource("append", 1, "record"), dinner.replace("周一", "周三"))
        val prefs = updater.update(listOf(home, shared, next), ManualRevisions()).single()
        assertEquals(home.eventKey, shared.eventKey)
        assertNotEquals(home.eventKey, next.eventKey)
        assertEquals(2, prefs.supportEventKeys.size)
        assertEquals(setOf("home", "share", "append"), prefs.support.map { it.source.sourceId }.toSet())
    }

    @Test fun weekdayRuleRequiresDatedEvidenceAndTheThresholdMatchesTheScene() {
        val unknown = extract("unknown", "加班后我吃了盖饭，省事。")
        val evidence = listOf(extract("first"), unknown)
        val candidate = updater.update(evidence, ManualRevisions()).single()
        assertEquals(listOf("first"), candidate.supportEventKeys)
        assertFalse(candidate.scope.matches("sat", queryOvertime = true, queryWeekend = true))
        assertThrows(IllegalArgumentException::class.java) { scene.copy(minIndependentSupports = 1) }
        val stricter = PreferenceUpdater(scene.copy(minIndependentSupports = 3))
        assertEquals(PreferenceStatus.WEAK, stricter.update(listOf(extract("a"), extract("b")), ManualRevisions()).single().status)
    }

    @Test fun newestSourceVersionAndRecordExclusionRemoveOnlyAffectedSupports() {
        val first = extract("first")
        val second = extract("second")
        val old = PreferenceSnapshot(updater.update(listOf(first, second), ManualRevisions()))
        val edited = extract("first", "收藏：下次想吃热汤面", version = 2)
        for (events in listOf(listOf(first, second, edited), listOf(edited, second, first))) {
            val pref = updater.update(events, ManualRevisions(), old).single()
            assertEquals(PreferenceStatus.WEAK, pref.status)
            assertEquals(listOf("second"), pref.supportEventKeys)
            assertTrue(pref.support.all { it.source.sourceId == "second" })
        }
        val repeated = extract("share", event = "first")
        val revisions = ManualRevisions(excludedTagsByRecord = mapOf("first" to setOf("省事")))
        val after = updater.update(listOf(first, second, repeated), revisions, old).single()
        assertEquals(listOf("second"), after.supportEventKeys)
        val unrelated = ManualRevisions(excludedTagsByRecord = mapOf("first" to setOf("朋友")))
        assertEquals(2, updater.update(listOf(first, second), unrelated).single().supportEventKeys.size)
    }

    @Test fun correctionsReplaceInferencesAndSnapshotsUseLiveEvidenceOnReplay() {
        val events = listOf(extract("first"), extract("second"))
        val setting = ExplicitPreference("weekday_overtime_convenience", "convenience",
            ConditionScope(weekdays = setOf("mon"), overtime = true), null)
        val opposite = setting.copy(direction = "leisurely")
        val corrected = updater.update(events, ManualRevisions(explicit = listOf(opposite, setting)))
        val active = corrected.single()
        assertEquals(PreferenceStatus.EXPLICIT, active.status)
        assertEquals("convenience", active.direction)
        assertEquals(setOf("first", "second"), active.support.map { it.source.sourceId }.toSet())
        val replay = updater.update(emptyList(), ManualRevisions(), PreferenceSnapshot(corrected)).single()
        assertTrue(replay.support.isEmpty())
        assertTrue(replay.supportEventKeys.isEmpty())
        assertEquals(setting.scope, replay.scope)
        val replaced = updater.update(events, ManualRevisions(explicit = listOf(opposite)), PreferenceSnapshot(corrected)).single()
        assertEquals("leisurely", replaced.direction)
        assertTrue(replaced.support.isEmpty())
        val weekend = setting.copy(scope = ConditionScope(weekend = true))
        val moved = updater.update(events, ManualRevisions(explicit = listOf(weekend)), PreferenceSnapshot(corrected)).single()
        assertTrue(moved.support.isEmpty())
        assertTrue(moved.supportEventKeys.isEmpty())
    }

    @Test fun plansReportsCollectionsAndWeekendsWithOrdinaryCleanupDoNotVoteForPreference() {
        val plan = extract("plan", "明天加班，打算吃热汤面，省事。")
        val report = extract("other", "  室友说他周一加班吃盖饭省事。")
        val saved = extract("recipe", "收藏：加班热汤面，不用洗锅。")
        val ordinary = extract("ordinary", "周末吃完盖饭，洗锅花了十分钟。")
        assertEquals(ObservationType.SELF_PLAN, plan.observationType)
        assertEquals(ObservationType.REPORTED, report.observationType)
        assertEquals("室友", report.subject)
        assertEquals(ObservationType.OTHER, saved.observationType)
        assertNull(ordinary.weekday)
        assertNull(ordinary.facts.willingToCleanUp)
        assertNull(ordinary.facts.willingCookMinutes)
        assertNull(ordinary.facts.exceptionFor)
        val pref = updater.update(listOf(plan, report, saved, ordinary), ManualRevisions()).single()
        assertTrue(pref.support.isEmpty())
        assertTrue(pref.counterExamples.isEmpty())
    }

    @Test fun evidenceUsesTheMatchedOccurrenceAndUnicodeCodePoints() {
        val text = "🍜20元是原价，晚饭预算20元，我吃了18元的热汤面很开心。没加班。"
        val event = extract("source", text, version = 4)
        val budget = event.evidence.single { it.role == "budget" }
        assertEquals(text.codePointCount(0, text.lastIndexOf("20元")), budget.span.start)
        assertEquals("20元", budget.span.quoteIn(text))
        assertEquals(4L, budget.source.sourceVersion)
        assertEquals("18元的热汤面", event.evidence.single { it.role == "meal" }.span.quoteIn(text))
        assertEquals("没加班", event.evidence.single { it.role == "overtime" }.span.quoteIn(text))
        assertEquals(18, event.facts.priceCny)
        assertEquals(20, event.facts.budgetCny)
        assertEquals("热汤面", event.facts.meal)
        assertNull(event.facts.weekend)
        assertFalse(extract("negative", "周一加班，吃盖饭一点也不省事").facts.convenienceMentioned)
    }

    @Test fun invalidModelEvidenceAndAdapterFailuresKeepLocalResultsAndCancellationPropagates() {
        val source = EvidenceSource("first", 1, "first")
        val local = extractor.extract(source, dinner)
        fun using(response: () -> ExtractedEvent?): RuleEventExtractor =
            RuleEventExtractor(scene, object : InferencePort {
                override val modelId = "test"
                override fun suggestExtraction(source: EvidenceSource, text: String, scene: ScenePackage) = response()
            })
        val outside = local.evidence.first().copy(span = CodePointSpan(1, 1000))
        val other = local.evidence.first().copy(source = source.copy(sourceVersion = 2))
        for (refs in listOf(emptyList(), listOf(outside), listOf(other), listOf(local.evidence.first()))) {
            val model = local.copy(tags = listOf("周末", "未经核对"), evidence = refs)
            assertEquals(local, using { model }.extract(source, dinner))
        }
        assertEquals(local, using { throw IOException("offline") }.extract(source, dinner))
        assertThrows(CancellationException::class.java) {
            using { throw CancellationException("cancelled") }.extract(source, dinner)
        }
    }
}
