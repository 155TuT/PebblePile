# Issue #4 交接说明

## 当前可交付

- 带 Gradle Wrapper 8.9 的 Kotlin + Compose Android 工程。
- 首页、记录库和详情已全部接入 Issue #3 的共享 VI 组件。
- 首页输入、添加材料、系统语音入口或明确回退、对话／记录切换、草稿保持、保存回执和整理状态。
- 独立记录库、时间线／记录本切换、搜索、标签、人物、事项状态组合筛选和详情导航。
- `detail/{recordId}` 详情路由。
- Android 系统分享和首页共用 `CoreApi` 与本地资料库。
- 已验证进程结束重开后记录仍可找回。

## 界面方案

- 前端框架选定 Kotlin + Jetpack Compose。
- 页面层使用 Compose Navigation；入口为 `home`、`library` 和 `detail/{recordId}`。
- 组件层统一使用 `PebbleComponentTheme`、`PebbleComposer`、`PebbleTag`、`PebbleCard`、`PebbleEvidenceCard`、`PebbleFeedback`、`PebbleDialog` 等 Issue #3 共享组件。
- 页面只保留 Compose 基础布局与 Material 3 字体排版能力，不再用 Material 3 默认控件搭建主界面。

## 依赖约定

- 包名：`com.pebblepile.android`
- `minSdk=26`，`compileSdk=35`，`targetSdk=35`
- 首页路由：`home`
- 记录库路由：`library`
- 详情路由：`detail/{recordId}`
- 设置路由：`settings`、`settings/memory`、`settings/rules`、`settings/data`
- 首页保存、系统分享都调用 `CoreApi.recordExperience`
- 记录库用 `CoreApi.recentRecords` 取列表，详情用 `getRecordDetail(recordId)` 读记录和长期条件。

## 接入团队模块

首页和分享已通过 `PebblePileApplication.coreApi` 接入 #6 的 `LocalCoreApi`。入口为每次新操作生成 `operationId`，重试时沿用原标识和参数。核心在同一次写入中保存记录、来源、适用的待办和回执。[回执样例](../docs/core-api-receipt-examples.json)

1. #7 实现 `CoreRepository` 后接入 SQLite。#4、#5 分别从首页和分享保存一条原话，重开后逐条核对 `recordId`、原话、人物、事项状态和首次回执。
2. #3 共享组件已接入首页、记录库和详情，后续组件变更由 #4、#5 同步回归。
3. #8、#9 交出任务进度和带来源版本的饮食建议后，#5 用真实调度替换当前排队演示，#10 用固定的晚餐材料回放。[业务接口与仓储契约](../docs/core-api-contract.md)

## 构建入口

配置 JDK 和 Android SDK 后，在仓库根目录运行。环境版本见[构建说明](../docs/android-build-release.md)。

```powershell
cd android
.\gradlew.bat clean assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`
