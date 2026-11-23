package com.pebblepile.android.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.DeleteRequest
import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeleteTargetType
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.model.RecordTag
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.service.CaptureRequest
import com.pebblepile.android.service.LocalCoreApi
import com.pebblepile.scheduler.CommitOutput
import com.pebblepile.scheduler.StageOutput
import com.pebblepile.scheduler.StaleInputException
import com.pebblepile.scheduler.TaskStage
import com.pebblepile.scheduler.TaskStatus
import com.pebblepile.scheduler.WAITING_ALGORITHM_UNAVAILABLE
import com.pebblepile.scheduler.WAITING_USER_PAUSED
import com.pebblepile.scheduler.inputDigest
import com.pebblepile.scheduler.taskKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomDietInputsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "diet-inputs-${UUID.randomUUID()}"
    private var db = PebbleDatabase.build(context, name)
    private val store get() = RoomTaskStore(db)
    private val repository get() = RoomCoreRepository(db)
    private val api get() = LocalCoreApi(repository)

    @After fun close() {
        db.close()
        context.deleteDatabase(name)
    }

    private fun capture(text: String, op: String, organize: Boolean = true): ApiReceipt =
        LocalCoreApi(repository, autoOrganize = organize).recordExperience(CaptureRequest(text, "android_share", op))

    @Test fun sourceIdentityAndHistorySurviveReopenAndSkipUnorganizedCaptures() {
        val older = capture("周一加班吃面", "older")
        val current = api.recordExperience(CaptureRequest("今天晚餐吃什么", "app_home_record", "question"))
        val id = requireNotNull(current.jobId)
        val oldRecord = requireNotNull(older.recordId)
        repository.write { tx ->
            val record = requireNotNull(tx.record(oldRecord))
            tx.putRecord(record.copy(revision = record.revision + 1,
                tags = listOf(RecordTag("tag", "省事", "周一加班吃面", "test", excluded = true))))
        }
        capture("仅保存的记录", "off", organize = false)
        val job = requireNotNull(store.get(id))
        assertEquals(current.source?.sourceId, job.input().captureSourceId)
        val snapshot = store.loadDietContext(job.input())
        assertEquals(current.source, snapshot.target.ref)
        assertEquals(setOf(current.source, older.source), snapshot.sources.map { it.ref }.toSet())
        assertEquals(mapOf(oldRecord to setOf("省事")), snapshot.excludedTagsByRecord)

        val original = snapshot.sources.single { it.recordId == oldRecord }
        repository.write { it.putSource(original) }
        assertThrows(RepositoryWriteException::class.java) {
            repository.write { it.putSource(original.copy(text = "同一版本的另一份原话")) }
        }
        assertEquals(snapshot, store.loadDietContext(job.input()))
        assertNotNull(store.claim(id, 1, 10))
        db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { v3 ->
            v3.execSQL("DROP TABLE organized_sources")
            v3.version = 3
        }
        db = PebbleDatabase.build(context, name)
        assertEquals(snapshot, store.loadDietContext(requireNotNull(store.get(id)).input()))
    }

    @Test fun crossRecordEditRollsBackCheckpointAndDeletionMakesRankedJobStale() {
        val historical = capture("周三加班吃盖饭", "history")
        val question = capture("周四加班，晚餐预算二十", "question")
        val id = requireNotNull(question.jobId)
        val lease = requireNotNull(store.claim(id, 1, 500))
        val key = store.loadDietContext(lease.input()).contextKey
        val historyId = requireNotNull(historical.recordId)
        assertTrue(api.updateRecordTitle(historyId, requireNotNull(historical.revision), "晚餐经历", "change").success)
        assertThrows(StaleInputException::class.java) {
            store.recordCheckpoint(id, lease.leaseToken, TaskStage.EXTRACT,
                StageOutput("old", contextKey = key), 2, 500)
        }
        assertTrue(requireNotNull(store.get(id)).checkpoints.isEmpty())
        val currentKey = store.loadDietContext(lease.input()).contextKey
        assertNotEquals(key, currentKey)
        for (stage in TaskStage.entries.filter { it != TaskStage.COMMIT }) {
            store.recordCheckpoint(id, lease.leaseToken, stage, StageOutput(stage.name, contextKey = currentKey), 3, 500)
        }
        val target = DeleteTarget(DeleteTargetType.RECORD, historyId)
        val caller = api
        val preview = requireNotNull(caller.previewDeleteMemory(target).deletePreview)
        assertTrue(caller.deleteMemory(DeleteRequest(target, preview.previewId, preview.baseRevision, "delete")).success)
        val result = store.finishIfCurrent(id, lease.leaseToken, CommitOutput("old-decision"), store, 4)
        assertEquals(TaskStatus.STALE, result.status)
        assertNull(result.decisionId)
    }

    @Test fun coldStartUpgradesWaitingCaptureOnceAndKeepsPauseAndTerminalState() {
        val waiting = requireNotNull(capture("周三加班吃盖饭", "wait").jobId)
        val paused = requireNotNull(capture("周四晚饭", "pause").jobId)
        val finished = requireNotNull(capture("周日晚饭", "done").jobId)
        simulateV3(waiting, TaskState.WAITING, WAITING_ALGORITHM_UNAVAILABLE)
        simulateV3(paused, TaskState.WAITING, WAITING_USER_PAUSED)
        simulateV3(finished, TaskState.COMPLETED, null)
        db.close()
        db = PebbleDatabase.build(context, name)
        assertEquals(listOf(waiting), store.recoverableJobIds())
        val activated = requireNotNull(store.get(waiting))
        assertEquals(RoomTaskStore.ALGORITHM_VERSION, activated.algorithmVersion)
        assertEquals(TaskStatus.QUEUED, activated.status)
        assertNotNull(activated.captureSourceId)
        assertEquals(WAITING_USER_PAUSED, store.get(paused)?.waitingReason)
        assertEquals("capture-v0.1", store.get(finished)?.algorithmVersion)
        val revision = api.getTask(waiting)?.revision
        assertEquals(listOf(waiting), store.recoverableJobIds())
        assertEquals(revision, api.getTask(waiting)?.revision)
        assertEquals(activated, store.get(waiting))
    }

    private fun simulateV3(id: String, state: TaskState, reason: String?) {
        val saved = requireNotNull(store.get(id))
        val historical = saved.input().copy(algorithmVersion = "capture-v0.1",
            captureSourceId = null, captureSourceVersion = null)
        val version = saved.copy(algorithmVersion = historical.algorithmVersion,
            captureSourceId = null, captureSourceVersion = null, idempotencyKey = taskKey(historical),
            inputDigest = inputDigest(historical), status = TaskStatus.valueOf(state.name), waitingReason = reason)
        val business = requireNotNull(api.getTask(id)).copy(state = state, waitReason = reason)
        db.runInTransaction {
            val row = requireNotNull(db.taskDao().get(id))
            db.taskDao().put(row.copy(taskJson = DbJson.encode(business), state = state.name))
            db.schedulerDao().put(ScheduledJobEntity(id, version.idempotencyKey, DbJson.encode(version)))
        }
    }
}
