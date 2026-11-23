package com.pebblepile.android.db

import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.TaskKind
import com.pebblepile.android.model.TaskState
import com.pebblepile.scheduler.WAITING_RETRY_PENDING
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
    @Query("SELECT r.revision FROM records r WHERE r.id = :id AND NOT EXISTS " +
        "(SELECT 1 FROM deletion_markers m WHERE m.type = 'record' AND m.id = r.id)")
    fun visibleRevision(id: String): Long?

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

    @Query("""INSERT OR REPLACE INTO dinner_publication_counts(record_id, publications)
        VALUES (:recordId, 1 + COALESCE((SELECT publications FROM dinner_publication_counts WHERE record_id = :recordId), 0))""")
    fun countDinnerPublication(recordId: String)

    @Query("DELETE FROM records WHERE id = :id")
    fun delete(id: String)
}

private const val VISIBLE_SOURCES = """SELECT s.* FROM source_versions s WHERE s.record_id = :recordId
    AND NOT EXISTS (SELECT 1 FROM deletion_markers m WHERE
        (m.type = 'record' AND m.id = s.record_id) OR
        (m.type = 'source' AND m.id = s.source_id) OR
        (m.type = 'source_version' AND m.id = s.source_id AND m.version = s.source_version))"""

data class DietInputVersion(val recordId: String, val recordRevision: Long, val sourceId: String, val sourceVersion: Long)

private const val DIET_INPUT_ROWS = """ FROM source_versions s JOIN records r ON r.id = s.record_id
    LEFT JOIN organized_sources o ON o.source_id = s.source_id AND o.source_version = s.source_version
    WHERE (o.source_id IS NOT NULL OR (s.record_id = :recordId AND s.source_id = :sourceId AND s.source_version = :version))
    AND NOT EXISTS (SELECT 1 FROM source_versions newer WHERE newer.source_id = s.source_id AND newer.source_version > s.source_version)
    AND NOT EXISTS (SELECT 1 FROM deletion_markers m WHERE
        (m.type = 'record' AND m.id = s.record_id) OR (m.type = 'source' AND m.id = s.source_id) OR
        (m.type = 'source_version' AND m.id = s.source_id AND m.version = s.source_version) OR
        (m.type = 'record_source' AND m.related_id = :recordId AND m.id = s.source_id AND m.version = s.source_version)) """

@Dao
interface SourceDao {
    @Query("""SELECT r.id AS recordId, CASE WHEN r.id = :recordId THEN r.revision ELSE r.revision -
        COALESCE((SELECT c.publications FROM dinner_publication_counts c WHERE c.record_id = r.id), 0) END AS recordRevision,
        s.source_id AS sourceId, s.source_version AS sourceVersion""" + DIET_INPUT_ROWS + "ORDER BY r.id, s.source_id")
    fun dietVersions(recordId: String, sourceId: String, version: Long): List<DietInputVersion>

    @Query("SELECT s.source_json" + DIET_INPUT_ROWS + "ORDER BY r.id, s.source_id")
    fun dietSources(recordId: String, sourceId: String, version: Long): List<String>

    @Query("SELECT DISTINCT r.record_json" + DIET_INPUT_ROWS)
    fun dietRecords(recordId: String, sourceId: String, version: Long): List<String>

    @Query("""INSERT OR IGNORE INTO organized_sources(source_id, source_version)
        SELECT source_id, source_version FROM source_versions
        WHERE record_id = :recordId AND source_id = :sourceId AND source_version = :version""")
    fun enableOrganizing(recordId: String, sourceId: String, version: Long)

    @Query("SELECT EXISTS (SELECT 1 FROM organized_sources WHERE source_id = :sourceId AND source_version = :version)")
    fun isOrganized(sourceId: String, version: Long): Boolean

    @Query(VISIBLE_SOURCES + " ORDER BY s.rowid DESC LIMIT 1")
    fun latestVisibleCapture(recordId: String): SourceVersionEntity?

