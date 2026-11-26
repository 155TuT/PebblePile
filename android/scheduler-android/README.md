# Android 后台任务与进程恢复

`:scheduler-android` 使用 AndroidX WorkManager 2.9.1 唤醒已写入资料库的任务。首页输入和系统分享都在 Room 提交成功后按回执中的 `jobId` 入队，系统工作名为 `pebblepile-task-<jobId>`。同一工作默认使用 `KEEP` 去重，用户继续或重试时使用 `REPLACE`。

## 冷启动怎样恢复

`PebblePileApplication` 配置 `PersistentTaskWorkerFactory`，并在启动时排入一项恢复工作。恢复程序先完成旧资料导入，再从 Room 查找排队中、执行中和等待重试的整理任务，以及待处理的删除任务。这样即使进程在提交业务事务后、唤醒 Worker 前退出，下次启动仍能找到原任务。旧版因等待算法而挂起的整理任务沿用原编号执行，手动暂停的任务等用户继续。

Worker 收到任务 ID 后，`AndroidTaskRuntime` 按任务类型分派。整理任务由 `RoomTaskStore` 领取，从已有检查点继续计算。删除任务由 `RoomCleanupRunner` 逐步提交清理结果。[应用调度入口](../app/src/main/java/com/pebblepile/android/AndroidTaskRuntime.kt) · [调度阶段与回归](../scheduler-core/README.md)

| 业务结果 | WorkManager 的处理 |
| --- | --- |
| 任务正由其他执行者持有，或等待原因是 `retry_pending` | 返回重试。退避时间从 30 秒起按指数增长。 |
| 用户已暂停，或任务因其他原因等待 | 结束本次唤醒，由用户操作或后续调度继续。 |
| 任务完成、待核对、过期、已取消，或数据库中已找不到该编号 | 结束本次唤醒，业务状态由 Room 保存。 |
| 整理任务失败 | Worker 返回失败。任务可执行的操作由业务状态决定。 |
| 仓储写入异常或 SQLite 异常抛回 Android 运行入口 | Worker 返回重试。 |

整理任务的默认租期是 60 秒，每次保存阶段产物时续租。某阶段计算超过租期时，新的执行者可以接管任务，再次从最近一次有效检查点计算。[执行者与重试规则](src/main/kotlin/com/pebblepile/scheduler/work/PersistentTaskWorker.kt)
