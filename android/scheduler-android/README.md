# WorkManager 接入说明

`:scheduler-android` 是 Android 库。它通过 `:scheduler-core` 执行任务，使用 AndroidX WorkManager 2.9.1 调度工作。

## 应用接入

1. `:app` 添加 `implementation(project(":scheduler-android"))`。在 `PebblePileApplication` 中用 Room 仓储、材料与偏好修订读取器、算法管线构造协调器。
2. 让 Application 实现 `Configuration.Provider`，用 `Configuration.Builder().setWorkerFactory(PersistentTaskWorkerFactory(coordinator)).build()` 提供配置。在合并后的清单中移除 `androidx.startup.InitializationProvider` 下的 `androidx.work.WorkManagerInitializer` 元数据，保留同一 provider 中的其他初始化器。系统重建应用进程后，也会使用这套工厂。
3. 保存事务提交任务后调用 `PersistentTaskWorker.enqueue(context, jobId)`。同一任务重复入队默认保留已有工作。用户继续或重试时，先通过 `controlTask` 更新业务状态，再用 `ExistingWorkPolicy.REPLACE` 重新入队，确保先前正在退出的 Worker 也能交接。
4. 暂停或取消时，先更新任务状态并撤销租约，再调用 `PersistentTaskWorker.cancel(context, jobId)` 结束系统工作。

| 任务结果 | Worker 处理 |
| --- | --- |
| `BUSY` 或 `WAITING(retry_pending)` | 按指数退避重试。现有租约到期后，下一次唤醒可从检查点继续。 |
| 用户暂停或其他等待原因 | 结束本次唤醒，等待显式继续或新调度。 |
| 已完成、待确认、已过期、已取消或任务缺失 | 结束本次唤醒。 |
| 失败 | 返回失败，页面按业务回执显示可执行操作。 |

阶段计算需要在单次租约内完成，保存检查点会续租。集成后按设备实测耗时配置租期，再用进程回收场景验收持久恢复。
