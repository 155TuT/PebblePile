package com.pebblepile.android.db

import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.repository.CoreReader
import com.pebblepile.android.repository.CoreRepository
import com.pebblepile.android.repository.CoreWriter
import com.pebblepile.android.repository.DeletionObject
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.repository.StoredReceipt

/**
 * Room-backed [CoreRepository]. One instance is bound to one library (the default library row).
 *
 * - [write] runs the whole callback in one SQLite transaction; any exception rolls back and is
 *   surfaced as [RepositoryWriteException] so the business layer can return a retryable receipt.
 * - [read] serves the callback from a single immutable snapshot, giving a consistent read view.
 *
 * @param failPoint optional hook invoked right after a source is written, used to inject a
 *   "write succeeded but receipt not committed" failure for rollback tests.
 */
class RoomCoreRepository(
    private val database: PebbleDatabase,
    private val failPoint: (() -> Unit)? = null,
) : CoreRepository {

    override fun <T> read(block: (CoreReader) -> T): T = block(snapshotReader())

    override fun <T> write(block: (CoreWriter) -> T): T {
        val supportDb = database.openHelper.writableDatabase
        supportDb.beginTransaction()
        try {
            val tx = RoomWriter()
            val result = block(tx)
            supportDb.setTransactionSuccessful()
            return result
        } catch (e: RepositoryWriteException) {
            throw e
        } catch (e: Throwable) {
            throw RepositoryWriteException().apply { initCause(e) }
        } finally {
            supportDb.endTransaction()
        }
    }

    /** Close the underlying database; reopening a file-backed database restores all state. */
    fun close() = database.close()

    private fun snapshotReader(): CoreReader {
        val library = database.libraryDao().get() ?: LibraryEntity()
        val records = database.recordDao().all().mapNotNull { DbJson.decode<PebbleRecord>(it.recordJson) }
        val sources = database.sourceDao().all().mapNotNull { DbJson.decode<CapturedSource>(it.sourceJson) }
        val tasks = database.taskDao().all().mapNotNull { DbJson.decode<CoreTask>(it.taskJson) }
        val receipts = database.receiptDao().all().associate { entity ->
            OperationKey(entity.clientId, entity.operationId) to StoredReceipt(
                requestHash = entity.requestHash,
                receipt = DbJson.decode<ApiReceipt>(entity.receiptJson) ?: ApiReceipt(false),
                deletionScope = DbJson.decode<Set<DeletionObject>>(entity.deletionScopeJson) ?: emptySet(),
            )
        }
        val deleted = database.deletionDao().markers().map { it.key }.toSet()
        val future = DbJson.decode<ConditionSetting>(library.futureConditionJson)
        return SnapshotReader(library.revision, future, records, sources, tasks, receipts, deleted)
    }

    private inner class RoomWriter : CoreWriter {
        override val revision: Long
            get() = database.libraryDao().get()?.revision ?: 0

        override val futureCondition: ConditionSetting?
            get() = DbJson.decode(database.libraryDao().get()?.futureConditionJson)

        override val deletion = RoomDeletionWriter(database)

        override fun record(id: String): PebbleRecord? =
            database.recordDao().get(id)?.let { DbJson.decode(it.recordJson) }

        override fun records(): List<PebbleRecord> =
            database.recordDao().all().mapNotNull { DbJson.decode(it.recordJson) }

        override fun sources(recordId: String): List<CapturedSource> =
            database.sourceDao().forRecord(recordId).mapNotNull { DbJson.decode(it.sourceJson) }

        override fun task(id: String): CoreTask? =
            database.taskDao().get(id)?.let { DbJson.decode(it.taskJson) }

        override fun tasks(recordId: String): List<CoreTask> =
            database.taskDao().forRecord(recordId).mapNotNull { DbJson.decode(it.taskJson) }

        override fun receipt(key: OperationKey): StoredReceipt? =
            database.receiptDao().get(key.clientId, key.operationId)?.let { entity ->
                StoredReceipt(
                    requestHash = entity.requestHash,
                    receipt = DbJson.decode<ApiReceipt>(entity.receiptJson) ?: ApiReceipt(false),
                    deletionScope = DbJson.decode<Set<DeletionObject>>(entity.deletionScopeJson) ?: emptySet(),
                )
            }

        override fun putRecord(record: PebbleRecord) {
            database.recordDao().put(
                RecordEntity(
                    id = record.id,
                    recordJson = DbJson.encode(record),
                    capturedAt = record.capturedAt,
                    status = record.status,
                    title = record.title,
                    revision = record.revision,
                )
            )
        }

        override fun putSource(source: CapturedSource) {
            database.sourceDao().put(
                SourceVersionEntity(
                    sourceId = source.ref.sourceId,
                    sourceVersion = source.ref.sourceVersion,
                    recordId = source.recordId,
                    sourceJson = DbJson.encode(source),
                    capturedAt = source.capturedAt,
                    text = source.text,
                )
            )
            failPoint?.invoke()
        }

        override fun putTask(task: CoreTask) {
            database.taskDao().put(
                TaskEntity(
                    id = task.id,
                    taskJson = DbJson.encode(task),
                    recordId = task.recordId,
                    kind = task.kind.name,
                    state = task.state.name,
                    revision = task.revision,
                    inputRevision = task.inputRevision,
                )
            )
            // A task's phase is its most recent durable checkpoint (job_steps.checkpoint baseline).
            task.phase?.let { phase ->
                database.checkpointDao().put(
                    TaskCheckpointEntity(
                        taskId = task.id,
                        stage = phase,
                        checkpointJson = DbJson.encode(mapOf("phase" to phase, "waitReason" to task.waitReason)),
                        updatedAt = "",
                    )
                )
            }
        }

        override fun putReceipt(key: OperationKey, receipt: StoredReceipt) {
            database.receiptDao().put(
                OperationReceiptEntity(
                    clientId = key.clientId,
                    operationId = key.operationId,
                    requestHash = receipt.requestHash,
                    receiptJson = DbJson.encode(receipt.receipt),
                    deletionScopeJson = DbJson.encode(receipt.deletionScope),
                    committedRevision = receipt.receipt.libraryRevision ?: revision,
                )
            )
        }

        override fun setFutureCondition(condition: ConditionSetting) {
            val library = database.libraryDao().get() ?: LibraryEntity()
            database.libraryDao().put(library.copy(futureConditionJson = DbJson.encode(condition)))
        }

        override fun advanceRevision(): Long {
            if (database.libraryDao().get() == null) {
                database.libraryDao().put(LibraryEntity(revision = 0))
            }
            database.libraryDao().advance()
            return database.libraryDao().get()?.revision ?: 0
        }
    }

    private class SnapshotReader(
        override val revision: Long,
        override val futureCondition: ConditionSetting?,
        private val records: List<PebbleRecord>,
        private val sources: List<CapturedSource>,
        private val tasks: List<CoreTask>,
        private val receipts: Map<OperationKey, StoredReceipt>,
        private val deleted: Set<String>,
    ) : CoreReader {
        override val deletion = null
        override fun record(id: String) = records.firstOrNull { it.id == id && !isDeleted("record", id) }
        override fun records() = records.filterNot { isDeleted("record", it.id) }
        override fun sources(recordId: String) = sources.filter { source ->
            source.recordId == recordId &&
                !isDeleted("source_version", source.ref.sourceId, source.ref.sourceVersion)
        }
        override fun task(id: String) = tasks.firstOrNull { it.id == id }
        override fun tasks(recordId: String) = tasks.filter { it.recordId == recordId }
        override fun receipt(key: OperationKey) = receipts[key]

        private fun isDeleted(type: String, id: String, version: Long? = null): Boolean =
            DeletionKeys.of(DeletionObject(type, id, version)) in deleted
    }
}
