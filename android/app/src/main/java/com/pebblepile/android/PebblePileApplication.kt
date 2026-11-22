package com.pebblepile.android

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pebblepile.android.service.CoreApi
import com.pebblepile.scheduler.work.PersistentTaskWorkerFactory

class PebblePileApplication : Application(), Configuration.Provider {
    internal val taskRuntime by lazy { AndroidTaskRuntime(this) }
    val coreApi: CoreApi by lazy { DemoCoreApi(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(PersistentTaskWorkerFactory(taskRuntime))
            .build()

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "pebblepile-recover-tasks", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RecoverTasksWorker>().build(),
        )
    }
}
