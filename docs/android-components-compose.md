# Android 共用组件

叠石的首页、记录库、详情和设置共用 `PebbleComponentTheme`。页面持有草稿、选中项和业务回执，组件接收当前值，并通过回调把用户操作交给页面。控件的颜色、字体、凸凹表面和焦点样式沿用项目 VI。[移动端原型](prototype-design.md) · [VI 组件配方](vi-design.md#7-正式组件配方) · [基础样张](../assets/vi/components.png) · [系统样张](../assets/vi/system-components.png)

## 页面与组件

| 页面 | 组件与行为 |
| --- | --- |
| 首页 | `PebbleTopBar` 放记录库和设置两个独立入口。`PebbleCaptureModeSelector` 切换对话与记录，两种方式共用一份草稿。`PebbleComposer` 接文本、补充材料、语音和提交动作，输入区最多显示四行。 |
| 记录库 | `PebbleLibraryViewSelector` 切换时间线和记录本，`PebbleSearchField` 接收搜索词，`PebbleTagGroup` 让筛选标签随宽度换行。筛选标签表示当前条件，详情标签可以打开依据。 |
| 详情 | `PebbleQuote` 展示原话，`PebbleEvidenceCard` 和 `PebbleEvidenceSource` 展示条件、起源与引文。`PebbleSheet` 承载标签依据，`PebbleChoiceTile` 供用户选择“仅本次”或“以后按此条件”。 |
| 设置 | `PebbleCard` 分组承载开关、字号和子页入口。`PebbleSwitch` 给出当前开关状态，`PebbleSegmented` 展示字号选项，`PebbleListRow` 从当前值进入详情。 |
| 回执与进度 | `PebbleToast` 给出即时提示，`PebbleStatusBadge` 显示状态，`PebbleBanner` 保留等待原因和下一步操作。保存回执表示原话已经写入。整理进度跟随对应任务，整理失败时可以保留原记录并重试。 |

输入字段用 `PebbleInput` 的常显标签、帮助文字和错误提示。提交按钮可通过 `busy` 和 `busyLabel` 显示进度。图标按钮提供动作名称，菜单与触发按钮放在同一个 `Box`。系统栏、软键盘避让和滚动空间由页面处理。[操作与输入](../components/compose/PebbleControls.kt) · [页面容器](../components/compose/PebbleLayout.kt) · [选择控件](../components/compose/PebbleSelection.kt) · [浮层](../components/compose/PebbleOverlays.kt)

## 依据怎样回到原话

每条引文带有记录编号、来源编号、来源版本和原文区间。点击“查看原文”时，页面按 `recordId` 和 `SourceRef` 回查来源，把 Unicode 码点的半开区间 `[start, end)` 换算为字符串下标，再高亮对应文字。来源失效时，该引文显示原因，其余有效引文仍可打开。纠正遇到版本冲突时，页面保留草稿，供用户对照最新条件再提交。[依据与反馈组件](../components/compose/PebbleComponents.kt) · [业务接口](core-api-contract.md)

## 表面、字体与触点

页面和控件共用浅砂底色 `#F5F3EB`。普通操作左上受光、右下投影，输入框、按下态和首页当前方式内凹。字号选项在凹陷轨道中凸起。待核对标签用浅砂黄底和褐色文字，选中时增加勾选标记。输入框保留 1 dp 边界，键盘焦点使用外偏移 3 dp 的 2 dp 焦点环，可与错误边界同时显示。独立操作的触点至少为 48 × 48 dp。[设计值](../assets/design-tokens.json) · [表面与阴影](../components/compose/PebbleRelief.kt)

中文随包使用霞鹜文楷 Regular 400 和 Medium 500。0.1.0 中的英文和数字使用系统无衬线 400，设计稿中的 Leftist Mono Serif 依字体许可管理。功能图标取自固定版本的 Iconoir Regular，Android 矢量资源保留 24 × 24 视图与原描边。[文字实现](../components/compose/PebbleTypography.kt) · [字体与许可](../assets/fonts/README.md) · [图标映射](../assets/icons/manifest.json)

## 小屏与大字号复验

组件开发时在 Android 16（API 36）模拟器上采用 360 × 800 dp 纵向屏幕，系统字号 100% 和 200% 各通过 11 项组件用例。用例覆盖草稿、标签、引文、保存与整理重试、菜单焦点和表面明暗。下列截图记录了当时的组件状态。[组件设备用例](../test/device/PebbleComponentsTest.kt)

- 输入区切换方式后保留草稿。弹出软键盘时，输入框可同时显示焦点和错误边界。[输入区](testing/issue3/vi-capture-360.png) · [键盘与输入焦点](testing/issue3/vi-keyboard-360.png)
- 底部面板为系统手势条留出空间。标签和引文各自保留点击动作，放大字号后可滚动查看。[标签、回执与依据](testing/issue3/vi-feedback-360.png) · [200% 字号面板](testing/issue3/vi-sheet-360-large.png)
- 两种字号的完整组件样例用于对照换行、选中态和触点。设备上复验读屏时，逐项核对动作名称、状态播报和关闭弹层后的焦点。[100% 字号](testing/issue3/components-360.png) · [200% 字号](testing/issue3/components-360-large.png) · [来源弹窗](testing/issue3/source-360-large.png)
