# WorkManager 接入说明

`PersistentTaskWorker` 和 `PersistentTaskWorkerFactory` 依赖 AndroidX WorkManager 与协程，不能在当前没有 Android 工程与 SDK 的仓库中编译。接入 #4 的 Android 工程时：

1. 在 app 的 `build.gradle.kts` 添加 WorkManager 依赖：
   ```kotlin
   implementation("androidx.work:work-runtime-ktx:<项目统一版本>")
   ```
2. 在该工程的依赖注入位置构造唯一 `PersistentTaskCoordinator`。
3. 在 `Application` 中安装：
   ```kotlin
   WorkManager.initialize(
       context,
       Configuration.Builder()
           .setWorkerFactory(PersistentTaskWorkerFactory(coordinator))
           .build(),
   )
   ```
4. 记录保存成功后调用 `PersistentTaskWorker.enqueue(context, jobId)`。
5. 用户取消或删除输入时同时调用 `controlTask(jobId, CANCEL)` 和 `PersistentTaskWorker.cancel(context, jobId)`。

Worker 返回值：

| 核心结果 | Worker 结果 |
| --- | --- |
| `COMPLETED/NEEDS_REVIEW/STALE/CANCELLED/IDLE` | `Result.success()` |
| `WAITING(retry_pending)` | `Result.retry()` |
| `WAITING(user_paused/server unavailable)` | `Result.success()`，等待 UI 操作或新的调度 |
| `FAILED` | `Result.failure()`，由用户控制重试 |

`ExistingWorkPolicy.KEEP` 保证同一 `jobId` 不重复入队。真正的阶段结果幂等由 `TaskStore` 的阶段检查点和租约令牌保证。
