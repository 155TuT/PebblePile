package com.pebblepile.android.service

import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.OutcomeItem
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskKind
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.CoreRepository
import com.pebblepile.android.repository.CoreWriter
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.repository.StoredReceipt
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class CaptureRequest(
    val text: String,
    val captureMethod: String,
    val operationId: String,
    val recordId: String? = null,
    val baseRevision: Long? = null,
    val capturedAt: String? = null
)

data class RecordDetail(
    val record: PebbleRecord,
    val sources: List<CapturedSource>,
    val jobIds: List<String>,
    val libraryRevision: Long
)

data class RecordQueryResult(val records: List<PebbleRecord>, val libraryRevision: Long)

data class LocalContext(
    val currentCondition: String?,
    val futureCondition: ConditionSetting?,
    val snapshotId: String,
    val libraryRevision: Long
)

/** Local, single-library CoreAPI. The same instance serves home capture and system sharing. */
class LocalCoreApi(
    private val repository: CoreRepository,
    private val clientId: String = "android",
    private val autoOrganize: Boolean = true,
    private val clock: () -> String = { LocalDateTime.now().format(dateTimeFormat) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val decorateSampleRecord: (PebbleRecord) -> PebbleRecord = { it }
) : CoreApi {
    override suspend fun recordExperience(
        text: String,
        captureMethod: String,
        operationId: String,
        recordId: String?,
        baseRevision: Long?
    ): ApiReceipt = recordExperience(CaptureRequest(text, captureMethod, operationId, recordId, baseRevision))

    fun recordExperience(request: CaptureRequest): ApiReceipt = write(
        request.operationId, "record_experience", request.text, request.captureMethod,
        request.recordId, request.baseRevision?.toString(), request.capturedAt
    ) { tx ->
        if (request.text.isBlank() || request.text.codePointCount(0, request.text.length) > 10_000) {
            return@write invalid("请输入 1 至 10000 个字符的原话")
        }
        if (request.captureMethod.isBlank()) return@write invalid("缺少记录来源")
        if (request.recordId == null && request.baseRevision != null) {
            return@write invalid("新增记录无需提供基准修订")
        }
        val old = request.recordId?.let { tx.record(it) }
        if (request.recordId != null) {
            if (old == null) return@write invalid("记录不存在")
            checkRevision(old, request.baseRevision)?.let { return@write it }
        }
        val recordId = old?.id ?: newId()
        val capturedAt = request.capturedAt ?: clock()
        val record = old?.copy(revision = old.revision + 1) ?: decorateSampleRecord(
            PebbleRecord(recordId, request.text, capturedAt, request.captureMethod)
        )
        val source = SourceRef(newId(), 1)
        tx.putRecord(record)
        tx.putSource(CapturedSource(source, recordId, request.text, request.captureMethod, capturedAt))
        saved(record, "已保存").copy(source = source, jobId = enqueue(tx, record, TaskKind.ORGANIZE))
    }

    override suspend fun getRecord(recordId: String): PebbleRecord? = getRecordDetail(recordId)?.record

    fun getRecordDetail(recordId: String, sourceRef: SourceRef? = null): RecordDetail? = repository.read { tx ->
        val record = tx.record(recordId) ?: return@read null
        val sources = tx.sources(recordId).filter { sourceRef == null || it.ref == sourceRef }
        if (sourceRef != null && sources.isEmpty()) return@read null
        RecordDetail(record, sources, tx.tasks(recordId).map { it.id }, tx.revision)
    }

    override suspend fun recentRecords(): List<PebbleRecord> = queryRecords().records

    fun queryRecords(tags: Set<String> = emptySet()): RecordQueryResult = repository.read { tx ->
        val records = tx.records().asReversed().filter { record ->
            tags.all { name -> record.tags.any { it.name == name && !it.excluded } }
        }.sortedByDescending { it.capturedAt }
        RecordQueryResult(records, tx.revision)
    }

    fun queryContext(recordId: String): LocalContext? = repository.read { tx ->
        val record = tx.record(recordId) ?: return@read null
        LocalContext(
            record.currentGoalCondition,
            tx.futureCondition,
            "local-${tx.futureCondition?.revision ?: 0}",
            tx.revision
        )
    }

    fun updateRecordTitle(recordId: String, baseRevision: Long, title: String, operationId: String): ApiReceipt =
        changeRecord(recordId, baseRevision, operationId, "update_record", title) { tx, record ->
            if (title.isBlank()) return@changeRecord invalid("记录标题不能为空")
            val updated = record.copy(title = title, revision = record.revision + 1)
            tx.putRecord(updated)
            saved(updated, "记录标题已更新")
        }

    override suspend fun reviseTags(recordId: String, baseRevision: Long, tagId: String, operationId: String): ApiReceipt =
        changeRecord(recordId, baseRevision, operationId, "revise_tags", tagId) { tx, record ->
            if (record.tags.none { it.id == tagId }) return@changeRecord invalid("标签不存在")
            val updated = record.copy(
                revision = record.revision + 1,
                tags = record.tags.map { if (it.id == tagId) it.copy(excluded = true) else it }
            )
            tx.putRecord(updated)
            saved(updated, "已排除标签")
        }

    override suspend fun recordOutcome(
        recordId: String, baseRevision: Long, choice: String, experience: String, operationId: String
    ): ApiReceipt = changeRecord(recordId, baseRevision, operationId, "record_outcome", choice, experience) { tx, record ->
        if (choice.isBlank() && experience.isBlank()) return@changeRecord invalid("请补充实际选择或体验")
        val outcome = OutcomeItem(newId(), choice, experience, clock())
        val updated = record.copy(revision = record.revision + 1, outcomes = record.outcomes + outcome)
        tx.putRecord(updated)
        saved(updated, "实际反馈已保存").copy(outcomeId = outcome.id, jobId = enqueue(tx, updated, TaskKind.APPLY_FEEDBACK))
    }

    override suspend fun reviseMemory(
        recordId: String, baseRevision: Long, condition: String, scope: CorrectionScope, operationId: String
    ): ApiReceipt = changeRecord(recordId, baseRevision, operationId, "revise_memory", condition, scope.name) { tx, record ->
        if (condition.isBlank()) return@changeRecord invalid("适用条件不能为空")
        val updated = record.copy(
            currentCondition = condition,
            conditionScope = scope,
            revision = record.revision + 1,
            currentGoalCondition = if (scope == CorrectionScope.THIS_TIME) condition else record.currentGoalCondition
        )
        tx.putRecord(updated)
        if (scope == CorrectionScope.THIS_TIME) {
            saved(updated, "仅本次条件已更新")
        } else {
            tx.setFutureCondition(ConditionSetting(condition, recordId, tx.revision + 1))
            saved(updated, "已保存为以后适用的明确偏好").copy(jobId = enqueue(tx, updated, TaskKind.REBUILD_CONTEXT))
        }
    }

    fun getTask(jobId: String): CoreTask? = repository.read { it.task(jobId) }

    fun controlTask(jobId: String, baseRevision: Long, action: TaskAction, operationId: String): ApiReceipt =
        write(operationId, "control_task", jobId, baseRevision.toString(), action.name) { tx ->
            val task = tx.task(jobId) ?: return@write invalid("任务不存在")
            if (task.revision != baseRevision) {
                return@write conflict(task.recordId, task.revision).copy(jobId = task.id, task = task)
            }
            if (action !in task.allowedActions) return@write invalid("当前任务状态不支持此操作")
            val updated = when (action) {
                TaskAction.PAUSE -> task.copy(state = TaskState.WAITING, waitReason = "user_paused")
                TaskAction.RESUME -> task.copy(state = TaskState.QUEUED, waitReason = null)
                TaskAction.CANCEL -> task.copy(state = TaskState.CANCELLED, waitReason = null)
            }.copy(revision = task.revision + 1)
            tx.putTask(updated)
            ApiReceipt(true, recordId = updated.recordId, jobId = updated.id, task = updated)
        }

    private fun changeRecord(
        recordId: String,
        baseRevision: Long,
        operationId: String,
        method: String,
        vararg params: String,
        change: (CoreWriter, PebbleRecord) -> ApiReceipt
    ): ApiReceipt = write(operationId, method, recordId, baseRevision.toString(), *params) { tx ->
        val record = tx.record(recordId) ?: return@write invalid("记录不存在")
        checkRevision(record, baseRevision) ?: change(tx, record)
    }

    private fun write(
        operationId: String,
        method: String,
        vararg params: String?,
        apply: (CoreWriter) -> ApiReceipt
    ): ApiReceipt {
        if (operationId.isBlank()) return invalid("缺少操作标识")
        val key = OperationKey(clientId, operationId)
        val hash = requestHash(method, *params)
        return try {
            repository.write { tx ->
                val previous = tx.receipt(key)
                if (previous != null) {
                    return@write if (previous.requestHash == hash) previous.receipt
                    else invalid("操作标识已用于其他内容").copy(reason = "OPERATION_ID_REUSED", libraryRevision = tx.revision)
                }
                val result = apply(tx)
                if (!result.success) return@write result.copy(libraryRevision = tx.revision)
                val committed = result.copy(libraryRevision = tx.advanceRevision())
                tx.putReceipt(key, StoredReceipt(hash, committed))
                committed
            }
        } catch (_: RepositoryWriteException) {
            ApiReceipt(false, message = "保存失败，请重试", code = "PROCESSING_FAILED", nextAction = "retry", retryable = true)
        }
    }

    private fun enqueue(tx: CoreWriter, record: PebbleRecord, kind: TaskKind): String? {
        if (!autoOrganize) return null
        val task = CoreTask(newId(), record.id, kind, record.revision)
        tx.putTask(task)
        return task.id
    }

    private fun checkRevision(record: PebbleRecord, baseRevision: Long?): ApiReceipt? = when {
        baseRevision == null -> invalid("修改记录需要基准修订")
        record.revision != baseRevision -> conflict(record.id, record.revision).copy(currentRecord = record)
        else -> null
    }

    private fun saved(record: PebbleRecord, message: String) = ApiReceipt(true, record.id, record.revision, message)

    private fun invalid(message: String) = ApiReceipt(false, message = message, code = "INVALID_INPUT")

    private fun conflict(recordId: String, revision: Long) = ApiReceipt(
        false, recordId, message = "内容已更新，请核对当前版本后重试。",
        conflict = true, currentRevision = revision, code = "REVISION_CONFLICT", nextAction = "review_current_revision"
    )

    private fun requestHash(method: String, vararg params: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf("0.1.0", method) + params) {
            val bytes = part?.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
            if (bytes != null) digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
