# 叠石 · 项目标志与界面图标

三块自然河石对应材料、认识与持续更新。石块错位搭接，以俯视和侧视呈现体积；轮廓、层次与 Paper 设计稿保持一致。

| 文件 | 用途 |
| --- | --- |
| [icon.svg](icon.svg) | 界面单色图标：透明背景，只保留三块石头，以遮罩留白区分搭接层次 |
| [app-icon.svg](app-icon.svg) | 应用图标：青灰石头配米白圆角背景，浅色河流贯穿背景，岸边点缀少量水草与芦苇 |

界面图标推荐显示为 **24 × 24 px**，可点击区域至少 **48 × 48 px**。三块石头使用 `currentColor`，默认色为苔绿 `#536D58`；内联使用时可通过 SVG 的 CSS `color` 设置界面颜色，作为外链图片使用时保留默认色。

应用图标为 **512 × 512** 原生矢量。河流、植物、石面渐变与阴影用于品牌装饰；界面的文字、状态、操作与反馈另设语义颜色 token。图标中的米白底色为 `#F5F3EB`。

## 界面功能图标

正式界面统一使用 **Iconoir Regular**，素材在 [icons/](icons/SOURCES.md)。34 枚图标覆盖对话与记录、记录库、时间线、语义标签、事项进展、设置，以及常用操作和状态。记忆图标用于设置内的记忆与偏好管理。

原生尺寸 **24 × 24 px**，描边 **1.5 px**，颜色为 `currentColor`，点击区域至少 **48 × 48 px**。内联 SVG 或组件通过 CSS `color` 使用界面的语义颜色。独立图标按钮提供可读名称，状态图标配合具体文字。

[manifest.json](icons/manifest.json) 按业务语义返回图标路径，并提供 `plus → add`、`upload → import`、`user → subject` 等别名。[SOURCES.md](icons/SOURCES.md) 记录每枚图标的用途、固定提交与来源；[LICENSE](icons/LICENSE) 保留上游 MIT 许可。字体、图标与控件的选择依据见[视觉选择记录](../docs/design-decisions.md)。

## 设计资产

[字体说明](fonts/README.md)维护霞鹜文楷 + Leftist Mono Serif 的角色、加载与许可。[设计 token](design-tokens.json)、[主题 CSS](theme.css)和[组件 CSS](components.css)保存正式新拟物规则；[vi/](vi/)只保留当前排版、基础组件与系统组件快照。品牌图直接使用本页 SVG，历史对照保留在 [Paper 探索页](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-3-0)。
