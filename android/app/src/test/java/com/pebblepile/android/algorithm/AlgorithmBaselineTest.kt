package com.pebblepile.android.algorithm

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #9 算法回归基线。输入读取测试 classpath 上冻结的 `fixtures.json`，
 * 候选顺序对齐 `algorithm-baseline.json`；同一输入跑三次，顺序必须稳定。
 */
class AlgorithmBaselineTest {

    private val fixtures = loadJson("/fixtures.json")
    private val baseline = loadJson("/algorithm-baseline.json")

    private fun record(id: String) = fixtures.getAsJsonArray("records")
        .map { it.asJsonObject }.single { it["id"].asString == id }

    private fun query(id: String) = fixtures.getAsJsonArray("queries")
        .map { it.asJsonObject }.single { it["id"].asString == id }

    private fun correction(id: String) = fixtures.getAsJsonArray("corrections")
        .map { it.asJsonObject }.single { it["id"].asString == id }

    private fun regression(id: String) = fixtures.getAsJsonArray("regressions")
        .map { it.asJsonObject }.single { it["id"].asString == id }

    private fun extract(engine: AlgorithmEngine, id: String): ExtractedEvent {
        val source = EvidenceSource(id, sourceVersion = 1, recordId = id)
        return engine.extract(source, record(id)["text"].asString)
    }

    private fun dinnerEvents(engine: AlgorithmEngine) =
        listOf(extract(engine, "E01"), extract(engine, "E02"), extract(engine, "E03"))

    private fun constraintOf(q: com.google.gson.JsonObject): QueryConstraint {
        val weekend = q["weekday"].asString in setOf("sat", "sun")
        return QueryConstraint(
            weekday = q["weekday"].asString,
            overtime = q["overtime"].asBoolean,
            weekend = weekend,
            budgetCny = q["budgetCny"]?.takeIf { !it.isJsonNull }?.asInt,
            cookMinutes = q["cookMinutes"]?.takeIf { !it.isJsonNull }?.asInt,
        )
    }

    // G01 — E01 事实与原话区间
    @Test fun extractE01FactsAndSpansMatchFixtures() {
        val engine = AlgorithmEngine()
        val event = extract(engine, "E01")
        val facts = event.facts
        assertEquals(true, facts.overtime)
        assertEquals(false, facts.weekend)
        assertEquals(20, facts.budgetCny)
        assertEquals(18, facts.priceCny)
        assertEquals("热汤面", facts.meal)
        assertEquals(10, facts.durationMinutes)
        assertEquals(false, facts.cleanupNeeded)
        assertTrue(facts.convenienceMentioned)
        assertNull(facts.prepNeeded)
        assertSpansMatchFixtures("E01", event)
    }

    // G02 — E02 事实与原话区间
    @Test fun extractE02FactsAndSpansMatchFixtures() {
        val engine = AlgorithmEngine()
        val event = extract(engine, "E02")
        val facts = event.facts
        assertEquals(true, facts.overtime)
        assertEquals(false, facts.weekend)
        assertEquals(20, facts.budgetCny)
        assertEquals(16, facts.priceCny)
        assertEquals("盖饭", facts.meal)
        assertEquals(false, facts.prepNeeded)
        assertEquals(false, facts.cleanupNeeded)
        assertTrue(facts.convenienceMentioned)
        assertEquals("省事让我很满意", facts.satisfaction)
        assertSpansMatchFixtures("E02", event)
    }

    // G03 — E03 周末例外，不计入加班省事的支持次数
    @Test fun extractE03WeekendException() {
        val engine = AlgorithmEngine()
        val event = extract(engine, "E03")
        val facts = event.facts
        assertEquals(false, facts.overtime)
        assertEquals(true, facts.weekend)
        assertEquals("番茄鸡蛋面", facts.meal)
        assertEquals(40, facts.willingCookMinutes)
        assertEquals(true, facts.willingToCleanUp)
        assertEquals("weekday_overtime_convenience", facts.exceptionFor)
        assertEquals("放松", facts.satisfaction)
        assertSpansMatchFixtures("E03", event)
    }

