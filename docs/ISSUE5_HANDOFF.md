# Issue #5 交接清单

## 已覆盖的页面和入口

| Issue #5 要求 | 当前代码位置 |
|---|---|
| Android 分享纯文字 | `MainActivity.kt` 的 `acceptShare`、`ACTION_SEND` manifest filter |
| 分享后保存并返回 recordId | `CoreApi.recordExperience`；分享确认弹窗 |
| 详情按 recordId 加载 | `RecordDetailScreen(recordId)` 调用 `CoreApi.getRecordDetail` |
| 原话、标签、建议依据 | `RecordDetailScreen`；证据按 `SourceRef`、版本和码点区间回查 |
| 候选顺序、排序依据与反馈关联 | `DinnerDecisionCard` 展示候选、条件与每条依据；保存反馈时把 `decisionId` 传给 `CoreApi.recordOutcome`；详情标出“当时建议”，并可按编号回查历史候选 |
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
- [ ] 在真机按 A08 复验：从候选回填反馈，重开后仍显示“当时建议／实际选择”；纠正条件后旧建议收起，仍可按编号回查。

## 反馈与纠正

详情按 `DinnerDecision` 展示本次候选、适用条件、排序依据和每条原话。用户点候选可把菜名带进“实际选择”，也可以点“其他”自行填写；保存时把当前建议编号一并提交，回执和反馈都保留这个编号。已保存的反馈会标出“当时建议”，用户修改条件后旧建议从详情收起，仍可从反馈按编号回查当时的候选、条件、算法版本和原话。[晚餐验收材料](../docs/issue10-testing.md)

## 2026-11-23 建议与反馈关联

- `RecordDetailScreen` 读取 `PebbleRecord.currentDinnerDecision`，按发布顺序展示候选；首选单独标记，其余标为候选 2、3……
- 反馈区新增候选选项和“其他”。未点“其他”时，`CoreApi.recordOutcome` 会收到当前 `decisionId`；点“其他”则按独立体验保存。
- 每条已保存反馈用 `decisionForOutcome` 找回原建议，同屏显示“当时建议”和实际选择；被纠正或脱敏的建议仍保留编号。
- “查看当时的候选与依据”调用 `CoreApi.getDinnerDecision(recordId, decisionId)`，展示历史候选、条件、算法版本和依据原话。
- 对应验收项：G12、O01、A08。JVM 侧新增 `DinnerDecisionReviewTest`，设备侧在 `DetailIntegrityTest` 增加反馈携带 `decisionId` 的用例；真机结果仍待补。
