package com.pebblepile.android.model

import com.pebblepile.android.algorithm.AlgorithmManifest
import com.pebblepile.android.algorithm.QueryConstraint

enum class TagReviewState(val label: String) {
    AUTO("自动整理"),
    NEEDS_REVIEW("待核对"),
    CONFIRMED("已确认"),
}

enum class TagAction { CONFIRM, MODIFY, EXCLUDE, REVIEW }

data class RecordTag(
    val id: String,
    val name: String,
    val sourceQuote: String,
    val algorithmVersion: String,
    val excluded: Boolean = false,
    val reviewState: TagReviewState = TagReviewState.AUTO,
    val sourceRef: SourceRef? = null,
)

data class EvidenceItem(
    val id: String,
    val title: String,
    val quote: String,
    val recordDate: String,
    val sourceRecordId: String,
    val sourceRef: SourceRef = SourceRef(sourceRecordId, 1),
    val start: Int = 0,
    val end: Int = quote.codePointCount(0, quote.length),
    val unavailableReason: String? = null,
)

data class SuggestionItem(val id: String, val title: String, val reason: String, val evidence: List<EvidenceItem>)
data class DinnerDecision(
    val id: String,
    val jobId: String,
    val inputRevision: Long,
    val preferenceRevision: Long,
    val snapshotId: String?,
    val manifest: AlgorithmManifest?,
    val constraint: QueryConstraint?,
    val suggestions: List<SuggestionItem>,
) {
    val redacted: Boolean get() = snapshotId == null
}

data class OutcomeItem(
    val id: String,
    val choice: String,
    val experience: String,
    val createdAt: String,
    val decisionId: String? = null,
)

enum class ItemStatus(val label: String) {
    OPEN("待开始"),
    IN_PROGRESS("进行中"),
    WAITING("等待条件"),
    NEEDS_REVIEW("待核对"),
    COMPLETED("已完成"),
}

data class PebbleRecord(
    val id: String,
    val rawText: String,
    val capturedAt: String,
    val captureMethod: String,
    val revision: Long = 1,
    val tags: List<RecordTag> = emptyList(),
    val suggestions: List<SuggestionItem> = emptyList(),
    val outcomes: List<OutcomeItem> = emptyList(),
    val currentCondition: String = "",
    val status: String = "已保存",
    val conditionScope: CorrectionScope? = null,
    val title: String = "",
    // currentCondition and conditionScope describe the last change displayed by the UI.
    val currentGoalCondition: String? = currentCondition.takeIf {
        it.isNotBlank() && conditionScope != CorrectionScope.FUTURE
    },
    val people: List<String> = emptyList(),
    val itemStatus: ItemStatus = ItemStatus.OPEN,
    // Nullable for records already stored as JSON before dinner decisions were published.
    val dinnerDecisions: List<DinnerDecision>? = null,
) {
    val currentDinnerDecision: DinnerDecision? get() = dinnerDecisions?.lastOrNull()?.takeUnless { it.redacted }
}

enum class CorrectionScope(val label: String) {
    THIS_TIME("仅本次"), FUTURE("以后按此条件")
}

data class ApiReceipt(
    val success: Boolean,
    val recordId: String? = null,
    val revision: Long? = null,
    val message: String = "",
    val conflict: Boolean = false,
    val currentRevision: Long? = null,
    val libraryRevision: Long? = null,
    val source: SourceRef? = null,
    val jobId: String? = null,
    val outcomeId: String? = null,
    val code: String? = null,
    val reason: String? = null,
    val nextAction: String? = null,
    val retryable: Boolean = false,
    val currentRecord: PebbleRecord? = null,
    val task: CoreTask? = null,
    val title: String? = null,
    val currentPreference: ConditionSetting? = null,
    val deletePreview: DeletePreview? = null,
    val deletion: DeletionResult? = null,
    val decisionId: String? = null,
)

data class SourceRef(val sourceId: String, val sourceVersion: Long)

data class CapturedSource(
    val ref: SourceRef,
    val recordId: String,
    val text: String,
    val captureMethod: String,
    val capturedAt: String
)

enum class TaskState { QUEUED, RUNNING, WAITING, NEEDS_REVIEW, COMPLETED, FAILED, CANCELLED, STALE }
enum class TaskAction { PAUSE, RESUME, CANCEL, RETRY }
enum class TaskKind { ORGANIZE, APPLY_FEEDBACK, REBUILD_CONTEXT, DELETE_CLEANUP }

data class CoreTask(
    val id: String,
    val recordId: String?,
    val kind: TaskKind,
    val inputRevision: Long,
    val revision: Long = 1,
    val state: TaskState = TaskState.QUEUED,
    val waitReason: String? = null,
    val phase: String? = null
) {
    val allowedActions: Set<TaskAction>
        get() = if (kind == TaskKind.DELETE_CLEANUP) emptySet() else when (state) {
            TaskState.QUEUED, TaskState.RUNNING -> setOf(TaskAction.PAUSE, TaskAction.CANCEL)
            TaskState.WAITING -> when (waitReason) {
                "user_paused" -> setOf(TaskAction.RESUME, TaskAction.CANCEL)
                "retry_pending", "algorithm_unavailable" -> setOf(TaskAction.RETRY, TaskAction.CANCEL)
                else -> setOf(TaskAction.CANCEL)
            }
            TaskState.NEEDS_REVIEW -> setOf(TaskAction.CANCEL)
            TaskState.FAILED -> setOf(TaskAction.RETRY)
            else -> emptySet()
        }
}

data class ConditionSetting(val text: String, val recordId: String, val revision: Long)
