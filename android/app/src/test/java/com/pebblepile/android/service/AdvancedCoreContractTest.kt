package com.pebblepile.android.service

import com.pebblepile.android.db.SnapshotCoreRepository
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.RecordTag
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.StoredReceipt
import com.pebblepile.android.model.ItemStatus
import com.pebblepile.android.model.TagAction
import com.pebblepile.android.model.TagReviewState
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedCoreContractTest {
    @Test
    fun tagActionsAndGoalMetadataPersistAsRevisionedChanges() = runBlocking {
        val repository = SnapshotCoreRepository()
        val core = LocalCoreApi(
            repository = repository,
            decorateCreatedRecord = { record, _ ->
                record.copy(
                    tags = listOf(
                        RecordTag(
                            "tag-1", "饮食", "晚饭", "test-rules",
                            reviewState = TagReviewState.NEEDS_REVIEW,
                        )
                    )
                )
            },
        )
        val saved = core.recordExperience("晚饭吃面", "app_home_record", "save")
        val id = requireNotNull(saved.recordId)
        var revision = requireNotNull(saved.revision)

        assertTrue(core.reviseTag(id, revision, "tag-1", TagAction.CONFIRM, null, "confirm").success)
        revision += 1
        var record = requireNotNull(core.getRecord(id))
        assertEquals(TagReviewState.CONFIRMED, record.tags.single().reviewState)

        assertTrue(core.reviseTag(id, revision, "tag-1", TagAction.MODIFY, "通勤", "modify").success)
        revision += 1
        record = requireNotNull(core.getRecord(id))
        assertEquals("通勤", record.tags.single().name)
        assertEquals(TagReviewState.CONFIRMED, record.tags.single().reviewState)

        assertTrue(core.reviseTag(id, revision, "tag-1", TagAction.REVIEW, null, "review").success)
        revision += 1
        record = requireNotNull(core.getRecord(id))
        assertEquals(TagReviewState.NEEDS_REVIEW, record.tags.single().reviewState)

        assertTrue(core.reviseTag(id, revision, "tag-1", TagAction.EXCLUDE, null, "exclude").success)
        revision += 1
        record = requireNotNull(core.getRecord(id))
        assertTrue(record.tags.single().excluded)

        assertTrue(core.updateRecordGoal(id, revision, "周末晚餐", ItemStatus.WAITING, "goal").success)
        record = requireNotNull(core.getRecord(id))
        assertEquals(TaskState.QUEUED, core.getTask(requireNotNull(saved.jobId))?.state)
        assertEquals("周末晚餐", record.title)
        assertEquals(ItemStatus.WAITING, record.itemStatus)
        assertTrue(core.getRecord(id)!!.tags.single().excluded)
        assertTrue(core.recordOutcome(id, record.revision, "热汤面", "等取餐", "feedback").success)
        val afterFeedback = requireNotNull(core.getRecord(id))
        assertTrue(core.reviseMemory(id, afterFeedback.revision, "工作日晚餐", CorrectionScope.THIS_TIME, "condition").success)
        assertEquals(ItemStatus.WAITING, core.queryRecords().records.single().itemStatus)
    }

    @Test
    fun disablingAutoOrganizeSavesOnlyTheOriginal() = runBlocking {
        val core = LocalCoreApi(SnapshotCoreRepository(), decorateCreatedRecord = { record, _ ->
            record.copy(tags = listOf(RecordTag("food", "饮食", "晚餐", "test")))
        })
        core.setAutoOrganize(false)
        val saved = core.recordExperience("晚餐", "app_home_record", "raw-only")
        assertTrue(saved.success)
        assertNull(saved.jobId)
        val detail = requireNotNull(core.getRecordDetail(requireNotNull(saved.recordId)))
        assertEquals("晚餐", detail.sources.single().text)
        assertTrue(detail.record.tags.isEmpty())
        core.setAutoOrganize(true)
        val organized = core.recordExperience("晚餐", "app_home_record", "organized")
        assertEquals("饮食", core.getRecord(requireNotNull(organized.recordId))?.tags?.single()?.name)
    }

    @Test
    fun replaysTagExclusionsCommittedByThePreviousVersion() = runBlocking {
        val repository = SnapshotCoreRepository()
        val previous = ApiReceipt(true, recordId = "r1", revision = 2, libraryRevision = 2, message = "已排除标签")
        repository.write { tx ->
            // Hash of the original revise_tags request for record r1, revision 1, tag t1.
            tx.putReceipt(OperationKey("android", "legacy-exclude"), StoredReceipt(
                "22b068c9f8a9e50fd9cea01f83d6bd07740f294a72c2c2ece143ee7c4f0c3408", previous,
            ))
        }
        val core = LocalCoreApi(repository)
        assertEquals(previous, core.reviseTags("r1", 1, "t1", "legacy-exclude"))
        assertEquals(previous, core.reviseTag("r1", 1, "t1", TagAction.EXCLUDE, null, "legacy-exclude"))
    }

    @Test
    fun failedTaskCanRetryByItsOwnJobIdWithoutCreatingANewRecord() = runBlocking {
        val repository = SnapshotCoreRepository()
        val core = LocalCoreApi(repository, autoOrganize = true)
        val saved = core.recordExperience("需要重试的整理", "app_home_record", "save")
        val id = requireNotNull(saved.recordId)
        val jobId = requireNotNull(saved.jobId)
        val queued = requireNotNull(core.getTask(jobId))
        repository.write { tx ->
            tx.putTask(queued.copy(state = TaskState.FAILED, revision = queued.revision + 1))
        }

        val receipt = core.controlTask(jobId, queued.revision + 1, TaskAction.RETRY, "retry")

        assertTrue(receipt.success)
        assertEquals(jobId, receipt.jobId)
        assertEquals(TaskState.QUEUED, receipt.task?.state)
        assertEquals(id, core.getRecord(id)?.id)
        assertEquals(1, core.queryRecords().records.size)
        val stale = requireNotNull(receipt.task).copy(state = TaskState.STALE)
        repository.write { it.putTask(stale) }
        assertFalse(core.controlTask(jobId, stale.revision, TaskAction.RETRY, "stale-retry").success)
        assertEquals(stale, core.getTask(jobId))
    }
}
