# Android 共用组件交接

#18 交付了输入、标签、依据卡片和处理反馈。这次按 20 张移动端原型和两张 VI 样张补齐共用控件。两位前端在页面共同父级使用 `PebbleComponentTheme`，由页面持有草稿、选中项和业务回执，组件通过回调交回用户操作。[移动端原型](prototype-design.md#2-页面与状态) · [VI 组件配方](vi-design.md#7-正式组件配方) · [基础样张](../assets/vi/components.png) · [系统样张](../assets/vi/system-components.png)

## 页面用哪些组件

| 原型位置 | 组件和接法 |
| --- | --- |
| H0、H1 首页，H2、H3、H6 继续记录 | `PebbleTopBar` 放左右两个独立的图标操作。`PebbleCaptureModeSelector` 放在顶栏下方，切换对话和记录时复用同一份草稿。`PebbleComposer` 接文字、方式和提交回调，输入最多显示四行。两种方式沿用发送图标，动作名称分别为“发送”和“保存”。材料、语音入口由对应回调启用，页面通过 `canSubmit` 控制提交。 |
| H2、H5–H8，M5 处理进度与结果 | `PebbleStatusBadge` 标记简短状态，`PebbleToast` 显示即时回执，`PebbleBanner` 保留等待原因和下一步。`PebbleFeedback` 已区分保存失败、整理失败、等待与暂停。先展示保存回执，再按任务状态展示进度。保存和整理各用自己的重试回调。 |
| H3、H4、L3 原话、建议与实际反馈 | `PebbleQuote` 放原话与来源，`PebbleCard` 放建议或反馈。`PebbleInput` 用框外常显标签和内凹输入面，单行最小高 64 dp，多行 80 dp。帮助和错误紧跟字段，提交时用 `PebbleButton` 的 `busy` 和 `busyLabel` 显示进度。 |
| L1、L2 时间线、记录本与筛选 | `PebbleSearchField` 提供 56 dp 搜索框和键盘搜索动作。`PebbleLibraryViewSelector` 用图标和文字切换时间线、记录本。`PebbleTagGroup` 自动换行。筛选标签绑定选中状态，记录上的标签点开依据，资料说明里的标签只展示文字和起源。`PebbleListRow` 用于可打开的条目，批量操作放入 `PebbleToolbar`。 |
| L4 标签依据，M3、M4 认识与纠正 | `PebbleSheet` 展示标签依据。`PebbleEvidenceCard` 放条件与起源，每条原话用一个 `PebbleEvidenceSource` 绑定自己的记录、来源版本和区间。`PebbleChoiceTile` 配合 `selectableGroup()` 展示“仅本次／以后按此条件”。需要用户确认影响时用 `PebbleDialog`。 |
| M1、M2、M5–M7 设置、偏好、连接与数据 | 用 `PebbleCard` 分组，`PebbleListRow` 放名称、当前值和详情入口。`PebbleSwitch` 同步显示“已开启／已关闭”。字号等通用选项用凹陷轨道、选中项凸起的 `PebbleSegmented`。资料库、备份与配置页复用输入、按钮和确认框。 |

`PebbleButton` 提供主、次和危险三种样式。`PebbleIconButton` 接动作名称，`PebbleLink` 接链接文字和读屏动作名称。`PebbleMenu` 与触发按钮放在同一 `Box`，菜单项提供图标、当前项和禁用状态。`PebbleCheckbox`、`PebbleRadio` 和 `PebbleSlider` 保留相应的选择与调值语义。顶栏和输入区的系统栏、键盘避让与滚动留白由页面统一安排。[操作与输入](../android/app/src/main/java/com/pebblepile/android/PebbleControls.kt) · [页面共用容器](../android/app/src/main/java/com/pebblepile/android/PebbleLayout.kt) · [选择控件](../android/app/src/main/java/com/pebblepile/android/PebbleSelection.kt) · [菜单与浮层](../android/app/src/main/java/com/pebblepile/android/PebbleOverlays.kt)

## 视觉与字体

控件与页面共用浅砂底 `#F5F3EB`。普通操作左上受光、右下投影，输入和按下态内凹。首页方式选中后内凹，通用分段的选中项凸起。待核对标签使用浅砂黄底和褐色文字，选中时另有勾选标记。字段保留 1 dp 边界，键盘焦点使用外偏移 3 dp 的 2 dp 焦点环，错误边界可同时显示。独立操作的触点至少 48 × 48 dp。[设计值](../assets/design-tokens.json) · [表面与阴影](../android/app/src/main/java/com/pebblepile/android/PebbleRelief.kt)

34 枚图标从仓库固定版本的 Iconoir Regular 转为 Android 矢量资源，沿用 24 × 24 视图和原描边。`PebbleText` 支持普通文字和带引文高亮的文字。中文随包使用霞鹜文楷 400／500，英文和数字使用系统无衬线 400，取得 Leftist 的嵌入授权后再接入。[图标映射](../assets/icons/manifest.json) · [字体清单](../assets/fonts/manifest.json)

## 两位前端怎样接

**陈柏睿 #4** 接首页输入、记录库标签和保存回执，切换对话与记录模式时保留草稿。选择标签更新筛选，保存成功后按回执里的 `recordId` 打开详情。[#4 交接留言](https://github.com/155TuT/PebblePile/issues/4#issuecomment-7045235933)

**陈柯男 #5** 接分享输入、依据、反馈和条件纠正。每条引文单独绑定查看动作，把 `recordId` 和精确的 `SourceRef` 传给 `CoreApi.getRecordDetail`，按来源版本取回原话，再高亮对应的 Unicode 码点区间。失效引用显示原因并禁用该条的原文按钮，其他引用仍可打开。纠正遇到修订冲突时保留草稿，供用户对照当前条件。[#5 交接留言](https://github.com/155TuT/PebblePile/issues/5#issuecomment-7045252542) · [业务契约](core-api-contract.md)

## 验收记录

本次在 Android 16（API 36）模拟器上使用 360 × 800 dp 纵向屏幕，系统字号 100% 和 200% 各通过 11 项组件用例。检查了切换方式后保留草稿、按材料状态启用提交、筛选和打开标签依据、保存与整理各自重试、只读与禁用状态，以及引文回查。按钮上沿受光、下沿投影，输入框上沿内凹，测试从实际渲染中取像素核对这两种表面和待核对标签的底色。[组件设备用例](../android/app/src/androidTest/java/com/pebblepile/android/PebbleComponentsTest.kt) · [输入区与两种切换](testing/issue3/vi-capture-360.png) · [标签、回执与依据](testing/issue3/vi-feedback-360.png)

软键盘弹出时，输入框同时显示焦点环和错误边界。底部面板为系统手势条留出空间。用键盘打开菜单后，当前项显示完整的焦点环，方向键跳过禁用项。按 Esc 关闭菜单，焦点回到触发按钮。[键盘与输入焦点](testing/issue3/vi-keyboard-360.png) · [200% 字号底部面板](testing/issue3/vi-sheet-360-large.png)

#18 的三张截图保留了首批四类组件的交互样例。两位前端接入页面后，在 #4、#5 回写接入位置和提交号，再与马晨曦、林叶鑫核对首测手机上的完整流程与 TalkBack 播报。[原样例 100%](testing/issue3/components-360.png) · [原样例 200%](testing/issue3/components-360-large.png) · [原来源弹窗](testing/issue3/source-360-large.png) · [#3 验收分工](https://github.com/155TuT/PebblePile/issues/3#issuecomment-7045266816)
