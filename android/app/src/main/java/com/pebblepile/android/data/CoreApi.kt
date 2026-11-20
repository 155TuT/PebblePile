package com.pebblepile.android.data

/**
 * UI-facing contract. Replace DemoCoreApi with the team's CoreAPI adapter when Issue #6/#7 land.
 * Keep Android Intent, Compose and storage implementation outside this interface.
 */
interface CoreApi {
    suspend fun recordExperience(text: String, captureMethod: String, operationId: String, recordId: String? = null): ApiReceipt
    suspend fun getRecord(recordId: String): PebbleRecord?
    suspend fun reviseTags(recordId: String, baseRevision: Long, tagId: String, operationId: String): ApiReceipt
    suspend fun recordOutcome(recordId: String, baseRevision: Long, choice: String, experience: String, operationId: String): ApiReceipt
    suspend fun reviseMemory(recordId: String, baseRevision: Long, condition: String, scope: CorrectionScope, operationId: String): ApiReceipt
    suspend fun recentRecords(): List<PebbleRecord>
}
