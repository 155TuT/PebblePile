package com.pebblepile.android.db

import android.content.Context
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
        SourceVersionEntity::class,
        TaskEntity::class,
        OperationReceiptEntity::class,
        DeletionMarkerEntity::class,
        DeletionJobEntity::class,
        TaskCheckpointEntity::class,
        ScheduledJobEntity::class,
    ],
    version = 3,
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

        private val pragmaCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA synchronous=FULL")
                db.setForeignKeyConstraintsEnabled(true)
            }
        }

        fun build(context: Context, name: String): PebbleDatabase =
            Room.databaseBuilder(context, PebbleDatabase::class.java, name)
                .addMigrations(migrateKeys, addScheduler)
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
