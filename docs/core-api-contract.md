# Android 本地业务接口

0.1.0 的首页、记录库、详情和系统分享通过 `PebblePileApplication.coreApi` 访问同一份本地资料库。页面调用 Kotlin 接口，`LocalCoreApi` 负责校验、修订冲突和回执，Android 通过 `DemoCoreApi` 接入 Room 和后台任务。保存原话和发布建议分两次提交，页面可凭首次回执打开详情，再订阅整理结果。[CoreApi](../android/app/src/main/java/com/pebblepile/android/service/CoreApi.kt) · [业务实现](../android/app/src/main/java/com/pebblepile/android/service/LocalCoreApi.kt) · [Android 装配](../android/app/src/main/java/com/pebblepile/android/DemoCoreApi.kt)

## 调用入口

| 操作 | Kotlin 接口 | 数据和结果 |
| --- | --- | --- |
| 保存原话 | `recordExperience` | 提交正文、采集方式和 `operationId`，可指定采集时间。新记录省略基准修订，追加材料带原 `recordId` 和 `baseRevision`。回执给出记录编号、记录修订、精确来源版本及适用的任务编号。 |
| 查记录 | `queryRecords`、`getRecordDetail` | 列表按采集时间倒序，传入的标签须全部命中，排除项退出筛选。详情返回记录、可见来源、任务编号、长期条件和资料库修订。传 `SourceRef` 可只读某一版来源，版本失效时返回 `null`。搜索、人物和事项状态由记录库页面筛选。 |
| 查条件 | `queryContext` | 按记录编号返回本次条件、长期条件、由修订生成的 `snapshotId` 和资料库修订。 |
| 修改记录 | `updateRecordTitle`、`updateRecordGoal`、`reviseTag` | 使用记录修订修改标题、事项名称和进展，或确认、改名、排除标签、标记待核对。确认可恢复已排除标签。 |
| 纠正条件 | `reviseMemory` | `THIS_TIME` 写入当前事项。`FUTURE` 保存为长期条件，校验记录修订和 `basePreferenceRevision`。第一次设置传 0。开启自动整理时，同时创建重算任务。 |
| 记实际反馈 | `recordOutcome`、`getDinnerDecision` | 选择、体验至少填写一项。反馈随原记录保存并获得 `outcomeId`。关联某次建议时，传入该记录下有效的 `decisionId`。按记录和建议编号可找回当时的排序。 |
| 查进展和控制任务 | `getTask`、`controlTask` | 按 `jobId` 读取状态、阶段和等待原因。控制请求带任务自身修订和操作编号，按 `allowedActions` 暂停、继续、取消或重试。 |
| 跟随后台更新 | `observeTasks`、`observeRecordChanges` | 前者返回指定任务的可见状态，后者通知页面重读记录。当 Worker 发布结果或长期条件改变，已打开的详情可以刷新。 |

## 写入、回执与重试

每份仓储绑定一个资料库。`CoreRepository.read` 提供一致的读视图，`write` 在一个事务内检查请求、更新数据、递增资料库修订并保存回执。首次保存一起写入记录、来源和适用的整理任务。提交成功后才通知调度器。[仓储契约](../android/app/src/main/java/com/pebblepile/android/repository/CoreRepository.kt) · [SQLite 存储](android-storage.md)

| 字段 | 何时使用 |
| --- | --- |
| `operationId` | 客户端为一次写操作生成。库内用 `(clientId, operationId)` 唯一定位回执。摘要包括接口版本、方法和参数。原样重发返回第一次的回执和提交修订。同键更换参数返回 `INVALID_INPUT`，原因是 `OPERATION_ID_REUSED`。 |
| `revision`、`baseRevision` | 记录回执中的 `revision` 是对象修订，修改时作为 `baseRevision` 提交。任务控制使用任务修订，跨对象删除使用预览时的资料库修订。 |
| `libraryRevision` | 标识整份资料库在读出或提交时的修订，便于比较两次操作的先后。 |
| `source.sourceId`、`source.sourceVersion` | 指向本次材料的具体版本。记录的标题、状态等可以继续修改，引文仍按原来的来源版本定位。 |

发生 `REVISION_CONFLICT` 时，调用方保留草稿，展示最新记录、任务或长期条件。用户核对后用新操作编号和当前修订提交。仓储写入失败时整笔回滚，回执为 `PROCESSING_FAILED` 且 `retryable=true`，可以原样重试。

## 建议、依据与反馈

