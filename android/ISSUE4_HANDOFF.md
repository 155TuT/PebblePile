# Issue #4 交接说明

## 当前可交付

- 带 Gradle Wrapper 8.9 的 Kotlin + Compose Android 工程。
- 首页输入、对话／记录切换、草稿保持和保存回执。
- 独立记录库、标签筛选、时间分组和详情导航。
- `detail/{recordId}` 详情路由。
- Android 系统分享和首页共用 `CoreApi` 与本地资料库。
- 已验证进程结束重开后记录仍可找回。

## 界面方案

- 前端框架选定 Kotlin + Jetpack Compose。
- 页面层使用 Compose Navigation；组件层按团队
  [`docs/android-components-handoff.md`](../docs/android-components-handoff.md)
  约定的输入、标签、依据和状态契约接入。
- 当前使用 Material 3 基础组件作为可运行占位，Issue #3 交付后再替换视觉组件。

## 依赖约定

- 包名：`com.pebblepile.android`
- `minSdk=26`，`compileSdk=35`，`targetSdk=35`
- 首页路由：`home`
- 记录库路由：`library`
- 详情路由：`detail/{recordId}`
- 首页保存、系统分享都调用 `CoreApi.recordExperience`
- 记录库用 `CoreApi.recentRecords` 取列表，详情用 `getRecordDetail(recordId)` 读记录和长期条件。

## 接入团队模块

首页和分享已通过 `PebblePileApplication.coreApi` 接入 #6 的 `LocalCoreApi`。入口为每次新操作生成 `operationId`，重试时沿用原标识和参数。核心在同一次写入中保存记录、来源、适用的待办和回执。[回执样例](../docs/core-api-receipt-examples.json)

1. #7 实现 `CoreRepository` 后接入 SQLite。#4、#5 分别从首页和分享保存一条原话，重开后逐条核对 `recordId`、原话和首次回执。
2. #3 交付共用组件后，#4 替换首页输入框和记录库筛选控件，#5 接详情的依据和反馈状态。
3. #8、#9 交出任务进度和带来源版本的饮食建议后，#5 接到详情，#10 用固定的晚餐材料回放。[业务接口与仓储契约](../docs/core-api-contract.md)

## 构建入口

配置 JDK 和 Android SDK 后，在仓库根目录运行。环境版本见[构建说明](../docs/android-build-release.md)。

```powershell
cd android
.\gradlew.bat clean assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`
