package com.pebblepile.android.algorithm

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.SourceRef
import com.pebblepile.scheduler.StageOutput
import com.pebblepile.scheduler.StaleInputException
import com.pebblepile.scheduler.TaskInput
import com.pebblepile.scheduler.TaskStage
import org.junit.Assert.*
import org.junit.Test

class DietTaskPipelineTest {
    private val gson = Gson()
    private fun json(name: String) = requireNotNull(javaClass.getResource("/$name")).openStream()
        .bufferedReader().use { gson.fromJson(it, JsonObject::class.java) }
    private val fixture = json("fixtures.json")
    private val cases = json("algorithm-baseline.json").getAsJsonObject("cases")
    private fun source(id: String, text: String, version: Long = 1) =
        CapturedSource(SourceRef(id, version), "record-$id", text, "android_share", "2026-11-19T18:00:00+08:00")
    private fun fixtureText(section: String, id: String) =
        fixture.getAsJsonArray(section).map { it.asJsonObject }.single { it["id"].asString == id }["text"].asString
    private val records = listOf("E01", "E02", "E03").map { source(it, fixtureText("records", it)) }
    private fun context(query: CapturedSource) = DietTaskContext(query, records + query, emptyMap(), null, null, "ctx-1")
    private fun input(context: DietTaskContext) = TaskInput(context.target.recordId, 1,
        context.futureCondition?.revision ?: 0, context.target.text, AlgorithmEngine().manifest().algorithmVersion)
    private fun run(context: DietTaskContext): Map<TaskStage, StageOutput> {
        val stages = mutableMapOf<TaskStage, StageOutput>()
        for (stage in TaskStage.entries - TaskStage.COMMIT) {
            // A new pipeline for each stage simulates reconstruction after a process restart.
            stages[stage] = DietTaskPipeline({ context }).runStage(stage, input(context), stages)
        }
        return stages
    }
    private inline fun <reified T> Map<TaskStage, StageOutput>.read(stage: TaskStage): T =
        gson.fromJson(getValue(stage).payload, T::class.java)

    @Test fun allFourDinnerQueriesMatchFrozenRankingAndResumeFromSerializedStages() {
        for (id in listOf("Q01", "Q02", "Q03", "Q01b")) {
            val query = source(id, fixtureText("queries", id))
            val correctionSource = source("C01", fixtureText("corrections", "C01"))
            val correction = if (id == "Q03" || id == "Q01b") {
                ConditionSetting(correctionSource.text, correctionSource.recordId, 7)
            } else null
            val context = context(query).copy(sources = records + listOfNotNull(correctionSource.takeIf { correction != null }) + query,
                futureCondition = correction)
            val stages = run(context)
            val rank = stages.read<DietRankCheckpoint>(TaskStage.RANK)
            assertEquals(id, DietTaskOutcome.RANKED, rank.outcome)
            val actual = requireNotNull(rank.result)
            val expected = cases.getAsJsonObject(id)
            assertEquals(id, expected.getAsJsonArray("order").map { it.asString }, actual.items.map { it.candidateId })
            assertEquals(id, expected.getAsJsonArray("cited").map { it.asString }.toSet(),
                actual.items.flatMap { item -> item.evidence.map { it.source.sourceId } }.toSet())
            assertEquals(0, rank.manifest.inferenceCalls)
            assertEquals(correction?.recordId, stages.getValue(TaskStage.RANK).conditionRecordId)
            val pipeline = DietTaskPipeline({ context })
            val saved = pipeline.prepareCommit(input(context), stages)
            assertNotNull(saved.decisionId)
            assertFalse(saved.needsReview)
            assertEquals(saved, pipeline.prepareCommit(input(context), run(context)))
        }
    }

    @Test fun sourceVersionsAndUnicodePositionSurviveWithoutStoringOtherRecordsRawText() {
        val first = records.first().let { source(it.ref.sourceId, "🍜" + it.text, version = 3) }
        val query = source("Q01", fixtureText("queries", "Q01"))
        val duplicate = source("dup", first.text)
        val reported = source("reported", fixtureText("regressions", "R01"))
        val unrelated = source("reminder", "明天带身份证去办证。")
        val context = context(query).copy(sources = listOf(first, records[1], duplicate, reported, unrelated, query))
        val stages = run(context)
        val update = stages.read<DietUpdateCheckpoint>(TaskStage.UPDATE).preferences.single()
        assertEquals(2, update.supportEventKeys.size)
        val citations = requireNotNull(stages.read<DietRankCheckpoint>(TaskStage.RANK).result)
            .items.flatMap { it.evidence }.filter { it.source.sourceId == first.ref.sourceId }
        assertTrue(citations.any { it.source.sourceVersion == 3L && it.span.quoteIn(first.text) == "加班到八点半" })
        for (output in stages.values) {
            assertFalse(output.payload.contains(first.text))
            assertFalse(output.payload.contains(unrelated.text))
            assertTrue(output.inputSources.orEmpty().none { it.sourceId == unrelated.ref.sourceId })
        }
        val excluded = context.copy(excludedTagsByRecord = mapOf(first.recordId to setOf("加班")))
        val revised = run(excluded).read<DietUpdateCheckpoint>(TaskStage.UPDATE).preferences.single()
        assertEquals(PreferenceStatus.WEAK, revised.status)
        assertEquals(1, revised.supportEventKeys.size)
    }

