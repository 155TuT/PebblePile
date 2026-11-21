# Issue #7 交接文档 — SQLite 存储与查询（Room 实现）

负责人：周恪玄 @34zkx · 分支 `issue-7-sqlite-storage` · 上游 Issue：https://github.com/155TuT/PebblePile/issues/7

## 1. 目标与结论

让一条已确认保存的原话，在重试、应用退出和重开后都能按同一个 `recordId` 找回。本次用 **Room + SQLite** 实现 `CoreRepository`，把 #6 冻结的仓储接口落到磁盘，替换演示用的 SharedPreferences 仓储。

**选型结论：Room + SQLite**（放弃 `SQLiteOpenHelper` + 显式 SQL），详见第 3 节 ADR。

## 2. 三条验收对照

| #7 验收 | 实现 | 验证 |
|---|---|---|
| 1. 选型说明 + 关联落表 | 第 3 节 ADR；`db/DatabaseEntities.kt` 8 张表 | PR 描述 + 本文档 |
| 2. 原文/回执/任务原子写入；重发只有一条记录与一份任务 | `RoomCoreRepository.write()` 单事务；`operation_receipts` 唯一键 `(client_id, operation_id)`；`requestHash` 由业务层 `LocalCoreApi` 计算并持久化 | `RoomCoreContractTest.steps1To3...`、`step4...` |
| 3. 重开可查；按时间/标签；按来源版本定位原话；读回任务检查点 | `source_versions` 复合主键保留历史版本；`task_checkpoints` 表持久化 phase；文件库 close/reopen 测试 | `RoomCoreContractTest.closeAndReopen...`、`step6...`、`historicalSourceVersions...` |

## 3. 架构决策记录（ADR）

**背景**：契约要求一次 `write` = 一个写事务（查回执 → 读修订 → 写记录/来源/任务/回执 → 递增库修订），失败整体回滚；`docs/database-design.md` 定义 8+ 张关联表、WAL、`synchronous=FULL`、`foreign_keys=ON`；工作量约 10%。

**候选 A — Room**：`@Entity/@Dao` 编译期校验 SQL；`withTransaction` 语义与契约一致；Kotlin/Compose 工程原生集成；`exportSchema` 可审计迁移。**代价**：注解样板多，需新增 KSP 插件与依赖。

**候选 B — SQLiteOpenHelper + 显式 SQL**：手写建表与 SQL，控制最直白，落地快。**代价**：无编译期校验，8 张表 + 复合主键 + 索引的 DDL/DML 全部手写，字段拼写错误只能在运行期暴露；与 Jetpack 生态割裂。

**决策**：选 Room。理由——表结构与关系较复杂，编译期校验与 DAO 映射能显著降低出错面；项目已是 Kotlin + Jetpack 全栈；下游 #4/#5 如需观察数据，Room 可直接提供 Flow 入口。放弃 B 的代价是前期多写注解，但换来可审计 schema 与更少的运行期 SQL 错误。

**后果**：新增 KSP 2.0.21-1.0.25、Room 2.6.1、Robolectric 4.13、room-testing 依赖；Gson 由 test 提升为 implementation，复杂对象（PebbleRecord / ApiReceipt / CoreTask）以 JSON 列整体持久化以保证逐字段回放；WAL / `synchronous=FULL` 通过 `RoomDatabase.Callback` 的 `onOpen` 执行 PRAGMA（对内存库做 try/catch 容错）。

## 4. 落表设计（`db/DatabaseEntities.kt`）

| 表 | 主键 | 说明 |
|---|---|---|
| `libraries` | id | 资料库修订 `revision` + `future_condition_json`；一库一仓储实例 |
| `records` | id | 完整 PebbleRecord JSON + 索引列（captured_at/status/title/revision）支持按时间与状态查询 |
| `source_versions` | (source_id, source_version) | 精确版本共存，保留历史原话；record_id 关联记录 |
| `tasks` | id | CoreTask JSON + kind/state/record_id/input_revision 索引列 |
| `operation_receipts` | (client_id, operation_id) | request_hash + 原成功回执 JSON + deletion_scope；幂等唯一键 |
| `deletion_markers` | key | 删除隔离墓碑，读视图据此隐藏已隔离行 |
| `deletion_jobs` | job_id | DELETE_CLEANUP 的 plan/access 持久化 |
| `task_checkpoints` | task_id | 任务最近一次持久检查点（phase/waitReason） |

## 5. 事务与幂等语义

- 写入顺序固定：`LocalCoreApi.commit()` 在 `repository.write{}` 内先查 `receipt(key)` → 校对 `baseRevision` → `apply`（putRecord/putSource/putTask）→ `advanceRevision()` → `putReceipt`。`requestHash` 由业务层按 `("0.1.0", method, *params)` 逐段长度前缀 SHA-256 生成，仓储原样持久化。
- 同键同载荷 → 返回原回执；同键异载荷 → `INVALID_INPUT / OPERATION_ID_REUSED`；旧修订 → `REVISION_CONFLICT`。
- 三种版本号互不混用：记录 `PebbleRecord.revision`、库 `CoreReader.revision`、原话 `SourceRef.sourceVersion`、任务 `CoreTask.revision`；`DELETE_CLEANUP.inputRevision` 取提交时库修订。
- 任何异常（含测试注入的 `failPoint`）由 `write` 捕获，事务 `endTransaction()` 不 setSuccessful → 整体回滚，抛 `RepositoryWriteException`，业务层返回 retryable 回执。

## 6. 交接对象

- 事务读写与查询实现 → 郑嘉文（#6）
- 任务表、检查点与 `DELETE_CLEANUP` → 陈飞扬（#8）
- 真实库与样例 → 陈柏睿（#4）、陈柯男（#5）验证首页与分享（`DemoCoreApi` 默认已切到 Room，可用 `-Dpebblepile.repository=demo` 回退演示仓储对比）
- 回归与故障注入 → 林叶鑫（#10）

## 7. 可重跑测试入口与实测结果

在 `android/` 目录：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*RoomCore*" --rerun-tasks
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
```

覆盖：6 步回执样例（保存/改标题/幂等重发/同键异载荷/旧修订冲突/按来源版本读回）、5 个补充场景（重开库、并发重发、同键异载荷、旧修订、写入来源后失败回滚）、历史版本保留、按时间查询、事务整体回滚。

**实测结果（2026-11-22）**：`:app:testDebugUnitTest` 全部通过 —— **45 tests, 0 failures, 0 errors**。

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `EvidenceResolutionTest` | — | 通过 |
| `RoomCoreContractTest`（本次新增） | 11 | 通过 |
| `AdvancedCoreContractTest` | — | 通过 |
| `DeletionContractTest` | — | 通过 |
| `LocalCoreApiContractTest` | — | 通过 |

报告路径：`android/app/build/reports/tests/testDebugUnitTest/index.html`。新增 11 个 Room 用例把 #6/#10 的 6 步样例与 5 个补充场景全部跑在真实 Room SQLite 上，含文件库 close/reopen、`failPoint` 注入回滚、并发重发幂等。

## 8. 环境说明

本机构建环境用 conda 隔离环境 `pebblepile-build`（JDK 17）+ 临时 Android SDK 完成编译与测试，交付后按约删除。Gradle 依赖通过阿里云 Maven 镜像解析。`android/local.properties` 仅本地存在，不入库。
