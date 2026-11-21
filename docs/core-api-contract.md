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
| `preview_delete_memory` | 单个 `target`，见[删除契约](#删除契约)。 | `previewId`、基准资料库修订和影响范围。 |
| `delete_memory` | `target`、`previewId`、预览返回的 `baseRevision`、`operationId`。 | 删除目标、提交修订、影响范围和清理状态，适用时返回清理 `jobId`。 |
| `get_task`、`control_task` | `jobId`。控制请求另带任务修订和暂停、继续或取消操作。 | `jobId`、任务修订、状态、阶段、等待原因、检查点和当前可执行操作。 |

## 删除契约

对应 [F08、A18 和 A40](prd.md#f08纠正停用与删除)及[数据库清理顺序](database-design.md#64-纠正停用与删除)。调用方用 `preview_delete_memory` 读取影响，用户确认后提交 `delete_memory`。删除接口和以下验收用例供 #6、#7、#8、#9 和 #10 联调。

### 删除对象

`target` 为 `{type, id}`，`id` 是稳定实体标识。一次请求处理一个目标及其依赖闭包。

| `target.type` / 入口 | 业务规则 |
| --- | --- |
| `record` | 删除记录、所属会话和事项正文，以及 `owner_record_id` 指向该记录的全部来源版本。其他记录对这些来源的引用同步失效。该记录引用的外部来源仍归原记录，本次只移除自身关联。 |
| `source` | 删除一个来源的全部版本及其在各记录中的引用。保留所属记录和其他独立材料。最后一条来源删掉后仍保留记录容器，详情返回空来源列表，生成标题和摘要只使用剩余有效内容。 |
| `knowledge_item` | 撤回该认识及其历史正文、参数和下游使用，从活动快照移除，并保留独立原始材料与共用证据。旧认识 ID、历史版本和在途任务均不得恢复该认识。确认页说明保留的有效材料仍可作为以后独立推断的依据。 |
| 移除引用 / `update_record` | 携带 `recordId`、记录自身修订及 `change={type: "remove_source_reference", sourceRef}`，只解除该记录对指定来源版本的跨记录关联，并撤回依赖该关联的结果。来源、其他引用和依赖来源本身的结果继续保留。 |

移出记录本只修改归属。停用只改变参与查询的状态。删除确认页逐项说明清理、保留和待重算的内容，并提示已外发副本与用户另存的旧备份由各自入口管理。

### 预览、确认与回执

| 字段 | 约定 |
| --- | --- |
| `previewId` | 服务签发的不可篡改预览标识，绑定权威实例、资料库代次、主体与 `clientId`、授权修订、目标、资料库修订和影响清单摘要。摘要按对象标识、修订和处置方式计算。预览使用一致读取，返回 `target`、`previewId`、`baseRevision` 和 `impact`，只读操作无需 `operationId`。 |
| `baseRevision` | 删除会跨对象传播，固定使用预览时的**资料库修订**。提交事务中重新计算影响并核对预览。资料库修订或影响清单任一变化都要求重新预览并确认。 |
| `impact` | 预览与提交使用同一结构。`remove`、`invalidate`、`cancel`、`rebuild` 四组按对象类型给出去重计数。`remove.source` 按稳定来源计数，版本单列为 `remove.source_version`。跨记录关联计入 `remove.record_source`，仍保留但读视图变化的记录计入 `invalidate.record`，并在 `affectedRecordIds` 返回这些记录的标识。待重算的对象也可计入 `invalidate`。空组用 `{}`，省略计数为零的类型，响应只传标识和计数。 |
| 成功回执 | 使用通用回执外层。`data` 包含 `target`、`impact` 和提交时的 `cleanupState`。`complete` 表示清理已完成。`pending` 返回清理 `jobId`，调用 `get_task` 查进度。外层 `revision` 为提交后的资料库修订。 |

预览与提交均检查当前身份、资料库及 `delete` 授权。非所有者的授权须覆盖目标和全部受影响范围，否则返回 `FORBIDDEN`。提交先校验当前授权，再按回执唯一键查重，最后校验预览、基准修订和当前依赖。相同请求重发返回原提交回执，即使预览已失效或清理已完成也沿用原状态。预览标识与基准修订均参与请求摘要。

| 情况 | 错误回执 |
| --- | --- |
| 修订或影响清单变化，或预览已无法验证 | `REVISION_CONFLICT`，`currentRevision` 为当前资料库修订，`nextAction=preview_again`，`retryable=false`。重新预览和确认后用新的 `operationId`。 |
| 权限已撤销或范围不足 | `FORBIDDEN`，`retryable=false`。响应省略目标存在性与影响明细。 |
| 参数无效 | `INVALID_INPUT`，`retryable=false`。 |
| 授权范围内目标不存在或已标记删除 | `INVALID_INPUT`，分别返回 `reason=TARGET_NOT_FOUND` 或 `TARGET_DELETED`，`retryable=false`。相同操作的有效回放优先返回原回执。 |
| 同一操作标识改用不同载荷 | `INVALID_INPUT`，`reason=OPERATION_ID_REUSED`，`retryable=false`。 |
| 提交事务失败 | `PROCESSING_FAILED`，`nextAction=retry`，`retryable=true`。原请求可携同一 `operationId` 重试。 |

Kotlin 调用 `CoreApi.previewDeleteMemory`、`deleteMemory` 和 `removeSourceReference`，通过 `ApiReceipt.deletePreview` 或 `ApiReceipt.deletion` 读取类型化结果。未确认的预览在业务服务重启后重新生成；已提交请求仍按持久回执重放。引用移除使用 `REVISE` 权限，并校验容器及实际受影响范围。

### 提交与清理

1. **原子隔离。** 在一个 `CoreRepository.write` 内重查授权、目标与依赖，登记无正文标记和任务，取消依赖作业及规则触发，撤回失效证据、索引项和模型视图，递增资料库与受影响对象修订，并保存成功回执。提交失败时整体回滚。清理未完的内容通过标记和有效性检查即时退出列表、详情、精确版本回查、上下文、搜索和导出。缓存命中也要通过有效性检查。
2. **清理依赖。** 沿证据、快照、决策、生成回答及其来源追踪到依赖闭包稳定。删除正文、派生副本、参数、工作区和含正文诊断。历史决策只保留必要引用和 `redacted` 状态。共享附件在最后一个有效引用解除后才能移除，检查引用与文件删除须串行化。已物化的兼容字段也要清理，包括旧库的 `rawText`、依赖被删记录的长期条件副本，以及旧回执内被清理的标题。旧回执保留请求摘要、状态、标识和修订，回放省略已清理正文。
3. **重算与防回写。** 失去全部依据的认识撤回。剩余独立依据支持的事件、标签或认识从有效版本重算。任务发布时在写事务内复核删除标记、输入版本、任务状态和租约。已取消任务继续为 `cancelled`，其他失效结果以 `STALE_INPUT` 拒绝并将仍属于该次执行的任务转为 `stale`。已向外部发送的真实动作保留最少结果状态。
4. **恢复与收尾。** 物理清理任务持久记录进度，失败或重启后从检查点幂等继续。普通任务控制无法暂停或取消清理。清理等待重试期间数据保持隔离，完成数据库、索引及失去引用的附件清理后标记为 `complete`，对应清理任务转为 `completed`。

仓储在同一事务的 `CoreReader.deletion` / `CoreWriter.deletion` 接入 [DeletionReader 和 DeletionWriter](../android/app/src/main/java/com/pebblepile/android/repository/DeletionRepository.kt)。读取端提供授权范围、对象修订、直接依赖与有效独立依据；业务层计算闭包、清理和重算范围。写入端落实隔离、回执可见性与 `DELETE_CLEANUP` 任务存储。现有演示仓储留空，删除调用返回 `DELETION_STORAGE_UNAVAILABLE`。任务调度接入后执行物理清理并发布重算结果。

### 验收场景

| 场景 | 预期 |
| --- | --- |
| A 引用 B 的来源。分别从相同初始数据执行移除 A 的引用、删除 A、删除 B 的来源。 | 前两项保留 B 的材料。删除来源使 A 的引用失效，来源的当前和历史版本都无法读取，A、B 的容器和独立材料仍在。 |
| 预览后新增引用或撤销授权，再提交。 | 分别返回 `REVISION_CONFLICT` 或 `FORBIDDEN`，无删除标记和清理任务。 |
| 写入中断、并发重发、提交后重开库。 | 中断整体回滚。相同操作只提交一次，重发返回原修订、影响范围与清理任务 ID。清理阶段可从任务查询得到当前进度。 |
| 物理清理失败，同时模型任务尝试发布旧结果。 | 删除内容持续不可读，迟到结果被拒绝。重启继续清理；仅由已删除输入支撑的推断被撤回，有独立依据的部分用新版本重算。 |
| 来源共用同一附件，记录的旧回执中另存了标题。 | 仍有有效引用时保留附件；最后一个引用解除后清理文件。删除记录后重放改名操作，回执保留原提交修订并省略已删除标题。 |
| 删除一条明确设置的认识，其证据同时支持另一条认识。 | 被删认识及其历史版本退出上下文，其他认识可继续读取共用证据。旧任务无法恢复被删的认识。 |

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
