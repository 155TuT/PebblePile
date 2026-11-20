package com.pebblepile.android.service

import com.pebblepile.android.db.SnapshotCoreRepository
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.RecordTag
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.CoreReader
import com.pebblepile.android.repository.CoreRepository
import com.pebblepile.android.repository.CoreWriter
import com.pebblepile.android.repository.RepositoryWriteException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #10 contract cases against the #6 local core.
 *
 * Inputs are the frozen dinner materials from docs/issue2-research.md. The repository is the
 * in-memory snapshot implementation, so these tests exercise the business contract without an
 * emulator or SQLite.
 */
class LocalCoreApiContractTest {

    @Test
    fun k01SaveReturnsReceiptSourceAndOrganizeTask() = runBlocking {
        val h = harness()

        val receipt = h.core.recordExperience(E01, "app_home_record", "op-save")

        assertTrue(receipt.success)
        assertEquals("已保存", receipt.message)
        assertEquals(1L, receipt.revision!!)
        assertEquals(1L, receipt.libraryRevision!!)
        assertEquals(1L, receipt.source!!.sourceVersion)
        assertNotNull(receipt.jobId)

        val detail = h.core.getRecordDetail(receipt.recordId!!)!!
        assertEquals(E01, detail.record.rawText)
        assertEquals(1, detail.sources.size)
        assertEquals(E01, detail.sources.single().text)
        assertEquals(1, detail.jobIds.size)
        assertEquals(1L, detail.libraryRevision)
        assertEquals(TaskState.QUEUED, h.core.getTask(receipt.jobId!!)?.state)
    }

    @Test
    fun k02DuplicateOperationReturnsOriginalReceiptAndWritesOnce() = runBlocking {
        val h = harness()

        val first = h.core.recordExperience(E01, "app_home_record", "op-save")
        val replay = h.core.recordExperience(E01, "app_home_record", "op-save")

        assertEquals(first, replay)
        val detail = h.core.getRecordDetail(first.recordId!!)!!
        assertEquals(1, detail.sources.size)
        assertEquals(1, detail.jobIds.size)
        assertEquals(1, h.core.queryRecords().records.size)
        assertEquals(1L, h.core.queryRecords().libraryRevision)
    }

    @Test
    fun k02DuplicateStillReturnsOriginalRevisionAfterLaterWrites() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val recordId = saved.recordId!!

        val renamed = h.core.updateRecordTitle(recordId, saved.revision!!, "加班晚餐", "op-rename")
        assertTrue(renamed.success)
        assertEquals(2L, renamed.revision!!)

