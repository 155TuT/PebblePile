package com.pebblepile.android.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 1,
    exportSchema = false,
)
abstract class PebbleDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao
    abstract fun recordDao(): RecordDao
    abstract fun sourceDao(): SourceDao
    abstract fun taskDao(): TaskDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun deletionDao(): DeletionDao
    abstract fun checkpointDao(): CheckpointDao

    companion object {
        private val pragmaCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                applyPragmas(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // WAL cannot be enabled on some in-memory test databases; never let PRAGMA block startup.
                try {
                    applyPragmas(db)
                } catch (_: Exception) {
                    // best effort for in-memory / restricted environments
                }
            }

            private fun applyPragmas(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA journal_mode=WAL")
                db.execSQL("PRAGMA synchronous=FULL")
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        fun build(context: Context, name: String): PebbleDatabase =
            Room.databaseBuilder(context, PebbleDatabase::class.java, name)
                .addCallback(pragmaCallback)
                .build()

        fun inMemory(context: Context): PebbleDatabase =
            Room.inMemoryDatabaseBuilder(context, PebbleDatabase::class.java)
                .allowMainThreadQueries()
                .addCallback(pragmaCallback)
                .build()
    }
}
