package com.pebblepile.android

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.pebblepile.android.algorithm.DietTaskPipeline
import com.pebblepile.android.db.RoomCleanupRunner
import com.pebblepile.android.db.RoomStore
import com.pebblepile.android.db.RoomTaskStore
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.TaskKind
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.scheduler.PersistentTaskCoordinator
import com.pebblepile.scheduler.work.PersistentJobRunner
import com.pebblepile.scheduler.work.PersistentTaskWorker
import com.pebblepile.scheduler.work.coordinatorRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import kotlin.coroutines.coroutineContext

internal class AndroidTaskRuntime(private val context: Context) : PersistentJobRunner {
    private val database by lazy { RoomStore.database(context) }
    private val repository by lazy { RoomStore.repository(context) }
    private val cleanup by lazy { RoomCleanupRunner(database) }
    private val store by lazy { RoomTaskStore(database) }
    private val organize by lazy {
        coordinatorRunner(PersistentTaskCoordinator(store, store, DietTaskPipeline(store::loadDietContext)))
    }

    fun enqueue(task: CoreTask, replace: Boolean) {
        if (task.kind != TaskKind.ORGANIZE && task.kind != TaskKind.DELETE_CLEANUP) return
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        try {
            PersistentTaskWorker.enqueue(context, task.id, policy)
        } catch (error: RuntimeException) {
            // The business transaction has committed. Startup recovery retries this wakeup.
            Log.w("PebblePileTasks", "WorkManager could not enqueue a committed task", error)
        }
    }

    fun recover() {
        // Complete any legacy import before enumerating work from the authoritative database.
        val pending = repository.write { store.recoverableJobIds() }
        for (id in pending) PersistentTaskWorker.enqueue(context, id).result.get()
    }

    override suspend fun run(jobId: String): Result = try {
        when (repository.read { it.task(jobId)?.kind }) {
            TaskKind.ORGANIZE -> organize.run(jobId)
            TaskKind.DELETE_CLEANUP -> {
                do {
                    coroutineContext.ensureActive()
                } while (!cleanup.advance(jobId))
                Result.success()
            }
            else -> Result.success()
        }
    } catch (_: RepositoryWriteException) {
        Result.retry()
    } catch (_: SQLiteException) {
        Result.retry()
    }
}

class RecoverTasksWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            (applicationContext as PebblePileApplication).taskRuntime.recover()
            Result.success()
        } catch (_: RepositoryWriteException) {
            Result.retry()
        } catch (_: SQLiteException) {
            Result.retry()
        } catch (_: ExecutionException) {
            Result.retry()
        }
    }
}
