# Issue #4 验证记录

验证日期：2026-11-21

## 环境

- Android Studio 2026.2.2.9
- JDK 21.0.13.1
- Gradle Wrapper 8.9
- Android SDK Platform 35 / 37.2
- Android Emulator 37.2.13
- Pixel 7 AVD，Android 17 / API 37

## 已通过

| 验收项 | 结果 |
| --- | --- |
| `assembleDebug` | 成功 |
| JVM 合约测试 | 28 项通过 |
| Android 仪器测试 | 21 项通过 |
| APK 安装 | 成功 |
| 首页输入原话 | 成功 |
| 对话／记录切换保留草稿 | 成功 |
| 保存后显示带 `recordId` 的回执 | 成功 |
| 保存后进入 `detail/{recordId}` | 成功 |
| 页面使用 Issue #3 共享组件 | 成功 |
| 首页进入记录库 | 成功 |
| 时间线按日期显示 | 成功 |
| 标签筛选入口显示 | 成功 |
| 从记录库进入详情 | 成功 |
| `force-stop` 后重开 | 成功 |
| 重开后原话仍在 | 成功 |
| 系统分享写入同一资料库 | 成功 |
| 分享与首页记录同时显示在记录库 | 成功 |

本次最终冒烟验证产生的记录：

```text
PebblePersistence20261121.
```

首页保存回执示例：

```text
已保存 · cab57763-66b7-4749-bb3a-c71e50aa2d83
```

## 复现命令

```powershell
$env:JAVA_HOME = "D:\Android\OpenJDK21"
$env:ANDROID_HOME = "D:\Android\Sdk"

.\gradlew.bat clean assembleDebug
.\gradlew.bat connectedDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell pm clear com.pebblepile.android
adb shell am start -n com.pebblepile.android/.MainActivity
```

首页输入一段文字，切换“对话／记录”，确认文字仍在，再保存。

进程重开：

```powershell
adb shell am force-stop com.pebblepile.android
adb shell am start -n com.pebblepile.android/.MainActivity
```

系统分享：

```powershell
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "Shared_record" -n com.pebblepile.android/.MainActivity
```

检查本地库：

```powershell
adb shell run-as com.pebblepile.android cat shared_prefs/pebblepile_issue4_demo.xml
```

预期同一 `records` JSON 中同时存在：

```text
FinalIssue4Home   captureMethod=app_home_record
FinalIssue4Shared captureMethod=android_share
```

## 后续复验

上面记录的是 #4、#5 在 Pixel 7 / Android 17 模拟器上的组件化前端结果。首页已接入 #6 的业务接口和 #3 的共享组件。#7 交付 SQLite 后，在首测手机上重做首页保存、系统分享和进程重开，核对两条原话和各自的编号。组件和算法的接手顺序见[工程交接](ISSUE4_HANDOFF.md)。
