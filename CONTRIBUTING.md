# 参与协作

PebblePile · 叠石面向移动端个人记忆与偏好建模。仓库当前交付需求、系统与数据库设计、VI、信息架构与移动端原型。应用工程按后续任务推进。

开始前阅读 [README](README.md)、[PRD](docs/prd.md)、[VI 规范](docs/vi-design.md)、[信息架构](docs/information-architecture.md)和 [AI 协作约定](AGENTS.md)。涉及业务与数据时，再阅读[系统设计](docs/system-design.md)和[数据库设计](docs/database-design.md)。

## 工作流程

1. 查看当前分支、`git status --short` 与相关差异，确认负责范围和已有改动。
2. 按功能、文档或设计板拆分任务，约定文件与 Paper 节点的负责人。并行工作避免编辑同一对象。
3. 在当前需求和已定 VI 内完成修改，同步受影响的设计稿、资产与说明。
4. 按改动影响验证，清理临时输出，再提交可审阅的差异。
5. PR 写清问题、结果、关键文件和实际验证；组员理解并维护自己负责的内容，保留真实贡献记录。

新分支使用能说明范围的名称；自动化代理默认使用 `codex/<topic>`。已有分支和未提交内容按当前协作安排处理。

## 文件职责

| 位置 | 内容与维护方式 |
| --- | --- |
| `docs/` | 需求、设计和规范正文；变更入口或数据语义时同步相关文档 |
| `docs/diagrams/` | PlantUML／Mermaid 源文件及正式 SVG；修改源文件后更新对应图形 |
| `assets/design-tokens.json` | 语义 token、扩展样式、组件组合与验证状态 |
| `assets/theme.css`、`assets/components.css` | 与 JSON 对应的主题变量和组件配方 |
| `assets/icon.svg`、`assets/app-icon.svg` | 三石单色标志与带河流背景的应用图标 |
| `assets/icons/` | 正式 Iconoir 集合、语义映射、来源和许可证 |
| `assets/fonts/` | 字体来源、版本、字重和许可说明；字体文件按各自授权管理 |
| `assets/prototype/` | 当前点击导览、流程清单与 Paper 导出的精确源；用生成脚本更新，保留最少复现资产 |
| `assets/vi/` | 文档实际引用的正式设计快照；同一用途保留当前版本 |
| `scripts/` | 可重复使用的生成与维护脚本；信息架构通过 `python scripts/render-information-architecture.py` 从 `.mmd` 与 VI token 生成 |
| `.build/`、`.local/` | 已忽略的验证输出、缓存及本地字体等环境资源 |

文件名使用有含义的 `kebab-case`。文档采用 UTF-8 与仓库内相对链接。历史探索保留简短决策和 Paper 链接；重复预览、裁切图、浏览器配置、下载包与临时日志留在忽略目录，验收后清理。

## 必须遵守的 VI

正式方向是**浅砂与苔绿的新拟物界面、霞鹜文楷 + Leftist Mono Serif、Iconoir Regular**。最新任务明确改变方向时，同步更新规范与资产；日常实现从现有规则复用。

- 中文使用文楷的真实 400／500 字重；Leftist 使用真实 400。中英分别绑定字体角色，关闭合成粗体与斜体，核对混排基线、数字和长句换行。
- 颜色、灰阶、间距、圆角、阴影、描边和动效读取 token。新增语义先判断能否复用已有值，并在 JSON、CSS 和 Paper 中保持一致。
- 功能图标使用 `assets/icons/` 中的 Iconoir，保留原生比例、1.5 描边和 `currentColor`。标准视图为 24 × 24，触点至少 48 × 48；新增图标同时更新映射、来源与许可。
- 三石标志承担品牌识别；应用图标的河流、植物与背景保留在品牌资产内。界面单色标志继续只显示三块石头。
- 新拟物通过同色表面和凸凹阴影表达状态。必要边界、焦点环、标签与错误文字承担识别，阴影用于补充触感。
- 按钮、输入、选择控件、菜单、顶栏、对话输入区、语义标签、Modal、底部面板和反馈提示复用正式配方。首页在顶栏下方选择对话或记录，输入区只承载输入与提交；顶栏用独立图标按钮提供记录库与设置入口；记忆管理位于设置，安全区在内容高度之外计算。

