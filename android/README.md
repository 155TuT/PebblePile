# PebblePile Android

Android 工程、首页输入、记录库、系统分享、详情、任务与设置页的组件化前端。

正式构建、签名、候选包和 Release 流程见 [Android 构建、签名与发布](../docs/android-build-release.md)。版本由 [`version.properties`](version.properties) 维护，签名发布必须使用环境变量或 GitHub Actions Secrets 提供密钥。

## 已覆盖

- Kotlin + Jetpack Compose 单 `app` 工程和 Gradle Wrapper 8.9。首页、记录库、系统分享和详情共用纵向界面。
- 页面已接入 Issue #3 的 `PebbleComponentTheme`、输入、标签、依据卡片、反馈、按钮和弹层组件。
- 首页直接输入，对话／记录模式切换时保留同一草稿。
- 首页提供“添加材料”和语音输入入口。添加材料会写入可编辑草稿；语音优先调用系统识别，缺少系统能力时明确说明并允许手动写入转写文字。
- 首页提交后显示带 `recordId` 的保存回执并进入详情。
- 首页可继续展示最近保存回执和整理任务状态，区分已保存、正在整理、等待条件、待核对、已更新和失败。
- 记录库支持时间线／记录本切换，以及搜索、标签、人物、事项状态的组合筛选。
- 返回记录库时保留视图、搜索和筛选状态；无记录与无筛选结果都有明确空状态。
- 详情路由为 `detail/{recordId}`，记录库和首页都通过同一 ID 打开。
- Android `ACTION_SEND` 纯文本分享进入同一资料库，保存后打开同一详情。
- 详情展示并编辑事项名称与状态，区分建议、用户选择、实际结果和当前目标条件。
- 详情从 `RecordDetail.jobIds` 读取整理任务，可查看阶段、等待原因并暂停、继续、取消或重试对应任务。
- 标签支持确认、修改、排除、恢复和标记待核对；记录库筛选随修订更新。
- 证据绑定 `SourceRef`、来源版本和 Unicode 码点半开区间 `[start,end)`，来源失效时单独显示原因。
- 设置页包含自动整理、推断辅助、文字大小、记忆与偏好、标签与规则和本地数据。
- 设置保存在 `pebblepile_settings`；自动整理会实际影响之后新记录的整理任务创建。
- `LocalCoreApi` 通过 SharedPreferences 仓储保存记录、原话版本、任务和回执。重开应用后重发同一次请求，可以取回首次保存的回执。

## 构建

将 `JAVA_HOME` 指向 JDK 17 或 21，`ANDROID_HOME` 指向装有 Platform 35 的 Android SDK。打包团队最新进度时，在仓库根目录同步 `main`。分支联调时，直接在当前分支运行 Gradle 命令。

```powershell
git switch main
git pull --ff-only
cd android
.\gradlew.bat :app:assembleDebug
```

安装下面的应用包。调试构建会清理上次设备测试留下的 APK。在 Git 检出目录构建时，应用版本名包含当前提交的短哈希。

```text
app\build\outputs\apk\debug\app-debug.apk
```

工程路径允许包含中文，配置中已开启 `android.overridePathCheck=true`。

接上一台测试设备，在 `android/` 运行 `.\gradlew.bat connectedDebugAndroidTest`。设备用例覆盖分享确认、依据回查、纠正草稿、冲突恢复、设置持久化、组合筛选、标签操作和任务暂停／继续。[契约测试](app/src/androidTest/java/com/pebblepile/android/CoreContractTest.kt) · [记录流程](app/src/androidTest/java/com/pebblepile/android/RecordFlowTest.kt) · [完整前端流程](app/src/androidTest/java/com/pebblepile/android/FrontendFlowTest.kt)

## 单元测试

JVM 用例直接读取 #10 冻结的晚餐原话，用内存仓储核对首次保存、重发、修订冲突、来源版本、条件、标签、事项状态、任务重试和反馈。写入中断后，重试应取回同一次操作的结果。[契约测试](app/src/test/java/com/pebblepile/android/service/LocalCoreApiContractTest.kt) · [扩展核心测试](app/src/test/java/com/pebblepile/android/service/AdvancedCoreContractTest.kt) · [固定样例](../docs/testing/issue10/fixtures.json)

