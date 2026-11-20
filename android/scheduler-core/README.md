# Issue #8 持久任务与 Agent 调度

本目录实现 `PebblePile` Android `v0.1.0` 的持久任务核心，负责保存后的整理任务、阶段检查点、恢复、租约、幂等提交和迟到结果处理。

## 选定方案

选择 **WorkManager + 业务任务表**：

- 用户保存记录后立即收到“已保存”，后台任务独立执行。
- 进程被结束、系统回收或设备进入等待状态后，任务仍可由 WorkManager 重新调度。
- 真实的 `TaskStore` 由 #7 的 SQLite/Room 实现替换，核心只依赖接口。
- Android Worker 适配代码位于 `android/scheduler-android/`，#4 建立 Android 工程后接入 WorkerFactory。

## 核心状态

任务状态：

`queued / running / waiting / needs_review / completed / failed / cancelled / stale`

阶段顺序：

`EXTRACT → UPDATE → CONTEXT → RANK → COMMIT`

每次领取任务都会递增租约令牌。过期租约允许恢复执行者重新领取；旧执行者提交时因令牌不匹配被拒绝。

## 已实现行为

- 同一 `recordId + revision + preferenceRevision + algorithmVersion` 幂等创建任务。
- `getTask(jobId)` 返回状态、当前阶段、最后检查点、等待原因、重试能力和可执行操作。
- `controlTask(jobId, action)` 支持暂停、继续、重试和取消。
- 每个阶段成功后写入检查点；恢复时跳过已提交阶段。
- 同一阶段重复执行不能产生第二份结果。
- 提交前重新读取记录修订和偏好修订；任一项变化则将任务转为 `stale`，不调用提交。
- 可重试错误进入 `waiting(retry_pending)`；普通失败进入 `failed` 并保留人工重试入口。
- 暂停或取消会立即撤销旧租约。

## 目录

```text
android/
├── scheduler-core/       # 纯 Kotlin 核心与自测
└── scheduler-android/    # WorkManager 适配源码
```

## 运行自测

仓库没有 Android 工程时，可直接用 Kotlin 编译器运行纯核心测试：

```powershell
powershell -File android/scheduler-core/check.ps1
```

脚本默认查找 `.build/tools/kotlinc/bin/kotlinc.bat`，也可以设置 `KOTLINC` 环境变量指向本地编译器。

## 与其他模块的交接

- #6：`AgentTaskPipeline` 与 `RevisionProvider` 是接入点；核心事务实现应在提交时复核修订。
- #7：实现 `TaskStore`，对应 `jobs` 与 `job_steps` 表，保持租约、阶段唯一键和原子提交语义。
- #9：实现 `AgentTaskPipeline` 的四个阶段与提交函数，先使用固定样例接入。
- #5：读取 `TaskView` 的 `status`、`currentStage`、`waitingReason`、`actions`、`decisionId` 和 `evidenceRefs`。
- #10：使用 `SchedulerSelfTest` 中覆盖的中断、重试、迟到、并发领取和取消场景做回归。

## 尚未完成的集成

- Android 工程和 WorkerFactory 安装依赖 #4。
- Room/SQLite 的 `TaskStore` 依赖 #7。
- 真实抽取、排序和提交函数依赖 #9 与 #6。
- 真机进程终止后的 WorkManager 恢复需在 #4 工程建立后执行。
