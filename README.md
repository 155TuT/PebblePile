<h1 align="center">
  <img src="assets/app-icon.svg" width="40" height="40" alt="">
  PebblePile · 叠石
</h1>

<p align="center">移动端本地优先的个人记忆与偏好建模服务</p>

<p align="center">
  <a href="#快速开始">快速开始</a> ·
  <a href="docs/development.md">Expo 开发调试</a> ·
  <a href="docs/android-build-release.md">Android 构建与发布</a> ·
  <a href="#项目文档">项目文档</a> ·
  <a href="LICENSE">MIT License</a>
</p>

## 项目简介

叠石是 2601 福州大学软件工程团队作业。用户把日常经历记在手机上，下一次做决定时，可以查看哪些原话支持这次建议，也可以把实际结果和自己的纠正留在原记录里。Android 0.1.0 已完成移动端原型复刻，以晚餐选择为第一个离线可用的场景。

## 快速开始

- **Android 客户端**：在 Android 8.0 及以上设备安装 [0.1.0 稳定版](https://github.com/155TuT/PebblePile/releases/tag/v0.1.0)。安装后在首页记一段原话，或从其他应用分享纯文本，再到记录库打开同一条记录。
- **开发调试**：从 [本地运行](#本地开发) 启动 Expo Android 开发包。在模拟器上操作叠石，并用 Fast Refresh 查看 TypeScript 改动。
- **在线查看**：[Paper 移动端原型](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-5-0)包含 20 个画板，覆盖输入、整理、记录回查、反馈和记忆纠正。
- **本地体验**：下载完整仓库，用浏览器打开 [本地点击导览](assets/prototype/index.html)。导览使用虚构资料模拟页面跳转、输入和反馈，无需启动服务器。

## 主要功能

- **留下原话**　首页对话和记录共用草稿，可以补充文字或语音转写，也可以从系统分享菜单保存纯文本。
- **找回记录**　记录库提供时间线和记录本两种查看方式，可以按文字、标签、人物和事项状态筛选。打开详情后，可查看原话、核对标签、跟进事项和整理进度。
- **询问晚餐建议**　记下两次加班晚餐，再问“今天周四加班，预算二十元，晚饭吃什么？”叠石按随包菜单比较候选，并把建议连回对应经历的原话。
- **补记实际结果**　实际吃了什么，记在当时的建议下。用户也可以把“优先省事”限定到工作日加班，下次提问时按新条件比较，仍能回看上一次的建议。[完整晚餐示例](docs/releases/0.1.0.md)

## 技术栈

Android 客户端使用 Kotlin、Jetpack Compose 和 Navigation Compose，数据存储使用 Room，后台调度使用 WorkManager。

开发入口使用 Expo SDK 55 和 React Native 0.83，通过本地原生视图挂载同一套 Compose 页面。正式 Android 工程使用 Gradle Wrapper 8.9、Android Gradle Plugin 8.7.3 和 compileSdk 35，最低支持 Android 8.0（API 26）。CI 使用 JDK 17 和 Android SDK 35。

## 项目架构

首页和系统分享通过同一个 `CoreApi` 写入记录，`LocalCoreApi` 处理操作重放与修订冲突，Room 把原话版本、任务、建议和回执保存在本机 SQLite。开启自动整理后，WorkManager 执行后台任务。晚餐提问使用随包词表、菜单和排序规则，详情可以按来源版本和原文区间查回依据。[Android 工程说明](docs/android-client.md) · [业务接口](docs/core-api-contract.md)

## 本地开发

首次开发需要准备 Android 工具链，并在 Android Studio 里创建一台虚拟手机。以后在仓库根目录运行 Expo 即可打开叠石。

1. 安装 Node.js 22.x（项目最低要求 20.19.4）、OpenJDK 17 和 Android Studio。项目的环境检查和原生构建脚本使用 Python 3.9 或更新版本。[工具下载与版本](docs/development.md#安装开发工具)
2. 在 Android Studio 的 **SDK Manager** 安装 Android SDK Platform 36 和 35、对应构建工具、NDK、CMake 和模拟器。设置 `JAVA_HOME`、`ANDROID_HOME`，再把 JDK 的 `bin` 和 SDK 的 `platform-tools`、`emulator` 加入 PATH。[SDK 组件清单](docs/development.md#安装-android-sdk) · [环境变量设置](docs/development.md#配置环境变量)
3. 在 **Device Manager** 创建一台 API 36 虚拟手机。打开终端进入仓库根目录，检查本机环境，再按锁文件安装依赖。[创建模拟器](docs/development.md#创建模拟器)

```sh
python scripts/check-android-env.py
npm ci
```

准备完成后，用一条命令生成 Android 宿主、编译并安装开发包、启动 Metro。模拟器打开首页后即可录入第一条记录。

```sh
npx expo run:android
```

保存 TypeScript 文件后，Fast Refresh 会更新正在运行的页面。开发包已安装时，下次只改 TypeScript 可以用 `npx expo start --android --dev-client`。改动 Kotlin、Room 或原生模块后，重新运行 `npx expo run:android`。开发包与正式版各自保存资料。[断点调试与常见问题](docs/development.md#热刷新与调试)

## 目录结构

```text
PebblePile/
├── .github/workflows/       # Android 检查、签名构建与发布
├── android/                 # Expo 在本地生成的 Android 宿主
├── assets/                  # 品牌、设计 token、字体、图标和原型导览
│   └── android/             # 应用打包使用的资源与许可
├── components/              # 共用 Compose 控件、主题与预览
├── config/                  # Expo 宿主生成插件、应用版本
│   └── gradle/              # 独立 Android 工程、模块配置与 Gradle Wrapper
├── docs/                    # 项目文档索引、产品设计、开发与发布指南
├── lib/                     # Kotlin 领域模型、仓储契约、业务、算法与调度
├── modules/pebble-client/   # Expo 与原生客户端之间的桥接
├── scripts/                 # Python 环境检查、构建与资产维护脚本
├── src/
│   ├── App.tsx              # React 应用入口
│   ├── runtime/             # 应用装配、系统分享与任务分派
│   ├── screens/             # 首页、记录库、详情与设置
│   ├── storage/             # Room 数据库、迁移与仓储实现
│   └── work/                # WorkManager 唤醒与重试
├── test/                    # 单元和设备测试、固定样例与发布校验
├── utils/                   # 跨模块共用工具
├── app.json                 # Expo 应用配置
├── CONTRIBUTING.md          # 参与说明
└── AGENTS.md                # AI 协作约定
```

开发包和正式包共用同一份 Kotlin 源码。Expo 可以从 `app.json`、配置插件和原生模块重建整个 `android/`。要单独构建 APK，从仓库根目录运行 `python scripts/gradle.py :app:assembleDebug`。[客户端工程说明](docs/android-client.md) · [构建、签名与发布](docs/android-build-release.md)

## 常用命令

在仓库根目录运行以下命令，维护原型导览与设计图。

| 命令 | 用途 |
| --- | --- |
| `python scripts/build-prototype-preview.py` | 从 Paper 导出源与动作清单生成点击导览 |
| `python scripts/build-prototype-preview.py --check` | 检查原型画板与动作映射 |
| `python scripts/render-information-architecture.py` | 从 Mermaid 思维导图与 VI token 生成信息架构 SVG |
| `python scripts/render-diagrams.py --plantuml-jar "JAR 路径"` | 使用 Java 与官方 PlantUML JAR 渲染系统和数据库图 |
| `python scripts/gradle.py :app:assembleDebug` | 在仓库根目录构建 Android 调试包 |

## 项目文档

### 需求与概要设计

| 文档 | 内容 |
| --- | --- |
| [产品需求](docs/prd.md) | 产品范围、功能与验收 |
| [产品与技术调研](docs/research.md) · [用户研究与同题对照](docs/user-research.md) | 产品假设、固定晚餐材料、访谈与 Beta 回访方法 |
| [信息架构](docs/information-architecture.md) · [Paper 信息架构](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-4-0) | 对话与记录、记录库、设置，以及语义标签与事项的分工 |
| [系统设计说明书](docs/system-design.md) | 模块与 UML、算法、接口和恢复机制 |
| [数据库设计说明书](docs/database-design.md) | 实体关系、关系模型、字段约束、对象映射与数据管理 |
| [第二次团队作业要求](https://edu.cnblogs.com/campus/fzu/202601SofwareEngineering/homework/16773) | 原型、概要设计与课程提交要求 |

系统与数据库说明书参照 GB/T 8567—2006 编排，当前作为设计基线。历史材料见 [早期菜谱方向](docs/recipe/)。

[图形源文件与 SVG](docs/diagrams/)保存在同一目录。转换 Markdown 文档时保留相对目录即可。维护脚本见 [UML 渲染脚本](scripts/render-diagrams.py)和 [信息架构渲染脚本](scripts/render-information-architecture.py)。

### 视觉设计与原型

| 文档或资产 | 内容 |
| --- | --- |
| [移动端原型](docs/prototype-design.md) | 画板、状态、交互流程与需求对应 |
| [VI 与设计系统](docs/vi-design.md) · [Paper VI 基线](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-1-0) | 新拟物控件、Iconoir 图标、配色、排版与验证记录 |
| [视觉选择记录](docs/design-decisions.md) · [Paper 探索页](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-3-0) | 霞鹜文楷、Leftist Mono Serif、Iconoir Regular 与新拟物风格的选择依据 |
| [字体资产](assets/fonts/README.md) | 中文 400／500、英文 400 的角色、加载方式与许可 |
| [项目图标](assets/README.md) · [Iconoir 集合](assets/icons/SOURCES.md) | 三石标志、带河流与植物背景的应用图标和 34 枚功能图标 |
| [设计 token](assets/design-tokens.json) · [CSS 主题](assets/theme.css) · [组件配方](assets/components.css) | 122 项主题值，以及阴影、顶栏、输入区、标签、对话框与提示 |
| [VI 快照](assets/vi/) | 正式排版、基础组件与系统组件样张 |

### Android 实现与测试

| 文档 | 内容 |
| --- | --- |
| [Android 工程说明](docs/android-client.md) · [记录与详情](docs/android-record-flow.md) | 客户端入口、记录保存与回查、条件纠正、反馈和重开 |
| [Compose 共用组件](docs/android-components-compose.md) | 控件、字体、状态反馈与小屏验证 |
| [业务接口](docs/core-api-contract.md) · [本地存储](docs/android-storage.md) | 请求、回执、Room 事务、版本与迁移 |
| [调度状态机](docs/scheduler-core.md) · [WorkManager 接入](docs/scheduler-android.md) | 任务领取、阶段检查点、暂停与恢复 |
| [饮食算法](docs/algorithm/dinner-ranking.md) | 经历提取、偏好条件、菜单排序与回放基线 |
| [测试说明](docs/testing/README.md) | 固定样例、契约、设备流程与故障恢复 |
| [构建、签名与发布](docs/android-build-release.md) | 调试构建、候选包验证和稳定版来源 |

## 参与协作

协作前阅读 [参与说明](CONTRIBUTING.md) 与 [AI 协作约定](AGENTS.md)。修改设计时，同步受影响的说明、Paper 画板与本地资产。

## 许可证

仓库采用 [MIT License](LICENSE)。字体与图标的来源和许可见对应资产说明。
