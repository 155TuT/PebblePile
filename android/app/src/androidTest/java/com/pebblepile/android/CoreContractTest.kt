package com.pebblepile.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.db.DemoLibrary
import com.pebblepile.android.db.DemoLibraryStore
import com.pebblepile.android.db.SnapshotCoreRepository
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.service.CaptureRequest
import com.pebblepile.android.service.CoreApi
import com.pebblepile.android.service.LocalCoreApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class CoreContractTest {
    private val dinner = CaptureRequest(
        "今天周一加班到八点半，晚饭预算二十元。我吃了十八元的热汤面，十分钟就吃上了，不用洗锅。",
        "app_home_record", "save-dinner", capturedAt = "2026-11-16T20:30:00+08:00"
    )

    @Test fun replayDinnerReceiptsAfterReopening() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "core-contract-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        fun open(): CoreApi = LocalCoreApi(DemoLibraryStore(preferences).repository())
        try {
            val api = open()
            val saved = api.recordExperience(dinner)
            val id = requireNotNull(saved.recordId)
            val job = requireNotNull(saved.jobId)
            assertEquals(1L, saved.revision)
            val renamed = api.updateRecordTitle(id, 1, "加班晚餐", "rename-dinner")
            assertEquals("加班晚餐", renamed.title)
            val reopened = open()
            assertEquals(saved, reopened.recordExperience(dinner))
            val reused = reopened.recordExperience(dinner.copy(text = "另一次晚餐"))
            assertEquals("INVALID_INPUT", reused.code)
            assertEquals("OPERATION_ID_REUSED", reused.reason)
            val stale = reopened.reviseMemory(id, 1, "只在工作日加班", CorrectionScope.FUTURE, "correct-dinner-stale")
            assertEquals("REVISION_CONFLICT", stale.code)
            assertEquals("review_current_revision", stale.nextAction)
            assertEquals(2L, stale.currentRevision)
            val detail = requireNotNull(reopened.getRecordDetail(id, saved.source))
            assertEquals(dinner.text, detail.sources.single().text)
            assertEquals(listOf(job), detail.jobIds)
            assertEquals(2L, detail.libraryRevision)
            assertTrue(reopened.updateRecordTitle(id, 2, "新标题", "rename-again").success)
            assertEquals(renamed, open().updateRecordTitle(id, 1, "加班晚餐", "rename-dinner"))
            assertEquals(TaskState.QUEUED, reopened.getTask(job)?.state)
            val paused = reopened.controlTask(job, 1, TaskAction.PAUSE, "pause")
            assertEquals(TaskState.WAITING, paused.task?.state)
            assertEquals(paused, open().controlTask(job, 1, TaskAction.PAUSE, "pause"))
            assertEquals("REVISION_CONFLICT", reopened.controlTask(job, 1, TaskAction.CANCEL, "old-cancel").code)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun stalePreferenceFromADifferentRecordRequiresReview() = runBlocking {
        val api: CoreApi = LocalCoreApi(SnapshotCoreRepository())
        val a = requireNotNull(api.recordExperience(dinner).recordId)
        val b = requireNotNull(api.recordExperience(dinner.copy(operationId = "second")).recordId)
        assertNull(api.getRecordDetail(b)?.futureCondition)
        assertTrue(api.reviseMemory(a, 1, "工作日", CorrectionScope.FUTURE, "first", 0).success)
        val before = requireNotNull(api.queryContext(b))
        val stale = api.reviseMemory(b, 1, "周末", CorrectionScope.FUTURE, "stale", 0)
        assertEquals("REVISION_CONFLICT", stale.code)
        assertEquals(before.futureCondition, stale.currentPreference)
        assertEquals(before, api.queryContext(b))
        val revision = requireNotNull(stale.currentPreference).revision
        assertTrue(api.reviseMemory(b, 1, "周末", CorrectionScope.FUTURE, "reviewed", revision).success)
        val snapshot = requireNotNull(api.queryContext(b)).snapshotId
        assertTrue(api.reviseMemory(b, 2, "仅今晚", CorrectionScope.THIS_TIME, "tonight").success)
        assertNotEquals(snapshot, api.queryContext(b)?.snapshotId)
        assertEquals("仅今晚", api.queryContext(b)?.currentCondition)
        assertEquals("周末", api.queryContext(a)?.futureCondition?.text)
    }

    @Test fun failedCommitRollsBackAndConcurrentRetriesShareOneReceipt() {
        var fail = true
        val repository = SnapshotCoreRepository(DemoLibrary()) { if (fail) throw RepositoryWriteException() }
        val api = LocalCoreApi(repository)
        assertEquals("PROCESSING_FAILED", api.recordExperience(dinner).code)
        assertTrue(api.queryRecords().records.isEmpty())
        assertEquals(0L, api.queryRecords().libraryRevision)
        fail = false
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..8).map { pool.submit<com.pebblepile.android.model.ApiReceipt> { api.recordExperience(dinner) } }
                .map { it.get() }
            val record = api.queryRecords().records.single()
            assertTrue(results.first().success)
            assertEquals(1, results.distinct().size)
            val detail = requireNotNull(api.getRecordDetail(record.id))
            assertEquals(1L, detail.libraryRevision)
            assertEquals(1, detail.sources.size)
            assertEquals(1, detail.jobIds.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun timelineOrdersLocalAndOffsetTimestampsByInstant() {
        val api: CoreApi = LocalCoreApi(SnapshotCoreRepository())
        val evening = LocalDateTime.of(2026, 11, 16, 21, 0).atZone(ZoneId.systemDefault()).toInstant()
        val earlier = api.recordExperience(dinner.copy(capturedAt = evening.minusSeconds(60).toString()))
        val later = api.recordExperience(dinner.copy(operationId = "later", capturedAt = "2026-11-16 21:00"))
        assertTrue(earlier.success && later.success)
        assertEquals(listOf(later.recordId, earlier.recordId), api.queryRecords().records.map { it.id })
        val invalid = api.recordExperience(dinner.copy(operationId = "invalid", capturedAt = "yesterday"))
        assertEquals("INVALID_INPUT", invalid.code)
        assertEquals(2L, api.queryRecords().libraryRevision)
    }

    @Test fun singleExplicitPreferenceFromIssue14RemainsQueryable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "core-upgrade-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val legacy = """[{"id":"old","rawText":"工作日晚饭","condition":"只在工作日","conditionScope":"FUTURE","revision":2}]"""
            assertTrue(preferences.edit().putString("records", legacy).commit())
            val api: CoreApi = LocalCoreApi(DemoLibraryStore(preferences).repository())
            assertEquals("只在工作日", api.queryContext("old")?.futureCondition?.text)
            assertNull(api.queryContext("old")?.currentCondition)
            assertTrue(api.updateRecordTitle("old", 2, "旧晚餐", "title").success)
            val reopened = LocalCoreApi(DemoLibraryStore(preferences).repository())
            assertEquals("只在工作日", reopened.getRecordDetail("old")?.futureCondition?.text)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
