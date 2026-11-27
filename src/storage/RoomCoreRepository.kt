package com.pebblepile.android.db

import android.database.sqlite.SQLiteException
import java.util.concurrent.Callable
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

/** Executes each CoreAPI callback against one SQLite transaction and one library. */
class RoomCoreRepository(
    private val database: PebbleDatabase,
    private val failPoint: (() -> Unit)? = null,
) : CoreRepository {

    private val scheduler = RoomTaskStore(database)

    override fun <T> read(block: (CoreReader) -> T): T =
        database.runInTransaction(Callable { block(RoomWriter()) })

    override fun <T> write(block: (CoreWriter) -> T): T = try {
        database.runInTransaction(Callable { block(RoomWriter()) })
    } catch (error: SQLiteException) {
        throw RepositoryWriteException().apply { initCause(error) }
    }

    fun close() = database.close()

    private inner class RoomWriter : CoreWriter {
        override val revision: Long
            get() = database.libraryDao().get()?.revision ?: 0

        override val futureCondition: ConditionSetting?
            get() = DbJson.decode(database.libraryDao().get()?.futureConditionJson)

        override val deletion = RoomDeletionWriter(database, ::putTask)

        override fun record(id: String): PebbleRecord? =
            database.recordDao().visible(id)?.let { DbJson.decode(it.recordJson) }

        override fun records(): List<PebbleRecord> =
            database.recordDao().visibleRecords().mapNotNull { DbJson.decode(it.recordJson) }

        override fun sources(recordId: String): List<CapturedSource> {
            val record = record(recordId) ?: return emptyList()
            val owned = database.sourceDao().visibleSources(recordId).map { row ->
                checkNotNull(DbJson.decode<CapturedSource>(row.sourceJson))
            }
            val references = record.tags.mapNotNull { it.sourceRef } +
                (record.suggestions + record.dinnerDecisions.orEmpty().flatMap { it.suggestions })
                    .flatMap { suggestion -> suggestion.evidence.map { it.sourceRef } }
            val external = (references.toSet() - owned.map { it.ref }.toSet()).mapNotNull { ref ->
                val link = DeletionObject("record_source", ref.sourceId, ref.sourceVersion, recordId)
                val version = DeletionObject("source_version", ref.sourceId, ref.sourceVersion)
                if (deletion.isDeleted(link) || deletion.isDeleted(version)) return@mapNotNull null
                database.sourceDao().get(ref.sourceId, ref.sourceVersion)?.let { DbJson.decode<CapturedSource>(it.sourceJson) }
            }
            return owned + external
        }

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
            // Control receipts may change phase; preserve the last committed scheduler payload.
            if (scheduler.onBusinessTaskWritten(task)) return
            task.phase?.let { phase ->
                database.checkpointDao().put(
                    TaskCheckpointEntity(
                        taskId = task.id,
                        stage = phase,
                        checkpointJson = DbJson.encode(mapOf("phase" to phase, "waitReason" to task.waitReason)),
                        updatedAt = java.time.Instant.now().toString(),
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
            val saved = receipt.receipt
            if (saved.success && saved.recordId != null && saved.jobId != null && saved.source != null) {
                database.sourceDao().enableOrganizing(saved.recordId, saved.source.sourceId, saved.source.sourceVersion)
            }
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
}
