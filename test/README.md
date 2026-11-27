# Android 回归测试

叠石用一组虚构的晚餐记录验证从保存、推荐到纠正和反馈的完整流程。复验时，按 `recordId` 找回同一条记录，按 `sourceId` 和来源版本找回建议所引的原话，再按 `decisionId` 核对实际选择记在哪一次建议下。[逐字输入与预期](fixtures/issue10/README.md) · [业务接口与回执](../docs/core-api-contract.md)

## 运行测试

在仓库根目录执行。前三条命令校验晚餐样例、发布规则和 JVM 用例。最后一条在已连接的测试机或模拟器上运行。

```powershell
python test/fixtures/issue10/check_fixtures.py
python -m unittest discover -s test/release -p test_android_promotion.py
python scripts/gradle.py :scheduler-core:test :scheduler-android:testDebugUnitTest :app:testDebugUnitTest
python scripts/gradle.py :app:connectedDebugAndroidTest
```

[unit/](unit/) 存放应用单元测试，[device/](device/) 存放设备用例，[shared/](shared/) 提供两者共用的契约和样例读取方式。调度状态机和 Android 唤醒策略分别在 [scheduler-core/](scheduler-core/) 和 [scheduler-android/](scheduler-android/) 验证。运行 `python scripts/gradle.py :scheduler-core:test :scheduler-android:testDebugUnitTest :scheduler-android:assembleDebug` 可单独检查这两个模块。

| 层次 | 主要用例 | 核对内容 |
| --- | --- | --- |
| 业务与存储 | [共享契约](shared/service/CoreApiContractSuite.kt)、[Room JVM 测试](unit/db/RoomCoreContractTest.kt)、[Room 设备测试](device/db/RoomStorageContractTest.kt) | 首次写入、重发、修订冲突、事务回滚，以及数据库关闭重开后的记录、来源版本与回执 |
| 算法 | [饮食回放](unit/algorithm/AlgorithmBaselineTest.kt) | 原话区间、独立经历计数、人工排除、预算筛选、条件命中与排序 |
| 任务 | [调度测试](scheduler-core/SchedulerSelfTest.kt)、[Room 检查点](device/db/RoomTaskStoreTest.kt)、[WorkManager 集成](device/db/WorkDispatchIntegrationTest.kt) | 任务中断后从检查点恢复，同一阶段只提交一次，重复唤醒保留原决定 |
| 页面 | [记录流程](device/RecordFlowTest.kt)、[失败恢复](device/PageRecoveryTest.kt)、[晚餐建议与反馈](device/DinnerDetailIntegrationTest.kt) | 分享确认、草稿恢复、冲突重载，以及纠正前后给原决定补记反馈 |

Android CI 按提交保存 JVM 测试报告。发布工作流在 API 35 模拟器上运行调试版本的设备测试，再安装签名候选 APK，检查应用能否启动并保持运行。真机操作记录需注明所测 APK 的 SHA-256、机型、系统版本和操作结果。[Android CI](../.github/workflows/android-ci.yml) · [候选包检查脚本](release/android-candidate-smoke.sh)

## 根目录迁移验证

2026 年 11 月 28 日，移开已有的 `android/` 后，在仓库根目录运行 `npx expo run:android`。Expo 重新生成的 37 个宿主文件与原文件逐一相同，模拟器自动打开首页。修改并还原标题，两次保存都触发了 Fast Refresh，应用进程和 Hermes 调试连接持续可用。

同一天用 `python scripts/gradle.py` 构建独立调试 APK。127 项 JVM 测试、96 项 Android 16 模拟器测试全部通过，应用与 WorkManager 模块通过 Lint。两个 APK 均包含约定的中文字体及字体、图标许可。

## 0.1.0 已有验证

