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
        leaseMillis: Long,
    ): TaskJob
    fun markWaiting(jobId: String, leaseToken: Long, reason: String, now: Long): TaskJob
    fun markFailed(jobId: String, leaseToken: Long, retryable: Boolean, now: Long): TaskJob
    fun markStale(jobId: String, leaseToken: Long, now: Long): TaskJob
    /** 在同一事务校验租约和输入修订，再发布决定、证据与任务终态。 */
    fun finishIfCurrent(
        jobId: String,
        leaseToken: Long,
        result: CommitOutput,
        revisions: RevisionProvider,
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
        idempotency[job.idempotencyKey]?.let {
            val existing = jobs.getValue(it)
            require(existing.inputDigest == job.inputDigest) { "同一修订的记录内容须一致" }
            return existing
        }
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
        leaseMillis: Long,
    ): TaskJob {
        val job = requireLease(jobId, leaseToken, now)
        if (stage == TaskStage.COMMIT) throw IllegalArgumentException("提交阶段使用 finishIfCurrent")
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
            leaseUntil = now + leaseMillis,
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
    override fun markStale(jobId: String, leaseToken: Long, now: Long): TaskJob {
        val job = requireLease(jobId, leaseToken, now)
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
    override fun finishIfCurrent(
        jobId: String,
        leaseToken: Long,
        result: CommitOutput,
        revisions: RevisionProvider,
        now: Long,
    ): TaskJob {
        val job = requireLease(jobId, leaseToken, now)
        check(job.currentStage == TaskStage.COMMIT) { "发布结果前须完成 RANK 检查点" }
        if (revisions.currentRevision(job.recordId) != RevisionSnapshot(job.inputRevision, job.preferenceRevision)) {
            return markStale(jobId, leaseToken, now)
        }
        val updated = job.copy(
            status = if (result.needsReview) TaskStatus.NEEDS_REVIEW else TaskStatus.COMPLETED,
            waitingReason = null,
            retryable = false,
            decisionId = result.decisionId,
            evidenceRefs = (job.checkpoints.values.flatMap { it.evidenceRefs } + result.evidenceRefs).distinct(),
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
        check(action in job.toView().actions) { "任务 ${job.status} 可执行 ${job.toView().actions}" }
        val updated = when (action) {
            TaskAction.PAUSE -> job.copy(
                status = TaskStatus.WAITING,
                waitingReason = WAITING_USER_PAUSED,
                retryable = false,
                leaseToken = job.leaseToken + 1,
                leaseUntil = 0,
                updatedAt = now,
            )
            TaskAction.RESUME, TaskAction.RETRY -> job.copy(
                status = TaskStatus.QUEUED,
                waitingReason = null,
                retryable = false,
                updatedAt = now,
            )
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

