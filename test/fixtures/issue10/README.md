# 晚餐回归样例

首页记录、系统分享、业务契约和饮食算法共用数据集 `issue10-dinner-v1`。三条虚构经历覆盖工作日加班和周末休息，两次工作日经历共同支持「加班时优先省事」。后续提问、纠正和反馈用于检验这个偏好何时适用、建议依据能否回到原话。[设备回放与故障复验](../../README.md)

| 文件 | 用途 |
| --- | --- |
| [fixtures.json](fixtures.json) | 数据集 0.1.1 的原话、发生时间、提问条件、逐条事实与证据区间。 |
| [expected.json](expected.json) | 对应的预期版本 0.1.2，含经历计数、来源引用、条件命中、回执及故障用例。 |
| [algorithm-baseline.json](algorithm-baseline.json) | 排序基线 `dinner-ranking-0.1.0` 的菜单、参考价格、各题完整顺序、调用次数和三轮回放耗时。 |

## 按原话取证

按来源版本取原文，再按 Unicode 码点从 0 计数。区间 `[start,end)` 含起点，取到终点之前。例如 `🍜热汤面` 中的「热汤面」占 `[1,4)`。Python 3 可直接切片。Kotlin 需先把两个端点换算为 UTF-16 下标。

```kotlin
val quote = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
```

在仓库根目录运行 `python test/fixtures/issue10/check_fixtures.py`。脚本将样例与[研究文档中的逐字输入](../../../docs/user-research.md)对读，检查数据集版本、三条记录中的 16 处区间，以及 emoji 前缀样例。修改原话时同步更新输入和预期，重算区间并标明新的数据集版本。

## 怎样读预期

- 事实字段为 `null` 表示原话没有交代该事实。`scopeMatch` 为 `null` 表示提问发生在明确纠正之前。纠正后，`true` 表示命中工作日且加班的规则，`false` 表示按当前其余条件排序。
- `frozen` 标明固定输入或预期。`pending_contract`、`pending_apk` 是样例冻结时留下的状态。复验结论以所测提交的回执、测试报告和 APK 操作记录为准。
- F04 的预期文件沿用冻结时的错误名 `STALE_INPUT`。当前 Room 仓储通过任务状态 `STALE` 表达旧结果过期，设备测试还会核对该任务的 `decisionId` 为空。[Room 任务用例](../../device/db/RoomTaskStoreTest.kt)
- 更新词表、规则、菜单或排序时，按四次提问逐项比较候选顺序、超预算的菜品、所引原话与调用次数。比较耗时还需记录运行环境及轮次。
