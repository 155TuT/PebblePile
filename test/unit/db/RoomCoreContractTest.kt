package com.pebblepile.android.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.service.CaptureRequest
import com.pebblepile.android.service.CoreApi
import com.pebblepile.android.service.LocalCoreApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #7 acceptance: Room-backed CoreRepository must reproduce the #6 contract and survive
 * close/reopen. Uses Robolectric so Room's SQLite runs on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomCoreContractTest {

    private val e01 = "今天周一加班到八点半，晚饭预算二十元。我吃了十八元的热汤面，十分钟就吃上了，不用洗锅。"
    private val e02 = "今天周三又加班了，晚饭还是想控制在二十元以内。我吃了十六元的盖饭，不用准备食材，也不用洗锅，省事让我很满意。"
    private val c01 = "以后只在工作日加班时，优先给我推荐省事的晚饭。"

    private fun newRepo(failPoint: (() -> Unit)? = null): RoomCoreRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PebbleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return RoomCoreRepository(db, failPoint)
    }

    private fun core(repo: RoomCoreRepository, clientId: String = "android-demo"): CoreApi {
        val ids = AtomicInteger()
        return LocalCoreApi(
            repository = repo,
            clientId = clientId,
            clock = { "2026-11-16 20:30" },
            newId = { "id-${ids.incrementAndGet()}" },
        )
    }

    @Test
    fun step1SaveReturnsReceiptsSourceAndTask() = runBlocking {
        val repo = newRepo()
        val api = core(repo)
        val receipt = api.recordExperience(e01, "app_home_record", "save-dinner")
        assertTrue(receipt.success)
        assertEquals(1L, receipt.revision!!)
        assertEquals(1L, receipt.libraryRevision!!)
        assertEquals(1L, receipt.source!!.sourceVersion)
        assertNotNull(receipt.jobId)
        val detail = api.getRecordDetail(receipt.recordId!!)
        assertNotNull(detail)
        assertEquals(e01, detail!!.record.rawText)
        assertEquals(1, detail.sources.size)
        assertEquals(listOf(receipt.jobId), detail.jobIds)
    }

    @Test
    fun steps1To3DuplicateOperationReplaysOriginalReceiptWithoutExtraRows() = runBlocking {
        val repo = newRepo()
        val api = core(repo)
        val first = api.recordExperience(e01, "app_home_record", "save-dinner")
        api.updateRecordTitle("id-1", first.revision!!, "加班晚餐", "rename-dinner")
        val replay = api.recordExperience(e01, "app_home_record", "save-dinner")
        assertEquals(first, replay)
        assertEquals(1L, replay.revision!!)
        assertEquals(2L, api.queryRecords().libraryRevision)
        val detail = api.getRecordDetail("id-1")!!
        assertEquals(1, detail.sources.size)
        assertEquals(1, detail.jobIds.size)
        assertEquals(1, api.queryRecords().records.size)
        assertNotNull(repo.read { it.receipt(OperationKey("android-demo", "save-dinner")) })
    }

    @Test
    fun step4SameOperationDifferentPayloadIsRejected() = runBlocking {
        val repo = newRepo()
        val api = core(repo)
        api.recordExperience(e01, "app_home_record", "save-dinner")
        val rejected = api.recordExperience(e02, "app_home_record", "save-dinner")
        assertFalse(rejected.success)
        assertEquals("INVALID_INPUT", rejected.code)
        assertEquals("OPERATION_ID_REUSED", rejected.reason)
        assertEquals(1, api.queryRecords().records.size)
    }

    @Test
    fun step5StaleRevisionReturnsConflict() = runBlocking {
        val repo = newRepo()
        val api = core(repo)
        val saved = api.recordExperience(e01, "app_home_record", "save-dinner")
        api.updateRecordTitle("id-1", saved.revision!!, "加班晚餐", "rename-dinner")
        val conflict = api.reviseMemory("id-1", 1L, c01, CorrectionScope.FUTURE, "correct-dinner-stale")
        assertFalse(conflict.success)
        assertEquals("REVISION_CONFLICT", conflict.code)
        assertEquals("review_current_revision", conflict.nextAction)
        assertEquals(2L, conflict.currentRevision!!)
        assertEquals("加班晚餐", conflict.currentRecord!!.title)
    }

    @Test
    fun step6ReadBackByExactSourceVersion() = runBlocking {
        val repo = newRepo()
        val api = core(repo)
        val saved = api.recordExperience(e01, "app_home_record", "save-dinner")
        api.updateRecordTitle("id-1", saved.revision!!, "加班晚餐", "rename-dinner")
        val detail = api.getRecordDetail("id-1", saved.source)
        assertNotNull(detail)
        assertEquals("加班晚餐", detail!!.record.title)
        assertEquals(e01, detail.sources.single().text)
        assertEquals(listOf(saved.jobId), detail.jobIds)
        assertNull(api.getRecordDetail("id-1", saved.source!!.copy(sourceVersion = 99)))
    }

    @Test
    fun closeAndReopenFindsRecordSourceTaskAndReceipt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = java.io.File(context.cacheDir, "reopen-test.db")
        dbFile.delete()
        val db1 = Room.databaseBuilder(context, PebbleDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries().build()
        val repo1 = RoomCoreRepository(db1)
        val ids = AtomicInteger()
        val api1 = LocalCoreApi(repo1, clientId = "android-demo", clock = { "2026-11-16 20:30" },
            newId = { "id-${ids.incrementAndGet()}" })
        val saved = api1.recordExperience(e01, "app_home_record", "save-dinner")
        repo1.close()

        val db2 = Room.databaseBuilder(context, PebbleDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries().build()
        val repo2 = RoomCoreRepository(db2)
        val api2 = LocalCoreApi(repo2, clientId = "android-demo", clock = { "2026-11-16 20:30" },
            newId = { "id-${ids.incrementAndGet()}" })
        val detail = api2.getRecordDetail(saved.recordId!!, saved.source)
        assertNotNull(detail)
        assertEquals(e01, detail!!.sources.single().text)
        assertNotNull(api2.getTask(saved.jobId!!))
        assertEquals(saved, repo2.read { it.receipt(OperationKey("android-demo", "save-dinner"))?.receipt })
        assertEquals(saved, api2.recordExperience(e01, "app_home_record", "save-dinner"))
        repo2.close()
    }

    @Test
    fun writeFailureAfterSourceRollsBackEverything() = runBlocking {
        var armed = false
        val repo = newRepo(failPoint = { if (armed) throw RepositoryWriteException() })
        val api = core(repo)
        armed = true
        val failed = api.recordExperience(e01, "app_home_record", "save-dinner")
        assertFalse(failed.success)
        assertTrue(failed.retryable)
        assertEquals(0L, repo.read { it.revision })
        assertTrue(repo.read { it.records().isEmpty() })
        assertTrue(repo.read { it.sources("id-1").isEmpty() })
        assertNull(repo.read { it.receipt(OperationKey("android-demo", "save-dinner")) })
    }

    @Test
    fun throwInWriteRollsBackAndThrows() {
        val repo = newRepo()
        assertThrows(RuntimeException::class.java) {
            repo.write { tx ->
                tx.putRecord(com.pebblepile.android.model.PebbleRecord("rollback", e01, "2026-11-16 20:30", "test"))
                throw RuntimeException("boom")
            }
        }
        assertEquals(0, repo.read { it.records().size })
        assertEquals(0L, repo.read { it.revision })
    }

    @Test
    fun concurrentDuplicateOperationCommitsOnce() = runBlocking {
        val repo = newRepo()
        val api = core(repo)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val latch = CountDownLatch(1)
            fun submit() = workers.submit<ApiReceipt> {
                latch.await()
                api.recordExperience(CaptureRequest(e01, "app_home_record", "save-dinner"))
            }
            val first = submit()
            val second = submit()
            latch.countDown()
            val receipt = first.get()
            assertTrue(receipt.success)
            assertEquals(receipt, second.get())
            val detail = requireNotNull(api.getRecordDetail(requireNotNull(receipt.recordId)))
            assertEquals(listOf(receipt.source), detail.sources.map { it.ref })
            assertEquals(listOf(receipt.jobId), detail.jobIds)
        } finally {
            workers.shutdownNow()
        }
        assertEquals(1, api.queryRecords().records.size)
        assertEquals(1L, api.queryRecords().libraryRevision)
    }

    @Test
    fun historicalSourceVersionsAreAllKept() = runBlocking {
        val repo = newRepo()
        val api = core(repo)
        val first = api.recordExperience(e01, "app_home_record", "op-first")
        val second = api.recordExperience(e02, "android_share", "op-share",
            recordId = first.recordId, baseRevision = first.revision)
        assertTrue(second.success)
        val detail = api.getRecordDetail(first.recordId!!)!!
        assertEquals(setOf(first.source, second.source), detail.sources.map { it.ref }.toSet())
        assertEquals(e02, api.getRecordDetail(first.recordId, second.source)!!.sources.single().text)
    }

    @Test
    fun timelineOrdersByInstantAcrossTimeZones() = runBlocking {
        val api = core(newRepo())
        val latest = api.recordExperience(CaptureRequest(e01, "app_home_record", "save-latest",
            capturedAt = "2026-11-16T16:00:00Z"))
        val earliest = api.recordExperience(CaptureRequest(e02, "android_share", "save-earliest",
            capturedAt = "2026-11-16T21:00:00+08:00"))
        assertTrue(latest.success)
        assertTrue(earliest.success)
        assertEquals(listOf(latest.recordId, earliest.recordId), api.queryRecords().records.map { it.id })
    }
}
