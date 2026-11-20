package com.pebblepile.android.data

data class SourceRef(val sourceId: String, val sourceVersion: Long)

data class CapturedSource(
    val ref: SourceRef,
    val recordId: String,
    val text: String,
    val captureMethod: String,
    val capturedAt: String
)

enum class TaskState { QUEUED, RUNNING, WAITING, NEEDS_REVIEW, COMPLETED, FAILED, CANCELLED, STALE }
enum class TaskAction { PAUSE, RESUME, CANCEL }
enum class TaskKind { ORGANIZE, APPLY_FEEDBACK, REBUILD_CONTEXT }

data class CoreTask(
    val id: String,
    val recordId: String,
    val kind: TaskKind,
    val inputRevision: Long,
    val revision: Long = 1,
    val state: TaskState = TaskState.QUEUED,
    val waitReason: String? = null
) {
    val allowedActions: Set<TaskAction>
        get() = when (state) {
            TaskState.QUEUED, TaskState.RUNNING -> setOf(TaskAction.PAUSE, TaskAction.CANCEL)
            TaskState.WAITING -> if (waitReason == "user_paused") {
                setOf(TaskAction.RESUME, TaskAction.CANCEL)
            } else {
                setOf(TaskAction.CANCEL)
            }
            TaskState.NEEDS_REVIEW -> setOf(TaskAction.CANCEL)
            else -> emptySet()
        }
}

data class ConditionSetting(val text: String, val recordId: String, val revision: Long)
data class OperationKey(val clientId: String, val operationId: String)
data class StoredReceipt(val requestHash: String, val receipt: ApiReceipt)

/** Each instance opens one library. Reads are consistent and failed writes roll back. */
interface CoreRepository {
    fun <T> read(block: (CoreReader) -> T): T
    fun <T> write(block: (CoreWriter) -> T): T
}

interface CoreReader {
    val revision: Long
    val futureCondition: ConditionSetting?
    fun record(id: String): PebbleRecord?
    fun records(): List<PebbleRecord>
    fun sources(recordId: String): List<CapturedSource>
    fun task(id: String): CoreTask?
    fun tasks(recordId: String): List<CoreTask>
    fun receipt(key: OperationKey): StoredReceipt?
}

interface CoreWriter : CoreReader {
    fun putRecord(record: PebbleRecord)
    fun putSource(source: CapturedSource)
    fun putTask(task: CoreTask)
    fun putReceipt(key: OperationKey, receipt: StoredReceipt)
    fun setFutureCondition(condition: ConditionSetting)
    fun advanceRevision(): Long
}

class RepositoryWriteException : RuntimeException()

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
