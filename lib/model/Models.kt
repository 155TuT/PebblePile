package com.pebblepile.android.model

import com.pebblepile.android.algorithm.AlgorithmManifest
import com.pebblepile.android.algorithm.DefaultDietScenePackage
import com.pebblepile.android.algorithm.DietVocabulary
import com.pebblepile.android.algorithm.PreferenceCandidate
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
    val role: String? = null,
)

data class SuggestionItem(val id: String, val title: String, val reason: String, val evidence: List<EvidenceItem>)
data class DinnerRankingBasis(
    val appliedPreferences: List<String>,
    val unmatchedPreferences: List<String>,
    val filteredOut: List<String>,
    val preferenceDescriptions: Map<String, String>? = null,
    val filteredCandidateNames: Map<String, String>? = null,
)

data class DinnerDecision(
    val id: String,
    val jobId: String,
    val inputRevision: Long,
    val preferenceRevision: Long,
    val snapshotId: String?,
    val manifest: AlgorithmManifest?,
    val constraint: QueryConstraint?,
    val suggestions: List<SuggestionItem>,
    val rankingBasis: DinnerRankingBasis? = null,
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
    val supersededDinnerDecisionId: String? = null,
) {
    val currentDinnerDecision: DinnerDecision? get() = dinnerDecisions?.lastOrNull()?.takeUnless {
        it.redacted || it.id == supersededDinnerDecisionId
    }
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

/** The suggestion a record's feedback belongs to, including decisions hidden after a correction. */
fun PebbleRecord.decisionForOutcome(outcome: OutcomeItem): DinnerDecision? =
    outcome.decisionId?.let { id -> dinnerDecisions.orEmpty().firstOrNull { it.id == id } }

/** The first candidate is the published recommendation; the rest keep their ranked order. */
fun DinnerDecision.primarySuggestion(): SuggestionItem? = suggestions.firstOrNull()

/** A short, human-readable rendering of the conditions used for one decision. */
fun QueryConstraint.displaySummary(): String = buildList {
    weekday.takeIf { it.isNotBlank() }?.let { add(DietVocabulary.weekdayLabel(it)) }
    if (weekend) add("周末")
    add(if (overtime) "加班" else "不加班")
    budgetCny?.let { add("预算 $it 元") }
    cookMinutes?.let { add("可花 $it 分钟") }
}.joinToString(" · ")

/** Labels are saved with the decision so history keeps the meaning of that ranking. */
internal fun PreferenceCandidate.conditionDescription(): String {
    val whenApplied = buildList {
        val days = scope.weekdays
        when {
            days == setOf("mon", "tue", "wed", "thu", "fri") || (days.isEmpty() && scope.weekend == false) -> add("工作日")
            days == setOf("sat", "sun") || (days.isEmpty() && scope.weekend == true) -> add("周末")
            days.isNotEmpty() -> add(days.joinToString("、", transform = DietVocabulary::weekdayLabel))
        }
        scope.overtime?.let { add(if (it) "加班" else "不加班") }
    }.joinToString("")
    val priority = when (direction) {
        "convenience" -> "优先省事"
        "leisurely" -> "愿意慢慢做饭"
        else -> note.ifBlank { "饮食偏好" }
    }
    return if (whenApplied.isEmpty()) priority else "${whenApplied}时，$priority"
}

internal fun DinnerRankingBasis.preferenceLabel(id: String): String =
    preferenceDescriptions?.get(id) ?: when (id) {
        "pref-weekday-overtime-convenience" -> "工作日加班时，优先省事"
        "pref-explicit-weekday_overtime_convenience" -> "本人设置的晚餐条件"
        else -> id
    }

internal fun DinnerDecision.filteredCandidateLabel(id: String): String =
    rankingBasis?.filteredCandidateNames?.get(id) ?: if (manifest?.scenePackVersion == DefaultDietScenePackage.VERSION) {
        DefaultDietScenePackage.menu.firstOrNull { it.id == id }?.name ?: id
    } else id
