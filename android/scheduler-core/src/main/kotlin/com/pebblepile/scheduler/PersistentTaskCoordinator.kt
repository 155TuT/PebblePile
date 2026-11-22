package com.pebblepile.scheduler

/** 用于在提交前核对原材料和用户偏好是否仍为任务开始时的版本。 */
fun interface RevisionProvider {
    fun currentRevision(recordId: String): RevisionSnapshot?
}

/** #9 固定样例先实现此接口；真实算法只需替换实现。 */
interface AgentTaskPipeline {
    fun runStage(
        stage: TaskStage,
        input: TaskInput,
        completed: Map<TaskStage, StageOutput>,
    ): StageOutput

    /** 计算待提交结果，业务写入统一由 TaskStore.finishIfCurrent 完成。 */
    fun prepareCommit(
        input: TaskInput,
        completed: Map<TaskStage, StageOutput>,
    ): CommitOutput
}

class PersistentTaskCoordinator(
    private val store: TaskStore,
    private val revisions: RevisionProvider,
    private val pipeline: AgentTaskPipeline,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val leaseMillis: Long = 60_000,
) {
    init { require(leaseMillis > 0) { "任务租期须大于零" } }

    fun submit(input: TaskInput): TaskView {
        val at = now()
        val digest = inputDigest(input)
        val job = TaskJob(
            jobId = stableJobId(input),
            idempotencyKey = taskKey(input),
            recordId = input.recordId,
            inputRevision = input.revision,
            preferenceRevision = input.preferenceRevision,
            inputDigest = digest,
            algorithmVersion = input.algorithmVersion,
            sourceText = input.text,
            createdAt = at,
            updatedAt = at,
        )
        return store.createOrGet(job).toView()
    }

    fun getTask(jobId: String): TaskView? = store.get(jobId)?.toView()

    fun controlTask(jobId: String, action: TaskAction): TaskView =
        store.control(jobId, action, now()).toView()

    fun runNext(): TaskRunOutcome {
        val claimed = store.claimNext(now(), leaseMillis) ?: return TaskRunOutcome.IDLE
        return execute(claimed)
    }

    fun runJob(jobId: String): TaskRunOutcome {
        val claimed = store.claim(jobId, now(), leaseMillis)
        if (claimed != null) return execute(claimed)
        return store.get(jobId)?.let(::terminalOutcome) ?: TaskRunOutcome.IDLE
    }

    private fun execute(claimed: TaskJob): TaskRunOutcome = try {
        executeWithLease(claimed)
    } catch (_: LeaseLostException) {
        store.get(claimed.jobId)?.let(::terminalOutcome) ?: TaskRunOutcome.IDLE
    }

    private fun executeWithLease(claimed: TaskJob): TaskRunOutcome {
        var job = claimed
        return try {
            for (stage in TaskStage.entries.filter { it != TaskStage.COMMIT }) {
                if (job.checkpoints.containsKey(stage)) continue
                assertRevisionCurrent(job)
                val output = pipeline.runStage(stage, job.input(), job.checkpoints)
                // 检查计算期间材料和偏好的修订。
                assertRevisionCurrent(job)
                job = store.recordCheckpoint(
                    jobId = job.jobId,
                    leaseToken = job.leaseToken,
                    stage = stage,
                    output = output,
                    now = now(),
                    leaseMillis = leaseMillis,
                )
            }

            assertRevisionCurrent(job)
            val result = pipeline.prepareCommit(job.input(), job.checkpoints)
            terminalOutcome(store.finishIfCurrent(job.jobId, job.leaseToken, result, revisions, now()))
        } catch (_: StaleInputException) {
            terminalOutcome(store.markStale(job.jobId, job.leaseToken, now()))
        } catch (cause: LeaseLostException) {
            throw cause
        } catch (cause: java.util.concurrent.CancellationException) {
            throw cause
        } catch (_: AlgorithmUnavailableException) {
            terminalOutcome(store.markWaiting(job.jobId, job.leaseToken, WAITING_ALGORITHM_UNAVAILABLE, now()))
        } catch (_: RetryableTaskException) {
            terminalOutcome(store.markWaiting(job.jobId, job.leaseToken, WAITING_RETRY_PENDING, now()))
        } catch (_: Exception) {
            terminalOutcome(store.markFailed(job.jobId, job.leaseToken, true, now()))
        }
    }

    private fun assertRevisionCurrent(job: TaskJob) {
        val current = revisions.currentRevision(job.recordId)
            ?: throw StaleInputException("原记录已不存在")
        if (current.recordRevision != job.inputRevision) {
            throw StaleInputException("记录修订已变化")
        }
        if (current.preferenceRevision != job.preferenceRevision) {
            throw StaleInputException("用户偏好已变化")
        }
    }

    private fun terminalOutcome(job: TaskJob): TaskRunOutcome = when (job.status) {
        TaskStatus.COMPLETED -> TaskRunOutcome.COMPLETED
        TaskStatus.WAITING -> TaskRunOutcome.WAITING
        TaskStatus.NEEDS_REVIEW -> TaskRunOutcome.NEEDS_REVIEW
        TaskStatus.STALE -> TaskRunOutcome.STALE
        TaskStatus.FAILED -> TaskRunOutcome.FAILED
        TaskStatus.CANCELLED -> TaskRunOutcome.CANCELLED
        TaskStatus.QUEUED, TaskStatus.RUNNING -> TaskRunOutcome.BUSY
    }
}
