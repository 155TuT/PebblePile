# Issue #5 构建与模拟器验证

两种字号的设备用例和离线重开结果见 [#22 合并前复验](ISSUE4_VERIFICATION.md)。

## 2026-11-21 组件化前端复验

原提交 `d0e3231` 已接入 #3 组件、#4 首页和记录库、#5 分享和详情。下表记录了提交者在 Pixel 7 纵向模拟器（Android 17 / API 37）上的验证结果。

| 项目 | 结果 |
| --- | --- |
| `clean assembleDebug` | 成功 |
| JVM 合约测试 | 30 项通过 |
| Android 仪器测试 | 27 项通过 |
| 首页保存回执并进入详情 | 成功 |
| 结束进程后重开 | 成功，记录库仍显示同一原话 |
| 分享确认跨 Activity 重建 | 成功 |
| 来源引用卡直接点击 | 成功打开“来源与原话” |
| 纠正草稿跨 Activity 重建 | 成功保留 |
| 修订冲突后查看最新版本并重试 | 成功 |
| 记录本、搜索、标签、人物和状态组合筛选 | 成功，返回详情后筛选保留 |
| 事项名称与状态编辑 | 成功 |
| 标签确认、修改、排除 | 成功 |
| 任务暂停与继续 | 成功 |
| 设置结束进程后恢复 | 成功，自动整理关闭与字号选择保留 |
| 系统字号 2.0 页面检查 | 成功，无横向截断 |
| 自动整理开关 | 成功，影响之后新记录的整理任务创建 |

本次首页持久化冒烟记录：

```text
PebbleManual20261121
```

回执：`已保存 · 7467863c-d4bc-4357-8f61-ea20abddab72`

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

1. 点开标签，核对原话后选择“排除”，回到详情查看“已排除”状态。
2. 返回记录库，核对筛选项只列出仍有记录使用的有效标签。
3. 点击建议下的证据卡，预期出现“来源与原话”面板。
4. 在“实际选择”和“实际体验／感受”中填写内容，点击“保存实际反馈”，预期新增独立反馈记录。
5. 点击“修改条件”，分别验证“仅本次”和“以后按此条件”；确认后预期显示对应范围的保存说明。
6. 保持纵向，在条件弹窗输入草稿后重建 Activity，检查草稿与适用范围。用另一笔修订更新同一条记录后再提交，点“查看最新版本”，核对当前条件和自己的草稿，再次提交。

## 后续复验

分享和详情已接入 #6 的业务接口与 #3 的共享组件。#7 交付 SQLite 后，从首页和系统分享各保存一条原话，重开后按各自的 `recordId` 回查。#8、#9 接通真实任务和建议后，再核对原话依据、反馈归属和长期纠正后的推荐结果。[详情交接清单](ISSUE5_HANDOFF.md)