        val replay = h.core.recordExperience(E01, "app_home_record", "op-save")
        assertEquals(saved, replay)
        assertEquals(1L, replay.revision!!)
        assertEquals(2L, h.core.queryRecords().libraryRevision)
        assertEquals("加班晚餐", h.core.getRecord(recordId)!!.title)
    }

    @Test
    fun reusedOperationIdWithDifferentPayloadIsRejectedWithoutWriting() = runBlocking {
        val h = harness()
        h.core.recordExperience(E01, "app_home_record", "op-save")

        val rejected = h.core.recordExperience(E02, "app_home_record", "op-save")

        assertFalse(rejected.success)
        assertEquals("INVALID_INPUT", rejected.code)
        assertEquals("OPERATION_ID_REUSED", rejected.reason)
        assertEquals(1L, rejected.libraryRevision!!)
        assertEquals(1, h.core.queryRecords().records.size)
    }

    @Test
    fun k03StaleRevisionReturnsConflictAndKeepsCurrentState() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val recordId = saved.recordId!!
        val staleRevision = saved.revision!!
        h.core.updateRecordTitle(recordId, staleRevision, "加班晚餐", "op-rename")

        val conflict = h.core.reviseMemory(
            recordId, staleRevision, C01, CorrectionScope.FUTURE, "op-c01"
        )

        assertFalse(conflict.success)
        assertTrue(conflict.conflict)
        assertEquals("REVISION_CONFLICT", conflict.code)
        assertEquals(2L, conflict.currentRevision!!)
        assertEquals(2L, conflict.libraryRevision!!)
        assertEquals("review_current_revision", conflict.nextAction)
        assertEquals("加班晚餐", conflict.currentRecord?.title)
        assertNull(h.core.queryContext(recordId)!!.futureCondition)
        assertEquals(2L, h.core.queryRecords().libraryRevision)
    }

    @Test
    fun futureConditionIsStoredWithRebuildTaskAndSnapshot() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val recordId = saved.recordId!!

        val receipt = h.core.reviseMemory(
            recordId, saved.revision!!, C01, CorrectionScope.FUTURE, "op-c01"
        )

        assertTrue(receipt.success)
        assertEquals(2L, receipt.revision!!)
        assertNotNull(receipt.jobId)

        val record = h.core.getRecord(recordId)!!
        assertEquals(C01, record.currentCondition)
        assertEquals(CorrectionScope.FUTURE, record.conditionScope)
        assertNull(record.currentGoalCondition)

        val context = h.core.queryContext(recordId)!!
        assertEquals(C01, context.futureCondition!!.text)
        assertEquals(recordId, context.futureCondition!!.recordId)
        assertEquals(2L, context.futureCondition!!.revision)
        assertEquals("local-2", context.snapshotId)
        assertEquals(TaskState.QUEUED, h.core.getTask(receipt.jobId!!)?.state)
    }

    @Test
    fun thisTimeConditionChangesOnlyTheCurrentGoal() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val recordId = saved.recordId!!

        val receipt = h.core.reviseMemory(
            recordId, saved.revision!!, "今天不加班", CorrectionScope.THIS_TIME, "op-this-time"
        )

        assertTrue(receipt.success)
        assertNull(receipt.jobId)
        val record = h.core.getRecord(recordId)!!
        assertEquals("今天不加班", record.currentGoalCondition)
        assertEquals(CorrectionScope.THIS_TIME, record.conditionScope)
        assertNull(h.core.queryContext(recordId)!!.futureCondition)
    }

    @Test
    fun appendingToExistingRecordKeepsBothSourceVersions() = runBlocking {
        val h = harness()
        val first = h.core.recordExperience(E01, "app_home_record", "op-first")
        val recordId = first.recordId!!

        val second = h.core.recordExperience(
            E01, "system_share", "op-share", recordId = recordId, baseRevision = first.revision
        )

        assertTrue(second.success)
        assertEquals(2L, second.revision!!)
        assertNotEquals(first.source, second.source)
        val detail = h.core.getRecordDetail(recordId)!!
        assertEquals(2, detail.sources.size)
        assertEquals(2, detail.jobIds.size)
        assertEquals(E01, detail.record.rawText)
    }

    @Test
    fun recordDetailCanBeReadByExactSourceVersion() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val recordId = saved.recordId!!

        val detail = h.core.getRecordDetail(recordId, saved.source!!)
        assertNotNull(detail)
        assertEquals(listOf(saved.source), detail!!.sources.map { it.ref })
        assertNull(h.core.getRecordDetail(recordId, SourceRef("missing-source", 1)))
    }

    @Test
    fun excludedTagDropsOutOfFilteredQueryAndSurvivesReplay() = runBlocking {
        val tag = RecordTag("tag-food", "饮食", E01.take(10), "test-0.1")
        val h = harness(decorate = { it.copy(tags = listOf(tag)) })
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val recordId = saved.recordId!!
        assertEquals(1, h.core.queryRecords(setOf("饮食")).records.size)

        val excluded = h.core.reviseTags(recordId, saved.revision!!, tag.id, "op-exclude-tag")
        val replay = h.core.reviseTags(recordId, saved.revision!!, tag.id, "op-exclude-tag")

        assertTrue(excluded.success)
        assertEquals(2L, excluded.revision!!)
        assertEquals(excluded, replay)
        assertEquals(0, h.core.queryRecords(setOf("饮食")).records.size)
        assertEquals(1, h.core.queryRecords().records.size)
        assertTrue(h.core.getRecord(recordId)!!.tags.single().excluded)
    }

    @Test
    fun outcomeIsPersistedWithJobAndReplays() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val recordId = saved.recordId!!

        val outcome = h.core.recordOutcome(
            recordId, saved.revision!!, "热汤面", "省时也省事", "op-outcome"
        )
        val replay = h.core.recordOutcome(
            recordId, saved.revision!!, "热汤面", "省时也省事", "op-outcome"
        )

        assertTrue(outcome.success)
        assertEquals(2L, outcome.revision!!)
        assertNotNull(outcome.outcomeId)
        assertNotNull(outcome.jobId)
        assertEquals(outcome, replay)
        assertEquals(1, h.core.getRecord(recordId)!!.outcomes.size)
    }

    @Test
    fun taskCanBePausedResumedAndCancelled() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val jobId = saved.jobId!!
        val task = h.core.getTask(jobId)!!

        val paused = h.core.controlTask(jobId, task.revision, TaskAction.PAUSE, "op-pause")
        assertTrue(paused.success)
        assertEquals(TaskState.WAITING, paused.task?.state)
        assertEquals("user_paused", paused.task?.waitReason)

        val resumed = h.core.controlTask(
            jobId, paused.task!!.revision, TaskAction.RESUME, "op-resume"
        )
        assertEquals(TaskState.QUEUED, resumed.task?.state)

        val cancelled = h.core.controlTask(
            jobId, resumed.task!!.revision, TaskAction.CANCEL, "op-cancel"
        )
        assertEquals(TaskState.CANCELLED, cancelled.task?.state)

        val stale = h.core.controlTask(jobId, 1L, TaskAction.CANCEL, "op-stale-control")
        assertFalse(stale.success)
        assertEquals("REVISION_CONFLICT", stale.code)
        assertEquals(4L, stale.currentRevision!!)
    }

    @Test
    fun invalidInputsAreRejectedWithoutWriting() = runBlocking {
        val h = harness()

        val blankText = h.core.recordExperience("   ", "app_home_record", "op-blank")
        val tooLong = h.core.recordExperience("字".repeat(10_001), "app_home_record", "op-long")
        val blankMethod = h.core.recordExperience(E01, " ", "op-method")
        val blankOperation = h.core.recordExperience(
            CaptureRequest(E01, "app_home_record", " ")
        )

        assertFalse(blankText.success)
        assertFalse(tooLong.success)
        assertFalse(blankMethod.success)
        assertFalse(blankOperation.success)
        assertEquals("INVALID_INPUT", blankText.code)
        assertEquals(0L, blankText.libraryRevision!!)
        assertEquals(0, h.core.queryRecords().records.size)
        assertEquals(0L, h.core.queryRecords().libraryRevision)
    }

    @Test
    fun appendingRequiresTheCurrentRecordRevision() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")

        val missingBase = h.core.recordExperience(
            CaptureRequest(E01, "system_share", "op-append", recordId = saved.recordId)
        )
        val staleBase = h.core.recordExperience(
            CaptureRequest(E01, "system_share", "op-append-2", recordId = saved.recordId, baseRevision = 99L)
        )

        assertFalse(missingBase.success)
        assertEquals("INVALID_INPUT", missingBase.code)
        assertFalse(staleBase.success)
        assertEquals("REVISION_CONFLICT", staleBase.code)
        assertEquals(1, h.core.getRecordDetail(saved.recordId!!)!!.sources.size)
    }

    @Test
    fun autoOrganizeOffCreatesNoJob() = runBlocking {
        val h = harness(autoOrganize = false)

        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")

        assertTrue(saved.success)
        assertNull(saved.jobId)
        assertTrue(h.core.getRecordDetail(saved.recordId!!)!!.jobIds.isEmpty())
    }

    @Test
    fun repositoryWriteFailureReturnsRetryableReceipt() = runBlocking {
        val core = LocalCoreApi(FailingRepository())

        val receipt = core.recordExperience(E01, "app_home_record", "op-failing")

        assertFalse(receipt.success)
        assertEquals("PROCESSING_FAILED", receipt.code)
        assertTrue(receipt.retryable)
        assertEquals("retry", receipt.nextAction)
    }

    @Test
    fun snapshotRepositoryRollsBackWhenWriteThrows() {
        val repository = SnapshotCoreRepository()

        assertThrows(RuntimeException::class.java) {
            repository.write<Unit> { tx ->
                tx.putRecord(
                    PebbleRecord("record-rollback", E01, "2026-11-16 20:30", "app_home_record")
                )
                throw RuntimeException("boom")
            }
        }

        assertEquals(0, repository.read { it.records().size })
        assertEquals(0L, repository.read { it.revision })
    }

    private class Harness(val core: LocalCoreApi, val repository: SnapshotCoreRepository)

    private fun harness(
        autoOrganize: Boolean = true,
        decorate: (PebbleRecord) -> PebbleRecord = { it }
    ): Harness {
        val repository = SnapshotCoreRepository()
        val ids = AtomicInteger()
        val core = LocalCoreApi(
            repository = repository,
            clientId = "issue10-test",
            autoOrganize = autoOrganize,
            clock = { "2026-11-16 20:30" },
            newId = { "id-${ids.incrementAndGet()}" },
            decorateSampleRecord = decorate
        )
        return Harness(core, repository)
    }

    private class FailingRepository : CoreRepository {
        private val delegate = SnapshotCoreRepository()

        override fun <T> read(block: (CoreReader) -> T): T = delegate.read(block)

        override fun <T> write(block: (CoreWriter) -> T): T = throw RepositoryWriteException()
    }

    private companion object {
        const val E01 = "今天周一加班到八点半，晚饭预算二十元。我吃了十八元的热汤面，十分钟就吃上了，不用洗锅。"
        const val E02 = "今天周三又加班了，晚饭还是想控制在二十元以内。我吃了十六元的盖饭，不用准备食材，也不用洗锅，省事让我很满意。"
        const val C01 = "以后只在工作日加班时，优先给我推荐省事的晚饭。"
    }
}
