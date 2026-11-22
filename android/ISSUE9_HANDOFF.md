# Issue #9 饮食算法交接

张洋负责本版饮食算法。#8 接三个计算接口，#6 接候选和证据结构，#10 按 `dinner-ranking-0.1.0` 回放固定样例。#2 可用周日休息、周六加班和周四加班三种条件复测产品解释。[任务 #9](https://github.com/155TuT/PebblePile/issues/9)

## 陈飞扬接调度，郑嘉文接提交

`AlgorithmEngine` 装配 `EventExtractor`、`PreferenceUpdate` 和 `DinnerRanker`。默认使用随包饮食词表、菜单和离线规则。三步都在写事务外计算。

| 接口 | 调用方传入 | 供下一步使用 |
| --- | --- | --- |
| `extract(source, text)` | 本次来源 ID、版本、所属记录和原文。已有经历归并记录时传 `eventId`。 | 观察类型、事件键、候选标签、饮食事实，以及支持它们的原文位置。 |
| `update(events, revisions, snapshot)` | 当前有效经历、按记录排除的标签、本人确认的条件及上一版快照。 | 每条偏好的状态、适用范围、支持经历、可回查的引文与周末反例。 |
| `rank(constraint, preferences, history = events)` | 本次预算、可用时间、星期和加班情况，更新后的偏好，以及带来源的经历。 | `RankResult` 返回候选顺序、采用和未命中的偏好、筛除项、条件和证据。 |

同一经历多次分享时沿用 `eventId`，各次分享保留各自的来源 ID 和版本。明确属于不同经历时传不同 ID。尚无归并信息时，相同原文按摘要保守去重。两次独立本人实际经历可形成推断偏好，单次支持记作 `WEAK` 供确认。明确纠正优先生效，按当前记录排除的标签会在重算时继续生效。

来源按 `sourceId + sourceVersion` 定位，`CodePointSpan.quoteIn(text)` 用 Unicode 码点区间 `[start,end)` 取回原话。`DietVocabulary.parseCorrectionScope` 和 `correctionDirection` 返回已识别的条件与方向，无法识别时返回 `null`，交给用户确认。#6/#8 提交前复核记录与偏好修订，连同算法清单、决定及证据一起写入。O01 所属决定的持久关联由应用验收 A08 核对。

## 林叶鑫接回放，林金晶接条件复测

在 `android/` 执行以下命令，使用 JDK 17、Android SDK 35 和本机已有的 Gradle 依赖。

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest --tests "*AlgorithmBaselineTest" --rerun
```

本次 PR 审查在 Windows 11 / JDK 17.0.15 上将 Q01、Q02、Q03、Q01b 各跑三次。四组均按预算筛除 22 元的麻辣烫。周四加班首选盖饭，周日休息首选番茄鸡蛋面，周六加班首选轻食便当。每次回放均为 0 次远程调用。[十二次分段耗时与完整候选顺序](../docs/testing/issue10/algorithm-baseline.json) · [逐项回归和待联调内容](../docs/issue10-testing.md#四算法回归)

直接调用 `dinnerQuery` 时，引擎复用已经抽取的经历完成更新和排序，产物附本次查询耗时与调用量。`engine.inferenceCalls` 记录该引擎实际请求已配置模型端口的累计次数。更换策略后，用同一输入检查名次、引用、耗时和调用量。[本版选型与复跑口径](../docs/algorithm/issue9-baseline.md)
