package com.pebblepile.android.service

import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.PebbleRecord

/**
 * UI-facing contract implemented by the local core. The Android assembly supplies a repository.
 */
interface CoreApi {
    fun recordExperience(request: CaptureRequest): ApiReceipt
    fun getRecordDetail(recordId: String, sourceRef: SourceRef? = null): RecordDetail?
    fun queryRecords(tags: Set<String> = emptySet()): RecordQueryResult
    fun queryContext(recordId: String): LocalContext?
    fun updateRecordTitle(recordId: String, baseRevision: Long, title: String, operationId: String): ApiReceipt
    fun getTask(jobId: String): CoreTask?
    fun controlTask(jobId: String, baseRevision: Long, action: TaskAction, operationId: String): ApiReceipt
    suspend fun recordExperience(
        text: String,
        captureMethod: String,
        operationId: String,
        recordId: String? = null,
        baseRevision: Long? = null
    ): ApiReceipt
    suspend fun getRecord(recordId: String): PebbleRecord?
    suspend fun reviseTags(recordId: String, baseRevision: Long, tagId: String, operationId: String): ApiReceipt
    suspend fun recordOutcome(recordId: String, baseRevision: Long, choice: String, experience: String, operationId: String): ApiReceipt
    suspend fun reviseMemory(
        recordId: String, baseRevision: Long, condition: String, scope: CorrectionScope, operationId: String,
        basePreferenceRevision: Long? = null
    ): ApiReceipt
    suspend fun recentRecords(): List<PebbleRecord>
}
