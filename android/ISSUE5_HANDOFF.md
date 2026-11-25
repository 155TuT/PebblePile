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
- [x] 首页和系统分享写入同一 SQLite 资料库。重开后可按记录编号找回原话、来源版本和首次回执。[存储与重开复验](ISSUE4_VERIFICATION.md)
- [x] #8 发布排序结果后，详情自动显示 #9 给出的候选顺序与理由。打开依据时，按当前事项引用的来源版本和码点区间取回原话。
- [ ] 与 Issue #10 按两次加班经历、一次周末例外和一次纠正的样例完成回归。
- [ ] 在真机验证系统分享、退出重开、360dp 小屏、触点和进程恢复。
- [ ] 在真机按 A08 复验：从候选回填反馈，重开后仍显示“当时建议／实际选择”；纠正条件后旧建议收起，仍可按编号回查。

## 反馈与纠正

在晚餐提问的详情页等整理完成，页面会显示首选、其余候选、采用的条件和原话。首选先列两处依据，优先展示不同来源。点菜名对应的“全部依据”可查其余引文。点一道菜会把菜名带进“实际选择”。最后吃了别的菜，点“其他菜品”自行填写，反馈仍关联起笔时的建议。单独记一次体验时，点“改为独立体验”。

纠正前的建议可从“选择此前的建议”找回。保存反馈后，点“查看当时的候选与依据”，核对原先推荐的菜、当时的条件和原话。林叶鑫按 A08 对照首次周四的盖饭和实际吃的热汤面，登记真机结果。[晚餐验收材料](../docs/issue10-testing.md)
