package com.pebblepile.android.db

import android.content.Context
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.TaskState
import com.pebblepile.scheduler.TaskJob
import com.pebblepile.scheduler.TaskStatus
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for PebblePile Issue #7. One instance is bound to one library.
 *
 * WAL and synchronous=FULL are applied on open to match docs/database-design.md §5.1.
 * Room enables foreign_keys=ON by default.
 */
@Database(
    entities = [
        LibraryEntity::class,
        RecordEntity::class,
        DinnerPublicationCount::class,
        SourceVersionEntity::class,
        OrganizedSourceEntity::class,
        TaskEntity::class,
        OperationReceiptEntity::class,
        DeletionMarkerEntity::class,
        DeletionJobEntity::class,
        TaskCheckpointEntity::class,
        ScheduledJobEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class PebbleDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao
    abstract fun recordDao(): RecordDao
    abstract fun sourceDao(): SourceDao
    abstract fun taskDao(): TaskDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun deletionDao(): DeletionDao
    abstract fun schedulerDao(): SchedulerDao
    abstract fun checkpointDao(): CheckpointDao

    companion object {
        private val migrateKeys = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE operation_receipts_v2 (
                    client_id TEXT NOT NULL, operation_id TEXT NOT NULL, request_hash TEXT NOT NULL,
                    receipt_json TEXT NOT NULL, deletion_scope_json TEXT NOT NULL, committed_revision INTEGER NOT NULL,
                    PRIMARY KEY (client_id, operation_id))""")
                // Older builds could store the same operation twice. Keep the latest projection.
                db.execSQL("""INSERT OR REPLACE INTO operation_receipts_v2
                    SELECT client_id, operation_id, request_hash, receipt_json, deletion_scope_json, committed_revision
                    FROM operation_receipts ORDER BY row_id""")
                db.execSQL("DROP TABLE operation_receipts")
                db.execSQL("ALTER TABLE operation_receipts_v2 RENAME TO operation_receipts")
                db.execSQL("""CREATE TABLE deletion_jobs_v2 (
                    job_id TEXT NOT NULL PRIMARY KEY, plan_json TEXT NOT NULL, access_json TEXT NOT NULL)""")
                db.execSQL("""INSERT OR REPLACE INTO deletion_jobs_v2
                    SELECT job_id, plan_json, access_json FROM deletion_jobs ORDER BY rowid""")
                db.execSQL("DROP TABLE deletion_jobs")
                db.execSQL("ALTER TABLE deletion_jobs_v2 RENAME TO deletion_jobs")
            }
        }

        private val addScheduler = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE scheduler_jobs (
                    job_id TEXT NOT NULL PRIMARY KEY, input_key TEXT NOT NULL, snapshot_json TEXT NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX index_scheduler_jobs_input_key ON scheduler_jobs(input_key)")
            }
        }

        private val indexOrganizedSources = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE organized_sources (
                    source_id TEXT NOT NULL, source_version INTEGER NOT NULL, PRIMARY KEY(source_id, source_version),
                    FOREIGN KEY(source_id, source_version) REFERENCES source_versions(source_id, source_version) ON DELETE CASCADE)""")
                db.compileStatement("""INSERT OR IGNORE INTO organized_sources
                    SELECT source_id, source_version FROM source_versions
                    WHERE record_id = ? AND source_id = ? AND source_version = ?""").use { insert ->
                    db.query("SELECT receipt_json FROM operation_receipts").use { receipts ->
                        while (receipts.moveToNext()) {
                            val saved = checkNotNull(DbJson.decode<ApiReceipt>(receipts.getString(0)))
                            val source = saved.source ?: continue
                            if (!saved.success || saved.jobId == null || saved.recordId == null) continue
                            insert.bindString(1, saved.recordId)
                            insert.bindString(2, source.sourceId)
                            insert.bindLong(3, source.sourceVersion)
                            insert.executeInsert()
                        }
                    }
                }
            }
        }

        private val trackDinnerPublications = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE dinner_publication_counts (
                    record_id TEXT NOT NULL PRIMARY KEY, publications INTEGER NOT NULL,
                    FOREIGN KEY(record_id) REFERENCES records(id) ON DELETE CASCADE)""")
                requeueUnpublishedDinners(db)
            }
        }

        // #29 completed the rank checkpoint; #30 also publishes a decision and advances the target revision.
        private fun requeueUnpublishedDinners(db: SupportSQLiteDatabase) {
            val ids = db.query("""SELECT t.id FROM tasks t JOIN records r ON r.id = t.record_id
                JOIN scheduler_jobs j ON j.job_id = t.id JOIN task_checkpoints c ON c.task_id = t.id
                WHERE t.kind = 'ORGANIZE' AND t.state = 'COMPLETED' AND c.stage = 'rank'
                AND t.input_revision = r.revision AND instr(j.snapshot_json, '"decisionId":"diet-') > 0
                AND NOT EXISTS (SELECT 1 FROM deletion_markers m WHERE m.type = 'record' AND m.id = r.id)""").use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            for (id in ids) {
                db.query("""SELECT t.task_json, j.snapshot_json FROM tasks t
                    JOIN scheduler_jobs j ON j.job_id = t.id WHERE t.id = ?""", arrayOf(id)).use { row ->
                    check(row.moveToFirst())
                    val task = checkNotNull(DbJson.decode<CoreTask>(row.getString(0)))
                    val job = checkNotNull(DbJson.decode<TaskJob>(row.getString(1)))
                    val pending = task.copy(state = TaskState.QUEUED, revision = task.revision + 1)
                    val restored = job.copy(status = TaskStatus.QUEUED, decisionId = null, evidenceRefs = emptyList(),
                        leaseToken = job.leaseToken + 1, leaseUntil = 0, updatedAt = System.currentTimeMillis())
                    db.execSQL("UPDATE tasks SET state = ?, revision = ?, task_json = ? WHERE id = ?",
                        arrayOf(pending.state.name, pending.revision, DbJson.encode(pending), id))
                    db.execSQL("UPDATE scheduler_jobs SET snapshot_json = ? WHERE job_id = ?", arrayOf(DbJson.encode(restored), id))
                }
            }
            if (ids.isNotEmpty()) db.execSQL("UPDATE libraries SET revision = revision + 1")
        }

        private val pragmaCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA synchronous=FULL")
                db.setForeignKeyConstraintsEnabled(true)
            }
        }

        fun build(context: Context, name: String): PebbleDatabase =
            Room.databaseBuilder(context, PebbleDatabase::class.java, name)
                .addMigrations(migrateKeys, addScheduler, indexOrganizedSources, trackDinnerPublications)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(pragmaCallback)
                .build()

        fun inMemory(context: Context): PebbleDatabase =
            Room.inMemoryDatabaseBuilder(context, PebbleDatabase::class.java)
                .allowMainThreadQueries()
                .addCallback(pragmaCallback)
                .build()
    }
}
