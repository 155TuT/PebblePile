# 后台任务调度

首页和系统分享保存原话后，用户先拿到回执。开启自动整理时，回执中的任务 ID 对应一个可恢复的后台任务。`:scheduler-core` 管理任务状态、阶段产物和租约，Android 通过 [WorkManager 适配器](../scheduler-android/README.md)唤醒它。

## 任务如何推进

1. **领取任务。** 应用复用保存回执中的任务 ID。单独调用 `submit` 时，核心按记录 ID、记录修订、偏好修订和算法版本计算任务键。相同输入复用任务，同一键下的不同原文或来源版本会触发输入冲突。
2. **计算并保存。** 整理依次经过 `EXTRACT → UPDATE → CONTEXT → RANK`。执行者领取任务时取得租约，每完成一个阶段就提交检查点并续租。恢复时从第一个待处理阶段继续。暂停或取消会收回租约，已开始的计算需凭有效租约才能写入。
3. **发布结果。** `prepareCommit` 根据检查点组装决定编号及证据，`finishIfCurrent` 在同一事务里核对租约、材料与偏好修订。核对通过后写入结果和终态。材料已删除或修订变化时，旧任务转为过期。
4. **查询或控制任务。** `getTask` 返回当前阶段、最近检查点、等待原因和可执行操作。执行中可暂停或取消，暂停后可继续。失败时按 `retryable` 提供重试，遇到待核对的结果则打开详情。

`InMemoryTaskStore` 用于 JVM 状态机测试。设备使用 `RoomTaskStore` 持久化任务和各阶段的产物，由 `DietTaskPipeline` 计算晚餐候选及依据。删除清理使用独立的检查点，依次清理来源版本、记录正文和旧任务产物。[本地存储](../../docs/android-storage.md) · [晚餐算法与回放](../../docs/algorithm/dinner-ranking.md)

## 调度回归

在 `android/` 运行模块测试与 lint。第二条命令需要已连接的模拟器或真机。

```powershell
.\gradlew.bat :scheduler-core:test :scheduler-android:testDebugUnitTest :scheduler-android:lintDebug :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

| 场景 | 复核内容 |
| --- | --- |
| 重复保存或重复唤醒 | 用同一回执中的任务 ID 查询，两次唤醒只发布一份决定。关闭自动整理后，原话仍可打开。 |
| 暂停、重启或修改偏好 | 从第二个 Room 连接接管已到期的租约，检查任务沿用原编号和检查点。暂停或偏好变化后，旧执行者交回的结果应保留最新业务状态。 |
| 旧版晚餐任务升级 | 对只有排序检查点的任务，升级后核对原任务 ID、回执和发布的决定。已发布的决定保留原编号。 |
| 删除中断与重试 | 每个清理阶段和检查点一起提交。模拟写库失败后重开，从上一个检查点继续。只解绑跨记录引用时，来源所属记录仍能打开原话。 |

[设备调度与数据库升级测试](../app/src/androidTest/java/com/pebblepile/android/db/RoomTaskStoreTest.kt) · [系统唤醒测试](../app/src/androidTest/java/com/pebblepile/android/db/WorkDispatchIntegrationTest.kt) · [清理回归测试](../app/src/test/java/com/pebblepile/android/db/RoomCleanupRunnerTest.kt)

2026 年 11 月 23 日调度模块接入饮食算法时（提交 `4b97083`），回归记录列出 113 项通过的 JVM 测试（调度核心 19 项、WorkManager 适配器 2 项、应用 92 项）和 87 项通过的 API 36.1 设备测试。升级样例从旧版等待算法的任务恢复，完成四个计算阶段后再次强停、冷启动，任务修订保持为 10，领取次数保持为 2。清理样例从 `sources_removed`（修订 2）恢复到 `complete`（修订 4），再启动后仍为同一任务。0.1.0 的晚餐验收材料见[测试说明](../../docs/testing/README.md)。
