# PebblePile 字体

正式配对为 **霞鹜文楷（LXGW WenKai）+ Leftist Mono Serif**。中文正文用 400，标题与操作文字用 500；英文、品牌英文与数字均用 400。

| 角色 | 文件 | 字重 | 来源 |
| --- | --- | --- | --- |
| 中文正文、说明 | `LXGWWenKai-Regular.ttf` | 400 | [文楷 v1.530](https://github.com/lxgw/LxgwWenKai/releases/tag/v1.530) |
| 中文标题、品牌、按钮、标签、导航 | `LXGWWenKai-Medium.ttf` | 500 | 同上 |
| 英文与数字 | `LeftistMonoSerif.ttf` | 400 | [Domenico Catapano 发布页](https://www.dafont.com/leftist-mono-serif.font) |

[manifest.json](manifest.json)记录文件版本、真实名称、字重与 SHA-256。本地字体统一存放在忽略目录 `.local/fonts/`；本目录只保存规范与许可。

## 加载

[安装脚本](../../scripts/install-design-fonts.ps1)已将三个原文件安装并注册到当前 Windows 用户字体目录。Paper 重启后识别文楷 400/500，以及 Leftist 的原生族名 `Leftist Mono Sans` 400。六张正式 VI 画板已应用并核对字形与换行，本地原型导览已用 Chrome DevTools Protocol 核验实际字形来源：中文分别命中文楷 Regular／Medium，英文命中 LeftistMonoSerif；原生应用与其他设备仍需验证。

[theme.css](../theme.css)使用三个 PostScript 名称声明 `local()` 字体：`LXGWWenKai-Regular`、`LXGWWenKai-Medium`、`LeftistMonoSerif`。其他开发环境取得对应原文件后，可运行安装脚本并重新加载预览环境。

文楷 Medium 的 family 为 `LXGW WenKai Medium`，typographic family 为 `LXGW WenKai`。Leftist 的 family 为 `Leftist Mono Serif`，typographic family 为 `Leftist Mono Sans`。`--font-latin` 保存 `"Leftist Mono Serif", "Leftist Mono Sans"` 兼容链，两个名称指向同一源文件。CSS 继续通过 PostScript 名绑定 Serif；Paper 试排回读到 `LeftistMonoSerif`，并已核对衬线字形。原文件保持原样。

Paper 的英文节点与混排片段直接设置这组 `font-family`，回读为 `"LeftistMonoSerif", "Leftist Mono Sans", system-ui, sans-serif`。该工具通过字体 token 读取兼容链时会出现系统回退，因此英文族名绑定采用直接样式；token 继续传递正式规范，其余属性照常绑定 token。

Web 中，中文继承主题字体，英文片段显式使用 `.pp-latin` 或 `data-script="latin"`。该角色把字重固定为 400，包含英文按钮与标题。数字可再加 `.pp-number`：

```html
<main class="pebblepile-theme">
  <h1>叠石 <span class="pp-latin" lang="en">PebblePile</span></h1>
  <time class="pp-latin pp-number" datetime="2026-11-09">2026.11.09</time>
</main>
```

主题设置 `font-synthesis: none`。英文强调采用字号、间隔与颜色；中文强调使用文楷 500。Leftist 的字母为比例宽度，所核文件的十个阿拉伯数字宽度相同；文件没有 `tnum`、`lnum` 等 OpenType 特性记录，数字样式仍以实际排字结果为准。

应用打包时按平台注册字体文件与字重，中文和英文分别指定文本角色。Web 托管需补充获准文件的 `url()` 来源；当前主题只声明本机字体。缺少字体时，系统回退保证内容可读；验收应检查实际渲染字体、中文 400/500、英文 400、长句换行与文字放大。

## 许可

文楷采用 SIL OFL 1.1。分发字体时保留版权及[完整许可](LXGW-WenKai-OFL.txt)，修改与名称使用遵循原文件的附加条款。

Leftist 的作者发布页标注 Donationware，下载包只有 TTF，未附嵌入与再分发条款。字体文件保留在本地；正式打包或托管前需确认相应授权。`OS/2.fsType` 是技术字段，不能替代许可。仓库不包含 Leftist 字体文件。
