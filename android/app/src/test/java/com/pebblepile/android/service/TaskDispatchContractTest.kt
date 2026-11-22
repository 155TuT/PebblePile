package com.pebblepile.android.service

import com.pebblepile.android.db.DemoLibrary
import com.pebblepile.android.db.SnapshotCoreRepository
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.RepositoryWriteException
import org.junit.Assert.*
import org.junit.Test

class TaskDispatchContractTest {
    @Test fun wakeSeesCommittedReceiptAndResumeOnlyReplacesOnce() {
        val repository = SnapshotCoreRepository()
        val wakes = mutableListOf<Pair<String, Boolean>>()
        val api = LocalCoreApi(repository, onTaskQueued = { task, replace ->
            repository.read { tx ->
                assertEquals(task, tx.task(task.id))
                assertNotNull(tx.receipt(OperationKey("android", "save")))
            }
            wakes += task.id to replace
        })
        val request = CaptureRequest("午餐想吃面", "android_share", "save")
        val saved = api.recordExperience(request)
        val id = requireNotNull(saved.jobId)
        assertEquals(listOf(id to false), wakes)
        val paused = api.controlTask(id, 1, TaskAction.PAUSE, "pause")
        assertEquals(saved, api.recordExperience(request))
        assertEquals(1, wakes.size)
        val revision = requireNotNull(paused.task).revision
        val resumed = api.controlTask(id, revision, TaskAction.RESUME, "resume")
        assertTrue(resumed.success)
        assertEquals(resumed, api.controlTask(id, revision, TaskAction.RESUME, "resume"))
        assertEquals(listOf(id to false, id to true, id to false), wakes)
    }

    @Test fun failedCommitAndCaptureWithOrganizingOffLeaveTheQueueAlone() {
        var fail = true
        val repository = SnapshotCoreRepository(DemoLibrary()) { if (fail) throw RepositoryWriteException() }
        val api = LocalCoreApi(repository, onTaskQueued = { _, _ -> error("Unexpected wake") })
        val request = CaptureRequest("保存原话", "app_home_record", "save")
        assertFalse(api.recordExperience(request).success)
        repository.read { assertTrue(it.records().isEmpty()) }
        fail = false
        api.setAutoOrganize(false)
        val saved = api.recordExperience(request)
        assertTrue(saved.success)
        assertNull(saved.jobId)
    }
}
