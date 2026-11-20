# PebblePile Android · Issue #5 starter

这是为 PebblePile Issue #5（Android 分享、详情与纠正）从零建立的 Kotlin + Jetpack Compose 功能工程。

## 当前实现

- Android `ACTION_SEND` / `text/plain` 接收其他应用分享的纯文本。
- 保存前展示确认弹窗；确认后调用 `CoreApi.recordExperience`。
- 通过稳定 `recordId` 打开记录详情。
- 详情展示原文、标签及标签来源片段／算法版本、建议及证据原话。
- 在原记录补记实际选择和体验，建议与反馈分开展示。
- 排除标签；纠正条件可选择“仅本次”或“以后按此条件”。
- API 返回修订冲突时保留编辑草稿并提示查看最新版本。
- 附带 `DemoCoreApi`，用 SharedPreferences 保存演示数据，便于没有后端实现时先运行和走查页面。

## 重要：演示适配器不是团队最终数据层

`DemoCoreApi` 仅用于独立演示和页面开发，不替代团队的 `CoreAPI`、Room/SQLite 仓储或事务规则。正式联调时：

1. 保留 `CoreApi` 的 UI 调用边界，按郑嘉文交付的接口版本和 DTO 实现 `TeamCoreApiAdapter`。
2. 将 `MainActivity` 中的 `DemoCoreApi(applicationContext)` 替换为团队核心的装配入口。
3. 把 `recordExperience`、`getRecord`、`reviseTags`、`recordOutcome`、`reviseMemory` 的参数和回执逐项映射到团队正式接口；不要在页面直接访问 SQLite。
4. 详情入口由首页／记录库通过 `recordId` 调用；分享入口保存成功后也导航到同一个详情路由。
5. 真实的“以后按此条件”必须由 CoreAPI 事务写入明确偏好并使受影响旧推断失效；当前 Demo 只在本地记录条件并显示说明，不实现真正的模型重算。

## 构建

工程已包含 Gradle Wrapper 8.9。需要 JDK 17 或 21，以及 Android SDK Platform 35。已在 Windows、JDK 21 和 Android API 37 模拟器上完成构建与启动验证。

Windows PowerShell：

```powershell
$env:JAVA_HOME = "D:\Android\OpenJDK21"
$env:ANDROID_HOME = "D:\Android\Sdk"
.\gradlew.bat assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

工程路径允许包含中文；`gradle.properties` 使用 `android.overridePathCheck=true` 开启 AGP 的兼容检查。

## 手动验证

1. 安装并启动应用：

   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb shell am start -n com.pebblepile.android/.MainActivity
   ```

2. 从任意支持纯文本分享的应用选择“叠石”，或使用 ADB 模拟分享：

   ```powershell
   adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "今天加班，晚饭想省事一点，预算二十元。" -n com.pebblepile.android/.MainActivity
   ```

3. 在确认弹窗点击“确认保存”，预期进入详情，看到原话、标签、建议和证据。
4. 返回记录库，确认该记录存在，并点击进入同一详情。
5. 结束进程后重开：

   ```powershell
   adb shell am force-stop com.pebblepile.android
   adb shell am start -n com.pebblepile.android/.MainActivity
   ```

   预期记录仍在，原话和 `recordId` 对应关系不丢失。
6. 排除一个标签，确认其显示为“已排除”。
7. 补记实际选择和体验，确认其单独出现在“实际反馈”区域。
8. 分别测试“仅本次”和“以后按此条件”；旋转屏幕或打开冲突提示后，草稿应保留。
9. 接入正式 CoreAPI 后模拟 `REVISION_CONFLICT`，确认页面保留草稿并提示查看最新版本。

## 可直接提交的范围

可以提交为 Issue #5 的功能切片和团队联调基础，已验证：

- Gradle Wrapper 8.9 可构建 `assembleDebug`。
- APK 可安装到 Android API 37 模拟器。
- 系统分享、保存确认、按 `recordId` 展示详情、标签纠正、反馈和条件纠正入口已经接好。
- Demo 数据使用 SharedPreferences，可用于演示和前端联调。

还不能替代正式发布版本的部分：

- `DemoCoreApi` 的标签和建议是演示数据，不是算法或模型结果。
- 幂等回执目前只在进程内保存；进程重启后的重复请求去重需由正式 CoreAPI 保证。
- 尚未接入团队 Room/SQLite 仓储、真实任务、证据来源版本和偏好重算。
- 尚未接入 Issue #3 的正式组件和 Issue #4 首页输入、完整记录库。

## 设计依据

页面分组和交互依据仓库 `docs/prototype-design.md` 中的 L3、L4、H3、H4、M3、M4、M5 状态；数据边界依据 `docs/system-design.md` 的 CoreAPI 与修订约定。当前仅做 Issue #5 的功能切片，不包含首页完整对话、记录库筛选、真实 SQLite、任务调度和饮食算法。
