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

    fun commit(
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
    fun submit(input: TaskInput): TaskView {
        val at = now()
        val digest = inputDigest(input)
        val key = listOf(
            input.recordId,
            input.revision,
            input.preferenceRevision,
            input.algorithmVersion,
        ).joinToString(":")
        val job = TaskJob(
            jobId = stableJobId(input),
            idempotencyKey = key,
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

    private fun execute(claimed: TaskJob): TaskRunOutcome {
        var job = claimed
        return try {
            for (stage in TaskStage.entries.filter { it != TaskStage.COMMIT }) {
                if (job.checkpoints.containsKey(stage)) continue
                assertRevisionCurrent(job)
                val output = pipeline.runStage(stage, job.input(), job.checkpoints)
                // 计算期间用户又修改了材料或偏好时，不发布该阶段结果。
                assertRevisionCurrent(job)
                job = store.recordCheckpoint(
                    jobId = job.jobId,
                    leaseToken = job.leaseToken,
                    stage = stage,
                    output = output,
                    now = now(),
                )
            }

            assertRevisionCurrent(job)
            val commit = pipeline.commit(job.input(), job.checkpoints)
            val evidence = (job.checkpoints.values.flatMap { it.evidenceRefs } + commit.evidenceRefs).distinct()
            store.markCompleted(
                jobId = job.jobId,
                leaseToken = job.leaseToken,
                decisionId = commit.decisionId,
                evidenceRefs = evidence,
                now = now(),
            )
            TaskRunOutcome.COMPLETED
        } catch (cause: StaleInputException) {
            runCatching { store.markStale(job.jobId, now()) }
            TaskRunOutcome.STALE
        } catch (_: LeaseLostException) {
            TaskRunOutcome.IDLE
        } catch (cause: RetryableTaskException) {
            runCatching { store.markWaiting(job.jobId, job.leaseToken, WAITING_RETRY_PENDING, now()) }
            TaskRunOutcome.WAITING
        } catch (cause: Throwable) {
            if (store.get(job.jobId)?.status == TaskStatus.RUNNING) {
                runCatching { store.markFailed(job.jobId, job.leaseToken, true, now()) }
            }
            TaskRunOutcome.FAILED
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
        TaskStatus.QUEUED, TaskStatus.RUNNING -> TaskRunOutcome.IDLE
    }
}
