# PebblePile · 叠石

<img src="assets/app-icon.svg" width="144" alt="叠石：河流与植物背景中的三块自然河石">

[2601 福州大学软件工程团队作业] 开源、移动端本地优先的个人记忆与偏好建模服务。

从对话与记录开始，由 Agent 结合本地算法整理语义标签、事件和证据，形成可检索的本地事件库。需要跟进的记录承载目标与结果，习惯和条件偏好随实际反馈更新。

## 项目文档

| 文档 | 内容 |
| --- | --- |
| [产品需求](docs/prd.md) | 产品范围、功能与验收 |
| [产品与技术调研](docs/research.md) | 用户假设、个人模型、移动运行和复用依据 |
| [系统设计说明书](docs/system-design.md) | 架构、五类 UML、算法、接口与恢复机制 |
| [数据库设计说明书](docs/database-design.md) | 实体关系、关系模型、字段约束、对象映射与数据管理 |
| [VI 与设计系统](docs/vi-design.md) | 正式新拟物控件、Iconoir 图标、配色、排版基线与验证记录 |
| [移动端原型](docs/prototype-design.md) | Paper 可编辑画板、本地点击导览与主要流程 |
| [信息架构](docs/information-architecture.md) | 对话与记录首页、记录库、设置，语义标签与事项的能力分工 |
| [视觉选择记录](docs/design-decisions.md) | 霞鹜文楷 + Leftist Mono Serif、Iconoir Regular、全局新拟物及选择依据 |
| [字体资产](assets/fonts/README.md) | 中文 400/500、英文 400 的角色、加载方式与来源许可 |
| [第二次团队作业要求](task-requirement.md) | 原型、概要设计和课程提交要求 |
| [项目图标](assets/README.md) · [Iconoir 集合](assets/icons/SOURCES.md) | 三石品牌标志、带河流植物背景的应用图标与 34 枚功能图标 |
| [设计 token](assets/design-tokens.json) · [CSS 主题](assets/theme.css) · [组件配方](assets/components.css) | 122 项主题值、新拟物阴影、顶栏、输入区、标签、对话框与提示 |

设计图在 [docs/diagrams](docs/diagrams/) 中保留 PlantUML／Mermaid 源文件和 SVG。Markdown 直接引用 SVG；转换文档时保留相对目录即可。[UML 渲染脚本](scripts/render-diagrams.ps1)重新生成系统与数据库图；[信息架构渲染脚本](scripts/render-information-architecture.py)读取 Mermaid 思维导图源文件和 VI token，生成分组布局。

系统与数据库说明书参照 GB/T 8567—2006 编排，当前为设计基线。历史菜谱方向材料保存在 `docs/recipe/`。

[Paper VI 基线](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-1-0)采用霞鹜文楷 + Leftist Mono Serif、Iconoir Regular 与全局新拟物，包含标志、配色、排版和组件。当前快照见 [assets/vi](assets/vi/)，历史对照保留在 [Paper 探索页](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-3-0)。

[Paper 信息架构](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-4-0)区分日常入口、内容模型与后台处理能力。首页直接对话或记录，记录库通过时间线、记录本和语义标签组织同一批内容；记忆与偏好管理位于设置。

[Paper 移动端原型](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-5-0)覆盖输入、整理、时间线与标签、实际反馈及记忆纠正。[本地点击导览](assets/prototype/index.html)可在浏览器直接打开，交互数据为虚构演示。

协作前阅读 [参与说明](CONTRIBUTING.md)与 [AI 协作约定](AGENTS.md)。
