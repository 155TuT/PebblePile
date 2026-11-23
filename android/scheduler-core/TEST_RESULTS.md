# Issue #8 调度验证

用 JDK 17 在 `android/` 运行构建、JVM 测试和 lint。启动 Android 模拟器后，第二条命令运行设备套件。

```powershell
.\gradlew.bat :scheduler-core:test :scheduler-android:testDebugUnitTest :scheduler-android:assembleDebug :scheduler-android:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

| 验证对象 | 操作与结果 |
| --- | --- |
| 保存入口 | 首页和系统分享沿用保存回执中的任务编号。相同操作重发仍只产生一个业务任务。关闭自动整理时保存原话，事务回滚时队列保持原样。 |
| 任务表 | 从第二个 Room 连接领取到期租约，按原检查点继续。暂停后交回旧租约的结果、提交前修改长期偏好，均保留新的业务状态。旧版任务沿用原编号。 |
| 删除清理 | 来源行、记录正文和旧任务产物分三步清理，阶段产物与检查点在同一事务提交。模拟写库失败后从原检查点重试，重复唤醒沿用同一清理任务。解绑后，原记录的来源仍可读取。 |
| 算法交接 | Worker 已接入饮食算法。普通记录核对整理终态，晚餐提问核对排序检查点、算法清单和证据位置。嘉文接 CoreAPI 发布校验，林叶鑫用同一组材料复验页面决定和证据。 |

本次通过 113 项 JVM 测试（调度核心 19 项、WorkManager 适配器 2 项、应用 92 项）和 87 项 API 36.1 设备测试。应用与适配器 lint 均为 0 个错误。四个固定晚餐提问核对了候选顺序、来源版本和原话区间。设置中直接改偏好时，排序检查点保留条件所属记录及其修订。

升级验证从 #27 的 APK 开始。通过系统分享保存材料，等任务进入等待整理后，保留数据安装本版。首次启动后，Worker 沿用原任务编号跑完四个阶段。再次强停并启动，任务修订仍为 10，领取次数仍为 2，排序检查点与原回执均保留。

冷启动恢复单独使用一份已提交 `sources_removed` 的 Room 文件库。启动正式应用入口后，WorkManager 以同一清理任务编号从修订 2 推进到 `complete`（修订 4）。记录正文从一条变为零条，两份原回执仍可查询。再次强停并冷启动，清理任务数仍为一，修订仍为 4。
