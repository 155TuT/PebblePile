package com.pebblepile.scheduler

/** #7 的真实 SQLite/Room 任务表实现此接口，核心调度不依赖具体存储。 */
interface TaskStore {
    fun createOrGet(job: TaskJob): TaskJob
    fun get(jobId: String): TaskJob?
    fun claim(jobId: String, now: Long, leaseMillis: Long): TaskJob?
    fun claimNext(now: Long, leaseMillis: Long): TaskJob?
    fun recordCheckpoint(
        jobId: String,
        leaseToken: Long,
        stage: TaskStage,
        output: StageOutput,
        now: Long,
    ): TaskJob
    fun markWaiting(jobId: String, leaseToken: Long, reason: String, now: Long): TaskJob
    fun markFailed(jobId: String, leaseToken: Long, retryable: Boolean, now: Long): TaskJob
    fun markStale(jobId: String, now: Long): TaskJob
    fun markCompleted(
        jobId: String,
        leaseToken: Long,
        decisionId: String,
        evidenceRefs: List<String>,
        now: Long,
    ): TaskJob
    fun markNeedsReview(
        jobId: String,
        leaseToken: Long,
        decisionId: String,
        evidenceRefs: List<String>,
        now: Long,
    ): TaskJob
    fun control(jobId: String, action: TaskAction, now: Long): TaskJob
    fun list(): List<TaskJob>
}

/**
 * 内存实现用于契约测试和接入前的端到端编排。
 * 真实 Android 工程应由 #7 的 Room/SQLite 实现替换，保持相同原子语义。
 */
class InMemoryTaskStore : TaskStore {
    private val jobs = LinkedHashMap<String, TaskJob>()
    private val idempotency = mutableMapOf<String, String>()

    @Synchronized
    override fun createOrGet(job: TaskJob): TaskJob {
        idempotency[job.idempotencyKey]?.let { return jobs.getValue(it) }
        val existing = jobs[job.jobId]
        if (existing != null) return existing
        jobs[job.jobId] = job
        idempotency[job.idempotencyKey] = job.jobId
        return job
    }

    @Synchronized
    override fun get(jobId: String): TaskJob? = jobs[jobId]

    @Synchronized
    override fun claim(jobId: String, now: Long, leaseMillis: Long): TaskJob? {
        val job = jobs[jobId] ?: return null
        return claimInternal(job, now, leaseMillis)
    }

    @Synchronized
    override fun claimNext(now: Long, leaseMillis: Long): TaskJob? {
        val next = jobs.values
            .filter {
                it.status == TaskStatus.QUEUED ||
                    (it.status == TaskStatus.WAITING && it.waitingReason != WAITING_USER_PAUSED) ||
                    (it.status == TaskStatus.RUNNING && it.leaseUntil <= now)
            }
            .sortedBy { it.createdAt }
            .firstOrNull { it.leaseUntil <= now }
            ?: return null
        return claimInternal(next, now, leaseMillis)
    }

    private fun claimInternal(job: TaskJob, now: Long, leaseMillis: Long): TaskJob? {
        val runnable = job.status == TaskStatus.QUEUED ||
            (job.status == TaskStatus.WAITING && job.waitingReason != WAITING_USER_PAUSED) ||
            (job.status == TaskStatus.RUNNING && job.leaseUntil <= now)
        if (!runnable || job.leaseUntil > now) return null
        val updated = job.copy(
            status = TaskStatus.RUNNING,
            waitingReason = null,
            retryable = false,
            attempts = job.attempts + 1,
            leaseToken = job.leaseToken + 1,
            leaseUntil = now + leaseMillis,
            updatedAt = now,
        )
        jobs[job.jobId] = updated
        return updated
    }

    @Synchronized
    override fun recordCheckpoint(
        jobId: String,
        leaseToken: Long,
        stage: TaskStage,
        output: StageOutput,
        now: Long,
    ): TaskJob {
        val job = requireLease(jobId, leaseToken, now)
        if (stage == TaskStage.COMMIT) throw IllegalArgumentException("提交阶段使用 markCompleted")
        val existing = job.checkpoints[stage]
        if (existing != null) {
            if (existing != output) throw IllegalStateException("同一阶段出现冲突结果：$stage")
            return job
        }
        if (job.currentStage != stage) throw IllegalStateException("阶段顺序不匹配：期望 ${job.currentStage}，实际 $stage")
        val next = stage.next() ?: TaskStage.COMMIT
        val updated = job.copy(
            currentStage = next,
            checkpoints = job.checkpoints + (stage to output),
            updatedAt = now,
        )
        jobs[jobId] = updated
        return updated
    }

    @Synchronized
    override fun markWaiting(jobId: String, leaseToken: Long, reason: String, now: Long): TaskJob {
        val job = requireLease(jobId, leaseToken, now)
        val updated = job.copy(
            status = TaskStatus.WAITING,
            waitingReason = reason,
            retryable = true,
            leaseToken = job.leaseToken + 1,
            leaseUntil = 0,
            updatedAt = now,
        )
        jobs[jobId] = updated
        return updated
    }

