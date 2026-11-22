package com.pebblepile.android.repository

import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.PebbleRecord

data class OperationKey(val clientId: String, val operationId: String)
data class StoredReceipt(
    val requestHash: String,
    val receipt: ApiReceipt,
    val deletionScope: Set<DeletionObject> = emptySet()
)

/** SQLite implements this contract for one library per repository instance. */
interface CoreRepository {
    /** Run the entire callback against a consistent read view. */
    fun <T> read(block: (CoreReader) -> T): T

    /** Commit the entire callback as one transaction. A failed write rolls back and throws. */
    fun <T> write(block: (CoreWriter) -> T): T
}

interface CoreReader {
    val revision: Long
    val futureCondition: ConditionSetting?
    val deletion: DeletionReader? get() = null
    fun record(id: String): PebbleRecord?
    fun records(): List<PebbleRecord>
    fun sources(recordId: String): List<CapturedSource>
    fun task(id: String): CoreTask?
    fun tasks(recordId: String): List<CoreTask>
    fun receipt(key: OperationKey): StoredReceipt?
}

interface CoreWriter : CoreReader {
    override val deletion: DeletionWriter? get() = null

    fun putRecord(record: PebbleRecord)

    /** Insert an exact source version, keeping previously stored versions available. */
    fun putSource(source: CapturedSource)

    fun putTask(task: CoreTask)

    /** Store the original successful receipt under its unique operation key. */
    fun putReceipt(key: OperationKey, receipt: StoredReceipt)

    fun setFutureCondition(condition: ConditionSetting)
    fun advanceRevision(): Long
}

class RepositoryWriteException : RuntimeException()
