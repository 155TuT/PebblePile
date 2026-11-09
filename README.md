<h1 align="center">
  <img src="assets/app-icon.svg" width="40" height="40" alt="">
  PebblePile · 叠石
</h1>

<p align="center">移动端本地优先的个人记忆与偏好建模服务</p>

<p align="center">
  <a href="#快速开始">原型预览</a> ·
  <a href="#项目文档">项目文档</a> ·
  <a href="LICENSE">MIT License</a>
</p>

## 项目简介

本项目是 2601 福州大学软件工程团队作业，从日常对话与记录出发，由 Agent 结合本地算法整理事件、语义标签与证据，逐步形成习惯和条件偏好。后续事项可以复用这些认识，实际结果和用户纠正会用于更新个人模型。

## 快速开始

- **在线查看**：[Paper 移动端原型](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-5-0)包含 20 个画板，覆盖输入、整理、记录回查、反馈和记忆纠正。
- **本地体验**：下载完整仓库，用浏览器打开 [本地点击导览](assets/prototype/index.html)。导览使用虚构资料模拟页面跳转、输入和反馈，无需启动服务器。

## 主要功能

以下是当前的产品设计，主要流程可在原型中体验。

- **对话与记录**：在首页切换对话或记录方式，补充材料并查看当前记录的整理进度。
- **记录库**：通过时间线、记录本和语义标签找回同一批内容，按人物、时间和状态缩小范围。
- **事项跟进**：在原记录中保留目标、建议与进展，并补充实际结果。
- **标签与记忆纠正**：回查语义标签的来源与算法版本，确认、修改或排除标签。在设置中查看习惯和条件偏好的依据，调整其适用范围。

## 技术栈

## 项目架构

## 目录结构

```text
PebblePile/
├── assets/                  # 品牌图标、设计 token、主题与组件样式
│   ├── fonts/               # 字体来源、加载方式与许可
│   ├── icons/               # Iconoir SVG 与语义映射
│   ├── prototype/           # 点击导览、Paper 导出源与动作清单
│   └── vi/                  # 正式设计快照
├── docs/                    # 产品需求、调研与设计说明
│   ├── diagrams/            # PlantUML／Mermaid 源文件与 SVG
│   └── recipe/              # 早期菜谱方向的研究和需求
├── scripts/                 # 导览生成、图形渲染与字体安装
├── task-requirement.md      # 第二次团队作业要求
├── CONTRIBUTING.md          # 参与说明
└── AGENTS.md                # AI 协作约定
```

## 本地运行

## 常用命令

在仓库根目录运行以下命令，维护原型导览与设计图。

| 命令 | 用途 |
| --- | --- |
| `python scripts/build-prototype-preview.py` | 从 Paper 导出源与动作清单生成点击导览 |
| `python scripts/build-prototype-preview.py --check` | 检查原型画板与动作映射 |
| `python scripts/render-information-architecture.py` | 从 Mermaid 思维导图与 VI token 生成信息架构 SVG |
| `./scripts/render-diagrams.ps1 -PlantUmlJar "JAR 路径"` | 使用 Java 与官方 PlantUML JAR 渲染系统和数据库图 |

## 项目文档

### 需求与概要设计

| 文档 | 内容 |
| --- | --- |
| [产品需求](docs/prd.md) | 产品范围、功能与验收 |
| [产品与技术调研](docs/research.md) | 用户假设、个人模型、移动运行与跨工具复用 |
| [信息架构](docs/information-architecture.md) · [Paper 信息架构](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-4-0) | 对话与记录、记录库、设置，以及语义标签与事项的分工 |
| [系统设计说明书](docs/system-design.md) | 模块与 UML、算法、接口和恢复机制 |
| [数据库设计说明书](docs/database-design.md) | 实体关系、关系模型、字段约束、对象映射与数据管理 |
| [第二次团队作业要求](task-requirement.md) | 原型、概要设计与课程提交要求 |

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

## 部署

## 参与协作

协作前阅读 [参与说明](CONTRIBUTING.md) 与 [AI 协作约定](AGENTS.md)。修改设计时，同步受影响的说明、Paper 画板与本地资产。

## 许可证

仓库采用 [MIT License](LICENSE)。字体与图标的来源和许可见对应资产说明。
