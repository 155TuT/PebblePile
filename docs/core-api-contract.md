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

## 算法候选与纠正

候选携带 `jobId`、目标及输入修订、来源版本和所用 `snapshotId`。证据位置以 `sourceId`、`sourceVersion` 和 Unicode 码点半开区间 `[start,end)` 表达。若证据来自其他记录，回查入口使用该来源所属或已关联的 `recordId`。例如在原型的原话「今天加班，晚饭想省事一点，预算二十元。」里，`[8,10)` 定位「省事」。

候选发布时在写事务中复查目标和来源版本、人工排除、授权和任务状态。任务带租约时也核对租约。失效输入产生 `STALE_INPUT`，将仍属于该次执行的任务标为 `stale`，已经取消的任务保持 `cancelled`。成功发布的建议保存证据引用与所用快照。

「仅本次」将新条件写入当前事项。长期纠正保存为本人明确设置，立即让受影响的旧推断退出上下文，再安排重算。回放迟到结果时，应观察到新条件继续生效，后续建议使用当前有效快照。

## 样例回放

[晚餐回执样例](core-api-receipt-examples.json)使用一份开启自动整理的空资料库，回放期间保持整理任务排队。数组按顺序列出请求、预期回执与持久状态。先保存原话，再修改记录标题。随后重发原操作，应收到初次保存回执。最后用旧记录修订提交新编辑，应收到冲突，详情仍读到已提交的标题与原话。实现生成的标识可与样例标识逐一绑定，用于后续请求与结果比较。

## Kotlin 接入

[LocalCoreApi](../android/app/src/main/java/com/pebblepile/android/data/LocalCoreApi.kt) 实现页面现有的 `CoreApi`，并提供按来源版本读详情、记录筛选、条件查询和任务控制。一次 `CoreRepository.write` 内检查重复请求和对象修订，写入记录、来源、适用的任务与成功回执。写操作的请求摘要包含调用方法及业务参数。对象修订通过 `ApiReceipt.revision` 返回，资料库修订通过 `libraryRevision` 返回，来源引用在 `source` 中。

独立接入时可用 `LocalCoreApi(SnapshotCoreRepository())` 从空内存库开始。用同一份 `CaptureRequest` 两次调用 `recordExperience` 检查回执一致性，用 `updateRecordTitle` 追加一次修改，再传旧修订检查 `REVISION_CONFLICT`。JSON 样例展示序列化后的预期值，Kotlin 调用直接使用类型化参数和回执。

Android 的 [DemoCoreApi](../android/app/src/main/java/com/pebblepile/android/data/DemoCoreApi.kt) 负责将同一个业务核心装配给首页和分享入口。它沿用 `pebblepile_issue4_demo` 的记录键，给旧有记录补来源引用。演示库用一次 SharedPreferences 提交保存记录、版本、任务和回执。任务可查询和控制，执行由后续调度接入。详情显示最后一次提交的条件及其范围，业务查询分别返回当前事项条件与晚餐场景的长期条件。建议区域继续显示 #14 的演示内容。

接入 #7 时替换为 SQLite 的 [CoreRepository](../android/app/src/main/java/com/pebblepile/android/data/CoreRepository.kt) 实现。算法候选发布、完整上下文检索、正式偏好重算和真机验收属于后续联调。
