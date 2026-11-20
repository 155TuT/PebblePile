package com.pebblepile.android.service

import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.PebbleRecord

/**
 * UI-facing contract implemented by the local core. The Android assembly supplies a repository.
 */
interface CoreApi {
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
    suspend fun reviseMemory(recordId: String, baseRevision: Long, condition: String, scope: CorrectionScope, operationId: String): ApiReceipt
    suspend fun recentRecords(): List<PebbleRecord>
}