- **晚餐建议和反馈。** 2026 年 11 月 25 日，在 Android 16、API 36.1 模拟器上，用提交 `2a9bfbb8` 的调试包离线录入 E01 和 E02。Q01 首选 16 元的盖饭，依据分别定位到「加班到八点半」和「又加班了」。纠正条件后，周六首选轻食便当，周四首选盖饭。给原决定补记实际吃的热汤面，强制停止应用再冷启动，原决定、两条引文和反馈仍可回查。设备回归还分别验证了先反馈再纠正、先纠正再补反馈两种顺序。[对应设备用例](device/DinnerDetailIntegrationTest.kt)
- **发布候选包。** 0.1.0 候选构建在 API 35 模拟器上完成 96 项调试版仪器测试，随后安装并启动签名 APK。正式版沿用该候选文件。两次运行分别记录了候选验证和原包发布。[候选构建](https://github.com/155TuT/PebblePile/actions/runs/45604399762) · [正式发布](https://github.com/155TuT/PebblePile/actions/runs/45618461469) · [版本与校验值](../docs/android-build-release.md)

## 保存与修订

| 用例 | 操作 | 预期回执与库内状态 |
| --- | --- | --- |
| K01 首次保存 | 在空库开启自动整理，用新操作号保存 E01 | 返回记录、来源版本、修订号和整理任务。按编号能读回原话。 |
| K02 原样重发 | 保留入口、操作号和完整参数，重发 K01 | 两次回执逐字段一致。库中仍是一条记录、一份来源和一个整理任务。 |
| K03 旧修订 | 先更新记录，再用新操作号和旧 `baseRevision` 提交条件纠正 | 返回 `REVISION_CONFLICT` 和当前修订。重载后可用当前版本再次提交。 |

若沿用 K01 的操作号，把正文改成 E02，核心返回 `INVALID_INPUT`，原因为 `OPERATION_ID_REUSED`。若用户从分享入口再次送入同一经历，则使用新操作号，按下文 D01 核对经历计数。[回执样例](../docs/core-api-receipt-examples.json)

## 晚餐回放

在离线、开启自动整理的空测试库中按顺序操作。等相关记录整理完成后再提问。E01–E03 的原话与时间、Q01–Q03 的逐字提问、C01 的纠正内容均见[固定样例](fixtures/issue10/fixtures.json)。

| 步骤 | 操作 | 核对结果 |
| --- | --- | --- |
| A01 | 在首页保存周一吃热汤面的 E01，退出重开 | 按 `recordId` 找回第一条原话。 |
| A02 | 从系统分享周三吃盖饭的 E02 并确认保存 | 记录库有两次独立的工作日加班经历，两处来源分别可查。 |
| A03 | 保存周日做番茄鸡蛋面的 E03，从记录库回查 | 三条原话均可打开，周日愿意花时间做饭的经历保留在 E03。 |
| A04 | 在新对话问 Q01，展开首选盖饭的依据 | 预算为 20 元，两条依据分别指向 E01、E02 的原话与版本。记下这次的 `decisionId`。 |
| A05 | 新对话问 Q02，给出周日、预算 20 元、可做饭 40 分钟的条件 | 首选番茄鸡蛋面，依据来自 E03。 |
| A06 | 回到 Q01，修改条件为 C01，选「以后按此条件」 | 范围为工作日且加班，回执给出新修订。 |
| A07 | 分别新开对话问周六加班的 Q03 和周四加班的 Q01 | 周六按当前条件首选轻食便当。周四命中纠正后的偏好，首选盖饭。 |
| A08 | 回到 A04，在「选择此前的建议」中选原决定，实际选择填「热汤面」，体验填「省时也省事」，点「保存实际反馈」 | 反馈关联原 `decisionId`，当时建议是盖饭，实际选择是热汤面。用独立空库再走一次，在 A06 之前填写反馈。 |
| A09 | 退出重开，从记录库打开 A04 的记录 | 可回查原问题、原决定的候选与依据、后来的反馈和修订后的条件。 |

保存的算法基线使用 `diet-scene-pack-0.1.0` 和 `local-rules-0.1.0`。Windows 11、JDK 17.0.15 环境中，四个提问各连续回放三次，候选顺序一致，22 元的麻辣烫均超出预算，每次远程推理调用数均为 0。完整顺序和每轮分段耗时见[基线记录](fixtures/issue10/algorithm-baseline.json)。A01–A09 在设备上核对页面与原话。两次独立支持、一次周末例外及分段耗时由 JVM 回放核对。

手工复验时，用 360 dp 宽的纵向窗口重做 A04、A06 和 A08。在大字号、读屏和弹出软键盘的状态下，分别检查能否读清依据归属、完成条件修订和保存反馈。

## 重试、中断与迟到结果

每项使用独立的测试库，记录原任务编号、操作前后的状态、修订与检查点。设备上检查用户可见的原话、标签和反馈，调度与仓储测试再核对租约、写入次数和事务结果。

| 场景 | 操作与预期 |
| --- | --- |
| 重试与重复经历（F01、D01） | 同一保存或反馈原样重发只写一次。用新操作号再次分享 E01，可以增加来源，E01、E02 仍计两次独立经历。R01 描述的是室友，其经历单列为转述。 |
| 进程中断（F02） | 整理中结束测试进程并重开，按原 `jobId` 查看等待或恢复状态。原文可读，接手者从最近的检查点继续，最终标签和证据数量与正常完成一次相同。 |
| 人工排除（T01、F03） | 设备上从 E01 详情排除「加班」，回到记录库按「加班」筛选，核对 E01 已移出、E02 仍可见。算法回放另用「省事」标签核对排除后的支持证据只剩 E02。 |
| 修订后交回旧结果（F04） | 用旧修订启动整理，在阶段提交前完成 C01。按原 `jobId` 查到 `STALE` 状态，旧任务的 `decisionId` 为空，当前条件及修订保留纠正后的值。 |
| 暂停、继续与取消 | 在同一阶段分别暂停或取消，再让旧执行者交回结果。核对暂停原因为 `user_paused`，继续后从检查点恢复。取消后状态保持 `cancelled`，原话可回查。 |
| 写入异常与重试 | 在测试库中注入事务失败，核对记录、来源、任务和回执一起回滚。用同一请求重试，最后各保留一份。双连接争用写锁时，分别记录成功写入或可重试错误。 |

复现问题时，附上 APK 版本与校验值、来源提交、设备、场景包与算法版本、起始库状态、用例编号、实际回执和重现次数。安装失败、确认保存后丢失、建议引用失效、纠正后仍采用旧条件，均属发布阻断项。修复后用同一输入复验，并记录新包的校验值。
