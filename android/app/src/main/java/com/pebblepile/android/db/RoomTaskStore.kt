package com.pebblepile.android.db

import com.pebblepile.android.algorithm.AlgorithmEngine
import com.pebblepile.android.algorithm.DietTaskContext
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.DinnerDecision
import com.pebblepile.android.model.TaskKind
import com.pebblepile.android.model.TaskState
import com.pebblepile.scheduler.CommitOutput
import com.pebblepile.scheduler.RevisionProvider
import com.pebblepile.scheduler.RevisionSnapshot
import com.pebblepile.scheduler.StageOutput
import com.pebblepile.scheduler.StaleInputException
import com.pebblepile.scheduler.TaskStage
import com.pebblepile.scheduler.WAITING_ALGORITHM_UNAVAILABLE
import com.pebblepile.scheduler.TaskInput
import com.pebblepile.scheduler.TaskJob
import com.pebblepile.scheduler.TaskStatus
import com.pebblepile.scheduler.TransactionalTaskStore
import com.pebblepile.scheduler.inputDigest
import com.pebblepile.scheduler.taskKey
import java.time.Instant
import java.util.concurrent.Callable

/** Scheduler operations and CoreAPI writes share the same Room database and business job ID. */
internal class RoomTaskStore(private val database: PebbleDatabase) : TransactionalTaskStore(), RevisionProvider {
    private val dietInputs = RoomDietInputs(database)
    private val tasks get() = database.taskDao()
    private val scheduler get() = database.schedulerDao()

