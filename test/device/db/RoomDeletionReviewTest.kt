package com.pebblepile.android.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.DemoCoreApi
import com.pebblepile.android.legacySuggestionFixture
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.DeleteRequest
import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeleteTargetType
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.service.CaptureRequest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDeletionReviewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "room-deletion-${UUID.randomUUID()}"
    private var database = PebbleDatabase.build(context, name)
    private var repository = RoomCoreRepository(database)
    private var core = DemoCoreApi(repository)
    private val ownerRequest = CaptureRequest("@阿叶 晚餐想省事", "app_home_record", "owner")
    private val otherRequest = CaptureRequest("加班晚饭想吃面，省事", "android_share", "other")

    @After fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun reopen() {
        database.close()
        database = PebbleDatabase.build(context, name)
        repository = RoomCoreRepository(database)
        core = DemoCoreApi(repository)
    }

    private fun request(target: DeleteTarget): DeleteRequest {
        val preview = requireNotNull(core.previewDeleteMemory(target).deletePreview)
        return DeleteRequest(target, preview.previewId, preview.baseRevision, "delete")
    }

    private data class Linked(val owner: String, val other: String, val source: SourceRef, val independent: SourceRef)

    private fun linkRecords(): Linked {
        val first = core.recordExperience(ownerRequest)
        val second = core.recordExperience(otherRequest)
        val owner = requireNotNull(first.recordId)
        val other = requireNotNull(second.recordId)
        val source = requireNotNull(first.source)
        val independent = requireNotNull(second.source)
        assertNull(core.getRecordDetail(other, source))
        repository.write { tx ->
            val original = requireNotNull(tx.record(owner))
            val referencing = requireNotNull(tx.record(other))
            val ownerSuggestion = tx.sources(owner).single().legacySuggestionFixture()
            val independentSuggestion = tx.sources(other).single().legacySuggestionFixture()
            tx.putRecord(original.copy(suggestions = listOf(ownerSuggestion)))
            tx.putRecord(referencing.copy(
                tags = referencing.tags + original.tags.first().copy(id = "borrowed-tag"),
                suggestions = listOf(independentSuggestion, ownerSuggestion.copy(id = "borrowed-suggestion")),
            ))
        }
        assertEquals(ownerRequest.text, core.getRecordDetail(other, source)?.sources?.single()?.text)
        return Linked(owner, other, source, independent)
    }

    private fun assertIndependentEvidenceRemains(link: Linked) {
        val record = requireNotNull(core.getRecordDetail(link.other)).record
        assertEquals(otherRequest.text, record.rawText)
        assertTrue(record.tags.none { it.id == "borrowed-tag" })
        assertTrue(record.suggestions.none { it.id == "borrowed-suggestion" })
        assertTrue(record.tags.any { it.sourceRef == link.independent })
        assertTrue(record.suggestions.any { s -> s.evidence.any { it.sourceRef == link.independent } })
        assertNull(core.getRecordDetail(link.other, link.source))
        assertEquals(otherRequest.text, core.getRecordDetail(link.other, link.independent)?.sources?.single()?.text)
    }

    @Test fun deletingSourceRemovesEveryVersionAndReferencedProjectionsAfterReopen() {
        val link = linkRecords()
        val owner = requireNotNull(core.getRecordDetail(link.owner))
        assertEquals(ownerRequest.text, owner.record.title)
        assertEquals(listOf("阿叶"), owner.record.people)
        val secondVersion = link.source.copy(sourceVersion = 2)
        repository.write { it.putSource(owner.sources.single().copy(ref = secondVersion, text = "第二版原话")) }

        val target = DeleteTarget(DeleteTargetType.SOURCE, link.source.sourceId)
        val receipt = core.deleteMemory(request(target))
        assertTrue(receipt.success)
        assertEquals(2, requireNotNull(receipt.deletion).impact.remove["source_version"])
        reopen()
        val after = requireNotNull(core.getRecordDetail(link.owner))
        assertTrue(after.sources.isEmpty())
        assertTrue(after.record.rawText.isEmpty())
        assertFalse(after.record.title.contains("阿叶"))
        assertTrue(after.record.people.isEmpty())
        assertTrue(after.record.tags.isEmpty())
        assertTrue(after.record.suggestions.isEmpty())
        assertNull(core.getRecordDetail(link.owner, secondVersion))
        assertNull(core.recordExperience(ownerRequest).source)
        assertEquals(TaskState.CANCELLED, core.getTask(requireNotNull(core.recordExperience(ownerRequest).jobId))?.state)
        assertIndependentEvidenceRemains(link)
    }

    @Test fun unlinkKeepsOwnerAndSourceDeletionPreservesTheHandwrittenTitle() {
        val link = linkRecords()
        val ownerBefore = requireNotNull(core.getRecordDetail(link.owner))
        val other = requireNotNull(core.getRecordDetail(link.other)).record
        val unlink = core.removeSourceReference(link.other, other.revision, link.source, "unlink")
        assertTrue(unlink.success)
        reopen()
        assertEquals(unlink, core.removeSourceReference(link.other, other.revision, link.source, "unlink"))
        assertIndependentEvidenceRemains(link)
        val ownerAfter = requireNotNull(core.getRecordDetail(link.owner, link.source))
        assertEquals(ownerBefore.record, ownerAfter.record)
        assertEquals(ownerRequest.text, ownerAfter.sources.single().text)

        val title = "手写标题"
        assertTrue(core.updateRecordTitle(link.owner, ownerAfter.record.revision, title, "rename").success)
        assertTrue(core.deleteMemory(request(DeleteTarget(DeleteTargetType.SOURCE, link.source.sourceId))).success)
        assertEquals(title, core.getRecordDetail(link.owner)?.record?.title)
        assertEquals(title, core.updateRecordTitle(link.owner, ownerAfter.record.revision, title, "rename").title)
    }

    @Test fun cleanupRegistrationFailureRollsBackAndReplayAfterRecordDeletionStaysIsolated() = runBlocking {
        val link = linkRecords()
        val owner = requireNotNull(core.getRecordDetail(link.owner)).record
        val renamed = core.updateRecordTitle(link.owner, owner.revision, "晚餐安排", "rename")
        assertTrue(renamed.success)
        val renamedRevision = requireNotNull(renamed.revision)
        val setting = core.reviseMemory(link.owner, renamedRevision,
            "工作日优先省事", CorrectionScope.FUTURE, "preference")
        assertTrue(setting.success)
        val before = requireNotNull(core.getRecordDetail(link.owner))
        assertEquals("工作日优先省事", core.queryContext(link.other)?.futureCondition?.text)
        val target = DeleteTarget(DeleteTargetType.RECORD, link.owner)
        val delete = request(target)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER fail_cleanup BEFORE INSERT ON deletion_jobs
            BEGIN SELECT RAISE(ABORT, 'cleanup registration failed'); END""")
        val failure = try { core.deleteMemory(delete) } finally { sql.execSQL("DROP TRIGGER fail_cleanup") }
        assertEquals("PROCESSING_FAILED", failure.code)
        assertTrue(failure.retryable)
        assertEquals(before, core.getRecordDetail(link.owner))
        assertEquals(before.libraryRevision, core.queryRecords().libraryRevision)
        assertTrue(database.deletionDao().markers().isEmpty())
        assertEquals(ownerRequest.text, core.getRecordDetail(link.other, link.source)?.sources?.single()?.text)
        assertEquals(renamed, core.updateRecordTitle(link.owner, owner.revision, "晚餐安排", "rename"))
        assertNull(database.receiptDao().get("android", delete.operationId))

        val committed = core.deleteMemory(delete)
        assertTrue(committed.success)
        reopen()
        assertEquals(committed, core.deleteMemory(delete))
        assertEquals("TARGET_DELETED", core.previewDeleteMemory(target).reason)
        assertNull(core.getRecordDetail(link.owner))
        assertNull(core.queryContext(link.other)?.futureCondition)
        assertTrue(core.queryRecords().records.none { it.id == link.owner })
        assertIndependentEvidenceRemains(link)
        val replay = core.updateRecordTitle(link.owner, owner.revision, "晚餐安排", "rename")
        assertTrue(replay.success)
        assertNull(replay.title)
        assertEquals(setting, core.reviseMemory(link.owner, renamedRevision,
            "工作日优先省事", CorrectionScope.FUTURE, "preference"))
        assertNull(core.queryContext(link.other)?.futureCondition)
        assertNull(core.recordExperience(ownerRequest).source)
        assertEquals(committed.libraryRevision, core.queryRecords().libraryRevision)
    }
}
