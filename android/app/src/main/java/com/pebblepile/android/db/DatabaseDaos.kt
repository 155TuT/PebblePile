package com.pebblepile.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LibraryDao {
    @Query("SELECT * FROM libraries WHERE id = :id")
    fun get(id: String = LibraryEntity.DEFAULT_ID): LibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(library: LibraryEntity)

    @Query("UPDATE libraries SET revision = revision + 1 WHERE id = :id")
    fun advance(id: String = LibraryEntity.DEFAULT_ID): Int
}

private const val VISIBLE_RECORDS = "SELECT r.* FROM records r WHERE NOT EXISTS " +
    "(SELECT 1 FROM deletion_markers m WHERE m.type = 'record' AND m.id = r.id)"

@Dao
interface RecordDao {
    @Query(VISIBLE_RECORDS + " AND r.id = :id")
    fun visible(id: String): RecordEntity?

    @Query(VISIBLE_RECORDS + " ORDER BY r.rowid")
    fun visibleRecords(): List<RecordEntity>

    @Query("SELECT * FROM records WHERE id = :id")
    fun get(id: String): RecordEntity?

    @Query("SELECT * FROM records ORDER BY captured_at DESC")
    fun all(): List<RecordEntity>

    @Upsert
    fun put(record: RecordEntity)

    @Query("DELETE FROM records WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface SourceDao {
    @Query("""SELECT s.* FROM source_versions s WHERE s.record_id = :recordId
        AND NOT EXISTS (SELECT 1 FROM deletion_markers m WHERE
            (m.type = 'record' AND m.id = s.record_id) OR
            (m.type = 'source' AND m.id = s.source_id) OR
            (m.type = 'source_version' AND m.id = s.source_id AND m.version = s.source_version))
        ORDER BY s.source_version, s.rowid""")
    fun visibleSources(recordId: String): List<SourceVersionEntity>

    @Query("SELECT * FROM source_versions WHERE record_id = :recordId ORDER BY source_version ASC")
    fun forRecord(recordId: String): List<SourceVersionEntity>

    @Query("SELECT * FROM source_versions")
    fun all(): List<SourceVersionEntity>

    @Query("SELECT * FROM source_versions WHERE source_id = :sourceId AND source_version = :version")
    fun get(sourceId: String, version: Long): SourceVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(source: SourceVersionEntity)

    @Query("DELETE FROM source_versions WHERE source_id = :sourceId AND source_version = :version")
    fun delete(sourceId: String, version: Long)

    @Query("DELETE FROM source_versions WHERE record_id = :recordId")
    fun deleteForRecord(recordId: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE record_id = :recordId")
    fun forRecord(recordId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    fun all(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM operation_receipts WHERE client_id = :clientId AND operation_id = :operationId LIMIT 1")
    fun get(clientId: String, operationId: String): OperationReceiptEntity?

    @Query("SELECT * FROM operation_receipts")
    fun all(): List<OperationReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(receipt: OperationReceiptEntity)
}

@Dao
interface DeletionDao {
    @Query("SELECT * FROM deletion_markers WHERE key = :key")
    fun marker(key: String): DeletionMarkerEntity?

    @Query("SELECT * FROM deletion_markers")
    fun markers(): List<DeletionMarkerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putMarker(marker: DeletionMarkerEntity)

    @Query("SELECT * FROM deletion_jobs WHERE job_id = :jobId LIMIT 1")
    fun job(jobId: String): DeletionJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putJob(job: DeletionJobEntity)
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM task_checkpoints WHERE task_id = :taskId")
    fun get(taskId: String): TaskCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(checkpoint: TaskCheckpointEntity)
}
