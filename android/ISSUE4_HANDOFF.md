# Issue #4 交接说明

## 当前可交付

- 带 Gradle Wrapper 8.9 的 Kotlin + Compose Android 工程。
- 首页输入、对话／记录切换、草稿保持和保存回执。
- 独立记录库、标签筛选、时间分组和详情导航。
- `detail/{recordId}` 详情路由。
- Android 系统分享和首页共用 `CoreApi` 与本地资料库。
- 已验证进程结束重开后记录仍可找回。

## 依赖约定

- 包名：`com.pebblepile.android`
- `minSdk=26`，`compileSdk=35`，`targetSdk=35`
- 首页路由：`home`
- 记录库路由：`library`
- 详情路由：`detail/{recordId}`
- 首页保存、系统分享都调用 `CoreApi.recordExperience`
- 首页、记录库、详情和分享读取同一 `CoreApi.recentRecords/getRecord`

## 接入团队模块

1. 用 Issue #6 的正式 CoreAPI 适配器替换 `DemoCoreApi`。
2. 用 Issue #7 的 Room/SQLite 仓储实现正式持久化。
3. 用 Issue #3 的输入、标签、依据和状态组件替换当前 Material 3 占位。
4. 把首页保存的 `operationId` 交给正式核心生成并持久化，避免进程重启后的重复写入。
5. 任务进度、证据版本和偏好重算接入详情后，再把 `DemoCoreApi` 移出正式构建。

## 构建入口

```powershell
$env:JAVA_HOME = "D:\Android\OpenJDK21"
$env:ANDROID_HOME = "D:\Android\Sdk"
.\gradlew.bat clean assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`