    override fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable { block() })

    private fun preferenceRevision(): Long =
        DbJson.decode<ConditionSetting>(database.libraryDao().get()?.futureConditionJson)?.revision ?: 0L

    override fun currentRevision(recordId: String): RevisionSnapshot? = transaction {
        database.recordDao().visibleRevision(recordId)?.let { RevisionSnapshot(it, preferenceRevision()) }
    }

    /** Called inside the transaction that persists the capture or its pause/resume/cancel receipt. */
    fun onBusinessTaskWritten(task: CoreTask): Boolean {
        if (task.kind != TaskKind.ORGANIZE || task.recordId == null) return false
        val previous = snapshot(task.id)
        val job = previous?.withBusinessState(task) ?: initialSnapshot(task, legacy = false)
        if (job != previous) putSnapshot(job)
        return job.checkpoints.isNotEmpty()
    }

    private fun initialSnapshot(task: CoreTask, legacy: Boolean): TaskJob {
        val recordId = requireNotNull(task.recordId)
        val revision = database.recordDao().visibleRevision(recordId)
        val source = if (revision == task.inputRevision) {
            database.sourceDao().latestVisibleCapture(recordId)
        } else null
        val text = source?.text.orEmpty()
        val preference = preferenceRevision()
        // Earlier schemas did not record the preference used by a queued task.
        val input = TaskInput(recordId, task.inputRevision,
            if (legacy && preference != 0L) -1L else preference, text, ALGORITHM_VERSION,
            captureSourceId = source?.sourceId, captureSourceVersion = source?.sourceVersion)
        val now = System.currentTimeMillis()
        return TaskJob(task.id, taskKey(input), recordId, task.inputRevision, input.preferenceRevision,
            inputDigest(input), input.algorithmVersion, text, status = TaskStatus.valueOf(task.state.name),
            waitingReason = task.waitReason, createdAt = now, updatedAt = now,
            captureSourceId = source?.sourceId, captureSourceVersion = source?.sourceVersion)
    }

    private fun TaskJob.withBusinessState(task: CoreTask): TaskJob {
        val state = TaskStatus.valueOf(task.state.name)
        if (state == status && task.waitReason == waitingReason) return this
        return copy(status = state, waitingReason = task.waitReason,
            retryable = state == TaskStatus.FAILED, leaseToken = leaseToken + 1, leaseUntil = 0,
            updatedAt = System.currentTimeMillis())
    }

    private fun snapshot(id: String): TaskJob? =
        scheduler.get(id)?.let { checkNotNull(DbJson.decode<TaskJob>(it.snapshotJson)) }

    private fun putSnapshot(job: TaskJob) =
        scheduler.put(ScheduledJobEntity(job.jobId, job.idempotencyKey, DbJson.encode(job)))

    override fun find(id: String): TaskJob? {
        val row = tasks.get(id)?.takeIf { it.kind == TaskKind.ORGANIZE.name && it.recordId != null } ?: return null
        val task = checkNotNull(DbJson.decode<CoreTask>(row.taskJson))
        val saved = snapshot(id)
        // Convert an existing v2 task on its first scheduler read, preserving its original ID.
        val job = saved?.withBusinessState(task) ?: initialSnapshot(task, legacy = true).also(::putSnapshot)
        return activateBundledAlgorithm(job)
    }

    /** Upgrade only unfinished tasks from the placeholder pipeline; retain the capture and receipt identity. */
    private fun activateBundledAlgorithm(job: TaskJob): TaskJob {
        if (job.algorithmVersion != LEGACY_VERSION || job.status !in UPGRADEABLE) return job
        val source = database.sourceDao().latestVisibleCapture(job.recordId)?.takeIf {
            database.recordDao().visibleRevision(job.recordId) == job.inputRevision && it.text == job.sourceText
        }
        val wasBlocked = job.status == TaskStatus.WAITING && job.waitingReason == WAITING_ALGORITHM_UNAVAILABLE
        val requeue = wasBlocked || job.status == TaskStatus.RUNNING
        val input = job.input().copy(algorithmVersion = ALGORITHM_VERSION,
            captureSourceId = source?.sourceId, captureSourceVersion = source?.sourceVersion)
        val upgraded = job.copy(algorithmVersion = ALGORITHM_VERSION, idempotencyKey = taskKey(input),
            inputDigest = inputDigest(input), captureSourceId = input.captureSourceId, captureSourceVersion = input.captureSourceVersion,
            status = if (requeue) TaskStatus.QUEUED else job.status, waitingReason = if (requeue) null else job.waitingReason,
            currentStage = TaskStage.EXTRACT, checkpoints = emptyMap(), decisionId = null, evidenceRefs = emptyList(),
            leaseToken = job.leaseToken + 1, leaseUntil = 0, updatedAt = System.currentTimeMillis())
        database.checkpointDao().remove(job.jobId)
        save(upgraded)
        return upgraded
    }

    fun recoverableJobIds(): List<String> = transaction {
        // A Worker started independently of app recovery upgrades on its first find() as well.
        tasks.recoveryCandidates().filter { it.kind == TaskKind.ORGANIZE.name }.forEach { find(it.id) }
        tasks.recoverableJobIds()
    }

    fun loadDietContext(input: TaskInput): DietTaskContext = transaction { dietInputs.read(input) }

    override fun recordCheckpoint(jobId: String, leaseToken: Long, stage: TaskStage, output: StageOutput,
        now: Long, leaseMillis: Long): TaskJob = transaction {
        val updated = super.recordCheckpoint(jobId, leaseToken, stage, output, now, leaseMillis)
        if (output.contextKey != null && dietInputs.key(updated.input()) != output.contextKey) {
            // Roll the proposed checkpoint back before the coordinator records STALE for this lease.
            throw StaleInputException("计算阶段使用的资料已变化")
        }
        updated
    }

    override fun findByKey(key: String): TaskJob? = scheduler.findIdByKey(key)?.let(::find)

    override fun readAll(): List<TaskJob> = tasks.organizeTasks().mapNotNull { find(it.id) }

    override fun save(job: TaskJob) {
        val row = tasks.get(job.jobId)
        val previous = row?.let { checkNotNull(DbJson.decode<CoreTask>(it.taskJson)) }
        require(previous == null || previous.kind == TaskKind.ORGANIZE)
        val stage = job.checkpoints.keys.maxByOrNull { it.sequence }
        val task = CoreTask(id = job.jobId, recordId = job.recordId, kind = TaskKind.ORGANIZE,
            inputRevision = job.inputRevision, revision = (previous?.revision ?: 0L) + 1,
            state = TaskState.valueOf(job.status.name), waitReason = job.waitingReason,
            phase = stage?.name?.lowercase() ?: previous?.phase)
        tasks.put(TaskEntity(task.id, DbJson.encode(task), task.recordId, task.kind.name,
            task.state.name, task.revision, task.inputRevision))
        putSnapshot(job)
        if (stage != null && database.checkpointDao().get(task.id)?.stage != stage.name.lowercase()) {
            database.checkpointDao().put(TaskCheckpointEntity(task.id, stage.name.lowercase(),
                DbJson.encode(job.checkpoints.getValue(stage)), Instant.ofEpochMilli(job.updatedAt).toString()))
        }
        database.libraryDao().advance()
    }

    override fun finishIfCurrent(jobId: String, leaseToken: Long, result: CommitOutput,
        revisions: RevisionProvider, now: Long): TaskJob {
        // Always reread the authoritative record and preference inside the commit transaction.
        return transaction {
            val job = find(jobId) ?: throw IllegalArgumentException("任务不存在：$jobId")
            val rank = job.checkpoints[TaskStage.RANK]
            var decision: DinnerDecision? = null
            val guard = RevisionProvider { recordId ->
                val revision = currentRevision(recordId)
                if (revision != RevisionSnapshot(job.inputRevision, job.preferenceRevision) ||
                    (revisions !== this && revisions.currentRevision(recordId) != revision)
                ) return@RevisionProvider null
                if (rank?.contextKey != null) {
                    try {
                        val decisionId = result.decisionId
                        if (decisionId != null && !result.needsReview) {
                            val context = dietInputs.read(job.input())
                            if (rank.contextKey != context.contextKey ||
                                !database.sourceDao().isOrganized(context.target.ref.sourceId, context.target.ref.sourceVersion)
                            ) return@RevisionProvider null
                            decision = prepareDinnerDecision(job, decisionId, rank, context)
                                ?: return@RevisionProvider null
                        } else if (rank.contextKey != dietInputs.key(job.input())) {
                            return@RevisionProvider null
                        }
                    } catch (_: StaleInputException) {
                        return@RevisionProvider null
                    }
                }
                revision
            }
            val finished = super.finishIfCurrent(jobId, leaseToken, result, guard, now)
            if (finished.status == TaskStatus.COMPLETED) {
                decision?.let { database.recordDao().publishDinnerDecision(job.recordId, it) }
            }
            finished
        }
    }

    /** Erase intermediate material from old projections; keep the task ID for receipt replay. */
    fun purgeIsolatedInputs(recordId: String, throughRecordRevision: Long) = transaction {
        for (row in tasks.forRecord(recordId)) {
            if (row.kind != TaskKind.ORGANIZE.name || row.inputRevision > throughRecordRevision) continue
            val task = checkNotNull(DbJson.decode<CoreTask>(row.taskJson))
            val job = snapshot(task.id)?.withBusinessState(task) ?: initialSnapshot(task, legacy = true)
            putSnapshot(job.copy(sourceText = "", checkpoints = emptyMap(), decisionId = null,
                evidenceRefs = emptyList(), leaseToken = job.leaseToken + 1, leaseUntil = 0))
            database.checkpointDao().remove(task.id)
        }
    }

    companion object {
        private const val LEGACY_VERSION = "capture-v0.1"
        private val UPGRADEABLE = setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.WAITING)
        val ALGORITHM_VERSION: String by lazy { AlgorithmEngine().manifest().algorithmVersion }
    }
}
