package com.pebblepile.android.service

import com.pebblepile.android.db.SnapshotCoreRepository
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.CleanupState
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.DeleteRequest
import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeleteTargetType
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskKind
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.CoreReader
import com.pebblepile.android.repository.CoreRepository
import com.pebblepile.android.repository.CoreWriter
import com.pebblepile.android.repository.DeletionAuthority
import com.pebblepile.android.repository.DeletionItem
import com.pebblepile.android.repository.DeletionJobAccess
import com.pebblepile.android.repository.DeletionLink
import com.pebblepile.android.repository.DeletionLinkKind
import com.pebblepile.android.repository.DeletionLinkKind.ACTIVE_JOB
import com.pebblepile.android.repository.DeletionLinkKind.DERIVED
import com.pebblepile.android.repository.DeletionLinkKind.RECORD_VIEW
import com.pebblepile.android.repository.DeletionLinkKind.REMOVE_WITH_INPUT
import com.pebblepile.android.repository.DeletionLinkKind.SHARED_ATTACHMENT
import com.pebblepile.android.repository.DeletionObject
import com.pebblepile.android.repository.DeletionPermission
import com.pebblepile.android.repository.DeletionPlan
import com.pebblepile.android.repository.DeletionWriter
import com.pebblepile.android.repository.RepositoryWriteException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionContractTest {
    @Test
    fun deletingSharedSourceIsolatesAllVersionsButPreservesOtherEvidenceAndAttachment() {
        val storage = TestRepository()
        val core: CoreApi = LocalCoreApi(storage)
        val preview = core.previewDeleteMemory(sourceTarget).deletePreview!!
        assertEquals(2, preview.impact.remove["source_version"])
        assertEquals(1, preview.impact.remove["record_source"])
        assertEquals(listOf("a", "b"), preview.impact.affectedRecordIds)
        assertEquals(2, preview.impact.invalidate["knowledge_item"])
        assertEquals(1, preview.impact.rebuild["knowledge_item"])
        assertEquals(1, preview.impact.cancel["job"])
        assertNull(preview.impact.remove["attachment"])
        assertEquals(0L, core.queryRecords().libraryRevision)

        val request = DeleteRequest(sourceTarget, preview.previewId, preview.baseRevision, "delete-b-source")
        val receipt = core.deleteMemory(request)
        assertTrue(receipt.success)
        assertEquals(preview.impact, receipt.deletion!!.impact)
        assertEquals(CleanupState.PENDING, receipt.deletion.cleanupState)
        assertEquals(1L, receipt.libraryRevision)
        assertNull(core.getRecordDetail("b", SourceRef("b-source", 1)))
        assertNull(core.getRecordDetail("b", SourceRef("b-source", 2)))
        assertTrue(core.getRecordDetail("b")!!.sources.isEmpty())
        assertEquals("", core.getRecordDetail("b")!!.record.rawText)
        assertEquals(2L, core.getRecordDetail("a")!!.record.revision)
        assertEquals(TaskState.CANCELLED, core.getTask("produce")!!.state)
        assertEquals(emptySet<TaskAction>(), core.getTask(receipt.jobId!!)!!.allowedActions)
        assertFalse(core.controlTask(receipt.jobId, 1, TaskAction.CANCEL, "cancel-cleanup").success)

        storage.finishCleanup(receipt.jobId)
        val reopened: CoreApi = LocalCoreApi(storage)
        assertEquals(TaskState.COMPLETED, reopened.getTask(receipt.jobId)!!.state)
        assertEquals(receipt, reopened.deleteMemory(request))
        assertEquals("OPERATION_ID_REUSED", reopened.deleteMemory(request.copy(baseRevision = 1)).reason)
        assertEquals("TARGET_DELETED", reopened.previewDeleteMemory(sourceTarget).reason)
        assertEquals("TARGET_DELETED", reopened.deleteMemory(request.copy(operationId = "delete-again")).reason)

        storage.denied = setOf(recordA)
        assertEquals("FORBIDDEN", reopened.deleteMemory(request).code)
        assertNull(reopened.getTask(receipt.jobId))
    }

    @Test
    fun previewRejectsChangedPlanWrongClientAndRevokedScope() {
        val storage = TestRepository()
        val core = LocalCoreApi(storage)
        val preview = core.previewDeleteMemory(sourceTarget).deletePreview!!
        val request = DeleteRequest(sourceTarget, preview.previewId, preview.baseRevision, "delete")
        val other = LocalCoreApi(storage, clientId = "other")
        assertEquals("REVISION_CONFLICT", other.deleteMemory(request).code)
        assertEquals("REVISION_CONFLICT", core.deleteMemory(request.copy(previewId = "tampered")).code)

        storage.jobRevision = 2
        val changed = core.deleteMemory(request)
        assertEquals("REVISION_CONFLICT", changed.code)
        assertEquals("preview_again", changed.nextAction)
        assertEquals(0L, changed.currentRevision)
        assertEquals(0L, core.queryRecords().libraryRevision)

        storage.denied = setOf(recordA)
        assertEquals("FORBIDDEN", core.deleteMemory(request).code)
        assertEquals("FORBIDDEN", core.previewDeleteMemory(sourceTarget).code)
        assertNotNull(core.getRecordDetail("b", SourceRef("b-source", 1)))
    }

    @Test
    fun failureRollsBackIsolationAndSameOperationCanRetry() {
        val storage = TestRepository()
        val core = LocalCoreApi(storage)
        val target = DeleteTarget(DeleteTargetType.RECORD, "b")
        val preview = core.previewDeleteMemory(target).deletePreview!!
        assertEquals(1, preview.impact.remove["record"])
        val request = DeleteRequest(target, preview.previewId, preview.baseRevision, "delete-record")
        storage.failCleanupRegistration = true
        assertTrue(core.deleteMemory(request).retryable)
        assertNotNull(core.getRecordDetail("b"))
        assertEquals(TaskState.QUEUED, core.getTask("produce")!!.state)
        assertEquals(0L, core.queryRecords().libraryRevision)

        storage.failCleanupRegistration = false
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<ApiReceipt> { core.deleteMemory(request) }
            val second = workers.submit<ApiReceipt> { core.deleteMemory(request) }
            val committed = first.get(5, TimeUnit.SECONDS)
            assertTrue(committed.success)
            assertEquals(committed, second.get(5, TimeUnit.SECONDS))
        } finally {
            workers.shutdownNow()
        }
        assertEquals(1L, core.queryRecords().libraryRevision)
        assertNull(core.getRecordDetail("b"))
        assertNull(core.queryContext("b"))
        assertEquals(listOf("a"), core.queryRecords().records.map { it.id })
    }

    @Test
    fun unlinkOnlyChangesReferencingRecordAndDeletingKnowledgeKeepsSources() {
        val storage = TestRepository()
        val core = LocalCoreApi(storage)
        val oldPreview = core.previewDeleteMemory(sourceTarget).deletePreview!!
        val reference = SourceRef("b-source", 1)
        val unlinked = core.removeSourceReference("a", 1, reference, "unlink")
        assertTrue(unlinked.success)
        assertEquals(2L, unlinked.revision)
        assertEquals(unlinked, core.removeSourceReference("a", 1, reference, "unlink"))
        assertNotNull(core.getRecordDetail("b", reference))
        assertEquals("REVISION_CONFLICT", core.deleteMemory(
            DeleteRequest(sourceTarget, oldPreview.previewId, oldPreview.baseRevision, "old")
        ).code)

        val knowledge = DeleteTarget(DeleteTargetType.KNOWLEDGE_ITEM, "independent")
        val preview = core.previewDeleteMemory(knowledge).deletePreview!!
        assertEquals(mapOf("knowledge_item" to 1), preview.impact.remove)
        storage.deferCleanup = false
        val removed = core.deleteMemory(DeleteRequest(knowledge, preview.previewId, preview.baseRevision, "delete-knowledge"))
        assertEquals(CleanupState.COMPLETE, removed.deletion!!.cleanupState)
        assertNull(removed.jobId)
        assertNotNull(core.getRecordDetail("b", reference))
    }

    @Test
    fun storageWithoutDeletionCapabilityReportsUnavailable() {
        val core = LocalCoreApi(SnapshotCoreRepository())
        val receipt = core.previewDeleteMemory(sourceTarget)
        assertFalse(receipt.success)
        assertEquals("DELETION_STORAGE_UNAVAILABLE", receipt.reason)
        assertEquals(0L, core.queryRecords().libraryRevision)
    }

    /** Graph and transaction state for the tests. Production adapters provide their own reads/writes. */
    private class TestRepository : CoreRepository {
        private val base = SnapshotCoreRepository()
        private var removed = emptySet<DeletionObject>()
        private var cleanup = emptyMap<String, DeletionJobAccess>()
        var denied = emptySet<DeletionObject>()
        var jobRevision = 1L
        var failCleanupRegistration = false
        var deferCleanup = true

        init {
            base.write { tx ->
                for (id in listOf("a", "b")) tx.putRecord(PebbleRecord(id, id, "2026-11-21 15:00", "test"))
                for (version in 1L..2L) tx.putSource(CapturedSource(SourceRef("b-source", version), "b", "b", "test", ""))
                tx.putTask(CoreTask("produce", "b", TaskKind.ORGANIZE, 1))
            }
        }

        override fun <T> read(block: (CoreReader) -> T): T = synchronized(this) {
            base.read { block(Tx(it as CoreWriter)) }
        }

        override fun <T> write(block: (CoreWriter) -> T): T = synchronized(this) {
            lateinit var committed: Tx
            val result = base.write { tx -> block(Tx(tx).also { committed = it }) }
            removed = committed.markers
            cleanup = committed.jobs
            result
        }

        fun finishCleanup(jobId: String) {
            base.write { tx -> tx.putTask(tx.task(jobId)!!.copy(state = TaskState.COMPLETED, revision = 2)) }
        }

        private inner class Tx(private val core: CoreWriter) : CoreWriter by core, DeletionWriter {
            var markers = removed
            var jobs = cleanup
            override val deletion get() = this
            override fun record(id: String) = core.record(id)?.takeUnless { isDeleted(DeletionObject("record", id)) }
            override fun records() = core.records().mapNotNull { record(it.id) }
            override fun sources(recordId: String) = core.sources(recordId).filterNot {
                isDeleted(DeletionObject("source_version", it.ref.sourceId, it.ref.sourceVersion))
            }

            override fun authority(clientId: String) = DeletionAuthority("local", "lib", "current", "owner", 1)

            override fun allows(
                authority: DeletionAuthority, permission: DeletionPermission, objects: Set<DeletionObject>
            ) = objects.none { it in denied }

            override fun item(ref: DeletionObject): DeletionItem? = if (isDeleted(ref)) null else when (ref.type) {
                "record" -> record(ref.id)?.let { DeletionItem(ref, it.revision) }
                "source" -> ref.takeIf { it == sourceB }?.let { DeletionItem(it, 1) }
                "knowledge_item" -> ref.takeIf { it == independent }?.let { DeletionItem(it, 1) }
                else -> null
            }

            override fun isDeleted(ref: DeletionObject) = ref in markers

            override fun dependents(ref: DeletionObject): List<DeletionLink> {
                fun link(to: DeletionObject, kind: DeletionLinkKind, revision: Long = 1) =
                    DeletionLink(DeletionItem(to, revision), kind)
                return when (ref) {
                    recordA -> listOf(link(reference, REMOVE_WITH_INPUT))
                    recordB -> listOf(link(sourceB, REMOVE_WITH_INPUT))
                    sourceB -> listOf(link(version1, REMOVE_WITH_INPUT), link(version2, REMOVE_WITH_INPUT))
                    version1 -> listOf(
                        link(reference, REMOVE_WITH_INPUT), link(independent, DERIVED), link(dependent, DERIVED),
                        link(attachment, SHARED_ATTACHMENT), link(job, ACTIVE_JOB, jobRevision)
                    )
                    version2 -> listOf(link(recordB, RECORD_VIEW, core.record("b")!!.revision))
                    reference -> listOf(link(recordA, RECORD_VIEW, core.record("a")!!.revision))
                    else -> emptyList()
                }.filterNot { isDeleted(it.item.ref) }
            }

            override fun supportGroups(ref: DeletionObject): List<Set<DeletionObject>> = when (ref) {
                independent, attachment -> listOf(setOf(version1), setOf(sourceA))
                dependent -> listOf(setOf(version1, sourceA))
                else -> emptyList()
            }

            override fun sourceReference(recordId: String, source: SourceRef): DeletionItem? =
                if (recordId == "a" && source == SourceRef("b-source", 1) && !isDeleted(reference)) {
                    DeletionItem(reference, 1)
                } else null

            override fun cleanupAccess(jobId: String) = jobs[jobId]

            override fun isolate(plan: DeletionPlan, deletionRevision: Long): Boolean {
                markers = markers + plan.remove.map { it.ref }
                for (item in plan.invalidate.filter { it.ref.type == "record" }) {
                    val record = core.record(item.ref.id)!!
                    val text = if (item.ref == recordB && isDeleted(version1)) "" else record.rawText
                    core.putRecord(record.copy(revision = record.revision + 1, rawText = text))
                }
                for (item in plan.cancel) {
                    val task = core.task(item.ref.id)!!
                    core.putTask(task.copy(state = TaskState.CANCELLED, revision = task.revision + 1))
                }
                return deferCleanup
            }

            override fun putCleanupTask(task: CoreTask, plan: DeletionPlan, access: DeletionJobAccess) {
                if (failCleanupRegistration) throw RepositoryWriteException()
                core.putTask(task)
                jobs = jobs + (task.id to access)
            }
        }
    }

    private companion object {
        val sourceTarget = DeleteTarget(DeleteTargetType.SOURCE, "b-source")
        val recordA = DeletionObject("record", "a")
        val recordB = DeletionObject("record", "b")
        val sourceA = DeletionObject("source", "a-source")
        val sourceB = DeletionObject("source", "b-source")
        val version1 = DeletionObject("source_version", "b-source", 1)
        val version2 = DeletionObject("source_version", "b-source", 2)
        val reference = DeletionObject("record_source", "b-source", 1, "a")
        val independent = DeletionObject("knowledge_item", "independent")
        val dependent = DeletionObject("knowledge_item", "dependent")
        val attachment = DeletionObject("attachment", "shared")
        val job = DeletionObject("job", "produce")
    }
}
