# PebblePile Android 客户端

0.1.0 将移动端原型中的首页、记录库、详情和设置做成了可安装的 Android 应用。最低支持 Android 8.0（API 26）。初次体验可以先记一条加班晚餐的原话，从记录库打开它，再按发布说明中的例子补充第二次经历、提问并查看依据。[下载 0.1.0 稳定版](https://github.com/155TuT/PebblePile/releases/tag/v0.1.0) · [晚餐示例](releases/0.1.0.md)

## 应用怎样处理一条记录

1. 首页输入、语音转写和补充材料共用一份文字草稿。从其他应用分享纯文本时，叠石先显示确认框。保存后两个入口都按回执里的 `recordId` 打开详情。[首页与系统分享](android-record-flow.md)
2. 记录、原话版本、事项、任务和操作回执保存在本机 SQLite。记录库可以在时间线和记录本之间切换，按文字、标签、人物和事项状态组合筛选。[本地存储](android-storage.md)
3. 开启自动整理后，新记录会创建后台任务。对于晚餐提问，本地规则从经历中提取条件和证据，按预算、时间和偏好比较随包菜单。详情随结果更新，每条依据可以回到对应版本的原话。[后台调度](scheduler-android.md) · [饮食算法](algorithm/dinner-ranking.md)
4. 用户在详情里补记实际选择和感受，反馈与当时的建议通过 `decisionId` 关联。纠正条件时可以选“仅本次”或“以后按此条件”，已有建议保留当时的条件与依据。[业务接口](core-api-contract.md)

设置中的“自动整理”控制新记录是否创建任务，“文字大小”调整应用字号，“推断辅助”保存开关选项。0.1.0 的晚餐排序使用本地规则。记忆与偏好页可回查条件，标签与规则页展示有效标签和核对状态，本地数据页可分享记录数、来源版本数和事项状态分布。[设置实现](../src/screens/SettingsScreen.kt)

## 源码怎样分工

两套入口共用同一份 Kotlin 客户端。Expo 从根目录的 TypeScript 入口挂载原生视图，用于热刷新和设备联调。独立 Gradle 工程从 `MainActivity` 启动 Compose，用于构建、设备验收和发布。[Expo 应用入口](../src/App.tsx) · [原生视图](../modules/pebble-client/) · [Android 入口](../src/runtime/MainActivity.kt)

`src/` 按职责组织。应用装配和系统入口放在 `runtime/`，接入 Room 和 WorkManager 的代码分别放在 `storage/` 和 `work/`。两个构建入口都从这些目录读取源码和[随包资源](../assets/android/)。独立构建由 [config/gradle/](../config/gradle/) 管理，Expo 构建所用的根 `android/` 由 Prebuild 生成。

| 要改的内容 | 位置 |
| --- | --- |
| 首页、记录库、详情、设置 | [src/screens/](../src/screens/) |
| 共用控件、主题、图标和预览 | [components/compose/](../components/compose/) · [components/preview/](../components/preview/) |
| 领域模型、业务接口、修订校验、仓储契约 | [lib/model/](../lib/model/) · [lib/service/](../lib/service/) · [lib/repository/](../lib/repository/) |
| 晚餐提取、偏好更新和排序 | [lib/algorithm/](../lib/algorithm/) · [算法与回放](algorithm/dinner-ranking.md) |
| 调度状态机、租约和检查点 | [lib/scheduler/](../lib/scheduler/) · [调度规则](scheduler-core.md) |
| 应用装配、Room 数据库存取、系统唤醒 | [src/runtime/](../src/runtime/) · [src/storage/](../src/storage/) · [src/work/](../src/work/) |
| 共用工具、构建参数和版本 | [utils/](../utils/) · [config/](../config/) |

`PebblePileApplication` 创建共享的 `CoreApi`。Android 入口 `DemoCoreApi` 将业务操作交给 `LocalCoreApi`，并连接 Room 仓储与后台调度。界面设置保存在 SharedPreferences 的 `pebblepile_settings` 中。Kotlin 的业务规则和调度状态机独立于 Android，当前构建与设备验证面向 Android。[应用装配](../src/runtime/PebblePileApplication.kt) · [业务接入](../src/runtime/DemoCoreApi.kt) · [Worker 恢复策略](scheduler-android.md)

本地联调见[开发指南](development.md)，单元测试和设备用例见[测试说明](../test/README.md)，签名与版本管理见[构建与发布](android-build-release.md)。
