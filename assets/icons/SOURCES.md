# Iconoir Regular · 正式界面图标

叠石统一使用 **Iconoir Regular**。本目录包含 34 枚界面图标，覆盖产品入口、材料与记忆操作、事项进展、语义标签和系统组件。

- 官方仓库：[iconoir-icons/iconoir](https://github.com/iconoir-icons/iconoir)
- 固定提交：[`39834e3f193bc0cca2c36bebb6a650471977433d`](https://github.com/iconoir-icons/iconoir/commit/39834e3f193bc0cca2c36bebb6a650471977433d)
- 原始目录：`icons/regular`
- 许可证：[MIT](LICENSE)
- 语义索引：[manifest.json](manifest.json)
- 设计依据：[视觉选择记录](../../docs/design-decisions.md)
- 获取与选定日期：2026-11-09

## 使用规则

图标原生为 **24 × 24 px**，描边 **1.5 px**，颜色使用 `currentColor`。内联 SVG 或组件通过 CSS `color` 接收界面语义颜色；外链图片按自身 SVG 渲染。点击区域至少 **48 × 48 px**。选中态沿用同一轮廓，通过文字、前景色与背景表达状态。

单独可点击的图标由按钮提供可读名称；已有文字标签时，图标作为装饰处理。状态图标配合“已保存”“已更新”“等待条件”“需要核对”等具体文字。

`sources` 表示原材料，`memory` 表示形成的认识；`restore` 用于恢复备份，`retry` 用于失败重试，`resume` 用于继续暂停任务。`subject` 表示经历归属人物。`import` 的界面文字为“导入材料”，可通过 `upload` 别名引用。

## 语义与来源

SVG 保留上游几何和属性，文件按业务语义命名。下表来源链接固定到同一提交；清单中的 Git blob SHA 可用于复核原始文件。

| 语义 | 用途 | 本地文件 | 上游文件 |
| --- | --- | --- | --- |
| `home` | 对话与记录首页 | [home.svg](home.svg) | [home-simple.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/home-simple.svg) |
| `chat` | 对话入口与消息 | [chat.svg](chat.svg) | [chat-bubble.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/chat-bubble.svg) |
| `memory` | 设置中的习惯、偏好与明确设置 | [memory.svg](memory.svg) | [book.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/book.svg) |
| `sources` | 原话、图片、链接与资源 | [sources.svg](sources.svg) | [multiple-pages.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/multiple-pages.svg) |
| `tasks` | 原记录中的目标、决策、执行与反馈 | [tasks.svg](tasks.svg) | [task-list.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/task-list.svg) |
| `settings` | 记忆偏好、标签规则、模型、数据与连接 | [settings.svg](settings.svg) | [settings.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/settings.svg) |
| `subject` | 经历归属人物与人物筛选 | [subject.svg](subject.svg) | [user.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/user.svg) |
| `timeline` | 记录库的时间线视图 | [timeline.svg](timeline.svg) | [clock-rotate-right.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/clock-rotate-right.svg) |
| `search` | 检索记录、事件与语义标签 | [search.svg](search.svg) | [search.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/search.svg) |
| `add` | 新增记录、明确设置或目标 | [add.svg](add.svg) | [plus.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/plus.svg) |
| `edit` | 修改资料或纠正内容 | [edit.svg](edit.svg) | [edit-pencil.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/edit-pencil.svg) |
| `back` | 返回上一层 | [back.svg](back.svg) | [arrow-left.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/arrow-left.svg) |
| `close` | 关闭浮层或退出当前视图 | [close.svg](close.svg) | [xmark.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/xmark.svg) |
| `more` | 展开次级操作 | [more.svg](more.svg) | [more-horiz.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/more-horiz.svg) |
| `delete` | 进入删除操作与范围确认 | [delete.svg](delete.svg) | [trash.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/trash.svg) |
| `export` | 导出备份或资料文件 | [export.svg](export.svg) | [download.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/download.svg) |
| `restore` | 校验并恢复备份 | [restore.svg](restore.svg) | [database-restore.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/database-restore.svg) |
| `import` | 从本机文件导入材料或选择备份 | [import.svg](import.svg) | [upload.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/upload.svg) |
| `pause` | 暂停处理任务 | [pause.svg](pause.svg) | [pause.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/pause.svg) |
| `resume` | 继续已暂停的处理任务 | [resume.svg](resume.svg) | [play.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/play.svg) |
| `retry` | 重试可重新执行的失败任务 | [retry.svg](retry.svg) | [refresh.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/refresh.svg) |
| `send` | 提交对话输入 | [send.svg](send.svg) | [send.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/send.svg) |
| `check` | 配合具体文字显示保存、更新或完成状态 | [check.svg](check.svg) | [check.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/check.svg) |
| `warning` | 需要核对、冲突与异常提示 | [warning.svg](warning.svg) | [warning-triangle.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/warning-triangle.svg) |
| `info` | 字段、授权与处理方式说明 | [info.svg](info.svg) | [info-circle.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/info-circle.svg) |
| `clock` | 等待执行条件与待处理状态 | [clock.svg](clock.svg) | [clock.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/clock.svg) |
| `chevron-right` | 进入列表项详情 | [chevron-right.svg](chevron-right.svg) | [nav-arrow-right.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/nav-arrow-right.svg) |
| `chevron-down` | 展开选择器或折叠区域 | [chevron-down.svg](chevron-down.svg) | [nav-arrow-down.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/nav-arrow-down.svg) |
| `library` | 记录库入口与记录本 | [library.svg](library.svg) | [folder.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/folder.svg) |
| `tag` | 语义标签筛选与纠正 | [tag.svg](tag.svg) | [label.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/label.svg) |
| `voice` | 系统听写输入文字 | [voice.svg](voice.svg) | [microphone.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/microphone.svg) |
| `connection` | 模型服务与外部连接 | [connection.svg](connection.svg) | [terminal.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/terminal.svg) |
| `appearance` | 主题与文字大小 | [appearance.svg](appearance.svg) | [palette.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/palette.svg) |
| `model` | 推理位置与模型配置 | [model.svg](model.svg) | [cpu.svg](https://github.com/iconoir-icons/iconoir/blob/39834e3f193bc0cca2c36bebb6a650471977433d/icons/regular/cpu.svg) |

`manifest.icons[语义].file` 返回仓库相对路径；`manifest.aliases` 将 `plus`、`upload`、`history` 等别名映射到正式语义。
