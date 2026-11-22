# SQLite 存储与查询交接

周恪玄负责 Android 本地存储。首页记录和系统分享共用 `DemoCoreApi`，经 `LocalCoreApi` 把原文、任务和回执写入 `pebblepile.sqlite`。[Issue #7](https://github.com/155TuT/PebblePile/issues/7)

## 选型

采用 Room 2.6.1 和 SQLite。KSP 在编译时校验 DAO 查询，资料库修订、来源版本和操作键由表结构管理。备选的 `SQLiteOpenHelper` 适合直接控制 SQL，建表、字段映射和查询校验都要由项目维护。两个方案共用 `CoreRepository`，业务层按同一套契约读写。

## 写入与读取

| 表 | 职责 |
| --- | --- |
| `libraries` | 保存资料库修订和后续生效的偏好条件。 |
| `records`、`source_versions` | 按记录 ID 取详情，按来源 ID 和版本找原话。记录修改后仍可定位历史材料。 |
| `tasks`、`task_checkpoints` | 保存任务状态、输入修订和最近一次阶段检查点，供任务模块恢复。 |
| `operation_receipts` | 以客户端 ID 和操作 ID 为主键，保存请求摘要、原回执和删除范围。 |
| `deletion_markers`、`deletion_jobs` | 保存隔离对象和清理计划，控制数据库重开后的可见范围。 |

一次写入将原文、来源、任务、修订和回执一并提交。重复提交相同操作时返回第一次的回执。复用操作 ID 并修改内容时返回 `OPERATION_ID_REUSED`，使用旧修订时返回 `REVISION_CONFLICT`。一次详情查询在同一事务中读取记录、来源和任务。时间线按实际采集时间排序，按标签名称筛选时使用有效标签。

文件库采用 WAL 和 `synchronous=FULL`。旧版用户首次访问时，程序在一笔事务中把 SharedPreferences 的资料导入 Room，保留记录、各版来源、任务、回执、偏好和修订。提交成功后清理旧数据。旧资料解析失败时保留原件，供下次重试。已装过首版 Room 的设备通过 1 → 2 迁移更新回执和清理任务的主键，同一操作保留最后一次存储的回执。

## 联调与验收

- 郑嘉文用 `CoreRepository` 核对六步回执样例，陈柏睿和陈柯男分别从首页、系统分享写入同一资料库。
- 陈飞扬读取任务、输入修订和阶段检查点，接续后台执行及清理。林叶鑫使用 Room 版共享契约复测保存、回放、冲突和异常回滚。
- `RoomCoreContractTest` 覆盖并发重发、来源写入后失败、历史版本、时间排序和文件重开。设备上的 `RoomStorageContractTest` 继承 20 项共享契约，并验证旧版资料迁移、损坏重试、两份冲突数据的保留及真实文件库的 WAL、同步级别和外键开关。

设备回归还覆盖来源删除与跨记录引用、旧回执、长期偏好和事务回滚。双入口联调中，首页和系统分享分别写入一条记录。强制停止应用后，冷启动进入记录库可分别打开两条原文，对应的采集方式为「应用内记录」和「来自系统分享」。

进入 `android/` 后运行以下命令。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```
