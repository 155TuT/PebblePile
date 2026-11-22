# Issue #9 交接说明

负责人：张洋（`hydrazZYT`）｜模块：饮食场景、证据与偏好算法｜对应 [#9](https://github.com/155TuT/PebblePile/issues/9)

## 当前可交付

- 三个可独立替换、单独测试的函数：`extract`、`update`、`rank`，位于 `com.pebblepile.android.algorithm` 包。
- 饮食场景包 `ScenePackage`：词表、离线候选菜单、餐价、备菜与清理投入、最少独立经历数，版本随 `AlgorithmManifest` 发布。
- 模型适配层 `InferencePort`：本地规则为默认离线实现；远程模型返回同构候选，未配置时返回 `null`，流程不依赖网络。
- JVM 回归测试 `AlgorithmBaselineTest`：覆盖 E01–E03 抽取与区间、两次独立经历计数、D01 重复分享、R01 转述、T01 排除标签、C01 范围、Q01/Q02/Q03/Q01b 排序与三次顺序稳定性。
- 排序基线快照 `docs/testing/issue10/algorithm-baseline.json`：候选清单、价格、预期顺序、引用证据、场景包与算法版本。

## 函数契约

| 函数 | 输入 | 输出 |
| --- | --- | --- |
| `extract(source, text)` | `EvidenceSource(sourceId, sourceVersion, recordId)`、原文、场景包 | `ExtractedEvent`：归并键、观察类型、标签候选、`DietFacts`、原话区间证据 |
| `update(events, revisions, snapshot)` | 有效事件、人工修订（排除标签 + 本人明确设置）、上一版快照 | `List<PreferenceCandidate>`：倾向、条件范围、状态、支持与反例证据、支持事件键 |
| `rank(constraint, preferences, historyMeals)` | 当前约束、有效偏好、历史经历菜名 | `RankResult`：排好序的候选、采用与未命中的偏好、被硬筛掉的候选、每项条件与证据 |

编排入口为 `AlgorithmEngine`：`dinnerQuery(constraint, events, revisions, snapshot?)` 一次完成更新、排序，并返回 `Manifest`、耗时（毫秒）与模型调用次数。

## 证据口径

- 证据带 `sourceId`、`sourceVersion` 和 Unicode 码点半开区间 `[start, end)`；引文用 `CodePointSpan.quoteIn(text)` 取回。
- 区间按码点计数，含 emoji 时先 `offsetByCodePoints` 换算 UTF-16 下标再 `substring`，接口仍返回码点区间。
- 推断偏好至少由 `ScenePackage.minIndependentSupports`（本版 2）次独立实际经历支持；不足为 `WEAK` 待核对。
- 本人明确设置为 `EXPLICIT`，优先于推断；转述 `REPORTED` 与他人材料不计入本人偏好；重复分享按事件归并键去重，不新增计数。

## 交接给下游

1. **#8 陈飞扬（调度）**：从 `AlgorithmEngine` 调用 `extract → update → rank`。算法在写事务外计算；提交时由核心复查输入修订、任务状态和人工排除。`Manifest` 随决策保存，候选证据可回查来源版本。
2. **#6 郑嘉文（接口校验）**：候选结构沿用 `EvidenceRef`（来源、版本、区间）与上述字段；长期纠正条件经 `DietVocabulary.parseCorrectionScope` 解析为 `ConditionScope`。候选发布时复查预算、范围、人工排除与任务状态。
3. **#10 林叶鑫（回归）**：读取 `algorithm-baseline.json` 的 `cases` 预期顺序；同一输入跑三次核对稳定性。基线版本字段：`algorithmVersion=local-rules-0.1.0`、`scenePackVersion=diet-scene-pack-0.1.0`。`expected.json` 的 `ranking.baselineVersion / scenePackVersion / algorithmVersion` 按清单回填。

## 构建与测试

配置 JDK 17 与 Android SDK 35 后：

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest --tests "com.pebblepile.android.algorithm.AlgorithmBaselineTest"
```

## 已知限制

- 本版抽取与排序全部为本地规则，`inferenceCallsPerQuery=0`；远程模型增强经 `InferencePort` 接入，启用时由用户选择发送片段。
- 耗时基线待 CI 或首测机三次实测后回填 `algorithm-baseline.json` 的 `latencyMillis`，当前不登记伪造数值。
- 在线逻辑回归作为下版增量参数候选，本版仅保留对照说明，未训练参数。