    @Test fun ordinaryCapturesAndReportedQuestionsDoNotProduceDinnerDecisions() {
        val direct = context(source("query", "今天周四加班，不知道晚饭吃什么，有什么推荐？"))
        assertEquals(DietTaskOutcome.RANKED, run(direct).read<DietRankCheckpoint>(TaskStage.RANK).outcome)
        for (text in listOf("整理堆栈笔记。", "昨晚不知道晚饭吃什么，最后吃了盖饭。", records.first().text, "室友说：今晚加班，晚饭吃什么？")) {
            val context = context(source("save", text))
            val stages = run(context)
            val rank = stages.read<DietRankCheckpoint>(TaskStage.RANK)
            assertEquals(DietTaskOutcome.ORGANIZED, rank.outcome)
            assertNull(rank.result)
            val completed = DietTaskPipeline({ context }).prepareCommit(input(context), stages)
            assertNull(completed.decisionId)
            assertFalse(completed.needsReview)
        }
    }

    @Test fun aQuestionRetainsMissingConditionsNoCandidatesAndUnknownCorrectionAsDistinctResults() {
        val missing = context(source("query", "晚饭吃什么？"))
        val cheap = context(source("query", "今晚加班，预算一元，晚饭吃什么？"))
        val unrecognized = context(source("query", fixtureText("queries", "Q01"))).copy(
            futureCondition = ConditionSetting("以后只在出差时优先给我推荐省事的晚饭。", "record-E01", 8))
        for ((context, expected) in listOf(missing to DietTaskOutcome.MISSING_CONDITIONS,
            cheap to DietTaskOutcome.NO_CANDIDATES, unrecognized to DietTaskOutcome.UNRECOGNIZED_CORRECTION)) {
            val stages = run(context)
            val rank = stages.read<DietRankCheckpoint>(TaskStage.RANK)
            assertEquals(expected, rank.outcome)
            val result = DietTaskPipeline({ context }).prepareCommit(input(context), stages)
            assertTrue(result.needsReview)
            assertNull(result.decisionId)
        }
        val resolved = missing.copy(currentGoalCondition = "这次周日不加班，预算二十元，只有十五分钟做饭。")
        val rank = run(resolved).read<DietRankCheckpoint>(TaskStage.RANK)
        assertEquals(QueryConstraint("sun", false, true, 20, 15), rank.constraint)
        assertTrue(requireNotNull(rank.result).filteredOut.contains("tomato-egg-noodle"))
    }

    @Test fun explicitTimeLimitFiltersTheMenuAndUnrepresentableTimeNeedsClarification() {
        val halfHour = context(source("query", "今天周日不加班，最多半小时做饭，晚饭吃什么？"))
        val ranked = run(halfHour).getValue(TaskStage.RANK).dietRankCheckpoint()
        assertEquals(30, ranked.constraint?.cookMinutes)
        assertTrue(requireNotNull(ranked.result).filteredOut.contains("tomato-egg-noodle"))
        val ambiguous = context(source("query", "今天周日不加班，最多十来分钟做饭，晚饭吃什么？"))
        assertEquals(DietTaskOutcome.MISSING_CONDITIONS, run(ambiguous).getValue(TaskStage.RANK).dietRankCheckpoint().outcome)
    }

    @Test fun explicitSettingWithoutAMatchingCaptureCarriesRevisionAndNeverBorrowsAnotherQuote() {
        val context = context(source("Q01b", fixtureText("queries", "Q01b"))).copy(futureCondition =
            ConditionSetting(fixtureText("corrections", "C01"), records.first().recordId, 9))
        val output = run(context).getValue(TaskStage.RANK)
        val rank = output.dietRankCheckpoint()
        assertEquals(9L, rank.settingRevision)
        assertEquals(records.first().recordId, output.conditionRecordId)
        val result = requireNotNull(rank.result)
        assertEquals(listOf("pref-explicit-weekday_overtime_convenience"), result.appliedPreferences)
        assertTrue(result.items.flatMap { it.evidence }.none { it.role == "explicit_rule" })
    }

    @Test fun changedSceneCannotResumeOrCommitStagesCreatedByTheOlderManifest() {
        val context = context(source("Q01", fixtureText("queries", "Q01")))
        val input = input(context)
        val stages = run(context)
        val updated = AlgorithmEngine(scene = DefaultDietScenePackage.scenePackage.copy(version = "next-scene"))
        val pipe = DietTaskPipeline({ context }, updated)
        assertThrows(StaleInputException::class.java) {
            pipe.runStage(TaskStage.UPDATE, input, mapOf(TaskStage.EXTRACT to stages.getValue(TaskStage.EXTRACT)))
        }
        assertThrows(StaleInputException::class.java) { pipe.prepareCommit(input, stages) }
    }

    @Test fun changedHistoryRejectsAResumedCheckpointBeforeReusingItsReferences() {
        var context = context(source("query", fixtureText("queries", "Q01")))
        val pipe = DietTaskPipeline({ context })
        val input = input(context)
        val extracted = pipe.runStage(TaskStage.EXTRACT, input, emptyMap())
        context = context.copy(contextKey = "ctx-2")
        assertThrows(StaleInputException::class.java) {
            pipe.runStage(TaskStage.UPDATE, input, mapOf(TaskStage.EXTRACT to extracted))
        }
    }
}
