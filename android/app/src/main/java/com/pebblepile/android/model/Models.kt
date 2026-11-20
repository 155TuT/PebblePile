package com.pebblepile.android.model

data class RecordTag(val id: String, val name: String, val sourceQuote: String, val algorithmVersion: String, val excluded: Boolean = false)
data class EvidenceItem(val id: String, val title: String, val quote: String, val recordDate: String, val sourceRecordId: String)
data class SuggestionItem(val id: String, val title: String, val reason: String, val evidence: List<EvidenceItem>)
data class OutcomeItem(val id: String, val choice: String, val experience: String, val createdAt: String)

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
    }
)

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
    val currentPreference: ConditionSetting? = null
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
