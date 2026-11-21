package com.pebblepile.android.service

import com.google.gson.Gson
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.RecordTag
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.CoreReader
import com.pebblepile.android.repository.CoreRepository
import com.pebblepile.android.repository.CoreWriter
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.repository.StoredReceipt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #10 contract cases that any `CoreRepository` must satisfy.
 *
 * Inputs come from the shared docs/testing/issue10 fixtures. Subclasses provide a repository-backed
 * harness; the in-memory snapshot implementation runs today, and the SQLite implementation from #7
 * can be added without duplicating these assertions.
 */
abstract class CoreApiContractSuite {

    protected val E01 = Issue10Fixtures.E01
    protected val E02 = Issue10Fixtures.E02
    protected val C01 = Issue10Fixtures.C01

    @Test
    fun k01SaveReturnsReceiptSourceAndOrganizeTask() = runBlocking {
        val h = harness()

        val receipt = h.core.recordExperience(E01, "app_home_record", "op-save")

        assertTrue(receipt.success)
        println("K01 " + Gson().toJson(receipt))
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
        println("K02 " + Gson().toJson(replay))
        assertEquals(first, h.repository.read { it.receipt(OperationKey("issue10-test", "op-save"))?.receipt })
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
        val original = h.core.recordExperience(E01, "app_home_record", "op-save")

        val rejected = h.core.recordExperience(E02, "app_home_record", "op-save")

        assertFalse(rejected.success)
        assertEquals("INVALID_INPUT", rejected.code)
        assertEquals("OPERATION_ID_REUSED", rejected.reason)
        assertEquals(original, h.core.recordExperience(E01, "app_home_record", "op-save"))
        assertEquals(E01, h.core.getRecord(original.recordId!!)!!.rawText)
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
        println("K03 " + Gson().toJson(conflict))
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

        val previousSnapshot = h.core.queryContext(recordId)!!.snapshotId
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
        assertTrue(context.snapshotId.isNotBlank())
        assertNotEquals(previousSnapshot, context.snapshotId)
        val replay = h.core.reviseMemory(recordId, saved.revision!!, C01, CorrectionScope.FUTURE, "op-c01")
        assertEquals(receipt, replay)
        assertEquals(context, h.core.queryContext(recordId))
        assertEquals(2, h.core.getRecordDetail(recordId)!!.jobIds.size)
        assertEquals(TaskState.QUEUED, h.core.getTask(receipt.jobId!!)?.state)
    }

    @Test
    fun k03StalePreferenceRevisionIsRejectedWithCurrentCondition() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val recordId = saved.recordId!!

        val first = h.core.reviseMemory(
            recordId, saved.revision!!, C01, CorrectionScope.FUTURE, "op-c01"
        )
        assertTrue(first.success)
        val other = h.core.recordExperience(E02, "android_share", "op-second-record")
        val before = h.core.getRecordDetail(other.recordId!!)!!

        val conflict = h.core.reviseMemory(
            other.recordId!!, other.revision!!, "新条件", CorrectionScope.FUTURE, "op-c01-2",
            basePreferenceRevision = 0L
        )

