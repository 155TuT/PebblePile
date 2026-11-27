package com.pebblepile.android.db

import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.repository.CoreReader
import com.pebblepile.android.repository.CoreRepository
import com.pebblepile.android.repository.CoreWriter
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.StoredReceipt

internal data class DemoLibrary(
    var revision: Long = 0,
    val records: MutableMap<String, PebbleRecord> = linkedMapOf(),
    val sources: MutableMap<SourceRef, CapturedSource> = linkedMapOf(),
    val tasks: MutableMap<String, CoreTask> = linkedMapOf(),
    val receipts: MutableMap<OperationKey, StoredReceipt> = linkedMapOf(),
    var futureCondition: ConditionSetting? = null
)

/** The no-argument constructor is an isolated, in-memory repository for examples. */
class SnapshotCoreRepository internal constructor(
    initial: DemoLibrary,
    private val persist: (DemoLibrary) -> Unit
) : CoreRepository {
    constructor() : this(DemoLibrary(), {})

    private val lock = Any()
    private var library = initial

    override fun <T> read(block: (CoreReader) -> T): T = synchronized(lock) {
        block(LibraryTransaction(library))
    }

    override fun <T> write(block: (CoreWriter) -> T): T = synchronized(lock) {
        val pending = library.copy(
            records = LinkedHashMap(library.records),
            sources = LinkedHashMap(library.sources),
            tasks = LinkedHashMap(library.tasks),
            receipts = LinkedHashMap(library.receipts)
        )
        val result = block(LibraryTransaction(pending))
        if (pending != library) {
            persist(pending)
            library = pending
        }
        result
    }

    private class LibraryTransaction(private val state: DemoLibrary) : CoreWriter {
        override val revision get() = state.revision
        override val futureCondition get() = state.futureCondition
        override fun record(id: String) = state.records[id]
        override fun records() = state.records.values.toList()
        override fun sources(recordId: String) = state.sources.values.filter { it.recordId == recordId }
        override fun task(id: String) = state.tasks[id]
        override fun tasks(recordId: String) = state.tasks.values.filter { it.recordId == recordId }
        override fun receipt(key: OperationKey) = state.receipts[key]

        override fun putRecord(record: PebbleRecord) { state.records[record.id] = record }
        override fun putSource(source: CapturedSource) { state.sources[source.ref] = source }
        override fun putTask(task: CoreTask) { state.tasks[task.id] = task }
        override fun putReceipt(key: OperationKey, receipt: StoredReceipt) { state.receipts[key] = receipt }
        override fun setFutureCondition(condition: ConditionSetting) { state.futureCondition = condition }
        override fun advanceRevision(): Long { return ++state.revision }
    }
}
