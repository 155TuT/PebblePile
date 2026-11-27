package com.pebblepile.android.algorithm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
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
    private val expected = loadJson("/expected.json")
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
        val source = EvidenceSource(id, sourceVersion = 1, recordId = id, eventId = id)
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
        assertTrue(event.tags.containsAll(listOf("饮食", "加班")))
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
        assertTrue(event.tags.containsAll(listOf("饮食", "加班")))
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
        val duplicate = engine.extract(
            EvidenceSource("D01", 1, "D01", eventId = "E01"), record("E01")["text"].asString,
        )
        val events = listOf(first, extract(engine, "E02"), extract(engine, "E03"), duplicate)
        val preferences = engine.update(events, ManualRevisions())
        val convenience = preferences.single { it.preferenceKey == "weekday_overtime_convenience" }
        assertEquals(2, convenience.supportEventKeys.size)
        assertEquals(setOf("E01", "E02", "D01"), convenience.support.map { it.source.sourceId }.toSet())
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
        val revisions = ManualRevisions(excludedTagsByRecord = mapOf("E01" to setOf("省事")))
        val preferences = engine.update(events, revisions)
        val convenience = preferences.single { it.preferenceKey == "weekday_overtime_convenience" }
        assertEquals(listOf("E02"), convenience.supportEventKeys)
        val replay = engine.update(dinnerEvents(engine), revisions)
        assertEquals(preferences, replay)
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
        val scope = requireNotNull(DietVocabulary.parseCorrectionScope(text))
        assertEquals(setOf("mon", "tue", "wed", "thu", "fri"), scope.weekdays)
        assertEquals(true, scope.overtime)
        assertEquals("convenience", DietVocabulary.correctionDirection(text))
    }

    private fun explicitC01(): ManualRevisions {
        val text = correction("C01")["text"].asString
        val setting = ExplicitPreference(
            preferenceKey = "weekday_overtime_convenience",
            direction = requireNotNull(DietVocabulary.correctionDirection(text)),
            scope = requireNotNull(DietVocabulary.parseCorrectionScope(text)),
            evidence = EvidenceRef(EvidenceSource("C01", 1, "C01"), CodePointSpan(0, text.codePointCount(0, text.length)), "explicit_rule"),
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
        val feedback = fixtures.getAsJsonArray("outcomes").single().asJsonObject
        val contract = expected.getAsJsonObject("conditions").getAsJsonObject("O01")
        val targetId = feedback["target"].asString
        assertEquals(contract["target"].asString, targetId)
        val eventsBefore = dinnerEvents(engine)
        val original = engine.dinnerQuery(constraintOf(query(targetId)), eventsBefore, ManualRevisions())
        val recommended = engine.scene.menu.single { it.id == original.result.items.first().candidateId }.name
        val outcome = engine.extract(EvidenceSource("O01", 1, targetId), feedback["text"].asString)
        assertEquals("盖饭", recommended)
        assertEquals(contract["actualMeal"].asString, outcome.facts.meal)
        assertTrue(outcome.evidence.isNotEmpty())
        assertTrue(outcome.evidence.all { it.source.recordId == targetId })

        val events = dinnerEvents(engine) + outcome
        val preferences = engine.update(events, ManualRevisions())
        val convenience = preferences.single { it.preferenceKey == "weekday_overtime_convenience" }
        assertEquals(2, convenience.supportEventKeys.size)
    }

    @Test fun rankingIsStableAcrossThreeRuns() {
        val contract = expected.getAsJsonObject("ranking")
        assertEquals("frozen", baseline["status"].asString)
        assertEquals(baseline["baselineVersion"], contract["baselineVersion"])
        assertEquals(fixtures["datasetId"], baseline["datasetId"])
        assertEquals(fixtures["datasetVersion"], baseline["datasetVersion"])
        val cases = baseline.getAsJsonObject("cases")
        assertEquals(cases.keySet(), contract.strings("cases").toSet())
        val runs = contract["runs"].asInt
        assertEquals(3, runs)
        assertEquals(runs, baseline["runs"].asInt)
        assertMenuMatchesBaseline()
        println("DINNER_ENV ${System.getProperty("os.name")} ${System.getProperty("os.arch")} | ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        for ((id, value) in cases.entrySet()) {
            val q = query(id)
            val constraint = constraintOf(q)
            val revisions = if (q["afterCorrection"].isJsonNull) ManualRevisions() else explicitC01()
            val condition = expected.getAsJsonObject("conditions").getAsJsonObject(id)
            val target = value.asJsonObject
            assertEquals(condition["scopeMatch"], target["scopeMatch"])
            repeat(runs) { run ->
                val engine = AlgorithmEngine()
                val begin = System.nanoTime()
                val events = dinnerEvents(engine)
                val extracted = System.nanoTime()
                val preferences = engine.update(events, revisions)
                val updated = System.nanoTime()
                val ranked = engine.rank(constraint, preferences, history = events)
                val finished = System.nanoTime()
                val stages = listOf(extracted - begin, updated - extracted, finished - updated)
                val millis = stages.joinToString(" ") { String.format(Locale.ROOT, "%.6f", it / 1_000_000.0) }
                println("DINNER_REPLAY $id ${run + 1} $millis ${engine.inferenceCalls}")
                val queryRun = engine.dinnerQuery(constraint, events, revisions)
                assertEquals("公开查询和分段回放的结果", ranked, queryRun.result)
                assertEquals(0, engine.inferenceCalls)
                assertEquals(baseline["inferenceCallsPerQuery"].asInt, queryRun.inferenceCalls)
                assertManifest(engine, queryRun.manifest)
                assertRanking(id, engine, constraint, target, condition, ranked)
            }
        }
    }

    private fun assertMenuMatchesBaseline() {
        val menu = AlgorithmEngine().scene.menu
        val expectedMenu = baseline.getAsJsonArray("menu").associate { row ->
            row.asJsonObject.let { it["id"].asString to it }
        }
        assertEquals(expectedMenu.keys, menu.map { it.id }.toSet())
        for (item in menu) {
            val row = expectedMenu.getValue(item.id)
            assertEquals(row["name"].asString, item.name)
            assertEquals(row["priceCny"].asInt, item.priceCny)
            assertEquals(row["prepMinutes"].asInt, item.prepMinutes)
            assertEquals(row["cleanupNeeded"].asBoolean, item.cleanupNeeded)
            assertEquals(row["convenienceScore"].asInt, item.convenienceScore)
            assertEquals(row["matchedMeal"]?.asString, item.matchedMeal)
        }
    }

    private fun assertRanking(
        id: String, engine: AlgorithmEngine, constraint: QueryConstraint,
        target: JsonObject, condition: JsonObject, ranked: RankResult,
    ) {
        assertEquals(target.strings("order"), ranked.items.map { it.candidateId })
        assertEquals(target.strings("filteredOut").toSet(), ranked.filteredOut.toSet())
        assertEquals(target.strings("appliedPreferences").toSet(), ranked.appliedPreferences.toSet())
        val explicitMatched = ranked.appliedPreferences.any { it.startsWith("pref-explicit") }
        if (!condition["scopeMatch"].isJsonNull) assertEquals(condition["scopeMatch"].asBoolean, explicitMatched)
        val allowed = engine.scene.menu.filter { item ->
            item.priceCny <= condition["budgetMaxCny"].asInt &&
                (constraint.cookMinutes?.let { item.prepMinutes <= it } ?: true)
        }
        assertEquals(allowed.map { it.id }.toSet(), ranked.items.map { it.candidateId }.toSet())
        val refs = ranked.items.flatMap { it.evidence }.distinct()
        val cited = refs.map { it.source.sourceId }.toSet()
        assertEquals("[$id] 引用来源", target.strings("cited").toSet(), cited)
        assertTrue(cited.containsAll(condition.strings("mustCite")))
        assertTrue((cited intersect condition.strings("excludedFromPreferenceEvidence").toSet()).isEmpty())
        for (ref in refs) checkSourceRef(ref)
    }

    private fun assertManifest(engine: AlgorithmEngine, actual: AlgorithmManifest) {
        val target = baseline.getAsJsonObject("manifest")
        val contract = expected.getAsJsonObject("ranking")
        assertEquals("${fixtures["datasetId"].asString}@${fixtures["datasetVersion"].asString}", actual.datasetVersion)
        assertEquals(contract["algorithmVersion"], target["algorithmVersion"])
        assertEquals(contract["scenePackVersion"], target["scenePackVersion"])
        assertEquals(target["algorithmVersion"].asString, actual.algorithmVersion)
        assertEquals(target["scenePackVersion"].asString, actual.scenePackVersion)
        assertEquals(target["extractor"].asString, actual.extractor)
        assertEquals(target["updater"].asString, actual.updater)
        assertEquals(target["ranker"].asString, actual.ranker)
        assertEquals(target["inferenceModel"].asString, actual.inferenceModel)
        assertEquals(target["minIndependentSupports"].asInt, engine.scene.minIndependentSupports)
        assertEquals(engine.scene.minIndependentSupports, actual.minIndependentSupports)
    }

    private fun checkSourceRef(ref: EvidenceRef) {
        val id = ref.source.sourceId
        assertEquals(id, ref.source.recordId)
        assertEquals(1L, ref.source.sourceVersion)
        if (id == "C01") {
            val text = correction(id)["text"].asString
            assertEquals(text, ref.span.quoteIn(text))
            return
        }
        val row = record(id)
        val span = row.getAsJsonArray("spans").map { it.asJsonObject }.single { it["role"].asString == ref.role }
        assertEquals(span["start"].asInt, ref.span.start)
        assertEquals(span["end"].asInt, ref.span.end)
        assertEquals(span["text"].asString, ref.span.quoteIn(row["text"].asString))
    }

    private fun JsonObject.strings(key: String): List<String> =
        getAsJsonArray(key)?.map { it.asString }.orEmpty()

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
