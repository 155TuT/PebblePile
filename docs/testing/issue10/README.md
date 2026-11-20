# issue10 机器可读样例

本目录供前端、存储和算法共用同一份晚餐材料与预期，对应 [`docs/issue10-testing.md`](../../issue10-testing.md)。

## 文件

| 文件 | 内容 |
| --- | --- |
| `fixtures.json` | 原文、发生时间、条件、证据区间和每条记录的预期事实 |
| `expected.json` | 计数、引用、条件匹配、回归样例、契约与故障不变量，以及待基线项 |

两份文件的 `datasetId` 必须一致；`datasetVersion` 变化视为一次新的数据集，旧结论作废。

## 区间口径

- 下标从 0 开始，`end` 为半开区间，单位是 UTF-16 码元，可直接用于 Kotlin `String.substring(start, end)`。
- `start`、`end` 只对 `fixtures.json` 中的 `text` 成立；改一个字都要重算并升 `datasetVersion`。
- 比较时同时核对文本和下标。只对文本或只对下标都算失败。

可用下面的脚本自检。脚本只读 JSON，不改文件，在仓库根目录执行。

```python
import json
from pathlib import Path

base = Path("docs/testing/issue10")
fixtures = json.loads((base / "fixtures.json").read_text(encoding="utf-8"))

for record in fixtures["records"]:
    text = record["text"]
    assert len(text) == record["length"], record["id"]
    for span in record["spans"]:
        actual = text[span["start"]:span["end"]]
        assert actual == span["text"], (record["id"], span, actual)

print("spans ok")
```

## 状态值

| 值 | 含义 |
| --- | --- |
| `frozen` | 材料与语义预期已冻结，可直接执行 |
| `pending_baseline` | 等 #9 的候选清单与版本化排序基线 |
| `pending_contract` | 等 #6 的样例回执与错误码 |
| `pending_apk` | 等 #11 的签名 APK 与 SHA-256 |
| `draft_pending_confirmation` | 文本与预期尚待林金晶、林叶鑫确认 |

`scopeMatch` 为 `null` 表示此时还没有 C01 的明确设置，不是“未测试”。C01 之后只允许 `true` 或 `false`。

## 使用约定

1. 三端读取 `fixtures.json` 作为唯一输入，不各自复制文本。
2. 算法返回的证据位置与 `spans` 逐字段比较；契约回执只比较 `expected.json` 中列出的不变量，字段名以 #6 为准。
3. 排序名次不写死在这里；拿到 #9 基线后填 `ranking.baselineVersion` 和 `scenePackVersion`，同一输入至少跑三次。
4. 结果写回 `docs/issue10-testing.md` 的“实际”和“结论”栏，并登记证据编号。
