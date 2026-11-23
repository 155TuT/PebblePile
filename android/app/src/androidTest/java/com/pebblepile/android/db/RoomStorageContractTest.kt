package com.pebblepile.android.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.repository.DeletionObject
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.service.CaptureRequest
import com.pebblepile.android.service.CoreApiContractSuite
import com.pebblepile.android.service.LocalCoreApi
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomStorageContractTest : CoreApiContractSuite() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "room-contract-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private var database = PebbleDatabase.build(context, name)

    override fun createRepository() = RoomCoreRepository(database)

    @After fun close() {
        database.close()
        context.deleteDatabase(name)
        context.deleteSharedPreferences(name)
    }

    @Test fun upgradeAndReopenRetainOriginalReceiptsVersionsConditionsAndCheckpoint() = runBlocking {
        val previous = DemoLibraryStore(preferences).repository()
        val oldApi = LocalCoreApi(previous)
        val first = CaptureRequest("第一份原文", "android_share", "share-1")
        val saved = oldApi.recordExperience(first)
        val recordId = requireNotNull(saved.recordId)
        val taskId = requireNotNull(saved.jobId)
        val appended = oldApi.recordExperience("补充原文", "app_home_record", "save-2",
            recordId = recordId, baseRevision = saved.revision)
        assertTrue(appended.success)
        val corrected = oldApi.reviseMemory(recordId, requireNotNull(appended.revision),
            "工作日优先", CorrectionScope.FUTURE, "correct-1")
        assertTrue(corrected.success)
        previous.write { tx -> tx.putTask(requireNotNull(tx.task(taskId)).copy(phase = "queued")) }
        val before = oldApi.getRecordDetail(recordId)
        val future = previous.read { it.futureCondition }
        assertNotNull(future)
        val oldRecords = requireNotNull(preferences.getString("records", null))
        val oldState = requireNotNull(preferences.getString("core_state", null))

        val migrated = MigratingRoomRepository(database, preferences)
        val api = LocalCoreApi(migrated)
        assertEquals(before, api.getRecordDetail(recordId))
        assertEquals(future, migrated.read { it.futureCondition })
        assertEquals(saved, api.recordExperience(first))
        assertEquals("queued", database.checkpointDao().get(taskId)?.stage)
        assertFalse(preferences.contains("core_state"))
        assertFalse(preferences.contains("records"))
        val sqlite = database.openHelper.readableDatabase
        sqlite.query("PRAGMA journal_mode").use { assertTrue(it.moveToFirst()); assertEquals("wal", it.getString(0)) }
        sqlite.query("PRAGMA synchronous").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        sqlite.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }

        database.close()
        // Reproduce a restart between the SQLite commit and clearing the two preference keys.
        preferences.edit().putString("records", oldRecords).putString("core_state", oldState).commit()
        database = PebbleDatabase.build(context, name)
        val reopened = LocalCoreApi(MigratingRoomRepository(database, preferences))
        assertEquals(before, reopened.getRecordDetail(recordId))
        assertFalse(preferences.contains("records"))
        assertEquals(saved, reopened.recordExperience(first))
        assertEquals("补充原文", reopened.getRecordDetail(recordId, appended.source)?.sources?.single()?.text)
        assertEquals("queued", reopened.getTask(taskId)?.phase)
        assertEquals("queued", database.checkpointDao().get(taskId)?.stage)
    }

    @Test fun roomVersionOneMigratesDuplicateReceiptsAndCleanupJobs() {
        val api = LocalCoreApi(createRepository())
        val capture = CaptureRequest("旧版数据库", "app_home_record", "capture")
        val saved = api.recordExperience(capture)
        database.close()
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.beginTransaction()
            try {
                old.execSQL("ALTER TABLE operation_receipts RENAME TO current_receipts")
                old.execSQL("""CREATE TABLE operation_receipts (
                    client_id TEXT NOT NULL, operation_id TEXT NOT NULL, request_hash TEXT NOT NULL,
                    receipt_json TEXT NOT NULL, deletion_scope_json TEXT NOT NULL, committed_revision INTEGER NOT NULL,
                    row_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)""")
                old.execSQL("INSERT INTO operation_receipts SELECT *, NULL FROM current_receipts")
                old.execSQL("INSERT INTO operation_receipts SELECT *, NULL FROM current_receipts")
                old.execSQL("DROP TABLE current_receipts")
                old.execSQL("DROP TABLE deletion_jobs")
                old.execSQL("""CREATE TABLE deletion_jobs (job_id TEXT NOT NULL, plan_json TEXT NOT NULL,
                    access_json TEXT NOT NULL, row_key TEXT NOT NULL PRIMARY KEY)""")
                old.execSQL("INSERT INTO deletion_jobs VALUES ('cleanup', '{}', '{}', 'cleanup')")
                old.execSQL("""INSERT INTO deletion_markers
                    (key, type, id, version, related_id, deleted_revision)
                    VALUES ('source_version|source|1|', 'source_version', 'source', 1, NULL, 2)""")
                old.execSQL("DROP TABLE organized_sources")
                old.execSQL("DROP TABLE IF EXISTS scheduler_jobs")
                old.version = 1
                old.setTransactionSuccessful()
            } finally {
                old.endTransaction()
            }
        }
        database = PebbleDatabase.build(context, name)
        assertEquals(saved, LocalCoreApi(createRepository()).recordExperience(capture))
        val row = database.receiptDao().all().single()
        database.receiptDao().put(row)
        assertEquals(1, database.receiptDao().all().size)
        assertEquals("{}", database.deletionDao().job("cleanup")?.planJson)
        createRepository().read { tx ->
            val deletion = requireNotNull(tx.deletion)
            assertTrue(deletion.isDeleted(DeletionObject("source_version", "source", 1)))
            assertFalse(deletion.isDeleted(DeletionObject("source_version", "source", 2)))
        }
    }

    @Test fun conflictingSnapshotsKeepBothCopies() {
        val current = LocalCoreApi(createRepository()).recordExperience(
            CaptureRequest("数据库里的记录", "android_share", "db-capture"))
        val old = """[{"id":"old-id","rawText":"旧版记录","revision":1}]"""
        preferences.edit().putString("records", old).commit()
        val migrated = MigratingRoomRepository(database, preferences)
        assertThrows(RepositoryWriteException::class.java) { migrated.read { it.records() } }
        assertEquals(old, preferences.getString("records", null))
        assertEquals("数据库里的记录", createRepository().read { it.record(requireNotNull(current.recordId))?.rawText })
    }

    @Test fun unreadableLegacyDataCanBeRepairedAndRetried() {
        preferences.edit().putString("records", "[").commit()
        val migrated = MigratingRoomRepository(database, preferences)
        assertThrows(RepositoryWriteException::class.java) { migrated.read { it.revision } }
        assertNull(database.libraryDao().get())
        assertEquals("[", preferences.getString("records", null))

        preferences.edit().putString("records",
            """[{"id":"old-record","rawText":"旧原文","capturedAt":"2026-11-16 20:30","revision":4}]""").commit()
        migrated.read { tx ->
            assertEquals(4L, tx.revision)
            assertEquals("旧原文", tx.record("old-record")?.rawText)
            assertEquals("旧原文", tx.sources("old-record").single().text)
        }
    }
}
