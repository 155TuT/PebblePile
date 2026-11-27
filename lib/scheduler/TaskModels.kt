package com.pebblepile.scheduler

import com.pebblepile.utils.toLowerHex
import java.security.MessageDigest

/** 任务阶段按固定顺序执行；恢复时从第一个未完成阶段继续。 */
enum class TaskStage(val sequence: Int) {
    EXTRACT(0),
    UPDATE(1),
    CONTEXT(2),
    RANK(3),
    COMMIT(4);

    fun next(): TaskStage? = entries.getOrNull(sequence + 1)
}

enum class TaskStatus {
    QUEUED,
    RUNNING,
    WAITING,
    NEEDS_REVIEW,
    COMPLETED,
    FAILED,
    CANCELLED,
    STALE,
}

enum class TaskAction {
    PAUSE,
    RESUME,
    RETRY,
    CANCEL,
    OPEN_DETAIL,
}

enum class TaskRunOutcome {
    IDLE,
    BUSY,
    COMPLETED,
    WAITING,
    NEEDS_REVIEW,
    STALE,
    FAILED,
    CANCELLED,
}

/** 输入版本与用户偏好版本共同决定旧任务是否仍可提交。 */
data class RevisionSnapshot(
    val recordRevision: Long,
    val preferenceRevision: Long,
)

data class TaskInput(
    val recordId: String,
    val revision: Long,
    val preferenceRevision: Long,
    val text: String,
    val algorithmVersion: String,
    val captureSourceId: String? = null,
    val captureSourceVersion: Long? = null,
) {
    init {
        require((captureSourceId == null) == (captureSourceVersion == null))
        require(captureSourceVersion == null || captureSourceVersion >= 1)
    }
}

/** An exact source used by a persisted stage. Redaction follows these references. */
data class StageSource(val recordId: String, val sourceId: String, val sourceVersion: Long)

data class StageOutput(
    val payload: String,
    val evidenceRefs: List<String> = emptyList(),
    val contextKey: String? = null,
    val inputSources: List<StageSource>? = null,
    val conditionRecordId: String? = null,
)

data class CommitOutput(
    val decisionId: String?,
    val evidenceRefs: List<String> = emptyList(),
    val needsReview: Boolean = false,
)

data class TaskJob(
    val jobId: String,
    val idempotencyKey: String,
    val recordId: String,
    val inputRevision: Long,
    val preferenceRevision: Long,
    val inputDigest: String,
    val algorithmVersion: String,
    val sourceText: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val currentStage: TaskStage = TaskStage.EXTRACT,
    val waitingReason: String? = null,
    val retryable: Boolean = false,
    val attempts: Int = 0,
    val decisionId: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val checkpoints: Map<TaskStage, StageOutput> = emptyMap(),
    val leaseToken: Long = 0,
    val leaseUntil: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val captureSourceId: String? = null,
    val captureSourceVersion: Long? = null,
) {
    fun input(): TaskInput = TaskInput(
        recordId = recordId,
        revision = inputRevision,
        preferenceRevision = preferenceRevision,
        text = sourceText,
        algorithmVersion = algorithmVersion,
        captureSourceId = captureSourceId,
        captureSourceVersion = captureSourceVersion,
    )

    fun toView(): TaskView = TaskView(
        jobId = jobId,
        recordId = recordId,
        status = status,
        currentStage = currentStage,
        checkpoint = checkpoints.keys.maxByOrNull { it.sequence },
        waitingReason = waitingReason,
        retryable = retryable,
        attempts = attempts,
        decisionId = decisionId,
        evidenceRefs = evidenceRefs,
        actions = actionsFor(status, waitingReason),
        updatedAt = updatedAt,
    )

    private fun actionsFor(status: TaskStatus, waitingReason: String?): List<TaskAction> = when (status) {
        TaskStatus.QUEUED, TaskStatus.RUNNING -> listOf(TaskAction.PAUSE, TaskAction.CANCEL)
        TaskStatus.WAITING -> if (waitingReason == WAITING_USER_PAUSED) {
            listOf(TaskAction.RESUME, TaskAction.CANCEL)
        } else {
            listOf(TaskAction.RETRY, TaskAction.CANCEL)
        }
        TaskStatus.FAILED -> if (retryable) listOf(TaskAction.RETRY, TaskAction.CANCEL) else listOf(TaskAction.CANCEL)
        TaskStatus.NEEDS_REVIEW -> listOf(TaskAction.OPEN_DETAIL, TaskAction.CANCEL)
        TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.STALE -> emptyList()
    }
}

data class TaskView(
    val jobId: String,
    val recordId: String,
    val status: TaskStatus,
    val currentStage: TaskStage,
    val checkpoint: TaskStage?,
    val waitingReason: String?,
    val retryable: Boolean,
    val attempts: Int,
    val decisionId: String?,
    val evidenceRefs: List<String>,
    val actions: List<TaskAction>,
    val updatedAt: Long,
)

const val WAITING_USER_PAUSED = "user_paused"
const val WAITING_RETRY_PENDING = "retry_pending"
const val WAITING_ALGORITHM_UNAVAILABLE = "algorithm_unavailable"

class LeaseLostException(jobId: String) : IllegalStateException("任务租约已失效：$jobId")

class AlgorithmUnavailableException : IllegalStateException("等待整理算法就绪")

class StaleInputException(message: String) : IllegalStateException(message)

class RetryableTaskException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

private fun digest(parts: List<String>): String {
    val raw = parts.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .toLowerHex()
}

fun taskKey(input: TaskInput): String = digest(
    listOf(input.recordId, input.revision.toString(), input.preferenceRevision.toString(), input.algorithmVersion),
)

fun inputDigest(input: TaskInput): String = digest(buildList {
    add(taskKey(input))
    add(input.text)
    if (input.captureSourceId != null) {
        add(input.captureSourceId)
        add(requireNotNull(input.captureSourceVersion).toString())
    }
})

fun stableJobId(input: TaskInput): String = "job-${taskKey(input)}"
