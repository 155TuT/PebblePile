# issue10 机器可读样例

本目录供前端、存储和算法共用同一份晚餐材料与预期，对应 [`docs/issue10-testing.md`](../../issue10-testing.md)。

## 文件

| 文件 | 内容 |
| --- | --- |
| `fixtures.json` | 原文、发生时间、条件、证据区间和每条记录的预期事实 |
| `expected.json` | 计数、引用、条件匹配、回归样例、契约与故障不变量及排序基线版本 |
| `algorithm-baseline.json` | 场景包、菜单、四次提问的候选顺序与引用、三轮离线回放耗时 |

三份文件按 `datasetId` 和 `datasetVersion` 配套使用。样例更新后，用新版本重跑受影响的用例。

## 区间口径

按 Unicode 码点从 0 计数，区间写作 `[start,end)`。先按来源版本取原文，再核对区间和引文。修改输入后重算区间并更新 `datasetVersion`。[证据存储约定](../../database-design.md#433-事件与证据)

例如 `🍜热汤面` 中的 `热汤面` 占码点区间 `[1,4)`。Python 3 可直接切片。Kotlin 先把两个端点换算成 UTF-16 下标，再取出引文。

```kotlin
val quote = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
```

在仓库根目录运行 `python docs/testing/issue10/check_fixtures.py`。脚本核对 #2 的逐字材料、两份 JSON 的版本、每处原话的下标与引文，并用带 emoji 的样例检查偏移单位。

## 状态值

| 值 | 含义 |
| --- | --- |
| `frozen` | 材料与语义预期已冻结，可直接执行 |
| `pending_contract` | 样例冻结时留给 #6 核对的字段和错误码。内存仓储的三种实测回执见[回执记录表](../../issue10-testing.md#33-回执记录表)，接入 SQLite 后按同一预期复验。 |
| `pending_apk` | 等 #11 的签名 APK 与 SHA-256 |

`scopeMatch` 为 `null` 表示提问发生在 C01 之前。C01 之后，`true` 表示命中本人设置，`false` 表示按本次其他有效条件排序。记录事实中的 `null` 表示原文未交代该信息。

## 使用约定

1. 三端读取 `fixtures.json` 作为唯一输入，不各自复制文本。
2. 按算法返回的位置读回原话，核对它支持的事实。#9 的基线指定了片段时，再比对端点。契约回执按 `expected.json` 中的不变量和 #6 的字段逐项核对。
3. 按 `expected.ranking` 指定的版本加载算法基线。每次换规则，复跑四个提问，逐项核对顺序、筛除、引用与调用量。
4. 结果写回 `docs/issue10-testing.md` 的“实际”和“结论”栏，并登记证据编号。
