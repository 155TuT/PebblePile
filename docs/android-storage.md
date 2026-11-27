# Android 本地存储

首页输入和系统分享的原话都保存在本机的 `pebblepile.sqlite`。0.1.0 使用 Room 2.6.1，数据库版本为 5。记录库按 `recordId` 找到记录，详情页按来源 ID 和版本取回当时的原话。概念模型与实体关系见[数据库设计](database-design.md)，业务请求与回执见[业务接口与仓储契约](core-api-contract.md)。

## 一条记录怎样落库

`LocalCoreApi` 通过 `RoomCoreRepository` 在同一事务中写入记录、来源、修订和回执。用户开启自动整理时，这次写入还会创建任务，登记允许整理的来源版本。Worker 在提交成功后按回执里的任务 ID 领取工作。读取详情也使用同一事务，页面看到的记录、来源和任务属于同一时刻。

| 表 | 保存的内容 |
| --- | --- |
| `libraries` | 资料库修订和以后生效的偏好条件。 |
| `records`、`source_versions` | 记录及其每一版来源。记录中的晚餐决定保存当次候选与证据，引用按来源 ID、版本和原文区间回查。 |
| `organized_sources` | 开启自动整理时保存的确切来源版本。 |
| `tasks`、`scheduler_jobs`、`task_checkpoints` | 业务任务、含各阶段产物和租约的调度快照，以及每个任务最近一次提交的检查点。 |
| `dinner_publication_counts` | 每条记录发布晚餐决定的次数，用于区分发布结果和修改算法输入引起的修订变化。 |
| `operation_receipts` | 以客户端 ID 和操作 ID 为主键，保存请求摘要、首次成功的回执和删除范围。 |
| `deletion_markers`、`deletion_jobs` | 已隔离的对象和后台清理计划。删除确认后，查询即按隔离标记决定可见范围。 |

相同操作重发时返回原回执。沿用操作 ID 更改内容时，错误码为 `INVALID_INPUT`，原因为 `OPERATION_ID_REUSED`，提交依赖的修订已变化时会收到 `REVISION_CONFLICT`。文件库启用 WAL、`synchronous=FULL` 和外键约束。[Room 实体](../src/storage/DatabaseEntities.kt) · [仓储读写](../src/storage/RoomCoreRepository.kt)

## 已安装版本怎样升级

早期版本把资料保存在 SharedPreferences。首次访问仓储时，应用在一个事务中导入记录、来源版本、任务、回执、偏好和资料库修订，提交后清理旧数据。两处都有数据时，应用先核对原快照。数据冲突或旧资料解析失败时保留原件，供后续重试。[旧资料导入](../src/storage/RoomStore.kt)

| 数据库版本 | 迁移内容 |
| --- | --- |
| 1 → 2 | 改用复合操作键及清理任务主键。相同操作留下最后一次存储的回执。 |
| 2 → 3 | 新增调度快照表，为输入键建立唯一索引。 |
| 3 → 4 | 根据成功创建整理任务的历史回执，补登记允许整理的来源版本。 |
| 4 → 5 | 新增晚餐决定发布计数。对已完成排序、记录修订仍匹配且记录仍可见的旧版晚餐任务，沿用原任务 ID 和检查点，重新排队发布决定。 |

文件库配置与各版 SQL 见[数据库定义](../src/storage/PebbleDatabase.kt)。

## 存储回归

在仓库根目录运行 JVM 测试，连接模拟器或真机后运行设备测试。

```powershell
python scripts/gradle.py :app:testDebugUnitTest --tests "*RoomCoreContractTest"
python scripts/gradle.py :app:connectedDebugAndroidTest
```

`RoomCoreContractTest` 检查并发重发、事务回滚、来源版本、时间线顺序和文件重开。`RoomStorageContractTest` 在设备上检查旧资料导入、迁移失败后的原件，以及文件库的 WAL、同步级别和外键约束。
