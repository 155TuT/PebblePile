package com.pebblepile.scheduler.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pebblepile.scheduler.PersistentTaskCoordinator
import com.pebblepile.scheduler.TaskRunOutcome
import com.pebblepile.scheduler.WAITING_RETRY_PENDING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The application routes a durable job ID to its business task. */
fun interface PersistentJobRunner {
    suspend fun run(jobId: String): ListenableWorker.Result
}

fun coordinatorRunner(coordinator: PersistentTaskCoordinator) = PersistentJobRunner { jobId ->
    val outcome = coordinator.runJob(jobId)
    val reason = if (outcome == TaskRunOutcome.WAITING) coordinator.getTask(jobId)?.waitingReason else null
    workerResult(outcome, reason)
}

class PersistentTaskWorker(
    context: Context,
    parameters: WorkerParameters,
    private val runner: PersistentJobRunner,
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters, coordinator: PersistentTaskCoordinator) :
        this(context, parameters, coordinatorRunner(coordinator))

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        return withContext(Dispatchers.IO) { runner.run(jobId) }
    }

    companion object {
        const val KEY_JOB_ID = "jobId"
        private const val UNIQUE_PREFIX = "pebblepile-task-"

        fun enqueue(context: Context, jobId: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP): Operation {
            val request = OneTimeWorkRequestBuilder<PersistentTaskWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + jobId,
                policy,
                request,
            )
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + jobId)
        }
    }
}

/** 将协调器注入 Worker；应用需要使用 Configuration.Builder().setWorkerFactory() 安装。 */
class PersistentTaskWorkerFactory(
    private val runner: PersistentJobRunner,
) : WorkerFactory() {
    constructor(coordinator: PersistentTaskCoordinator) : this(coordinatorRunner(coordinator))

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == PersistentTaskWorker::class.java.name) {
        PersistentTaskWorker(appContext, workerParameters, runner)
    } else {
        null
    }
}

internal fun workerResult(outcome: TaskRunOutcome, waitingReason: String?): ListenableWorker.Result = when (outcome) {
    TaskRunOutcome.BUSY -> ListenableWorker.Result.retry()
    TaskRunOutcome.WAITING -> if (waitingReason == WAITING_RETRY_PENDING) {
        ListenableWorker.Result.retry()
    } else {
        ListenableWorker.Result.success()
    }
    TaskRunOutcome.FAILED -> ListenableWorker.Result.failure()
    TaskRunOutcome.COMPLETED, TaskRunOutcome.NEEDS_REVIEW, TaskRunOutcome.STALE,
    TaskRunOutcome.CANCELLED, TaskRunOutcome.IDLE -> ListenableWorker.Result.success()
}
