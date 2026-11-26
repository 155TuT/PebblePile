# PebblePile Android 客户端

0.1.0 将移动端原型中的首页、记录库、详情和设置做成了可安装的 Android 应用。最低支持 Android 8.0（API 26）。初次体验可以先记一条加班晚餐的原话，从记录库打开它，再按发布说明中的例子补充第二次经历、提问并查看依据。[下载 0.1.0 稳定版](https://github.com/155TuT/PebblePile/releases/tag/v0.1.0) · [晚餐示例](release-notes.md)

## 应用怎样处理一条记录

1. 首页输入、语音转写和补充材料共用一份文字草稿。从其他应用分享纯文本时，叠石先显示确认框。保存后两个入口都按回执里的 `recordId` 打开详情。[首页与系统分享](../docs/android-record-flow.md)
2. 记录、原话版本、事项、任务和操作回执保存在本机 SQLite。记录库可以在时间线和记录本之间切换，按文字、标签、人物和事项状态组合筛选。[本地存储](../docs/android-storage.md)
3. 开启自动整理后，新记录会创建后台任务。对于晚餐提问，本地规则从经历中提取条件和证据，按预算、时间和偏好比较随包菜单。详情随结果更新，每条依据可以回到对应版本的原话。[后台调度](scheduler-android/README.md) · [饮食算法](../docs/algorithm/dinner-ranking.md)
4. 用户在详情里补记实际选择和感受，反馈与当时的建议通过 `decisionId` 关联。纠正条件时可以选“仅本次”或“以后按此条件”，已有建议保留当时的条件与依据。[业务接口](../docs/core-api-contract.md)

设置中的“自动整理”控制新记录是否创建任务，“文字大小”调整应用字号，“推断辅助”保存开关选项。0.1.0 的晚餐排序使用本地规则。记忆与偏好页可回查条件，标签与规则页展示有效标签和核对状态，本地数据页可分享记录数、来源版本数和事项状态分布。[设置实现](app/src/main/java/com/pebblepile/android/SettingsScreen.kt)

## 工程结构

| 模块 | 职责 |
| --- | --- |
| [app](app/) | Compose 页面、导航、业务接口、Room 数据库、饮食算法和应用内 Worker |
| [scheduler-core](scheduler-core/README.md) | Kotlin 调度状态机、任务租约和阶段检查点 |
| [scheduler-android](scheduler-android/README.md) | 用 WorkManager 执行和恢复持久任务 |

应用由 `PebblePileApplication` 创建共享的 `CoreApi`。Android 入口 `DemoCoreApi` 将业务操作交给 `LocalCoreApi`，并连接 Room 仓储与后台调度。界面设置单独保存在 SharedPreferences 的 `pebblepile_settings` 中。[应用入口](app/src/main/java/com/pebblepile/android/PebblePileApplication.kt) · [Android 业务入口](app/src/main/java/com/pebblepile/android/DemoCoreApi.kt)

## 构建与测试

在 `android/` 目录下运行。环境需要 JDK 17 或 21、Android SDK 35 和仓库自带的 Gradle Wrapper。首条命令运行调度及应用单元测试、Lint 检查，并生成调试 APK。

```powershell
.\gradlew.bat :scheduler-core:test :scheduler-android:testDebugUnitTest :scheduler-android:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.pebblepile.android/.MainActivity
```

连接测试设备或模拟器后，运行 `.\gradlew.bat :app:connectedDebugAndroidTest`。设备用例覆盖系统分享、同一记录重开、标签纠正、来源回查、后台任务和历史建议。晚餐样例、故障恢复和手动复验步骤见[测试说明](../docs/testing/README.md)。签名、校验和发布见[构建与发布](../docs/android-build-release.md)。
