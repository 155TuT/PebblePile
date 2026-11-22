# Issue #8 调度验证

在仓库的 `android/` 目录使用 JDK 17 和 Gradle Wrapper 执行：

```powershell
.\gradlew.bat :scheduler-core:test :scheduler-android:testDebugUnitTest :scheduler-android:assembleDebug :scheduler-android:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

| 检查 | 本次结果 |
| --- | --- |
| 调度核心 | 18 项 JVM 测试通过。覆盖幂等提交、阶段顺序、检查点续租与恢复、暂停和取消、重试、迟到结果与待确认决定。 |
| WorkManager 适配器 | 2 项 JVM 测试通过。覆盖租约占用后的退避、可重试等待、暂停、取消和失败的 Worker 返回值。Debug AAR 构建通过。 |
| Android 应用 | 36 项 JVM 测试通过，Debug APK 构建通过。应用与调度库的 lint 检查均为 0 个错误。 |

本轮恢复用例在同一内存仓储中推进时钟并重新领取任务。#7 接入 Room、#9 交付管线、应用注册 WorkerFactory 后，#8 与 #10 再在设备上结束进程并重启，核对原任务的检查点、决定和证据。
