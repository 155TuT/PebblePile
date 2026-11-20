# Issue #5 交接清单

## 已覆盖的页面和入口

| Issue #5 要求 | 当前代码位置 |
|---|---|
| Android 分享纯文字 | `MainActivity.kt` 的 `acceptShare`、`ACTION_SEND` manifest filter |
| 分享后保存并返回 recordId | `CoreApi.recordExperience`；分享确认弹窗 |
| 详情按 recordId 加载 | `CoreApi.getRecord`；`RecordDetailScreen(recordId)` |
| 原话、标签、建议依据 | `RecordDetailScreen` 的原始记录／语义标签／建议与依据 |
| 补记实际选择和体验 | `CoreApi.recordOutcome`；实际反馈区域 |
| 排除标签 | `CoreApi.reviseTags`；标签排除按钮 |
| 仅本次／以后按此条件 | `CorrectionScope`；纠正条件弹窗 |
| 修订冲突与草稿保留 | `ApiReceipt.conflict`；冲突提示；输入状态不清空 |

## 与团队协作的待办

- [x] 与 #4 统一到纵向 Compose 工程，包名为 `com.pebblepile.android`，首页、记录库和系统分享按 `detail/{recordId}` 打开详情。
- [ ] 从 Issue #6 取得正式 `CoreAPI` 版本、请求／回执 DTO 和错误码样例。
- [ ] 从 Issue #7 取得真实仓储实现；确认 `operationId` 幂等和 `baseRevision` 冲突语义。
- [ ] 从 Issue #3 接入正式输入框、标签、依据卡片和反馈状态组件。
- [ ] 从 Issue #8 接入真实建议和处理进度；验证证据的 `sourceId`、`sourceVersion`、原话区间。
- [ ] 与 Issue #10 按两次加班经历、一次周末例外和一次纠正的样例完成回归。
- [ ] 在真机验证系统分享、退出重开、360dp 小屏、触点和进程恢复。

## 当前限制

`DemoCoreApi` 用 SharedPreferences 保存原话、反馈、排除的标签和纠正范围。页面用固定的晚餐建议核对来源。#6、#7 接入事务、版本与幂等回执，#8、#9 用真实证据和偏好快照验证下一次建议如何变化。
