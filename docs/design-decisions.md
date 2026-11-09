# 叠石 · 视觉选择记录

2026-11-09，确定 **霞鹜文楷 + Leftist Mono Serif、Iconoir Regular、全局浅色新拟物**。这些选择共同服务于叠石的个人记忆主题：文字像日常手记，图标保持轻巧，控件延续河石的柔和触感。

| 对象 | 已选方案 | 应用规则与依据 |
| --- | --- | --- |
| 中文 | LXGW WenKai／霞鹜文楷 | 正文与说明用 400；标题、品牌和操作文字用 500。保留手写笔势，兼顾记忆短文与界面标签。 |
| 英文与数字 | Leftist Mono Serif | 使用真实 400 字重；英文片段单独绑定字体角色，与文楷形成连贯的手记气质。 |
| 功能图标 | Iconoir Regular | 统一 24 × 24 视图、1.5 描边、`currentColor` 与至少 48 × 48 触点。轻描边配合界面留白，选中状态由文字、前景色和容器表达。 |
| 全局控件 | 浅色新拟物 | 浅砂与苔绿配合同色表面、凸起与内凹阴影。按钮、输入、选择控件、导航与浮层沿用同一套规则，边界、焦点环和状态文字承担操作识别。 |
| 品牌标志 | 三块自然河石 | 保留俯侧视、错位与重叠；单色前景只有三块石头，河流与植物属于应用图标背景。 |

字体关闭合成粗体与斜体。所用 Leftist 文件的字母为比例宽度，数字宽度相同。具体角色、文件信息与加载方式见[字体资产说明](../assets/fonts/README.md)。文楷随字体分发保留 OFL；Leftist 的嵌入与再分发授权按该说明单独管理。

Iconoir 正式集合包含 30 枚 SVG，新增记录库与语义标签图标，固定到官方提交 `39834e3f193bc0cca2c36bebb6a650471977433d`，保留 MIT 许可。入口、操作与反馈按[语义清单](../assets/icons/manifest.json)选取，原始链接与使用规则见[图标来源](../assets/icons/SOURCES.md)。

正式数值与组件状态以 [VI 规范](vi-design.md)、[设计 token](../assets/design-tokens.json)、[主题 CSS](../assets/theme.css)和[组件配方](../assets/components.css)为准。品牌源图见[资产说明](../assets/README.md)。

历史对照保留在 [Paper 探索页](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-3-0)：第一轮六套中英字体、文楷与四种英文的对照、新拟物与 Material 3 控件、五套图标库。本地交付集中保存当前规则、正式资产和必要快照。

[Paper 正式 VI](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-1-0)维护选定样式；页面入口与功能归属见[信息架构](information-architecture.md)。首页采用对话与记录输入区，记录库通过时间线和语义标签组织内容。标签由 Agent 与本地算法生成，样式区分筛选状态、起源与核对状态。真实页面还需检查小字号、长句混排、文字放大、键盘与读屏表现。