    // 计数：两次独立加班经历 + 一次周末例外
    @Test fun updateCountsIndependentOvertimeDinnersAndWeekendException() {
        val engine = AlgorithmEngine()
        val events = dinnerEvents(engine)
        val preferences = engine.update(events, ManualRevisions())
        val convenience = preferences.single { it.preferenceKey == "weekday_overtime_convenience" }
        assertEquals(2, convenience.supportEventKeys.size)
        assertEquals(PreferenceStatus.INFERRED, convenience.status)
        assertEquals(1, convenience.counterExamples.size)
    }

    // G09 — D01 重复分享同一经历，支持次数不增加
    @Test fun duplicateShareDoesNotIncreaseSupportCount() {
        val engine = AlgorithmEngine()
        val first = extract(engine, "E01")
        val duplicate = first.copy(
            evidence = first.evidence.map { it.copy(source = EvidenceSource("D01", 1, "D01")) }
        )
        val events = listOf(first, extract(engine, "E02"), extract(engine, "E03"), duplicate)
        val preferences = engine.update(events, ManualRevisions())
        val convenience = preferences.single { it.preferenceKey == "weekday_overtime_convenience" }
        assertEquals(2, convenience.supportEventKeys.size)
        assertEquals(2, convenience.support.distinctBy { it.source.sourceId }.size)
    }

    // G10 — R01 转述不计入本人偏好
    @Test fun reportedEventIsNotCountedForSelf() {
        val engine = AlgorithmEngine()
        val reported = engine.extract(
            EvidenceSource("R01", 1, "R01"),
            regression("R01")["text"].asString,
        )
        assertEquals(ObservationType.REPORTED, reported.observationType)
        assertEquals("室友", reported.subject)

        val events = dinnerEvents(engine) + reported
        val preferences = engine.update(events, ManualRevisions())
        val convenience = preferences.single { it.preferenceKey == "weekday_overtime_convenience" }
        assertEquals(2, convenience.supportEventKeys.size)
    }

    // G11 — T01 人工排除标签后，该事件不再参与支持，重跑不复现
    @Test fun excludedTagStaysOutOfSupport() {
        val engine = AlgorithmEngine()
        val events = dinnerEvents(engine)
        val revisions = ManualRevisions(excludedTags = setOf("加班"))
        val preferences = engine.update(events, revisions)
        val convenience = preferences.single { it.preferenceKey == "weekday_overtime_convenience" }
        assertTrue(convenience.supportEventKeys.isEmpty())
        assertEquals(PreferenceStatus.WEAK, convenience.status)
    }

    // G04 — Q01 预算硬筛、引用 E01/E02、排除 E03，顺序对齐基线
    @Test fun rankQ01FiltersBudgetAndCitesOvertimeEvents() {
        val engine = AlgorithmEngine()
        val result = engine.dinnerQuery(constraintOf(query("Q01")), dinnerEvents(engine), ManualRevisions()).result

        assertTrue(result.filteredOut.contains("hotpot"))
        val cited = result.items.flatMap { it.evidence }.map { it.source.sourceId }.toSet()
        assertTrue(cited.containsAll(listOf("E01", "E02")))
        assertFalse(cited.contains("E03"))
        assertOrderMatchesBaseline("Q01", result.items.map { it.candidateId })
    }

    // G05 — Q02 引用周末例外 E03，并说明与加班时不同
    @Test fun rankQ02CitesWeekendException() {
        val engine = AlgorithmEngine()
        val result = engine.dinnerQuery(constraintOf(query("Q02")), dinnerEvents(engine), ManualRevisions()).result

        val cited = result.items.flatMap { it.evidence }.map { it.source.sourceId }.toSet()
        assertTrue(cited.contains("E03"))
        assertTrue(result.items.any { it.reason.contains("周末") })
        assertOrderMatchesBaseline("Q02", result.items.map { it.candidateId })
    }

    // G06 — C01 解析为「工作日且加班」的省事偏好
    @Test fun correctionC01ScopeIsWeekdayAndOvertime() {
        val text = correction("C01")["text"].asString
        val scope = DietVocabulary.parseCorrectionScope(text)
        assertEquals(setOf("mon", "tue", "wed", "thu", "fri"), scope.weekdays)
        assertEquals(true, scope.overtime)
        assertEquals("convenience", DietVocabulary.correctionDirection(text))
    }

