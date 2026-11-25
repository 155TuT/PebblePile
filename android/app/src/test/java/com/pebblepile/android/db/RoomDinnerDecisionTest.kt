package com.pebblepile.android.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pebblepile.android.algorithm.CodePointSpan
import com.pebblepile.android.algorithm.DietTaskPipeline
import com.pebblepile.android.algorithm.dietRankCheckpoint
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.DeleteRequest
import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeleteTargetType
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.service.CaptureRequest
import com.pebblepile.android.service.LocalCoreApi
import com.pebblepile.scheduler.PersistentTaskCoordinator
import com.pebblepile.scheduler.StageOutput
import com.pebblepile.scheduler.TaskJob
import com.pebblepile.scheduler.TaskRunOutcome
import com.pebblepile.scheduler.TaskStage
import com.pebblepile.scheduler.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomDinnerDecisionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = Files.createTempFile("dinner-decisions-", ".db").toFile()
    private var database = openDatabase()
    private var store = RoomTaskStore(database)
    private var api = LocalCoreApi(RoomCoreRepository(database))
    private val monday = "🍜今天周一加班到八点半，晚饭预算二十元。我吃了十八元的热汤面，十分钟就吃上了，不用洗锅。"
    private val wednesday = "今天周三又加班了，晚饭还是想控制在二十元以内。我吃了十六元的盖饭，不用准备食材，也不用洗锅，省事让我很满意。"
    private val query = "今天周四加班，预算二十元，晚饭吃什么？请说明依据。"

    private fun openDatabase(): PebbleDatabase =
        Room.databaseBuilder(context, PebbleDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()

    @After fun close() {
        database.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    private fun save(text: String, operation: String, recordId: String? = null, revision: Long? = null) =
        api.recordExperience(CaptureRequest(text, "app_home_dialog", operation, recordId, revision))
            .also { assertTrue("${it.code}: ${it.message}", it.success) }

    private fun run(receipt: ApiReceipt): TaskRunOutcome =
        PersistentTaskCoordinator(store, store, DietTaskPipeline(store::loadDietContext))
            .runJob(requireNotNull(receipt.jobId))

    private fun detail(receipt: ApiReceipt) = requireNotNull(api.getRecordDetail(requireNotNull(receipt.recordId)))

    @Test fun publishesOrderedSuggestionsAndKeepsFeedbackWithItsOriginalDecisionAfterReopen() = runBlocking {
        val history = save(monday, "monday")
        save(wednesday, "wednesday")
        val first = save(query, "query")
        assertEquals(TaskRunOutcome.COMPLETED, run(first))
        assertEquals(TaskRunOutcome.COMPLETED, run(first))
        val firstRecord = detail(first).record
        val decision = requireNotNull(firstRecord.currentDinnerDecision)
        val job = requireNotNull(store.get(requireNotNull(first.jobId)))
        val rank = job.checkpoints.getValue(TaskStage.RANK)
        assertEquals(job.decisionId, decision.id)
        assertEquals(rank.contextKey, decision.snapshotId)
        assertEquals(rank.dietRankCheckpoint().manifest, decision.manifest)
        val engineResult = requireNotNull(rank.dietRankCheckpoint().result)
        val basis = requireNotNull(decision.rankingBasis)
        assertEquals(engineResult.appliedPreferences, basis.appliedPreferences)
        assertEquals(engineResult.filteredOut, basis.filteredOut)
        assertTrue(basis.appliedPreferences.isNotEmpty())
        assertTrue(basis.filteredOut.isNotEmpty())
        assertEquals("工作日加班时，优先省事", basis.preferenceDescriptions?.get(basis.appliedPreferences.single()))
        assertEquals("麻辣烫", basis.filteredCandidateNames?.get("hotpot"))
        assertEquals(decision.suggestions, firstRecord.suggestions)
        assertEquals("盖饭", firstRecord.suggestions.first().title)
        val evidence = firstRecord.suggestions.flatMap { it.evidence }.first {
            it.sourceRef == history.source && it.quote == "加班到八点半"
        }
        val original = requireNotNull(api.getRecordDetail(evidence.sourceRecordId, evidence.sourceRef)).sources.single()
        assertEquals("overtime", evidence.role)
        assertEquals(evidence.quote, CodePointSpan(evidence.start, evidence.end).quoteIn(original.text))

        val second = save("今天周六加班，预算二十元，晚饭吃什么？", "query-again", firstRecord.id, firstRecord.revision)
        assertEquals(TaskRunOutcome.COMPLETED, run(second))
        val latest = detail(first).record
        val latestDecision = requireNotNull(latest.currentDinnerDecision)
        assertTrue(requireNotNull(latestDecision.rankingBasis).unmatchedPreferences.containsAll(basis.appliedPreferences))
        assertEquals("轻食便当", latest.suggestions.first().title)
        val feedback = api.recordOutcome(latest.id, latest.revision, "盖饭", "取餐快", "feedback", decision.id)
        assertTrue(feedback.success)
        assertEquals(decision.id, feedback.decisionId)
        assertEquals(feedback, api.recordOutcome(latest.id, latest.revision, "盖饭", "取餐快", "feedback", decision.id))
        assertFalse(api.recordOutcome(latest.id, latest.revision, "盖饭", "取餐快", "feedback", latestDecision.id).success)

        database.close()
        database = openDatabase()
        api = LocalCoreApi(RoomCoreRepository(database))
        assertEquals(decision, api.getDinnerDecision(latest.id, decision.id))
        assertEquals(latestDecision, detail(first).record.currentDinnerDecision)
        assertEquals(decision.id, detail(first).record.outcomes.single().decisionId)
        assertEquals(first, save(query, "query"))
    }

    @Test fun independentDinnerQueriesCanPublishFromTheSameInputSnapshot() {
        save(monday, "monday")
        save(wednesday, "wednesday")
        val first = save(query, "first-query")
        val second = save("今天周六加班，预算二十元，晚饭吃什么？", "second-query")
        val pipeline = DietTaskPipeline(store::loadDietContext)
        val pending = listOf(first, second).map { advanceToCommit(it, pipeline) }
        for (job in pending) {
            val result = pipeline.prepareCommit(job.input(), job.checkpoints)
            assertEquals(TaskStatus.COMPLETED, store.finishIfCurrent(job.jobId, job.leaseToken, result, store, 12).status)
        }
        val firstDecision = requireNotNull(detail(first).record.currentDinnerDecision)
        val secondDecision = requireNotNull(detail(second).record.currentDinnerDecision)
        assertEquals("盖饭", firstDecision.suggestions.first().title)
        assertEquals("轻食便当", secondDecision.suggestions.first().title)
        assertEquals(first.revision!! + 1, detail(first).record.revision)
    }

    @Test fun changedPreferenceRejectsLateCommitAndNewQueryUsesCurrentRevision() = runBlocking {
        val owner = save(monday, "monday")
        save(wednesday, "wednesday")
        val first = save(query, "query")
        val pipeline = DietTaskPipeline(store::loadDietContext)
        val ranked = advanceToCommit(first, pipeline)
        val result = pipeline.prepareCommit(ranked.input(), ranked.checkpoints)
        assertTrue(api.reviseMemory(requireNotNull(owner.recordId), detail(owner).record.revision,
            "以后只在周末不加班时，优先给我推荐省事的晚饭。", CorrectionScope.FUTURE, "correct").success)
        val stale = store.finishIfCurrent(ranked.jobId, ranked.leaseToken, result, store, 12)
        assertEquals(TaskStatus.STALE, stale.status)
        assertNull(stale.decisionId)
        assertTrue(detail(first).record.dinnerDecisions.orEmpty().isEmpty())

        val current = save(query, "query-current")
        assertEquals(TaskRunOutcome.COMPLETED, run(current))
        val detail = detail(current)
        val decision = requireNotNull(detail.record.currentDinnerDecision)
        assertEquals(detail.futureCondition?.revision, decision.preferenceRevision)
        assertTrue(decision.suggestions.none { "优先省事" in it.reason })
    }

    @Test fun rejectsInvalidCodePointEvidenceBeforePublishing() {
        save(monday, "monday")
        save(wednesday, "wednesday")
        val receipt = save(query, "query")
        val pipeline = DietTaskPipeline(store::loadDietContext)
        val ranked = advanceToCommit(receipt, pipeline) { output ->
            val checkpoint = output.dietRankCheckpoint()
            val result = requireNotNull(checkpoint.result)
            val first = result.items.first()
            val broken = first.evidence.first().copy(span = CodePointSpan(0, 100_000))
            val invalid = first.copy(evidence = listOf(broken))
            output.copy(payload = DbJson.encode(checkpoint.copy(result = result.copy(items = listOf(invalid) + result.items.drop(1)))))
        }
        val result = pipeline.prepareCommit(ranked.input(), ranked.checkpoints)
        val stale = store.finishIfCurrent(ranked.jobId, ranked.leaseToken, result, store, 12)
        assertEquals(TaskStatus.STALE, stale.status)
        assertEquals(TaskState.STALE, api.getTask(ranked.jobId)?.state)
        assertTrue(detail(receipt).record.suggestions.isEmpty())
    }

    @Test fun deletingASourceRedactsPublishedDecisionAndPreservesOutcomeIdentity() = runBlocking {
        val history = save(monday, "monday")
        save(wednesday, "wednesday")
        val receipt = save(query, "query")
        assertEquals(TaskRunOutcome.COMPLETED, run(receipt))
        val record = detail(receipt).record
        val decision = requireNotNull(record.currentDinnerDecision)
        assertTrue(api.recordOutcome(record.id, record.revision, "热汤面", "省时", "feedback", decision.id).success)
        val target = DeleteTarget(DeleteTargetType.RECORD, requireNotNull(history.recordId))
        val preview = requireNotNull(api.previewDeleteMemory(target).deletePreview)
        assertTrue(api.deleteMemory(DeleteRequest(target, preview.previewId, preview.baseRevision, "delete")).success)
        val redacted = requireNotNull(api.getDinnerDecision(record.id, decision.id))
        assertTrue(redacted.redacted)
        assertNull(redacted.rankingBasis)
        assertTrue(redacted.suggestions.isEmpty())
        assertTrue(detail(receipt).record.suggestions.isEmpty())
        assertEquals(decision.id, detail(receipt).record.outcomes.single().decisionId)
        assertEquals(TaskState.STALE, api.getTask(requireNotNull(receipt.jobId))?.state)
    }

    @Test fun correctionsRetireCurrentSuggestionsAndKeepHistoricalDecisionsForFeedback() = runBlocking {
        val owner = save(monday, "monday")
        save(wednesday, "wednesday")
        val receipt = save(query, "query")
        assertEquals(TaskRunOutcome.COMPLETED, run(receipt))
        val initial = detail(receipt).record
        val original = requireNotNull(initial.currentDinnerDecision)
        val conflict = api.updateRecordTitle(initial.id, initial.revision - 1, "改标题", "before-correction")
        assertEquals(original.suggestions, conflict.currentRecord?.suggestions)

        assertTrue(api.reviseMemory(initial.id, initial.revision, "本次晚饭预算二十元",
            CorrectionScope.THIS_TIME, "this-time").success)
        val afterCorrection = detail(receipt).record
        assertNull(afterCorrection.currentDinnerDecision)
        assertTrue(afterCorrection.suggestions.isEmpty())
        assertEquals(original, api.getDinnerDecision(initial.id, original.id))
        assertEquals(original.id, api.recordOutcome(initial.id, afterCorrection.revision,
            "盖饭", "取餐快", "past-feedback", original.id).decisionId)

        val rerun = save(query, "recalculate", initial.id, detail(receipt).record.revision)
        assertEquals(TaskRunOutcome.COMPLETED, run(rerun))
        val refreshed = requireNotNull(detail(rerun).record.currentDinnerDecision)
        assertFalse(refreshed.id == original.id)

        assertTrue(api.reviseMemory(requireNotNull(owner.recordId), detail(owner).record.revision,
            "以后只在工作日加班时，优先给我推荐省事的晚饭。", CorrectionScope.FUTURE, "future").success)
        database.close()
        database = openDatabase()
        api = LocalCoreApi(RoomCoreRepository(database))
        val fromDetail = detail(rerun).record
        val fromList = api.queryRecords().records.single { it.id == initial.id }
        assertNull(fromDetail.currentDinnerDecision)
        assertTrue(fromDetail.suggestions.isEmpty())
        assertEquals(fromDetail, fromList)
        assertEquals(refreshed, api.getDinnerDecision(initial.id, refreshed.id))
        assertEquals(fromDetail, api.updateRecordTitle(initial.id, initial.revision, "改标题", "after-correction").currentRecord)
        val feedback = api.recordOutcome(initial.id, fromDetail.revision, "面", "省事", "new-feedback", refreshed.id)
        assertEquals(refreshed.id, feedback.decisionId)
    }

    @Test fun unlinkRetractsOnlyTheConsumerAndKeepsTheOwnersExactSource() {
        val history = save(monday, "monday")
        save(wednesday, "wednesday")
        val first = save(query, "one")
        assertEquals(TaskRunOutcome.COMPLETED, run(first))
        val second = save(query, "two")
        assertEquals(TaskRunOutcome.COMPLETED, run(second))
        val consumer = detail(first).record
        val decision = requireNotNull(consumer.currentDinnerDecision)
        val unaffected = requireNotNull(detail(second).record.currentDinnerDecision)
        val source = requireNotNull(history.source)
        assertTrue(api.getRecordDetail(consumer.id, source)?.sources?.single()?.text == monday)
        assertTrue(api.removeSourceReference(consumer.id, consumer.revision, source, "unlink").success)

        assertNull(api.getRecordDetail(consumer.id, source))
        assertNull(detail(first).record.currentDinnerDecision)
        assertTrue(requireNotNull(api.getDinnerDecision(consumer.id, decision.id)).redacted)
        assertEquals(unaffected, detail(second).record.currentDinnerDecision)
        assertEquals(monday, requireNotNull(api.getRecordDetail(requireNotNull(history.recordId), source)).sources.single().text)
    }

    private fun advanceToCommit(
        receipt: ApiReceipt,
        pipeline: DietTaskPipeline,
        rankOutput: (StageOutput) -> StageOutput = { it },
    ): TaskJob {
        var job = requireNotNull(store.claim(requireNotNull(receipt.jobId), 10, 100))
        for (stage in TaskStage.entries.takeWhile { it != TaskStage.COMMIT }) {
            val output = pipeline.runStage(stage, job.input(), job.checkpoints)
            job = store.recordCheckpoint(job.jobId, job.leaseToken, stage,
                if (stage == TaskStage.RANK) rankOutput(output) else output, 11, 100)
        }
        return job
    }
}
