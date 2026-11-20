# Android 共用组件交接

马晨曦负责四类 Compose 组件。陈柏睿接首页和记录库，陈柯男接分享、详情和纠正。两位前端在页面的共同父级使用 `PebbleComponentTheme`，由页面持有草稿、选中状态和业务回执。[组件规格](android-components-handoff.md) · [组件源码](../android/app/src/main/java/com/pebblepile/android/PebbleComponents.kt)

| 组件 | 传什么，怎样接 |
| --- | --- |
| `PebbleInput` | 传原文、标签和 `onValueChange`。可传提示、帮助和错误原因。输入框最多显示四行，继续输入时在框内滚动。 |
| `PebbleTag` | `selected` 表示筛选状态，`origin` 说明标签来自哪里，`needsReview` 标出待核对。点按整项触发 `onSelectedChange`，选中状态同时显示勾选图标。详情中的“排除”由页面按标签编号调用业务接口。 |
| `PebbleEvidenceCard` | 展示适用条件和认识来源。每条引文放一个 `PebbleEvidenceSource`，将查看动作绑定到该记录、来源版本和码点区间。一张卡片共用一个纠正入口。单条引文也可沿用原有的 `quote`、`sourceLabel` 调用。 |
| `PebbleFeedback` | 页面收到持久化回执后显示“已保存”，整理任务另显示自己的状态。保存失败时沿用原操作标识重试；整理失败时保留保存回执，按对应任务重试。有继续或取消动作时传入相应回调。 |

## 两位前端怎样接

**陈柏睿 #4** 接首页输入、记录库标签和保存回执，切换对话与记录模式时保留草稿。选择标签更新筛选，保存成功后按回执里的 `recordId` 打开详情。[#4 交接留言](https://github.com/155TuT/PebblePile/issues/4#issuecomment-7045235933)

**陈柯男 #5** 接分享输入、依据、反馈和条件纠正。每条引文单独绑定查看动作，把 `recordId` 和精确的 `SourceRef` 传给 `CoreApi.getRecordDetail`，按来源版本取回原话，再高亮对应的 Unicode 码点区间。失效引用显示原因并禁用该条的原文按钮，其他引用仍可打开。纠正遇到修订冲突时保留草稿，供用户对照当前条件。[#5 交接留言](https://github.com/155TuT/PebblePile/issues/5#issuecomment-7045252542) · [业务契约](core-api-contract.md)

调试预览给出两条可操作的引文。周一的“十分钟就吃上了”和周三的“也不用洗锅”分别打开各自的原话和版本。预览使用虚构记录，保存在调试源码中。[可运行示例](../android/app/src/debug/java/com/pebblepile/android/PebbleComponentsPreview.kt)

## 视觉与字体

页面用浅砂色，依据卡片和来源弹窗用 `#FFFDF7`。未选标签凸起，输入框与选中标签内凹；焦点用 `#365B44` 描边，主要触点至少 48 × 48 dp。状态沿用 Iconoir 的图形和文字，读屏通过文字、勾选语义和状态播报区分结果。[设计值](../assets/design-tokens.json) · [图标许可](../assets/icons/LICENSE)

组件默认使用随 APK 打包的霞鹜文楷 Regular 400 和 Medium 500。两份文件已按清单核对 SHA-256，OFL 许可随包提供。英文和数字使用系统无衬线 400，Leftist 待嵌入授权确认后接入。[字体清单](../assets/fonts/manifest.json) · [排版实现](../android/app/src/main/java/com/pebblepile/android/PebbleTypography.kt)

## 验证与下一步

Android 16 模拟器使用 360 dp 纵向窗口。100% 字号下 14 项设备测试通过，其中 5 项覆盖这批组件。200% 字号下重跑这 5 项，覆盖长文与错误提示、禁用触点、两条来源回查与高亮、两种重试以及纠正的取消和确认。另在调试预览实际弹出软键盘并打开来源弹窗。[设备测试](../android/app/src/androidTest/java/com/pebblepile/android/PebbleComponentsTest.kt) · [100% 字号](testing/issue3/components-360.png) · [200% 字号](testing/issue3/components-360-large.png) · [放大后的来源弹窗](testing/issue3/source-360-large.png)

在 `android/` 执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

陈柏睿、陈柯男各接一处后，在 #4、#5 回写页面位置和提交号。马晨曦和林叶鑫在首测手机上复查中文与英文混排、软键盘、TalkBack 的焦点顺序和实际播报，结果登记到 #3。[#3 验收与分工](https://github.com/155TuT/PebblePile/issues/3#issuecomment-7045266816)

马晨曦负责组件及交接，Codex 协助实现、审查和回归验证。
