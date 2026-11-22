package com.pebblepile.android.db

import android.content.Context
import android.content.SharedPreferences
import com.pebblepile.android.repository.CoreReader
import com.pebblepile.android.repository.CoreRepository
import com.pebblepile.android.repository.CoreWriter
import com.pebblepile.android.repository.RepositoryWriteException
import org.json.JSONException

internal object RoomStore {
    private var instance: CoreRepository? = null

    @Synchronized
    fun repository(context: Context): CoreRepository {
        val app = context.applicationContext
        return instance ?: MigratingRoomRepository(
            PebbleDatabase.build(app, "pebblepile.sqlite"),
            app.getSharedPreferences("pebblepile_issue4_demo", Context.MODE_PRIVATE),
        ).also { instance = it }
    }
}

/** Open and import on the first repository operation, which Android dispatches on its IO thread. */
internal class MigratingRoomRepository(
    database: PebbleDatabase,
    preferences: SharedPreferences,
) : CoreRepository {
    private val repository by lazy {
        val repository = RoomCoreRepository(database)
        val hasLegacyData = preferences.contains("records") || preferences.contains("core_state")
        if (hasLegacyData) {
            repository.write { tx ->
                val old = try {
                    DemoLibraryStore(preferences).load()
                } catch (error: JSONException) {
                    throw RepositoryWriteException().apply { initCause(error) }
                }
                if (database.libraryDao().get() == null) {
                    database.libraryDao().put(LibraryEntity(revision = old.revision))
                    old.records.values.forEach(tx::putRecord)
                    old.sources.values.forEach(tx::putSource)
                    old.tasks.values.forEach(tx::putTask)
                    old.futureCondition?.let(tx::setFutureCondition)
                    old.receipts.forEach { (key, receipt) -> tx.putReceipt(key, receipt) }
                } else {
                    // A crash after import can leave both copies. Clear only a matching snapshot.
                    val alreadyImported = tx.revision == old.revision &&
                        tx.futureCondition == old.futureCondition &&
                        old.records.all { (id, record) -> tx.record(id) == record } &&
                        old.sources.values.groupBy { it.recordId }.all { (id, sources) ->
                            tx.sources(id).containsAll(sources)
                        } &&
                        old.tasks.all { (id, task) -> tx.task(id) == task } &&
                        old.receipts.all { (key, receipt) -> tx.receipt(key) == receipt }
                    if (!alreadyImported) throw RepositoryWriteException()
                }
            }
            // The library row and its data have committed. A restart can retry this cleanup.
            if (!preferences.edit().remove("records").remove("core_state").commit()) {
                throw RepositoryWriteException()
            }
        }
        repository
    }

    override fun <T> read(block: (CoreReader) -> T): T = repository.read(block)
    override fun <T> write(block: (CoreWriter) -> T): T = repository.write(block)
}
