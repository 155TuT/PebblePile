# Compose 共用组件交接（马晨曦）

关联 #3，接入方为 #4 陈柏睿（@baiyi060404）、#5 陈柯男（@Rophocale-KBlue）。本次在既有 Compose 工程中交付组件；原先的交接规格见 [PR #12](https://github.com/155TuT/PebblePile/pull/12)。

## 文件与接口

源码：[PebbleComponents.kt](../android/app/src/main/java/com/pebblepile/android/PebbleComponents.kt)，包名 `com.pebblepile.android`。使用工程已有 Compose UI / Material3 依赖，不新增库。

| 接口 | 使用方与约定 |
| --- | --- |
| `PebbleComponentTheme(cjk, content)` | 两位前端在页面共同父级应用；`cjk` 可注入获许可的真实 400/500 字体。 |
| `PebbleInput(value, onValueChange, label, modifier, enabled, errorMessage)` | 首页、分享编辑、反馈及条件纠正；最多显示四行，长文内部滚动，状态由调用方持有。 |
| `PebbleTag(label, selected, onSelectedChange, modifier, origin, needsReview, enabled)` | 记录库筛选、详情标签；回调只表达选择意图，筛选与排除业务由页面调用 CoreAPI。 |
| `PebbleEvidenceCard(quote, sourceLabel, condition, onOpenSource, onCorrect, modifier, sourceAvailable)` | 详情依据；引用真实原文，通过闭包保留 evidenceId / sourceId / sourceVersion，不把标签当作证据。来源不可用时禁用原文按钮。 |
| `PebbleFeedback(state, modifier, detail, onRetry)` | 已保存、正在整理、待核对、保存失败；只有失败且提供回调时显示重试。 |
| `PebbleComponentsPreview()` | Android Studio 两个预览：360dp、360dp + 字体 200%。支持编辑、选择、打开原文、纠正与样例重试。 |

首页接入示例（状态由现有页面保存）：

```kotlin
PebbleComponentTheme {
    PebbleInput(
        value = draft,
        onValueChange = { draft = it },
        label = "记录原话",
        enabled = !saving,
        errorMessage = saveError,
    )
}
```

详情接入示例（`evidence` 与导航函数替换为页面现有对象）：

```kotlin
PebbleEvidenceCard(
    quote = evidence.quote,
    sourceLabel = "本人原话",
    condition = conditionText,
    onOpenSource = { openEvidence(evidence.evidenceId) },
    onCorrect = { openCorrection() },
)
```

## VI 与字体

依据 [VI](vi-design.md)、[token](../assets/design-tokens.json)、[字体说明](../assets/fonts/README.md)。浅砂 #F5F3EB、苔绿 #536D58、正文 #222C24；控件 16dp、卡片 24dp 圆角，主要触点至少 48dp。正文 16sp/26sp，说明 14sp/20sp，标题 20sp/30sp。凸起与凹陷使用多层绘制近似软阴影，仍需与 VI 样张做设备视觉对照，不宣称像素完全一致。

图标路径来自仓库 Iconoir Regular 的 check.svg / info.svg / warning.svg 对应图形（本文件内联路径），保持 24 视图、1.5 描边和原有 assets/icons 许可。状态同时有文字，图标不重复朗读；标签具有 Checkbox 语义，状态支持 polite live region。

当前默认 **系统字体回退**，没有把回退字形冒充霞鹜文楷或 Leftist。组件主题关闭合成粗斜体。霞鹜文楷在字体文件与许可一并入包后通过 `cjk` 注入；Leftist 嵌入／再分发授权尚未确认，本 PR 不加入字体二进制，英文角色仍待授权后接入。字体与混排验收未完成。

## 两位前端接入与反馈

- 陈柏睿（#4）：首页原话输入、记录库标签及保存回执状态使用上述组件。保留现有草稿和 recordId 导航；只有收到核心持久化成功回执后才显示 SAVED，后台抽取仍用 PROCESSING。
- 陈柯男（#5）：分享编辑、详情依据、结果反馈和条件纠正使用同一组组件。`onOpenSource` 定位原文版本；`onCorrect` 进入现有纠正流程；失败保留草稿，重试复用业务 operationId，修订冲突不能直接显示成功。
- 请在对应 Issue 回写接入提交、构建结果及阻塞。组件回调或参数不足时贴出调用场景，由组件方继续调整。

## 验证状态与待验步骤

已完成：接口及静态源码检查、Iconoir 路径核对、空白差异检查。没有执行 Gradle 编译、Compose Preview 渲染、自动 UI 测试或真机操作：当前交付环境没有 JDK 和 Android SDK。源码预览中的所有材料均为虚构，点击重试只切换样例状态，不写入资料库。

有 Android 环境后，在 `android/` 执行 `./gradlew :app:assembleDebug :app:lintDebug`，再打开两个 Preview（交互模式）或运行预览到首测手机。需记录提交、机型、Android 版本与截图：

1. 360dp 宽、系统字号 100% / 200%：长中文、英文数字混排、五行以上输入、键盘弹出后可滚动并完成操作。
2. 标签切换、disabled 标签不可切换，TalkBack 读出选中状态；输入错误朗读，失败重试回调只执行一次。
3. 依据卡片打开原文、不可用来源按钮禁用；纠正确认更新样例，取消保留原值。
4. 真页面保存失败保留草稿，成功状态以 CoreAPI 回执为准；两个入口打开同一 recordId。
5. 核对真实字体字形、48dp 触点、焦点可见性和凸凹层次；回填截图后才完成 #3 的视觉验收。

本次代码和说明由 AI 辅助编写；不代替负责人及下游同学实际构建和验收。此交付不关闭 #3。