```powershell
cd android
.\gradlew.bat testDebugUnitTest
```

报告在 `app/build/reports/tests/testDebugUnitTest/index.html`。Android CI 在相关 PR 和 `main` 推送中运行同一组测试，上传 HTML 报告和逐项 JUnit XML。

在 Windows 中文路径下，Gradle 按检出目录和测试任务分别复制项目编译输出，Debug 和 Release 可以各自运行。

## 手动验收

### 首页记录

1. 启动应用：

   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb shell pm clear com.pebblepile.android
   adb shell am start -n com.pebblepile.android/.MainActivity
   ```

2. 在首页输入一段自选文字。
3. 在“对话／记录”之间切换，确认输入内容仍在。
4. 点击“添加材料”补充一段文字，确认它进入同一草稿；可尝试“语音输入”，无系统识别时按页面说明手动写入转写内容。
5. 点击输入区右侧的“发送”或“保存”图标。
6. 预期显示带 `recordId` 的保存回执并进入详情；返回首页仍显示最近回执与整理状态。

### 记录库与重开

1. 返回首页，点左上角的“记录库”。
2. 预期按日期看到刚才的记录；切换记录本，并尝试搜索、标签、人物和事项状态的组合筛选。
3. 打开记录再返回，预期视图与筛选仍保留。
4. 结束并重开：

   ```powershell
   adb shell am force-stop com.pebblepile.android
   adb shell am start -n com.pebblepile.android/.MainActivity
   ```

5. 再次进入记录库，预期原话仍存在。
6. 点击记录，预期进入同一 `recordId` 对应的详情。

### 详情、任务与标签

1. 在详情编辑事项名称和当前状态。
2. 点详情标签打开依据面板，核对原话版本和算法。确认、修改、标记待核对或排除标签后，返回记录库核对筛选。
3. 点击依据卡，预期面板显示来源 ID、来源版本和码点区间，并高亮完整来源原话。
4. 在整理任务中点暂停，再点继续。任务失败后用原 `jobId` 重试，回到详情核对原话。
5. 分别保存“仅本次”和“以后按此条件”，确认前者只改变当前事项，后者更新长期条件并创建上下文重建任务。

### 设置

1. 从首页左上角打开设置。
2. 切换自动整理、推断辅助和字号，结束进程后重开，预期状态仍保留。
3. 关闭自动整理后保存新记录，预期不再创建整理任务。
4. 打开记忆与偏好、标签与规则、本地数据三个入口，确认内容来自当前本地资料库。
5. 在本地数据页点“分享本地数据摘要”，选择接收应用，核对记录数、来源版本数和各事项状态的数量。

### 系统分享

```powershell
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "今天加班，晚饭想省事一点。" -n com.pebblepile.android/.MainActivity
```

点击“确认保存”后，预期详情显示该原话。返回记录库后应看到首页记录和分享记录来自同一份本地资料库。

检查本地数据：

```powershell
adb shell run-as com.pebblepile.android cat shared_prefs/pebblepile_issue4_demo.xml
```

## 模块接手

- #7 按 `CoreRepository` 接 SQLite，用同一组请求核对重开后的记录、任务和回执。[仓储交接](../docs/core-api-contract.md)
- #8 接任务调度，#9 接饮食标签、建议和证据。详情当前展示固定的晚餐样例，联调时按原话区间核对每条依据。[晚餐验收材料](../docs/issue10-testing.md)
- #10、#11 在首测手机和 360dp 小屏复验安装、分享、重开、标签、依据和反馈。[组件交接](../docs/android-components-handoff.md)

人物筛选从原话中的 `@人物` 取值。用户在详情里修改事项状态，后台任务单独记录进度。本地规则为加班晚餐样例提供建议和引文，正式仓储、调度和算法分别由 #7、#8、#9 接入。
