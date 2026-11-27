package com.pebblepile.android.model

enum class DeleteTargetType(val wireName: String) {
    RECORD("record"), SOURCE("source"), KNOWLEDGE_ITEM("knowledge_item")
}

data class DeleteTarget(val type: DeleteTargetType, val id: String)

data class DeleteRequest(
    val target: DeleteTarget,
    val previewId: String,
    val baseRevision: Long,
    val operationId: String
)

data class DeletionImpact(
    val remove: Map<String, Int>,
    val invalidate: Map<String, Int>,
    val cancel: Map<String, Int>,
    val rebuild: Map<String, Int>,
    val affectedRecordIds: List<String>
)

data class DeletePreview(
    val target: DeleteTarget,
    val previewId: String,
    val baseRevision: Long,
    val impact: DeletionImpact
)

enum class CleanupState { PENDING, COMPLETE }

data class DeletionResult(
    val target: DeleteTarget,
    val impact: DeletionImpact,
    val cleanupState: CleanupState
)
