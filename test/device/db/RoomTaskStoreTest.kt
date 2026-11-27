package com.pebblepile.android.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.algorithm.DietTaskPipeline
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.service.CaptureRequest
import com.pebblepile.android.service.LocalCoreApi
import com.pebblepile.scheduler.AgentTaskPipeline
import com.pebblepile.scheduler.CommitOutput
import com.pebblepile.scheduler.LeaseLostException
import com.pebblepile.scheduler.PersistentTaskCoordinator
import com.pebblepile.scheduler.RevisionProvider
import com.pebblepile.scheduler.StageOutput
import com.pebblepile.scheduler.TaskInput
import com.pebblepile.scheduler.TaskRunOutcome
import com.pebblepile.scheduler.TaskStage
import com.pebblepile.scheduler.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomTaskStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "room-worker-" + UUID.randomUUID()
    private var database = PebbleDatabase.build(context, name)
    private var store = RoomTaskStore(database)
    private var api = LocalCoreApi(RoomCoreRepository(database))
    private val capture = CaptureRequest("加班后吃面", "app_home_record", "capture")

    @After fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun reopen() {
        database.close()
        database = PebbleDatabase.build(context, name)
        store = RoomTaskStore(database)
        api = LocalCoreApi(RoomCoreRepository(database))
    }

    @Test fun resumesFromOriginalJobAndPublishesOnceAcrossConnections() {
        val first = api.recordExperience(capture)
        val id = requireNotNull(first.jobId)
        val claimed = requireNotNull(store.claim(id, 10, 20))
        val output = StageOutput("parsed", listOf("source/1"))
        store.recordCheckpoint(id, claimed.leaseToken, TaskStage.EXTRACT, output, 11, 20)
        reopen()
        assertEquals(first, api.recordExperience(capture))
        assertEquals("extract", api.getTask(id)?.phase)
        val competing = PebbleDatabase.build(context, name)
        try {
            assertNull(RoomTaskStore(competing).claim(id, 30, 20))
            var commitCount = 0
            val executed = mutableListOf<TaskStage>()
            val pipeline = object : AgentTaskPipeline {
                override fun runStage(stage: TaskStage, input: TaskInput, completed: Map<TaskStage, StageOutput>): StageOutput {
                    assertEquals("加班后吃面", input.text)
                    assertEquals(output, completed[TaskStage.EXTRACT])
                    executed += stage
                    return StageOutput(stage.name, listOf("source/1"))
                }
                override fun prepareCommit(input: TaskInput, completed: Map<TaskStage, StageOutput>): CommitOutput {
                    commitCount++
                    return CommitOutput("decision-1", listOf("source/1"))
                }
            }
            val coordinator = PersistentTaskCoordinator(store, store, pipeline, now = { 40 })
            assertEquals(id, coordinator.submit(claimed.input()).jobId)
            assertEquals(TaskRunOutcome.COMPLETED, coordinator.runJob(id))
            assertEquals(TaskRunOutcome.COMPLETED, coordinator.runJob(id))
            assertEquals(listOf(TaskStage.UPDATE, TaskStage.CONTEXT, TaskStage.RANK), executed)
            assertEquals(1, commitCount)
            val job = requireNotNull(RoomTaskStore(competing).get(id))
            assertEquals("decision-1", job.decisionId)
            assertEquals(listOf("source/1"), job.evidenceRefs)
            assertEquals(2, job.attempts)
            assertEquals(TaskState.COMPLETED, api.getTask(id)?.state)
        } finally { competing.close() }
    }

    @Test fun controlAndPreferenceChangesRejectLateWrites() = runBlocking {
        val id = requireNotNull(api.recordExperience(capture).jobId)
        val first = requireNotNull(store.claim(id, 1, 100))
        store.recordCheckpoint(id, first.leaseToken, TaskStage.EXTRACT, StageOutput("checkpoint"), 1, 100)
        val checkpoint = database.checkpointDao().get(id)
        var task = requireNotNull(api.getTask(id))
        assertTrue(api.controlTask(id, task.revision, TaskAction.PAUSE, "pause").success)
        assertThrows(LeaseLostException::class.java) {
            store.recordCheckpoint(id, first.leaseToken, TaskStage.EXTRACT, StageOutput("late"), 2, 100)
        }
        assertNull(store.claim(id, 200, 20))
        assertEquals(checkpoint, database.checkpointDao().get(id))
        task = requireNotNull(api.getTask(id))
        assertTrue(api.controlTask(id, task.revision, TaskAction.RESUME, "resume").success)
        val active = requireNotNull(store.claim(id, 210, 100))
        for (stage in TaskStage.entries.filter { it != TaskStage.EXTRACT && it != TaskStage.COMMIT }) {
            store.recordCheckpoint(id, active.leaseToken, stage, StageOutput(stage.name), 211, 100)
        }
        val other = api.recordExperience(capture.copy(text = "另一条经历", operationId = "other"))
        assertTrue(api.reviseMemory(requireNotNull(other.recordId), requireNotNull(other.revision),
            "以后只在工作日适用", CorrectionScope.FUTURE, "preference").success)
        // Even a caller holding the original revision cannot publish into the changed library.
        val stale = store.finishIfCurrent(id, active.leaseToken, CommitOutput("late"),
            RevisionProvider { com.pebblepile.scheduler.RevisionSnapshot(first.inputRevision, first.preferenceRevision) }, 212)
        assertEquals(TaskStatus.STALE, stale.status)
        assertNull(stale.decisionId)
        assertEquals(TaskState.STALE, api.getTask(id)?.state)
    }

    @Test fun v4RankCheckpointPublishesAfterDatabaseUpgrade() {
        val question = capture.copy(text = "今天周四加班，预算二十元，晚饭吃什么？")
        val saved = api.recordExperience(question)
        var job = requireNotNull(store.claim(requireNotNull(saved.jobId), 10, 100))
        val pipeline = DietTaskPipeline(store::loadDietContext)
        for (stage in TaskStage.entries.takeWhile { it != TaskStage.COMMIT }) {
            val output = pipeline.runStage(stage, job.input(), job.checkpoints)
            job = store.recordCheckpoint(job.jobId, job.leaseToken, stage, output, 11, 100)
        }
        upgradeFromV4()
        val restored = requireNotNull(store.get(job.jobId))
        assertEquals(job, restored)
        val result = DietTaskPipeline(store::loadDietContext).prepareCommit(restored.input(), restored.checkpoints)
        assertEquals(TaskStatus.COMPLETED, store.finishIfCurrent(job.jobId, job.leaseToken, result, store, 12).status)
        val record = requireNotNull(api.getRecordDetail(job.recordId)).record
        assertEquals(result.decisionId, requireNotNull(record.currentDinnerDecision).id)
        assertEquals(saved, api.recordExperience(question))
    }

    @Test fun completedV4DinnerResumesOnlyPublicationAndRetainsItsOriginalReceipt() {
        val question = capture.copy(text = "今天周六加班，预算二十元，晚饭吃什么？")
        val saved = api.recordExperience(question)
        val id = requireNotNull(saved.jobId)
        val coordinator = PersistentTaskCoordinator(store, store, DietTaskPipeline(store::loadDietContext))
        assertEquals(TaskRunOutcome.COMPLETED, coordinator.runJob(id))
        val ranked = requireNotNull(store.get(id))
        val recordId = requireNotNull(saved.recordId)
        val row = requireNotNull(database.recordDao().visible(recordId))
        val published = requireNotNull(DbJson.decode<PebbleRecord>(row.recordJson))
        val v4Record = published.copy(revision = ranked.inputRevision, dinnerDecisions = null)
        database.recordDao().put(row.copy(recordJson = DbJson.encode(v4Record), revision = v4Record.revision))
        upgradeFromV4()
        assertEquals(listOf(id), store.recoverableJobIds())
        val pending = requireNotNull(store.get(id))
        assertEquals(ranked.checkpoints, pending.checkpoints)
        assertNull(pending.decisionId)
        val upgraded = PersistentTaskCoordinator(store, store, DietTaskPipeline(store::loadDietContext))
        assertEquals(TaskRunOutcome.COMPLETED, upgraded.runJob(id))
        val decision = requireNotNull(api.getRecordDetail(recordId)?.record?.currentDinnerDecision)
        assertEquals(ranked.decisionId, decision.id)
        assertEquals(saved, api.recordExperience(question))
    }

    @Test fun aDinnerAlreadyPublishedByV4KeepsItsDecisionAndCompletedJob() {
        val question = capture.copy(text = "今天周四加班，预算二十元，晚饭吃什么？")
        val saved = api.recordExperience(question)
        val id = requireNotNull(saved.jobId)
        assertEquals(TaskRunOutcome.COMPLETED, PersistentTaskCoordinator(store, store,
            DietTaskPipeline(store::loadDietContext)).runJob(id))
        val ranked = requireNotNull(store.get(id))
        val recordId = requireNotNull(saved.recordId)
        val record = requireNotNull(api.getRecordDetail(recordId)).record
        assertNotNull(record.currentDinnerDecision)
        upgradeFromV4()
        assertTrue(store.recoverableJobIds().isEmpty())
        assertEquals(ranked, store.get(id))
        assertEquals(record, requireNotNull(api.getRecordDetail(recordId)).record)
    }

    private fun upgradeFromV4() {
        database.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { v4 ->
            v4.execSQL("DROP TABLE dinner_publication_counts")
            v4.version = 4
        }
        database = PebbleDatabase.build(context, name)
        store = RoomTaskStore(database)
        api = LocalCoreApi(RoomCoreRepository(database))
    }

    @Test fun appendKeepsItsOwnSourceAndV2JobsRecoverByBusinessId() = runBlocking {
        val first = api.recordExperience(capture)
        val appended = api.recordExperience("另一份原话", "android_share", "append",
            requireNotNull(first.recordId), first.revision)
        val id = requireNotNull(appended.jobId)
        assertEquals("另一份原话", store.get(id)?.sourceText)
        database.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.execSQL("DROP TABLE dinner_publication_counts")
            old.execSQL("DROP TABLE organized_sources")
            old.execSQL("DROP TABLE scheduler_jobs")
            old.version = 2
        }
        database = PebbleDatabase.build(context, name)
        store = RoomTaskStore(database)
        api = LocalCoreApi(RoomCoreRepository(database))
        val reclaimed = requireNotNull(store.claim(id, 10, 20))
        assertEquals("另一份原话", reclaimed.sourceText)
        assertEquals(id, api.getTask(id)?.id)
        assertEquals(first, api.recordExperience(capture))
        val previous = requireNotNull(store.get(requireNotNull(first.jobId)))
        assertNotEquals(previous.inputRevision, store.currentRevision(previous.recordId)?.recordRevision)
    }
}