    @Query(VISIBLE_SOURCES + " ORDER BY s.source_version, s.rowid")
    fun visibleSources(recordId: String): List<SourceVersionEntity>

    @Query("SELECT * FROM source_versions WHERE record_id = :recordId ORDER BY source_version ASC")
    fun forRecord(recordId: String): List<SourceVersionEntity>

    @Query("SELECT * FROM source_versions")
    fun all(): List<SourceVersionEntity>

    @Query("SELECT * FROM source_versions WHERE source_id = :sourceId AND source_version = :version")
    fun get(sourceId: String, version: Long): SourceVersionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertVersion(source: SourceVersionEntity): Long

    @Transaction
    fun put(source: SourceVersionEntity) {
        if (insertVersion(source) != -1L) return
        val stored = get(source.sourceId, source.sourceVersion)
        if (stored != null && stored.copy(sourceJson = source.sourceJson) == source &&
            DbJson.decode<CapturedSource>(stored.sourceJson) == DbJson.decode<CapturedSource>(source.sourceJson)) return
        throw RepositoryWriteException().apply { initCause(IllegalStateException("相同来源版本对应不同内容")) }
    }

    @Query("DELETE FROM source_versions WHERE source_id = :sourceId AND source_version = :version")
    fun delete(sourceId: String, version: Long)

    @Query("DELETE FROM source_versions WHERE record_id = :recordId")
    fun deleteForRecord(recordId: String)
}

@Dao
interface TaskDao {
    @Query("""SELECT * FROM tasks WHERE
        (kind = 'ORGANIZE' AND (state IN ('QUEUED', 'RUNNING') OR state = 'WAITING'))
        OR (kind = 'DELETE_CLEANUP' AND state IN ('QUEUED', 'RUNNING', 'WAITING'))""")
    fun recoveryCandidates(): List<TaskEntity>

    @Query("SELECT revision FROM tasks WHERE id IN (:ids) ORDER BY id")
    fun observedRevisions(ids: List<String>): kotlinx.coroutines.flow.Flow<List<Long>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE record_id = :recordId")
    fun forRecord(recordId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE kind = 'ORGANIZE' ORDER BY rowid")
    fun organizeTasks(): List<TaskEntity>

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
    @Query("""SELECT EXISTS(SELECT 1 FROM deletion_markers WHERE type = :type AND id = :id
        AND version IS :version AND related_id IS :relatedId)""")
    fun isDeleted(type: String, id: String, version: Long?, relatedId: String?): Boolean

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
    @Query("DELETE FROM task_checkpoints WHERE task_id = :id")
    fun remove(id: String)

    @Query("SELECT * FROM task_checkpoints WHERE task_id = :taskId")
    fun get(taskId: String): TaskCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(checkpoint: TaskCheckpointEntity)
}

@Dao
interface SchedulerDao {
    @Query("""SELECT * FROM scheduler_jobs WHERE (:after IS NULL OR job_id > :after)
        ORDER BY job_id LIMIT 32""")
    fun nextSnapshots(after: String?): List<ScheduledJobEntity>

    @Query("SELECT * FROM scheduler_jobs WHERE job_id = :id")
    fun get(id: String): ScheduledJobEntity?

    @Query("SELECT job_id FROM scheduler_jobs WHERE input_key = :key")
    fun findIdByKey(key: String): String?

    @Upsert
    fun put(snapshot: ScheduledJobEntity)
}

/** Use the persisted business state to distinguish retryable work from a paused task. */
internal fun TaskDao.recoverableJobIds(): List<String> = recoveryCandidates().mapNotNull { row ->
    val task = checkNotNull(DbJson.decode<CoreTask>(row.taskJson))
    task.id.takeIf {
        task.kind == TaskKind.DELETE_CLEANUP || task.state != TaskState.WAITING || task.waitReason == WAITING_RETRY_PENDING
    }
}
