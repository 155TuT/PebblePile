# Issue #5 构建与模拟器验证

## 原分支验证记录

验证日期：2026-11-20

| 项目 | 结果 |
| --- | --- |
| Gradle Wrapper | 8.9，已包含可执行的 `gradlew.bat` 和 Wrapper JAR |
| JDK | Microsoft OpenJDK 21.0.13.1 |
| Android SDK | Platform 35、Build-Tools 34.0.0 |
| 构建 | `.\gradlew.bat assembleDebug` 成功 |
| 安装 | `adb install -r` 成功 |
| 系统分享 | `ACTION_SEND` 纯文本进入确认弹窗 |
| 持久化 | SharedPreferences 中存在原话和 `recordId` |
| 进程恢复 | 强制结束并重开后，记录库仍显示同一条原文 |
| 详情 | 点击记录后进入详情，显示同一原文、标签、依据和反馈区域 |

本次测试记录 ID：`974a4b99-94b2-4b1e-8e9c-e786cf2f65de`。

## PR #14 复验

Windows、JDK 17.0.15、Gradle 8.9，使用 Android 16（API 36）手机模拟器。应用固定纵向。工程在 `android/`，回归用例在 `app/src/androidTest/`。

| 操作 | 实际结果 |
| --- | --- |
| 从系统分享文字，在确认前、保存后、取消后分别重建页面 | 待确认的原话能继续保存。保存或取消后重建页面，直接回到记录详情或首页。 |
| 打开建议旁的依据，再关闭面板 | 能看到引用片段及对应记录的全文，关闭后回到同一条记录。 |
| 输入纠正草稿后重建页面；在另一笔修订写入后提交草稿 | 草稿保留。点“查看最新版本”能核对新条件，再提交一次，记录按所选范围更新。 |
| 在 360dp 纵向窗口开启飞行模式，从空库经首页、系统分享各保存一条原话，再结束进程重开 | 首页切换模式保留草稿。记录库能按原有的两个 `recordId` 找回各自的文字与来源。 |
| 在已选标签下排除最后一条匹配，再给原事项补记选择和体验 | 筛选回到全部记录。反馈只写入原事项，结束进程后仍可找回。 |

## 快速验证

在仓库根目录执行：

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

从空库开始：

```powershell
adb shell pm clear com.pebblepile.android
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "今天加班，晚饭想省事一点，预算二十元。" -n com.pebblepile.android/.MainActivity
```

预期结果：

1. 出现“保存分享内容”确认弹窗，正文显示发送的原文。
2. 点击“确认保存”后进入详情页。
3. 详情显示原文、两个演示标签、建议及本条证据。

检查项目测试数据：

```powershell
adb shell run-as com.pebblepile.android cat shared_prefs/pebblepile_issue4_demo.xml
```

预期 `records` JSON 中包含原始文本、稳定 `id`、`captureMethod=android_share` 和 `revision`。

结束并重开：

```powershell
adb shell am force-stop com.pebblepile.android
adb shell am start -n com.pebblepile.android/.MainActivity
```

预期记录库仍显示刚才的原文。点击记录后，再次看到同一详情。

## 详情操作验证

1. 点击任一标签旁的“排除”，预期文字变为“已排除”。
2. 返回记录库，被排除标签不应继续出现在筛选项中。
3. 点击建议下的证据卡，预期出现“来源与原话”面板。
4. 在“实际选择”和“实际体验／感受”中填写内容，点击“保存实际反馈”，预期新增独立反馈记录。
5. 点击“修改条件”，分别验证“仅本次”和“以后按此条件”；确认后预期显示对应范围的保存说明。
6. 保持纵向，在条件弹窗输入草稿后重建 Activity，检查草稿与适用范围。用另一笔修订更新同一条记录后再提交，点“查看最新版本”，核对当前条件和自己的草稿，再次提交。

## 正式联调前必须替换

- 将 `DemoCoreApi` 替换为 Issue #6/#7 的正式 `CoreAPI` 适配器。
- 演示标签、建议和证据不得作为算法验收结果。
- 正式仓储必须持久保存 `operationId` 回执，并在同一事务写入原文、回执和待办任务。
- “以后按此条件”必须由正式 CoreAPI 使受影响的旧推断失效并重算。
- 用 #4 首页与系统分享分别保存原话，接入 #7 后按同一个 `recordId` 回查详情。