语义标签由 Agent 与本地算法整理，标签筛选作用于同一批记录。协作时同时维护生成依据、版本、核对状态和人工纠正规则；标签表示内容分类，事实、偏好和授权保留各自的数据约束。事项的目标、进展与结果留在原记录中，后台作业通过相关记录和设置查看。

完整数值与状态见 [VI 规范](docs/vi-design.md)。Leftist 的设计选定与字体文件再分发分别管理：当前正式仓库保留来源说明，嵌入或打包前按[字体资产说明](assets/fonts/README.md)确认授权。

Leftist 原文件的内部族名为 `Leftist Mono Sans`，PostScript 名为 `LeftistMonoSerif`。这是同一字体的名称差异。Paper 英文节点沿用[字体说明](assets/fonts/README.md)中的直接绑定方式，避免该工具对字体 token 兼容链的回退；其余属性继续绑定 token。

## Paper 与本地同步

工作文件为 [PebblePile](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q)。[正式 VI 页](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-1-0)维护当前视觉规则，[信息架构页](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-4-0)维护入口层级。[原型页](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-5-0)维护移动端页面与流转。探索页保存选择过程。原型修改同时同步导出源、动作映射和导览，运行规则见[原型说明](docs/prototype-design.md)。

1. 修改前读取当前文件、页面、节点与 token。若画布已有他人变更，先确认差异再编辑相关部分。
2. 字体先检查实际可用的 family、字重与文件版本。缺失、别名或回退情况如实记录；轮廓字样用于固定展示时保留原文和来源。
3. 优先调整现有节点，按组件逐步修改。长文本通过合理换行和容器增高处理，保留相关内容的顺序与入口。
4. 用 token 和精确样式完成设计；通过节点样式回读同步 JSON 与 CSS。截图用于检查观感，数值以样式与源资产为依据。
5. 检查受影响组件的间距、对齐、字号层级、对比、状态和裁切。导出当前文档需要的快照，更新引用与验证记录。
6. 同一变更内完成 Paper、JSON、CSS、SVG 和文档的相关同步；结束后释放正在编辑的节点标记。同步受阻时说明具体未完成项与已保存位置。

SVG 保留 `viewBox`、必要的路径与层次。功能图标保留上游文件与许可；导出文件移除工具缓存、本机绝对路径和无关生成元数据。截图使用稳定文件名，新增一张时明确它承担的文档用途。

## 验证与交付

| 变更 | 必要检查 |
| --- | --- |
| 文档 | 链接、术语、图文与需求映射；`git diff --check` |
| UML／数据库图 | 源文件和 SVG 同步，关系与正文一致，文字清楚且无裁切 |
| token／CSS | JSON 可解析、变量有定义、Paper／JSON／CSS 值等价；变化的颜色配对满足规范 |
| 字体／图标 | 实际字形、原生字重、目标尺寸、混排与来源许可；回退情况明确 |
| 控件／布局 | 默认、选中、按下、焦点、禁用、加载和错误等受影响状态；小屏、长内容与系统安全区 |

修改 PlantUML 后，可使用已有脚本：

```powershell
./scripts/render-diagrams.ps1 -PlantUmlJar <官方 PlantUML JAR 路径>
git diff --check
```

脚本需要 Java；也可将 JAR 放在其默认的 `.build/tools/plantuml.jar`。应用代码引入后，再按真实工程配置补充构建和测试命令。静态样张验证、运行时交互、模拟接口与真机结果分别记录。

PR 说明以具体结果为主，列出执行过的检查和仍待验证的部分。设计变更附对应 Paper 页或必要的前后对照；新增依赖说明用途并提交相应锁文件。示例使用虚构资料，密钥、真实生活材料、个人路径和环境缓存保持在正式交付之外。

协作结构参考 [AccountingHelper 的 CONTRIBUTING.md](https://github.com/155TuT/AccountingHelper/blob/main/CONTRIBUTING.md)，按本仓库的设计阶段、VI 和资产流程编写。