    private fun explicitC01(): ManualRevisions {
        val text = correction("C01")["text"].asString
        val setting = ExplicitPreference(
            preferenceKey = "weekday_overtime_convenience",
            direction = DietVocabulary.correctionDirection(text),
            scope = DietVocabulary.parseCorrectionScope(text),
            evidence = null,
        )
        return ManualRevisions(explicit = listOf(setting))
    }

    // G07 — Q03 周六加班：明确规则未命中，不得按省事规则加权
    @Test fun rankQ03AfterCorrectionDoesNotMatchExplicitRule() {
        val engine = AlgorithmEngine()
        val result = engine.dinnerQuery(
            constraintOf(query("Q03")), dinnerEvents(engine), explicitC01(),
        ).result

        assertTrue(result.unmatchedPreferences.any { it.startsWith("pref-explicit") })
        assertTrue(result.appliedPreferences.none { it.startsWith("pref-explicit") })
        assertOrderMatchesBaseline("Q03", result.items.map { it.candidateId })
    }

    // G08 — Q01b 周四加班：命中明确规则，优先省事，引用 E01/E02
    @Test fun rankQ01bAfterCorrectionMatchesExplicitRule() {
        val engine = AlgorithmEngine()
        val result = engine.dinnerQuery(
            constraintOf(query("Q01b")), dinnerEvents(engine), explicitC01(),
        ).result

        assertTrue(result.appliedPreferences.any { it.startsWith("pref-explicit") })
        val cited = result.items.flatMap { it.evidence }.map { it.source.sourceId }.toSet()
        assertTrue(cited.containsAll(listOf("E01", "E02")))
        assertOrderMatchesBaseline("Q01b", result.items.map { it.candidateId })
    }

    // G12 — O01 反馈回到原事项，抽取实际菜名，不新增偏好计数
    @Test fun outcomeRecordsActualMealWithoutChangingPreferenceCount() {
        val engine = AlgorithmEngine()
        val outcomeText = fixtures.getAsJsonArray("outcomes")
            .map { it.asJsonObject }.single { it["id"].asString == "O01" }["text"].asString
        val outcome = engine.extract(EvidenceSource("O01", 1, "O01"), outcomeText)
        assertEquals("热汤面", outcome.facts.meal)

        val events = dinnerEvents(engine) + outcome
        val preferences = engine.update(events, ManualRevisions())
        val convenience = preferences.single { it.preferenceKey == "weekday_overtime_convenience" }
        assertEquals(2, convenience.supportEventKeys.size)
    }

    // 稳定性：同一输入跑三次，候选顺序不变
    @Test fun rankingIsStableAcrossThreeRuns() {
        val engine = AlgorithmEngine()
        val events = dinnerEvents(engine)
        val orders = (1..3).map {
            engine.dinnerQuery(constraintOf(query("Q01")), events, ManualRevisions()).result
                .items.map { it.candidateId }
        }
        assertEquals(orders[0], orders[1])
        assertEquals(orders[2], orders[0])
    }

    private fun assertSpansMatchFixtures(recordId: String, event: ExtractedEvent) {
        val spans = record(recordId).getAsJsonArray("spans").map { it.asJsonObject }
        for (span in spans) {
            val role = span["role"].asString
            val expectedText = span["text"].asString
            val expectedStart = span["start"].asInt
            val expectedEnd = span["end"].asInt
            val evidence = event.evidence.singleOrNull { it.role == role }
            assertTrue("缺少角色 $role 的证据", evidence != null)
            assertEquals(expectedStart, evidence!!.span.start)
            assertEquals(expectedEnd, evidence.span.end)
            assertEquals(expectedText, evidence.span.quoteIn(event.rawText))
        }
    }

    private fun assertOrderMatchesBaseline(caseId: String, actual: List<String>) {
        val expected = baseline.getAsJsonObject("cases")
            .getAsJsonObject(caseId).getAsJsonArray("order")
            .map { it.asString }
        assertEquals(expected, actual)
    }

    private companion object {
        fun loadJson(path: String) = requireNotNull(
            AlgorithmBaselineTest::class.java.getResourceAsStream(path)
        ) { "缺少测试资源 $path" }.bufferedReader(Charsets.UTF_8).use {
            JsonParser.parseReader(it).asJsonObject
        }
    }
}
