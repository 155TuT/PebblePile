package com.pebblepile.android.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pebblepile.android.DemoCoreApi
import com.pebblepile.android.model.DeleteRequest
import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeleteTargetType
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.service.CaptureRequest
import com.pebblepile.scheduler.LeaseLostException
import com.pebblepile.scheduler.StageSource
import com.pebblepile.scheduler.StageOutput
import com.pebblepile.scheduler.TaskStage
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomCleanupRunnerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "cleanup-${UUID.randomUUID()}.db"
    private var database = open()
    private val core get() = DemoCoreApi(RoomCoreRepository(database))
    private val runner get() = RoomCleanupRunner(database)

    private fun open() = Room.databaseBuilder(context, PebbleDatabase::class.java, name)
        .allowMainThreadQueries().build()

    @After fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun reopen() {
        database.close()
        database = open()
    }

    @Test fun committedStepsSurviveReopenAndDuplicateWorkersPreserveOriginalReceipt() {
        val api = core
        val save = api.recordExperience(CaptureRequest("加班晚饭想省事", "app_home_record", "save"))
        val owner = requireNotNull(save.recordId)
        val source = requireNotNull(save.source)
        val target = DeleteTarget(DeleteTargetType.RECORD, owner)
        val preview = requireNotNull(api.previewDeleteMemory(target).deletePreview)
        val request = DeleteRequest(target, preview.previewId, preview.baseRevision, "delete")
        val receipt = api.deleteMemory(request)
        val jobId = requireNotNull(receipt.jobId)

        assertFalse(runner.advance(jobId))
        assertNull(database.sourceDao().get(source.sourceId, source.sourceVersion))
        assertNotNull(database.recordDao().get(owner))
        assertNull(api.getRecordDetail(owner))
        reopen()
        assertEquals("sources_removed", database.checkpointDao().get(jobId)?.stage)
        assertEquals("sources_removed", core.getTask(jobId)?.phase)

        val pool = Executors.newFixedThreadPool(3)
        val results = try {
            pool.invokeAll(List(3) { Callable { runner.advance(jobId) } }).map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, results.count { !it })
        assertNull(database.recordDao().get(owner))
        reopen()
        val completed = requireNotNull(core.getTask(jobId))
        assertEquals(TaskState.COMPLETED, completed.state)
        assertEquals("complete", completed.phase)
        assertEquals("complete", database.checkpointDao().get(jobId)?.stage)
        assertTrue(runner.advance(jobId))
        assertEquals(completed, core.getTask(jobId))
        assertEquals(requireNotNull(receipt.libraryRevision) + 3, core.queryRecords().libraryRevision)
        assertEquals("TARGET_DELETED", core.previewDeleteMemory(target).reason)
        assertEquals(receipt, core.deleteMemory(request))
    }

    @Test fun deletingHistoryRevokesDerivedCheckpointsInAnotherRecord() {
        val api = core
        val meal = api.recordExperience(CaptureRequest("周四加班吃盖饭", "android_share", "meal"))
        val question = api.recordExperience(CaptureRequest("周四晚饭吃什么", "app_home_record", "query"))
        val other = api.recordExperience(CaptureRequest("周末想吃面", "app_home_record", "other"))
        val version = requireNotNull(meal.source)
        val evidence = StageSource(requireNotNull(meal.recordId), version.sourceId, version.sourceVersion)
        val id = requireNotNull(question.jobId)
        val store = RoomTaskStore(database)
        val lease = requireNotNull(store.claim(id, 10, 100)).leaseToken
        val own = StageOutput("question")
        store.recordCheckpoint(id, lease, TaskStage.EXTRACT, own, 11, 100)
        store.recordCheckpoint(id, lease, TaskStage.UPDATE,
            StageOutput("derived-from-meal", inputSources = listOf(evidence)), 12, 100)
        val untouched = store.get(requireNotNull(other.jobId))
        val target = DeleteTarget(DeleteTargetType.SOURCE, version.sourceId)
        val preview = requireNotNull(api.previewDeleteMemory(target).deletePreview)
        assertTrue(api.deleteMemory(DeleteRequest(target, preview.previewId, preview.baseRevision, "delete-meal")).success)
        reopen()
        val revoked = requireNotNull(RoomTaskStore(database).get(id))
        assertEquals(TaskState.STALE, core.getTask(id)?.state)
        assertEquals(mapOf(TaskStage.EXTRACT to own), revoked.checkpoints)
        assertEquals("extract", database.checkpointDao().get(id)?.stage)
        assertFalse(requireNotNull(database.schedulerDao().get(id)).snapshotJson.contains("derived-from-meal"))
        assertEquals(untouched, RoomTaskStore(database).get(requireNotNull(other.jobId)))
        assertEquals("周四晚饭吃什么", core.getRecordDetail(requireNotNull(question.recordId))?.record?.rawText)
    }

    @Test fun failedCheckpointRollsBackPurgedRowsAndResumesAfterRepair() {
        val api = core
        val saved = api.recordExperience(CaptureRequest("原话", "android_share", "share"))
        val source = requireNotNull(saved.source)
        val target = DeleteTarget(DeleteTargetType.SOURCE, source.sourceId)
        val preview = requireNotNull(api.previewDeleteMemory(target).deletePreview)
        val jobId = requireNotNull(api.deleteMemory(DeleteRequest(target, preview.previewId, preview.baseRevision, "delete")).jobId)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER fail_cleanup_step BEFORE INSERT ON task_checkpoints
            WHEN NEW.stage = 'sources_removed' BEGIN SELECT RAISE(ABORT, 'checkpoint failure'); END""")
        try {
            assertThrows(RepositoryWriteException::class.java) { runner.advance(jobId) }
            assertEquals("isolated", database.checkpointDao().get(jobId)?.stage)
            assertEquals(TaskState.WAITING, api.getTask(jobId)?.state)
            assertEquals("retry_pending", api.getTask(jobId)?.waitReason)
            assertNotNull(database.sourceDao().get(source.sourceId, source.sourceVersion))
            assertNull(api.getRecordDetail(requireNotNull(saved.recordId), source))
        } finally {
            sql.execSQL("DROP TRIGGER fail_cleanup_step")
        }
        reopen()
        assertFalse(runner.advance(jobId))
        assertNull(database.sourceDao().get(source.sourceId, source.sourceVersion))
        assertFalse(runner.advance(jobId))
        assertTrue(runner.advance(jobId))
        assertNotNull(core.getRecordDetail(requireNotNull(saved.recordId)))
    }

    @Test fun unlinkPreservesSharedSourceAndNewJobsWhileDroppingOldLeasesAndOutputs() {
        val api = core
        val first = api.recordExperience(CaptureRequest("加班晚饭想省事", "app_home_record", "first"))
        val second = api.recordExperience(CaptureRequest("另一个人的记录", "android_share", "second"))
        val owner = requireNotNull(first.recordId)
        val borrower = requireNotNull(second.recordId)
        val shared = requireNotNull(first.source)
        val oldId = requireNotNull(second.jobId)
        val store = RoomTaskStore(database)
        val claimed = requireNotNull(store.claim(oldId, 1000, 60000))
        store.recordCheckpoint(oldId, claimed.leaseToken, TaskStage.EXTRACT, StageOutput("old-output"), 1001, 60000)
        RoomCoreRepository(database).write { tx ->
            val a = requireNotNull(tx.record(owner))
            val b = requireNotNull(tx.record(borrower))
            tx.putRecord(b.copy(tags = b.tags + a.tags.first().copy(id = "shared-tag")))
        }
        assertNotNull(api.getRecordDetail(borrower, shared))
        val unlink = api.removeSourceReference(borrower, requireNotNull(second.revision), shared, "unlink")
        val cleanupId = requireNotNull(unlink.jobId)
        assertThrows(LeaseLostException::class.java) {
            store.recordCheckpoint(oldId, claimed.leaseToken, TaskStage.UPDATE, StageOutput("late-output"), 1002, 60000)
        }
        val newer = api.recordExperience(CaptureRequest("新的独立内容", "app_home_record", "append",
            recordId = borrower, baseRevision = unlink.revision))
        val newId = requireNotNull(newer.jobId)
        val newClaim = requireNotNull(store.claim(newId, 1100, 60000))
        store.recordCheckpoint(newId, newClaim.leaseToken, TaskStage.EXTRACT, StageOutput("new-output"), 1101, 60000)
        repeat(3) { runner.advance(cleanupId) }

        assertEquals("加班晚饭想省事", api.getRecordDetail(owner, shared)?.sources?.single()?.text)
        assertNull(api.getRecordDetail(borrower, shared))
        assertEquals(TaskState.CANCELLED, api.getTask(oldId)?.state)
        val historical = store.get(oldId)
        assertTrue(historical?.checkpoints.orEmpty().isEmpty())
        assertTrue(historical?.sourceText.isNullOrEmpty())
        assertEquals(StageOutput("new-output"), store.get(newId)?.checkpoints?.get(TaskStage.EXTRACT))
    }
}
