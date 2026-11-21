# Issue #5 交接清单

## 已覆盖的页面和入口

| Issue #5 要求 | 当前代码位置 |
|---|---|
| Android 分享纯文字 | `MainActivity.kt` 的 `acceptShare`、`ACTION_SEND` manifest filter |
| 分享后保存并返回 recordId | `CoreApi.recordExperience`；分享确认弹窗 |
| 详情按 recordId 加载 | `RecordDetailScreen(recordId)` 调用 `CoreApi.getRecordDetail` |
| 原话、标签、建议依据 | `RecordDetailScreen`；证据按 `SourceRef`、版本和码点区间回查 |
| 补记实际选择和体验 | `CoreApi.recordOutcome`；实际反馈区域 |
| 标签确认／修改／排除／待核对 | 点 `PebbleTag` 打开依据面板，核对来源版本后调用 `CoreApi.reviseTag` |
| 事项信息与状态 | `CoreApi.updateRecordGoal`；详情“当前事项”区域 |
| 任务阶段与控制 | `RecordDetail.jobIds`、`CoreApi.getTask/controlTask`；暂停、继续、取消、失败重试 |
| 仅本次／以后按此条件 | `CorrectionScope`；纠正条件弹窗 |
| 修订冲突与草稿保留 | `ApiReceipt.conflict`；对照最新版本，保留草稿并重试 |

## 与团队协作的待办

- [x] 与 #4 统一到纵向 Compose 工程，包名为 `com.pebblepile.android`，首页、记录库和系统分享按 `detail/{recordId}` 打开详情。
- [x] 从 Issue #3 接入正式输入框、标签、依据卡片、反馈、按钮和弹层组件。
- [x] 页面已接入 #6 的 `CoreApi`。首次保存和原样重试共用同一份回执，带旧修订提交时保留草稿，供用户核对后重试。[接口契约](../docs/core-api-contract.md) · [回执样例](../docs/core-api-receipt-examples.json)
- [x] 详情已读取任务阶段并支持针对单个 `jobId` 的暂停、继续、取消和失败重试。
- [x] 来源回查已使用稳定来源、精确版本和 Unicode 半开区间；失效来源单独禁用。
- [x] 标签支持确认、修改、排除、恢复和待核对，并同步更新记录库筛选。
- [ ] #7 接入 SQLite 后重跑保存、重试和冲突，结束进程再核对原话、来源版本、待办和首次回执。
- [ ] #8 接入处理进度，#9 提供饮食建议和证据。详情按 `sourceId`、`sourceVersion` 和原话区间回查依据。
- [ ] 与 Issue #10 按两次加班经历、一次周末例外和一次纠正的样例完成回归。
- [ ] 在真机验证系统分享、退出重开、360dp 小屏、触点和进程恢复。

## 反馈与纠正

当前页面使用 Issue #3 共享组件，可保存反馈、编辑事项、管理任务，分别修改本条记录和长期条件。建议区域仍由本地演示规则生成。接入 #8、#9 后，按 `decisionId` 把反馈交回当时的建议，再用纠正后的周四和周六各问一次，对照条件、候选和依据。[晚餐验收材料](../docs/issue10-testing.md)
