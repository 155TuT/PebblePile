package com.pebblepile.android.db

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pebblepile.android.PebblePileApplication
import com.pebblepile.android.model.DeleteRequest
import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeleteTargetType
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.service.CaptureRequest
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkDispatchIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<PebblePileApplication>()
    private val api get() = app.coreApi

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(label, condition())
    }

    private fun workSucceeded(jobId: String): Boolean =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork("pebblepile-task-$jobId")
            .get(5, TimeUnit.SECONDS).any { it.state == WorkInfo.State.SUCCEEDED }

    @Test fun originalReceiptsAddressTheWorkersThatOrganizeAndCleanTheRecord() {
        api.setAutoOrganize(true)
        val token = UUID.randomUUID().toString()
        val request = CaptureRequest("后台整理联调 $token", "android_share", "capture-$token")
        val receipt = api.recordExperience(request)
        assertTrue(receipt.success)
        val recordId = requireNotNull(receipt.recordId)
        val organize = requireNotNull(receipt.jobId)
        val source = requireNotNull(receipt.source)
        assertEquals(receipt, api.recordExperience(request))
        await("整理 Worker 把同一个业务任务置为等待算法") {
            api.getTask(organize)?.waitReason == "algorithm_unavailable" && workSucceeded(organize)
        }
        assertEquals(listOf(organize), requireNotNull(api.getRecordDetail(recordId)).jobIds)
        val target = DeleteTarget(DeleteTargetType.RECORD, recordId)
        val preview = requireNotNull(api.previewDeleteMemory(target).deletePreview)
        val deletion = DeleteRequest(target, preview.previewId, preview.baseRevision, "delete-$token")
        val deleted = api.deleteMemory(deletion)
        assertTrue(deleted.success)
        val cleanup = requireNotNull(deleted.jobId)
        assertNull(api.getRecordDetail(recordId))
        await("清理 Worker 提交最终检查点") {
            api.getTask(cleanup)?.state == TaskState.COMPLETED && workSucceeded(cleanup)
        }
        val finished = requireNotNull(api.getTask(cleanup))
        assertEquals("complete", finished.phase)
        assertEquals(TaskState.CANCELLED, api.getTask(organize)?.state)
        assertEquals(deleted, api.deleteMemory(deletion))
        assertEquals(finished, api.getTask(cleanup))

        val db = PebbleDatabase.build(app, "pebblepile.sqlite")
        try {
            assertNull(db.recordDao().get(recordId))
            assertNull(db.sourceDao().get(source.sourceId, source.sourceVersion))
            assertEquals("complete", db.checkpointDao().get(cleanup)?.stage)
            assertEquals(setOf(organize, cleanup), db.taskDao().forRecord(recordId).map { it.id }.toSet())
        } finally {
            db.close()
        }
    }
}