    @Synchronized
    override fun markFailed(jobId: String, leaseToken: Long, retryable: Boolean, now: Long): TaskJob {
        val job = requireLease(jobId, leaseToken, now)
        val updated = job.copy(
            status = TaskStatus.FAILED,
            waitingReason = null,
            retryable = retryable,
            leaseToken = job.leaseToken + 1,
            leaseUntil = 0,
            updatedAt = now,
        )
        jobs[jobId] = updated
        return updated
    }

    @Synchronized
    override fun markStale(jobId: String, now: Long): TaskJob {
        val job = jobs[jobId] ?: throw IllegalArgumentException("任务不存在：$jobId")
        if (job.status == TaskStatus.COMPLETED || job.status == TaskStatus.STALE) return job
        val updated = job.copy(
            status = TaskStatus.STALE,
            waitingReason = null,
            retryable = false,
            leaseToken = job.leaseToken + 1,
            leaseUntil = 0,
            updatedAt = now,
        )
        jobs[jobId] = updated
        return updated
    }

    @Synchronized
    override fun markCompleted(
        jobId: String,
        leaseToken: Long,
        decisionId: String,
        evidenceRefs: List<String>,
        now: Long,
    ): TaskJob {
        val job = requireLease(jobId, leaseToken, now)
        val updated = job.copy(
            status = TaskStatus.COMPLETED,
            currentStage = TaskStage.COMMIT,
            waitingReason = null,
            retryable = false,
            decisionId = decisionId,
            evidenceRefs = evidenceRefs,
            leaseToken = job.leaseToken + 1,
            leaseUntil = 0,
            updatedAt = now,
        )
        jobs[jobId] = updated
        return updated
    }

    @Synchronized
    override fun markNeedsReview(
        jobId: String,
        leaseToken: Long,
        decisionId: String,
        evidenceRefs: List<String>,
        now: Long,
    ): TaskJob {
        val job = requireLease(jobId, leaseToken, now)
        val updated = job.copy(
            status = TaskStatus.NEEDS_REVIEW,
            decisionId = decisionId,
            evidenceRefs = evidenceRefs,
            leaseToken = job.leaseToken + 1,
            leaseUntil = 0,
            updatedAt = now,
        )
        jobs[jobId] = updated
        return updated
    }

    @Synchronized
    override fun control(jobId: String, action: TaskAction, now: Long): TaskJob {
        val job = jobs[jobId] ?: throw IllegalArgumentException("任务不存在：$jobId")
        if (job.status == TaskStatus.COMPLETED || job.status == TaskStatus.CANCELLED || job.status == TaskStatus.STALE) {
            return job
        }
        val updated = when (action) {
            TaskAction.PAUSE -> {
                if (job.status != TaskStatus.QUEUED && job.status != TaskStatus.RUNNING && job.status != TaskStatus.WAITING) {
                    throw IllegalStateException("当前状态不能暂停")
                }
                job.copy(
                    status = TaskStatus.WAITING,
                    waitingReason = WAITING_USER_PAUSED,
                    retryable = true,
                    leaseToken = job.leaseToken + 1,
                    leaseUntil = 0,
                    updatedAt = now,
                )
            }
            TaskAction.RESUME -> {
                if (job.status != TaskStatus.WAITING || job.waitingReason != WAITING_USER_PAUSED) {
                    throw IllegalStateException("当前状态不能继续")
                }
                job.copy(
                    status = TaskStatus.QUEUED,
                    waitingReason = null,
                    retryable = false,
                    updatedAt = now,
                )
            }
            TaskAction.RETRY -> {
                if (job.status != TaskStatus.FAILED && job.status != TaskStatus.WAITING) {
                    throw IllegalStateException("当前状态不能重试")
                }
                job.copy(
                    status = TaskStatus.QUEUED,
                    waitingReason = null,
                    retryable = false,
                    updatedAt = now,
                )
            }
            TaskAction.CANCEL -> job.copy(
                status = TaskStatus.CANCELLED,
                waitingReason = null,
                retryable = false,
                leaseToken = job.leaseToken + 1,
                leaseUntil = 0,
                updatedAt = now,
            )
            TaskAction.OPEN_DETAIL -> job
        }
        jobs[jobId] = updated
        return updated
    }

    @Synchronized
    override fun list(): List<TaskJob> = jobs.values.sortedBy { it.createdAt }

    private fun requireLease(jobId: String, leaseToken: Long, now: Long): TaskJob {
        val job = jobs[jobId] ?: throw IllegalArgumentException("任务不存在：$jobId")
        if (job.status != TaskStatus.RUNNING || job.leaseToken != leaseToken || job.leaseUntil <= now) {
            throw LeaseLostException(jobId)
        }
        return job
    }
}

