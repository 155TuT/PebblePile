<h1 align="center">
  <img src="assets/app-icon.svg" width="40" height="40" alt="">
  PebblePile · 叠石
</h1>

<p align="center">移动端本地优先的个人记忆与偏好建模服务</p>

<p align="center">
  <a href="#快速开始">快速开始</a> ·
  <a href="docs/android-build-release.md">Android 构建与发布</a> ·
  <a href="#项目文档">项目文档</a> ·
  <a href="LICENSE">MIT License</a>
</p>

## 项目简介

叠石是 2601 福州大学软件工程团队作业。用户把日常经历记在手机上，下一次做决定时，可以查看哪些原话支持这次建议，也可以把实际结果和自己的纠正留在原记录里。Android 0.1.0 已完成移动端原型复刻，以晚餐选择为第一个离线可用的场景。

## 快速开始

- **Android 客户端**：在 Android 8.0 及以上设备安装 [0.1.0 稳定版](https://github.com/155TuT/PebblePile/releases/tag/v0.1.0)，或按[本地运行](#本地运行)构建调试包。安装后在首页记一段原话，或从其他应用分享纯文本，再到记录库打开同一条记录。
- **在线查看**：[Paper 移动端原型](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-5-0)包含 20 个画板，覆盖输入、整理、记录回查、反馈和记忆纠正。
- **本地体验**：下载完整仓库，用浏览器打开 [本地点击导览](assets/prototype/index.html)。导览使用虚构资料模拟页面跳转、输入和反馈，无需启动服务器。

## 主要功能

- **留下原话**　首页对话和记录共用草稿，可以补充文字或语音转写，也可以从系统分享菜单保存纯文本。
- **找回记录**　记录库提供时间线和记录本两种查看方式，可以按文字、标签、人物和事项状态筛选。打开详情后，可查看原话、核对标签、跟进事项和整理进度。
- **询问晚餐建议**　记下两次加班晚餐，再问“今天周四加班，预算二十元，晚饭吃什么？”叠石按随包菜单比较候选，并把建议连回对应经历的原话。
- **补记实际结果**　实际吃了什么，记在当时的建议下。用户也可以把“优先省事”限定到工作日加班，下次提问时按新条件比较，仍能回看上一次的建议。[完整晚餐示例](android/release-notes.md)

## 技术栈

Android 客户端使用 Kotlin、Jetpack Compose 和 Navigation Compose，数据存储使用 Room，后台调度使用 WorkManager。

构建链为 Gradle Wrapper 8.9、Android Gradle Plugin 8.7.3、JDK 17、compileSdk 35，最低支持 Android 8.0（API 26）。CI 使用 JDK 17 和 Android SDK 35。

## 项目架构

首页和系统分享通过同一个 `CoreApi` 写入记录，`LocalCoreApi` 处理操作重放与修订冲突，Room 把原话版本、任务、建议和回执保存在本机 SQLite。开启自动整理后，WorkManager 执行后台任务。晚餐提问使用随包词表、菜单和排序规则，详情可以按来源版本和原文区间查回依据。[Android 工程说明](android/README.md) · [业务接口](docs/core-api-contract.md)

## 目录结构

```text
PebblePile/
├── .github/workflows/       # Android 检查、签名候选包与 Release 晋级
├── assets/                  # 品牌图标、设计 token、主题与组件样式
│   ├── fonts/               # 字体来源、加载方式与许可
│   ├── icons/               # Iconoir SVG 与语义映射
│   ├── prototype/           # 点击导览、Paper 导出源与动作清单
│   └── vi/                  # 正式设计快照
├── android/                 # Kotlin + Jetpack Compose Android 工程
├── docs/                    # 产品需求、调研与设计说明
│   ├── diagrams/            # PlantUML／Mermaid 源文件与 SVG
│   └── recipe/              # 早期菜谱方向的研究和需求
├── scripts/                 # 导览生成、图形渲染与字体安装
├── CONTRIBUTING.md          # 参与说明
└── AGENTS.md                # AI 协作约定
```

## 本地运行

构建调试 APK：

```powershell
cd android
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

产物为 `android/app/build/outputs/apk/debug/app-debug.apk`。运行前设置 JDK 17 或 21 的 `JAVA_HOME` 与 Android SDK 35 的 `ANDROID_HOME`，详见 [Android 构建、签名与发布](docs/android-build-release.md)。

## 常用命令

在仓库根目录运行以下命令，维护原型导览与设计图。

| 命令 | 用途 |
| --- | --- |
| `python scripts/build-prototype-preview.py` | 从 Paper 导出源与动作清单生成点击导览 |
| `python scripts/build-prototype-preview.py --check` | 检查原型画板与动作映射 |
| `python scripts/render-information-architecture.py` | 从 Mermaid 思维导图与 VI token 生成信息架构 SVG |
| `./scripts/render-diagrams.ps1 -PlantUmlJar "JAR 路径"` | 使用 Java 与官方 PlantUML JAR 渲染系统和数据库图 |
| `.\android\gradlew.bat -p android :app:assembleDebug` | 在仓库根目录构建 Android 调试包 |

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

[图形源文件与 SVG](docs/diagrams/)保存在同一目录。转换 Markdown 文档时保留相对目录即可。维护脚本见 [UML 渲染脚本](scripts/render-diagrams.ps1)和 [信息架构渲染脚本](scripts/render-information-architecture.py)。

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
| [Android 工程说明](android/README.md) · [记录与详情](docs/android-record-flow.md) | 客户端入口、记录保存与回查、条件纠正、反馈和重开 |
| [Compose 共用组件](docs/android-components-compose.md) | 控件、字体、状态反馈与小屏验证 |
| [业务接口](docs/core-api-contract.md) · [本地存储](docs/android-storage.md) | 请求、回执、Room 事务、版本与迁移 |
| [调度状态机](android/scheduler-core/README.md) · [WorkManager 接入](android/scheduler-android/README.md) | 任务领取、阶段检查点、暂停与恢复 |
| [饮食算法](docs/algorithm/dinner-ranking.md) | 经历提取、偏好条件、菜单排序与回放基线 |
| [测试说明](docs/testing/README.md) | 固定样例、契约、设备流程与故障恢复 |
| [构建、签名与发布](docs/android-build-release.md) | 调试构建、候选包验证和稳定版来源 |

## 参与协作

协作前阅读 [参与说明](CONTRIBUTING.md) 与 [AI 协作约定](AGENTS.md)。修改设计时，同步受影响的说明、Paper 画板与本地资产。

## 许可证

仓库采用 [MIT License](LICENSE)。字体与图标的来源和许可见对应资产说明。
