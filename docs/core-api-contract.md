# 本地业务核心接口草案

对应 [#6 本地业务核心与公共接口](https://github.com/155TuT/PebblePile/issues/6)。字段名与枚举供首轮实现使用。回执样例使用 [#2 的虚构晚餐输入](issue2-research.md#二统一晚餐材料)。通用语义沿用[系统设计 §7](system-design.md#7-接口设计)与[数据库设计 §6](database-design.md#6-访问操纵模块与运用设计)。

## 请求与回执

首页和系统分享调用同一套接口，使用同一份资料库。调用上下文注入客户端、资料库和授权范围。请求声明 `apiVersion`、接口 `method` 和 `params`。写入携带 `operationId`，修改时再携带 `baseRevision`。新增独立记录可省略 `baseRevision`。

| 字段 | 语义 |
| --- | --- |
| `operationId` | 由调用方为一次用户操作生成。网络超时、重复点击等重发沿用该值，用户确认修改后发起的新操作使用新值。 |
| `baseRevision` | 单对象修改携带该对象的 `revision`，批量修改携带资料库修订。来源定位使用独立的 `sourceVersion`。 |
| `authorityInstanceId`、`libraryId`、`revision` | 已授权回执关联权威实例与资料库。`revision` 是本次一致读取或提交时的资料库修订，重放返回原提交修订。 |
| `status`、`data`、`error` | `status=ok` 携带业务 `data`。`status=error` 携带 `error`，其中 `code` 供程序处理，`retryable` 表示原请求能否直接重试，`nextAction` 说明下一步。 |

`REVISION_CONFLICT` 附目标标识、当前对象修订及待核对字段，页面保留用户草稿。调用方呈现当前值，待用户确认后用新 `operationId` 和当前 `baseRevision` 再提交。

## 调用入口

| 接口 | 输入 | 返回 |
| --- | --- | --- |
| `record_experience` | `text`、`captureMethod`、`capturedAt`、`mode`，可选 `recordId`、`notebookId` 与场景。追加到既有记录时带该记录的 `baseRevision`。 | `recordId`、`recordRevision`、`sourceId`、`sourceVersion`、适用时的 `jobId`。 |
| `query_records` | 可选时间范围、记录本、语义标签、事项状态和分页游标。 | 符合筛选条件的记录摘要、状态、稳定 `recordId` 与下一页游标。默认仅用有效标签参与筛选。 |
| `get_record` | `recordId`，可选 `sourceRef` 以请求记录下的精确来源版本。 | 记录标识和对象修订、原话与来源版本、标签、事项、实际反馈和相关任务引用。查历史证据时使用请求指定的来源版本。 |
| `query_context` | 事项、人物、当前情境和字节／token 预算。 | `contextStatus`（`ready`、`empty`、`unavailable`）、有效条目、证据引用、`snapshotId`、`processedThrough`、未知项和遗漏。 |
| `record_outcome` | 原记录或事项标识，可选决策或事件引用，实际选择、体验及事项完成或继续的意图。涉及事项修改时携带该事项的修订。 | `outcomeId`、所属记录和受影响对象修订、适用时的更新任务标识。历史建议仍由 `decisionId` 回查。 |
| `update_record` | `recordId`、`baseRevision` 和标题、记录本或关联变更。 | `recordId`、新 `recordRevision` 与变更后的记录字段。 |
| `revise_tags` | 标签指派或添加目标的标识、相应 `baseRevision`，以及确认、添加、排除或撤销覆盖的意图。 | 有效标签及修订、人工覆盖状态和受影响范围。 |
| `update_goal` | 当前 `goalId`、事项修订和新条件。用于「仅本次」。 | 事项新修订及适用的重算任务。 |
| `revise_memory` | `knowledgeItemId`、认识修订、明确条件与纠正意图。用于「以后按此条件」。 | 新认识修订、影响范围、有效快照与适用的重算任务。 |
| `get_task`、`control_task` | `jobId`。控制请求另带任务修订和暂停、继续或取消操作。 | `jobId`、任务修订、状态、阶段、等待原因、检查点和当前可执行操作。 |

## 仓储边界

仓储公开一致读取与原子提交能力。核心决定业务规则，仓储负责在同一次写事务里完成条件检查、更新和回执登记，并在事务提交后返回成功。

1. 校验当前资料库及授权，按 `(libraryId, clientId, operationId)` 找回执。相同请求摘要返回原回执，包括原来的记录、来源、任务和提交修订。同一键携带不同内容时返回 `INVALID_INPUT`，`reason=OPERATION_ID_REUSED`。请求摘要涵盖接口版本、方法、基准修订和业务参数，忽略 JSON 字段顺序。
2. 无原回执时，在事务内校对适用的 `baseRevision`。冲突返回当前对象并保留草稿线索，资料库修订、记录和待办数量保持原值。命中已提交回执时以回放为准，避免后来发生的修订把旧请求的成功回执变成冲突。
3. 新保存同时写入记录和来源版本、适用的待办与操作回执，并递增资料库修订。任一步写入失败则整体回滚。并发收到同一操作时，回执唯一键和事务保证只有一个提交，另一次调用读取此前提交的回执。
4. 列表、详情和上下文在一致读范围内取数。回执只持久化状态和必要标识。回查原话时，根据回执所指的来源版本重新校验授权并读取正文。

### 交给 #7 的 SQLite 仓储契约

#7 在 `db/` 实现 [CoreRepository、CoreReader 和 CoreWriter](../android/app/src/main/java/com/pebblepile/android/repository/CoreRepository.kt)。每个仓储实例绑定一个资料库。#6 定义业务判断、请求摘要和回执，SQLite 实现负责事务、持久化与对象映射。Room 与显式 SQL 的选型由 #7 记录。

| 约定 | SQLite 实现要求 |
| --- | --- |
| 一致读取 | 每次 `read` 提供同一个读取视图。按记录 ID 读记录和关联任务，按来源 ID 与精确版本定位原话。 |
| 原子提交 | 每次 `write` 用一个写事务执行整个回调。回调先查原回执，再读目标修订，随后写记录、来源及适用的待办，递增库修订并登记回执。事务提交后返回结果，失败回滚并抛出 `RepositoryWriteException`。 |
| 操作唯一键 | 在同一资料库内，`(clientId, operationId)` 唯一。持久保存 `requestHash` 和原成功回执所需的标识、状态及修订。重开后用这些值复原当时的回执。 |
| 三种版本号 | `PebbleRecord.revision` 是记录修订，`CoreReader.revision` 是资料库修订，`SourceRef.sourceVersion` 是原话版本。任务的 `inputRevision` 在当前接口中指输入记录修订，`CoreTask.revision` 是任务自身修订。 |
| 任务数据 | 保存首个待办的 `jobId`、记录引用、输入修订、类型与状态。调度阶段、输入版本集合和持久检查点由 #7 与 [#8](https://github.com/155TuT/PebblePile/issues/8) 对齐后接入。 |

从空库开启自动整理，跑[回执样例](core-api-receipt-examples.json)。以下补充场景各用一份独立的空库：

| 操作 | 预期 |
| --- | --- |
| 保存后关闭并重新打开库 | 同一 `recordId` 可查，按精确来源版本读回原话，同时读回待办与原回执。 |
| 重开后重发保存请求，或并发提交两次同一操作 | 返回同一 `recordId`、来源、`jobId` 和原提交修订。库中只有一条记录、一份来源版本、一份待办和一份回执。 |
| 同一操作标识携带不同输入 | 由核心返回 `INVALID_INPUT`，已有记录和回执仍可读。 |
| 用旧修订修改记录 | 由核心返回 `REVISION_CONFLICT`、当前对象修订和下一步，库修订与记录内容保持原值。 |
| 写入来源之后、回执提交之前触发失败 | 重开后查不到本次写入的记录、来源、任务或回执。 |

#7 的交付包含仓储实现入口及可重跑的验证步骤。#6 根据 #10 的测试 PR 中发现的接口问题更新契约或实现。

## 算法候选与纠正

候选携带 `jobId`、目标及输入修订、来源版本和所用 `snapshotId`。证据位置以 `sourceId`、`sourceVersion` 和 Unicode 码点半开区间 `[start,end)` 表达。若证据来自其他记录，回查入口使用该来源所属或已关联的 `recordId`。例如在原型的原话「今天加班，晚饭想省事一点，预算二十元。」里，`[8,10)` 定位「省事」。

#10 的 E01–E03 样例中，码点和 UTF-16 码元偏移一致。含「🍜热汤面」等文本时，「热汤面」的码点区间为 `[1,4)`。Kotlin 用 `offsetByCodePoints` 换算为 UTF-16 的 `[2,5)` 后再调用 `substring`，接口仍返回码点区间。 [#9 证据约定](https://github.com/155TuT/PebblePile/issues/9) · [#10 测试 PR](https://github.com/155TuT/PebblePile/pull/15)

候选发布时在写事务中复查目标和来源版本、人工排除、授权和任务状态。任务带租约时也核对租约。失效输入产生 `STALE_INPUT`，将仍属于该次执行的任务标为 `stale`，已经取消的任务保持 `cancelled`。成功发布的建议保存证据引用与所用快照。

「仅本次」将新条件写入当前事项。长期纠正保存为本人明确设置，立即让受影响的旧推断退出上下文，再安排重算。回放迟到结果时，应观察到新条件继续生效，后续建议使用当前有效快照。

## 样例回放

[晚餐回执样例](core-api-receipt-examples.json)使用一份开启自动整理的空资料库，回放期间保持整理任务排队。数组按顺序列出请求、预期回执与持久状态。实现生成的标识可与样例标识逐一绑定，用于后续请求与结果比较。

| #10 用例 | #6 回执约定 |
| --- | --- |
| K01 保存 E01 | 首页记录用 `captureMethod=app_home_record`。新建记录使用新 `operationId`，省略 `baseRevision`。保存返回记录、来源版本、对象修订和适用的任务。 |
| K02 重发 E01 | 用同一标识和同一载荷重发，逐字段比对首次回执。样例在修改标题后再重发，检查回执中的原提交修订。 |
| K03 用旧修订提交 C01 | 返回 `REVISION_CONFLICT`，附当前记录、当前对象修订及 `nextAction=review_current_revision`。草稿由调用方保留。 |
| 补充用例：同一操作标识提交不同内容 | 返回 `INVALID_INPUT` 和 `reason=OPERATION_ID_REUSED`。记录、回执和资料库修订保持原值。 |

## Kotlin 接入

[LocalCoreApi](../android/app/src/main/java/com/pebblepile/android/service/LocalCoreApi.kt) 实现页面现有的 `CoreApi`，并提供按来源版本读详情、记录筛选、条件查询和任务控制。一次 `CoreRepository.write` 内检查重复请求和对象修订，写入记录、来源、适用的任务与成功回执。写操作的请求摘要包含调用方法及业务参数。对象修订通过 `ApiReceipt.revision` 返回，资料库修订通过 `libraryRevision` 返回，来源引用在 `source` 中。

独立接入时可用 `LocalCoreApi(SnapshotCoreRepository())` 从空内存库开始。[内存仓储实现](../android/app/src/main/java/com/pebblepile/android/db/SnapshotCoreRepository.kt)位于 `db/`。用 `recordExperience` 保存原话，用 `updateRecordTitle` 修改标题，再重发首次请求检查回执一致性。最后用旧修订提交纠正检查 `REVISION_CONFLICT`。JSON 样例展示序列化后的预期值，Kotlin 调用直接使用类型化参数和回执。

Android 的 [DemoCoreApi](../android/app/src/main/java/com/pebblepile/android/DemoCoreApi.kt) 将同一个业务服务装配给首页和分享入口。[DemoLibraryStore](../android/app/src/main/java/com/pebblepile/android/db/DemoLibraryStore.kt) 沿用 `pebblepile_issue4_demo` 的记录键，给旧有记录补来源引用，并用一次 SharedPreferences 提交保存记录、版本、任务和回执。任务可查询和控制，执行由后续调度接入。详情显示最后一次提交的条件及其范围，业务查询分别返回当前事项条件与晚餐场景的长期条件。建议区域继续显示 #14 的演示内容。

首页、分享和后续模块从 `PebblePileApplication.coreApi` 取同一个服务。页面用 `getRecordDetail` 一次读取记录和长期条件。用户选择“以后按此条件”时，同时提交打开编辑框时的记录修订和 `basePreferenceRevision`。首次设置长期条件传 0。同一条长期条件可以从不同记录修改，任一方先保存，另一方就先核对当前长期条件，再提交自己的草稿。

从 #14 的演示库升级时，旧记录保留各自的纠正范围。旧库里只有一种明确长期条件时，核心把它带入后续查询。出现多种时，页面保留各条记录的原值，由用户选定今后采用的条件。

在 `android/` 运行 `.\gradlew.bat connectedDebugAndroidTest`，可回放晚餐保存、改标题、重发、冲突和任务控制，核对重开后的原回执，也可复验跨记录修订和写入失败回滚。

#7 接仓储接口，在 SQLite 上重跑事务用例。#8、#9 接候选发布和证据校验，保存每次建议的 `decisionId`；#6 随建议接入把反馈关联到当时的决策。#10 据此验证同一事项收到多次建议、反馈仍属于原建议，以及纠正后迟到的候选如何处理。
