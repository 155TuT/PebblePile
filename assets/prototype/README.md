# 原型导览

用浏览器直接打开 [index.html](index.html)。导览读取 Paper 导出的静态 JSX，模拟页面跳转、选择、输入和反馈；示例内容为虚构资料。导览使用本地文件，无需服务器。

[source/manifest.json](source/manifest.json) 保存画板、动作、状态与返回关系，同目录保留带节点标识的 Paper JSX。样式复用 [theme.css](../theme.css) 和 [components.css](../components.css)。字体优先使用本机安装的霞鹜文楷与 Leftist，缺失时回退；字体来源和授权见[字体说明](../fonts/README.md)。

在仓库根目录重新生成：

```powershell
python scripts/build-prototype-preview.py
```

更新 Paper 后，以 `inline-styles`、`includeNodeIds: true`、`includeRootPositioning: false` 导出 JSX，并更新相应动作清单。可通过多次 `--manifest` 合并临时导出；`--check` 只检查节点、目标和静态语法。

长页通过 `viewportHeight` 指定手机视窗，保留源画板高度并滚动查看。`actions` 绑定导出节点，`bindings` 表达状态变化；模拟处理完成等演示动作放在画面外的 `demoTransitions`。输入限制、键盘焦点和临时状态用于原型体验，实际数据处理按 PRD 与系统设计实现。

截图和验证报告保存在忽略目录 `.build/prototype/`。
