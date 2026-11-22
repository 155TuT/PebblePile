package com.pebblepile.android.service

import com.pebblepile.android.db.DemoLibrary
import com.pebblepile.android.db.SnapshotCoreRepository
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.RepositoryWriteException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the shared issue #10 contract suite against the in-memory snapshot repository, plus the
 * failure and rollback cases that are specific to this implementation.
 */
class LocalCoreApiContractTest : CoreApiContractSuite() {

    override fun createRepository() = SnapshotCoreRepository()

    @Test
    fun snapshotRepositoryRollsBackWhenWriteThrows() {
        val repository = SnapshotCoreRepository()

        assertThrows(RuntimeException::class.java) {
            repository.write<Unit> { tx ->
                tx.putRecord(
                    PebbleRecord(
                        "record-rollback", Issue10Fixtures.E01, "2026-11-16 20:30", "app_home_record"
                    )
                )
                throw RuntimeException("boom")
            }
        }

        assertEquals(0, repository.read { it.records().size })
        assertEquals(0L, repository.read { it.revision })
    }

    @Test
    fun failedCommitCanRetryTheSameOperationWithoutPartialWrites() = runBlocking {
        var attempts = 0
        val repository = SnapshotCoreRepository(DemoLibrary()) {
            if (++attempts == 1) throw RepositoryWriteException()
        }
        val ids = AtomicInteger()
        val core: CoreApi = LocalCoreApi(
            repository,
            clientId = "issue10-test",
            newId = { "id-${ids.incrementAndGet()}" }
        )
        val key = OperationKey("issue10-test", "retry")

        val failed = core.recordExperience(Issue10Fixtures.E01, "app_home_record", key.operationId)
        assertFalse(failed.success)
        assertTrue(failed.retryable)
        repository.read { tx ->
            assertEquals(0L, tx.revision)
            assertTrue(tx.records().isEmpty())
            assertTrue(tx.sources("id-1").isEmpty())
            assertTrue(tx.tasks("id-1").isEmpty())
            assertNull(tx.receipt(key))
        }

        val saved = core.recordExperience(Issue10Fixtures.E01, "app_home_record", key.operationId)
        assertTrue(saved.success)
        val reopened = LocalCoreApi(repository, clientId = key.clientId)
        assertEquals(saved, reopened.recordExperience(Issue10Fixtures.E01, "app_home_record", key.operationId))
        val detail = reopened.getRecordDetail(saved.recordId!!)!!
        assertEquals(listOf(saved.source), detail.sources.map { it.ref })
        assertEquals(listOf(saved.jobId), detail.jobIds)
        assertEquals(1L, detail.libraryRevision)
        assertEquals(saved, repository.read { it.receipt(key)?.receipt })
        assertEquals(2, attempts)
    }
}