        assertFalse(conflict.success)
        assertEquals("REVISION_CONFLICT", conflict.code)
        assertEquals(before, h.core.getRecordDetail(other.recordId!!))
        assertEquals(recordId, conflict.currentPreference?.recordId)
        assertEquals(C01, conflict.currentPreference?.text)
        assertEquals(C01, h.core.queryContext(recordId)!!.futureCondition?.text)
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
            E02, "android_share", "op-share", recordId = recordId, baseRevision = first.revision
        )

        assertTrue(second.success)
        assertEquals(2L, second.revision!!)
        assertNotEquals(first.source, second.source)
        val detail = h.core.getRecordDetail(recordId)!!
        assertEquals(mapOf(first.source to E01, second.source to E02), detail.sources.associate { it.ref to it.text })
        assertEquals(E02, h.core.getRecordDetail(recordId, second.source!!)!!.sources.single().text)
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
        val ref = saved.source!!
        assertNull(h.core.getRecordDetail(recordId, ref.copy(sourceVersion = ref.sourceVersion + 1)))
        val other = h.core.recordExperience(E02, "android_share", "op-other")
        assertNull(h.core.getRecordDetail(other.recordId!!, ref))
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
    fun invalidCapturedAtIsRejectedWithoutWriting() = runBlocking {
        val h = harness()

        val receipt = h.core.recordExperience(
            CaptureRequest(E01, "app_home_record", "op-bad-time", capturedAt = "not-a-time")
        )

        assertFalse(receipt.success)
        assertEquals("INVALID_INPUT", receipt.code)
        assertEquals(0, h.core.queryRecords().records.size)
        assertEquals(0L, h.core.queryRecords().libraryRevision)
    }

    @Test
    fun updateRecordTitleReceiptCarriesTitle() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")

        val renamed = h.core.updateRecordTitle(
            saved.recordId!!, saved.revision!!, "加班晚餐", "op-rename"
        )

        assertTrue(renamed.success)
        assertEquals("加班晚餐", renamed.title)
        assertEquals(2L, renamed.revision!!)
        assertEquals("加班晚餐", h.core.getRecord(saved.recordId!!)!!.title)
    }

    @Test
    fun appendingRequiresTheCurrentRecordRevision() = runBlocking {
        val h = harness()
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")

        val missingBase = h.core.recordExperience(
            CaptureRequest(E01, "android_share", "op-append", recordId = saved.recordId)
        )
        val staleBase = h.core.recordExperience(
            CaptureRequest(E01, "android_share", "op-append-2", recordId = saved.recordId, baseRevision = 99L)
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
    fun staleTagAndOutcomeWritesKeepSourcesTasksAndRecordIntact() = runBlocking {
        val tag = RecordTag("tag-food", "饮食", E01.take(10), "test-0.1")
        val h = harness(decorate = { it.copy(tags = listOf(tag)) })
        val saved = h.core.recordExperience(E01, "app_home_record", "op-save")
        val id = saved.recordId!!
        val revision = saved.revision!!
        assertTrue(h.core.updateRecordTitle(id, revision, "晚餐", "rename").success)
        val before = h.core.getRecordDetail(id)!!

        val tagConflict = h.core.reviseTags(id, revision, tag.id, "exclude")
        val outcomeConflict = h.core.recordOutcome(id, revision, "热汤面", "省事", "feedback")
        for (conflict in listOf(tagConflict, outcomeConflict)) {
            assertEquals("REVISION_CONFLICT", conflict.code)
            assertEquals("review_current_revision", conflict.nextAction)
            assertEquals(before.record, conflict.currentRecord)
            assertEquals(before.libraryRevision, conflict.libraryRevision)
        }
        assertEquals(before, h.core.getRecordDetail(id))
        assertTrue(h.core.reviseTags(id, before.record.revision, tag.id, "exclude").success)
        val current = h.core.getRecord(id)!!
        assertTrue(h.core.recordOutcome(id, current.revision, "热汤面", "省事", "feedback").success)
    }

    protected class Harness(val core: CoreApi, val repository: CoreRepository)

    protected abstract fun createHarness(
        autoOrganize: Boolean,
        decorate: (PebbleRecord) -> PebbleRecord
    ): Harness

    protected fun harness(
        autoOrganize: Boolean = true,
        decorate: (PebbleRecord) -> PebbleRecord = { it }
    ): Harness = createHarness(autoOrganize, decorate)

    protected class FailingRepository : CoreRepository {
        override fun <T> read(block: (CoreReader) -> T): T = block(EmptyReader)

        override fun <T> write(block: (CoreWriter) -> T): T = throw RepositoryWriteException()
    }
}

private object EmptyReader : CoreReader {
    override val revision: Long = 0L
    override val futureCondition: ConditionSetting? = null
    override fun record(id: String): PebbleRecord? = null
    override fun records(): List<PebbleRecord> = emptyList()
    override fun sources(recordId: String): List<CapturedSource> = emptyList()
    override fun task(id: String): CoreTask? = null
    override fun tasks(recordId: String): List<CoreTask> = emptyList()
    override fun receipt(key: OperationKey): StoredReceipt? = null
}
