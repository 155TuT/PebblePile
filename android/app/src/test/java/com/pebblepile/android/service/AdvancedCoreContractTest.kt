package com.pebblepile.android.service

import com.pebblepile.android.db.SnapshotCoreRepository
import com.pebblepile.android.model.ItemStatus
import com.pebblepile.android.model.TagAction
import com.pebblepile.android.model.TagReviewState
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedCoreContractTest {
    @Test
    fun tagActionsAndGoalMetadataPersistAsRevisionedChanges() = runBlocking {
        val repository = SnapshotCoreRepository()
        val core = LocalCoreApi(
            repository = repository,
            autoOrganize = false,
            decorateCreatedRecord = { record, _ ->
                record.copy(
                    tags = listOf(
                        com.pebblepile.android.model.RecordTag(
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
        assertEquals("周末晚餐", record.title)
        assertEquals(ItemStatus.WAITING, record.itemStatus)
        assertTrue(core.getRecord(id)!!.tags.single().excluded)
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
    }
}
