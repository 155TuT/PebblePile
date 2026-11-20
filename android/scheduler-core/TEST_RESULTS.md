# Issue #8 调度核心自测结果

## 执行环境

- Windows
- JDK 17.0.21
- Kotlin compiler 2.4.21
- 运行命令：`powershell -NoProfile -File android/scheduler-core/check.ps1`

## 结果

`13 scheduler tests passed`

覆盖场景：

1. 相同输入幂等创建任务，偏好版本变化创建新任务。
2. `EXTRACT → UPDATE → CONTEXT → RANK → COMMIT` 顺序执行。
3. 可重试错误进入 `waiting(retry_pending)`。
4. 重试复用已提交检查点，不重复执行完成阶段。
5. 运行中偏好变化，旧任务转为 `stale` 且不提交。
6. 暂停撤销租约，旧执行者不能写回。
7. 进程中断后从已提交检查点恢复。
8. 同一活动租约不能重复领取。
9. 过期租约可被恢复执行者重新领取。
10. 取消后进入终态并可查询。
11. 普通异常进入 `failed`，允许人工重试。
12. `runNext` 一次只领取一个排队任务。
13. 记录修订变化或跳过前置阶段时拒绝旧/非法写入。

## 尚未执行

`android/scheduler-android` 的 WorkManager 适配依赖 Android 工程、Android SDK 和 WorkManager 依赖。当前仓库尚未建立 Android 工程，因此：

- 未执行 Android 编译。
- 未执行真机进程终止后的 WorkManager 恢复。
- 真实 Room 任务表由 #7 提供，真实算法由 #9 提供。

接入 #4 的 Android 工程后，应按 `android/scheduler-android/README.md` 安装 WorkerFactory，并补齐这两项真机验证。
