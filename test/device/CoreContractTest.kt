package com.pebblepile.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.db.DemoLibrary
import com.pebblepile.android.db.DemoLibraryStore
import com.pebblepile.android.db.SnapshotCoreRepository
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CleanupState
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeleteTargetType
import com.pebblepile.android.model.DeletionImpact
import com.pebblepile.android.model.DeletionResult
import com.pebblepile.android.model.TaskKind
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.DeletionObject
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.repository.StoredReceipt
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

    @Test fun aLibraryCleanupTaskAndItsCommittedReceiptSurviveReopening() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "core-cleanup-" + UUID.randomUUID()
        val store = DemoLibraryStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        try {
            val repository = store.repository()
            val saved = LocalCoreApi(repository).recordExperience(dinner)
            val source = requireNotNull(saved.source)
            val record = requireNotNull(saved.recordId)
            val target = DeleteTarget(DeleteTargetType.SOURCE, source.sourceId)
            val impact = DeletionImpact(mapOf("source_version" to 1), mapOf("record" to 1),
                mapOf("job" to 1), emptyMap(), listOf(record))
            val task = CoreTask("cleanup", null, TaskKind.DELETE_CLEANUP, 2, phase = "isolated")
            val key = OperationKey("android", "delete-source")
            val scope = setOf(DeletionObject("record_source", source.sourceId, source.sourceVersion, record))
            val receipt = StoredReceipt("test-digest", ApiReceipt(true, jobId = task.id, libraryRevision = 2,
                deletion = DeletionResult(target, impact, CleanupState.PENDING)), scope)
            repository.write { tx ->
                tx.putTask(task)
                tx.putReceipt(key, receipt)
                tx.advanceRevision()
            }

            val reopened = store.repository()
            reopened.read { tx ->
                assertEquals(2L, tx.revision)
                assertEquals(task, tx.task(task.id))
                assertEquals(receipt, tx.receipt(key))
            }
            assertEquals(saved, LocalCoreApi(reopened).recordExperience(dinner))
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

    @Test fun previousEvidenceResolvesTheStoredSourceAndPreservesCodePointOffsets() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "evidence-upgrade-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val oldRecords = """[{"id":"r1","rawText":"🍜晚餐","suggestions":[{"id":"s1","evidence":[
                {"id":"e1","quote":"🍜晚","sourceRecordId":"r1"},
                {"id":"e2","quote":"不在原文","sourceRecordId":"r1"}
            ]}]}]"""
            val oldState = """{"revision":1,"sources":[{"sourceId":"original-uuid","sourceVersion":1,
                "recordId":"r1","text":"🍜晚餐","captureMethod":"app","capturedAt":"2026-11-16 20:00"}]}"""
            assertTrue(preferences.edit().putString("records", oldRecords).putString("core_state", oldState).commit())
            val api = LocalCoreApi(DemoLibraryStore(preferences).repository())
            assertTrue(api.updateRecordTitle("r1", 1, "旧记录", "persist-migration").success)
            val reopened = LocalCoreApi(DemoLibraryStore(preferences).repository())
            val evidence = requireNotNull(reopened.getRecord("r1")).suggestions.single().evidence
            assertEquals("original-uuid", evidence[0].sourceRef.sourceId)
            assertEquals(2, evidence[0].end)
            assertEquals("🍜晚餐", reopened.getRecordDetail("r1", evidence[0].sourceRef)?.sources?.single()?.text)
            assertNotNull(evidence[1].unavailableReason)
            assertNull(reopened.getRecordDetail("r1", evidence[1].sourceRef))
        } finally {
            context.deleteSharedPreferences(name)
        }
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