一条晚餐建议有自己的 `decisionId`。排序结果保留提问条件、算法版本、候选顺序和依据，`rankingBasis` 记录采用的偏好、范围未命中的偏好和筛掉的菜品。每条引文通过记录、来源版本及 Unicode 码点半开区间 `[start,end)` 查回原话。例如「🍜热汤面」里的「热汤面」位于 `[1,4)`，Kotlin 取子串前用 `offsetByCodePoints` 换成 UTF-16 下标 `[2,5)`。[晚餐排序与来源](algorithm/dinner-ranking.md)

详情用反馈保存的 `decisionId` 对照当时的推荐和实际吃的菜。单独记录体验时可省略编号。修改本次条件或长期偏好后，详情收起已失效的建议，新提问按新条件排序。仍有效的历史依据可以回查。删除来源或解除相关引用时，受影响的建议撤回，反馈保留原建议编号。[记录与回查](android-record-flow.md)

## 删除契约

在本地核心中，删除记录和删除来源都是先预览、再确认。移除跨记录引用时，来源所属的原记录仍保留，引用方的读视图及受影响的标签、建议随之更新。Room 以当前设备上的资料库所有者执行这些操作。通用接口还定义了 `KNOWLEDGE_ITEM`，0.1.0 的 Room 将长期条件随资料库保存，独立认识实体属于[数据库概要设计](database-design.md#64-纠正停用与删除)。

| 入口 | 请求和结果 |
| --- | --- |
| `previewDeleteMemory(target)` | 读取目标及依赖，返回 `previewId`、`baseRevision` 和 `impact`。影响清单按移除、失效、取消、重算给出去重计数，并列出受影响的记录编号。 |
| `deleteMemory(request)` | 提交目标、预览标识、预览时的资料库修订和操作编号。事务内重查授权和影响清单，预览失效时返回 `REVISION_CONFLICT` 和 `preview_again`。成功回执含删除范围、清理状态及适用的 `jobId`。进程重启后重新预览待确认的删除，已成功提交的请求仍可原样重放。 |
| `removeSourceReference` | 提交引用方的记录编号、记录修订、精确来源版本和操作编号。撤回依赖这处引用的内容，来源及其他独立引用继续保留。 |

删除记录会隔离其拥有的各版来源，保留引用自其他记录的原材料。删除单个来源会撤回各记录对其各版原话的引用，原记录仍可保留其他材料。核心区分整对象删除（`FULL_OBJECT`）与引用变化（`RECORD_VIEW`），以免沿一条跨记录引用误删另一条记录的原话。[删除依赖接口](../android/app/src/main/java/com/pebblepile/android/repository/DeletionRepository.kt)

确认后，Room 在同一事务写入隔离标记、回执和清理任务，取消依赖任务并撤回受影响的标签和建议。旧回执保留原提交修订，清除已删内容的标题和来源引用。Worker 按 `isolated` → `sources_removed` → `records_removed` → `complete` 推进，依次清除计划中的来源版本、记录和旧任务输入。每一步连同检查点提交。失败时从上次成功的检查点重试，隔离期间数据持续退出查询。清理任务由系统推进，调用方凭原 `jobId` 查询最新进度。[Room 隔离处理](../android/app/src/main/java/com/pebblepile/android/db/RoomDeletionWriter.kt) · [清理执行器](../android/app/src/main/java/com/pebblepile/android/db/RoomCleanupRunner.kt)

## 契约验证

[六步回执样例](core-api-receipt-examples.json)从开启自动整理的空库开始，让整理任务保持排队，依次保存原话、改名、原样重发、更换载荷重发、按旧修订纠正和按版本回查。JSON 中的 `expectedResponse.revision` 对应 Kotlin 的 `libraryRevision`，`data.recordRevision` 对应记录回执的 `revision`。样例使用固定编号，执行时按首次回执绑定实际生成的标识。[共享契约用例](../android/app/src/sharedTest/java/com/pebblepile/android/service/CoreApiContractSuite.kt)

事务回归还覆盖并发重发、写入中断回滚以及数据库重开后读取原回执。删除回归分别检查预览失效、跨记录引用、旧任务输入和保留材料。[Room 事务测试](../android/app/src/test/java/com/pebblepile/android/db/RoomCoreContractTest.kt) · [设备删除回归](../android/app/src/androidTest/java/com/pebblepile/android/db/RoomDeletionReviewTest.kt) · [测试与验收](testing/README.md)
