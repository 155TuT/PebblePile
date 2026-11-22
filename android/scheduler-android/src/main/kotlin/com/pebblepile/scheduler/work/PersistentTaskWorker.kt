package com.pebblepile.scheduler.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
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

/**
 * WorkManager 从输入数据读取任务标识。
 * 核心状态机不依赖 Android，本文件只负责领取一次持久任务并按结果返回 Worker 结果。
 */
class PersistentTaskWorker(
    context: Context,
    parameters: WorkerParameters,
    private val coordinator: PersistentTaskCoordinator,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val outcome = withContext(Dispatchers.IO) { coordinator.runJob(jobId) }
        val waitingReason = if (outcome == TaskRunOutcome.WAITING) coordinator.getTask(jobId)?.waitingReason else null
        return workerResult(outcome, waitingReason)
    }

    companion object {
        const val KEY_JOB_ID = "jobId"
        private const val UNIQUE_PREFIX = "pebblepile-task-"

        fun enqueue(context: Context, jobId: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val request = OneTimeWorkRequestBuilder<PersistentTaskWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
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
    private val coordinator: PersistentTaskCoordinator,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == PersistentTaskWorker::class.java.name) {
        PersistentTaskWorker(appContext, workerParameters, coordinator)
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
