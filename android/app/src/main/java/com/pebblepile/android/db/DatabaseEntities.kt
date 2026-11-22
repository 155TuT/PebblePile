package com.pebblepile.android.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One row per library. A RoomCoreRepository instance is bound to one library (the default row). */
@Entity(tableName = "libraries")
data class LibraryEntity(
    @PrimaryKey val id: String = DEFAULT_ID,
    val revision: Long = 0,
    @ColumnInfo(name = "future_condition_json") val futureConditionJson: String? = null,
) {
    companion object { const val DEFAULT_ID = "default" }
}

/** Full PebbleRecord is stored as JSON to guarantee byte-for-byte field replay; columns support queries. */
@Entity(
    tableName = "records",
    indices = [Index("captured_at"), Index(value = ["status", "captured_at"])],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "record_json") val recordJson: String,
    @ColumnInfo(name = "captured_at") val capturedAt: String,
    val status: String,
    val title: String,
    val revision: Long,
)

/** Exact source version. Composite key keeps every historical version of a source. */
@Entity(
    tableName = "source_versions",
    primaryKeys = ["source_id", "source_version"],
    indices = [Index("record_id")],
)
data class SourceVersionEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "source_version") val sourceVersion: Long,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "source_json") val sourceJson: String,
    @ColumnInfo(name = "captured_at") val capturedAt: String,
    val text: String,
)

@Entity(
    tableName = "tasks",
    indices = [Index("record_id"), Index(value = ["kind", "state"])],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_json") val taskJson: String,
    @ColumnInfo(name = "record_id") val recordId: String?,
    val kind: String,
    val state: String,
    val revision: Long,
    @ColumnInfo(name = "input_revision") val inputRevision: Long,
)

/** Original successful receipt, keyed by (clientId, operationId) inside this library. */
@Entity(tableName = "operation_receipts", primaryKeys = ["client_id", "operation_id"])
data class OperationReceiptEntity(
    @ColumnInfo(name = "client_id") val clientId: String,
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "request_hash") val requestHash: String,
    @ColumnInfo(name = "receipt_json") val receiptJson: String,
    @ColumnInfo(name = "deletion_scope_json") val deletionScopeJson: String,
    @ColumnInfo(name = "committed_revision") val committedRevision: Long,
)

/** Tombstone for an isolated deletion object. */
@Entity(tableName = "deletion_markers")
data class DeletionMarkerEntity(
    @PrimaryKey val key: String,
    val type: String,
    val id: String,
    val version: Long?,
    @ColumnInfo(name = "related_id") val relatedId: String?,
    @ColumnInfo(name = "deleted_revision") val deletedRevision: Long,
)

/** Persisted cleanup job plan and authorization scope for DELETE_CLEANUP tasks. */
@Entity(tableName = "deletion_jobs")
data class DeletionJobEntity(
    @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "plan_json") val planJson: String,
    @ColumnInfo(name = "access_json") val accessJson: String,
)

/** Most recent durable checkpoint for a task (job_steps.checkpoint in the database design). */
@Entity(tableName = "task_checkpoints")
data class TaskCheckpointEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    val stage: String,
    @ColumnInfo(name = "checkpoint_json") val checkpointJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)
