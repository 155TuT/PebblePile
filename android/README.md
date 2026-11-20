# PebblePile Android · Issue #4

Android 工程、首页输入、记录库和详情路由的可构建基线。

## 已覆盖

- Kotlin + Jetpack Compose 单 `app` 工程和 Gradle Wrapper 8.9。
- 首页直接输入，对话／记录模式切换时保留同一草稿。
- 首页提交后显示带 `recordId` 的保存回执并进入详情。
- 记录库为独立页面，可回到首页、按标签筛选并按时间分组。
- 详情路由为 `detail/{recordId}`，记录库和首页都通过同一 ID 打开。
- Android `ACTION_SEND` 纯文本分享进入同一资料库，保存后打开同一详情。
- 本地演示仓储使用同步提交的 SharedPreferences，支持离线保存和进程重开。

## 构建

要求 JDK 17 或 21，以及 Android SDK Platform 35。

```powershell
$env:JAVA_HOME = "D:\Android\OpenJDK21"
$env:ANDROID_HOME = "D:\Android\Sdk"
.\gradlew.bat clean assembleDebug
```

APK 输出：

```text
app\build\outputs\apk\debug\app-debug.apk
```

工程路径允许包含中文，配置中已开启 `android.overridePathCheck=true`。

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
4. 点击“发送并保存”或“保存记录”。
5. 预期显示带 `recordId` 的保存回执并进入详情。

### 记录库与重开

1. 返回首页，点击顶部“记录库”或“打开记录库”。
2. 预期按日期看到刚才的记录，并可通过标签筛选。
3. 结束并重开：

   ```powershell
   adb shell am force-stop com.pebblepile.android
   adb shell am start -n com.pebblepile.android/.MainActivity
   ```

4. 再次进入记录库，预期原话仍存在。
5. 点击记录，预期进入同一 `recordId` 对应的详情。

### 系统分享

```powershell
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "今天加班，晚饭想省事一点。" -n com.pebblepile.android/.MainActivity
```

点击“确认保存”后，预期详情显示该原话。返回记录库后应看到首页记录和分享记录来自同一份本地资料库。

检查本地数据：

```powershell
adb shell run-as com.pebblepile.android cat shared_prefs/pebblepile_issue4_demo.xml
```

## 联调边界

`DemoCoreApi` 是可运行的临时适配器。正式联调时必须替换为 Issue #6 的 CoreAPI 和 Issue #7 的 Room/SQLite 仓储。演示标签和建议仍是固定样例；正式回执、幂等、任务、证据版本和偏好重算由正式核心负责。

Issue #3 的正式视觉组件尚未合入，当前页面使用可运行的 Material 3 占位组件。
