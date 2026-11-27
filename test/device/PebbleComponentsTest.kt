package com.pebblepile.android

import androidx.activity.compose.setContent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.view.KeyEvent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
class PebbleComponentsTest {
    @get:Rule val ui = createEmptyComposeRule()

    private fun show(content: @Composable () -> Unit): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { it.setContent { PebbleComponentTheme { content() } } }
        }

    @Test fun editableDraftErrorAndTagStateAreAccessible() {
        var disabledClicks = 0
        show {
            var text by remember { mutableStateOf("") }
            var selected by remember { mutableStateOf(false) }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PebbleTag("选", selected, { selected = it })
                PebbleTag("锁", false, { disabledClicks++ }, enabled = false)
                PebbleInput(text, { text = it }, "晚餐", errorMessage = if (text.isEmpty()) "补一段原话" else null)
            }
        }.use {
            val tag = ui.onNodeWithText("选")
            tag.assertIsOff().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            tag.performClick().assertIsOn()
            ui.onNodeWithText("锁").assertIsNotEnabled().performTouchInput { click() }
            assertEquals(0, disabledClicks)
            val input = ui.onNode(hasSetTextAction())
            input.assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "补一段原话"))
            val draft = "🍜晚餐 20 元\n".repeat(6)
            input.performTextReplacement(draft)
            input.assertTextContains(draft)
            ui.onNodeWithText("补一段原话").assertDoesNotExist()
        }
    }

    @Test fun eachDinnerCitationOpensItsOwnVersionAndHighlight() {
        show { PebbleComponentsPreview() }.use {
            val expected = listOf("周一晚餐 · E01" to "十分钟就吃上了", "周三晚餐 · E02" to "也不用洗锅")
            expected.forEachIndexed { index, (label, quote) ->
                ui.onAllNodesWithText("查看原文")[index].performScrollTo().performClick()
                ui.onNode(hasText(label) and hasAnyAncestor(isDialog())).assertExists()
                ui.onNode(hasText(if (index == 0) "来源 monday · 版本 1" else "来源 wednesday · 版本 1")
                    and hasAnyAncestor(isDialog())).assertExists()
                val original = ui.onNode(hasText(quote, substring = true) and hasAnyAncestor(isDialog()))
                    .fetchSemanticsNode().config[SemanticsProperties.Text].single()
                assertTrue(original.spanStyles.any { original.text.substring(it.start, it.end) == quote })
                ui.onNodeWithText("关闭").performClick()
            }
        }
    }

    @Test fun unavailableSourceShowsItsReasonAndLeavesCorrectionAvailable() {
        var opened = 0
        var corrected = 0
        show {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PebbleEvidenceCard("工作日加班", onCorrect = { corrected++ }) {
                    PebbleEvidenceSource("热汤面", "周一记录", { opened++ }, false, "该版本已移除")
                }
            }
        }.use {
            ui.onNodeWithText("该版本已移除").assertExists()
            ui.onNodeWithText("查看原文").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
            ui.onNodeWithText("纠正条件").performScrollTo().performClick()
            assertEquals(0, opened)
            assertEquals(1, corrected)
        }
    }

    @Test fun retryingAJobKeepsTheSaveReceiptAndDraft() {
        show { PebbleComponentsPreview() }.use {
            val draft = "加班晚餐 20 元"
            ui.onNode(hasSetTextAction()).performTextReplacement(draft)
            ui.onNodeWithText("重试保存").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            ui.onNodeWithText("已保存").assertExists()
            ui.onNodeWithText("整理失败").assertExists()
            ui.onNodeWithText("重试整理").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            ui.onNodeWithText("正在整理").assertExists()
            ui.onNodeWithText("已保存").assertExists()
            ui.onNode(hasSetTextAction()).assertTextContains(draft)
        }
    }

    @Test fun cancellingAndConfirmingALongCorrectionUseDifferentValues() {
        show { PebbleComponentsPreview() }.use {
            val draft = "只在工作日加班时优先省事，周末另选。".repeat(5)
            for (confirm in listOf(false, true)) {
                ui.onNodeWithText("纠正条件").performScrollTo().performClick()
                ui.waitUntil(5_000) {
                    ui.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                val field = ui.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0]
                field.assertTextContains("加班时优先省事").performTextReplacement(draft)
                ui.onNodeWithText(if (confirm) "确认" else "取消").performClick()
                ui.onNodeWithText(if (confirm) draft else "加班时优先省事").assertExists()
            }
        }
    }

    private fun componentColumn(content: @Composable ColumnScope.() -> Unit) = show {
        Column(Modifier.fillMaxSize().background(PebbleVisuals.Sand).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content)
    }

    /** Only a run with the viScreenshots argument exports images from the test device. */
    private fun saveScreen(name: String) {
        if (InstrumentationRegistry.getArguments().getString("viScreenshots") != "true") return
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Dialog and IME windows finish their platform transitions after Compose is idle.
        SystemClock.sleep(450)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "vi-components")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun captureAndLibraryModesKeepTheDraftAndDispatchTheirOwnActions() {
        var submitted = ""
        var attachments = 0
        var voices = 0
        componentColumn {
            var mode by remember { mutableStateOf(PebbleCaptureMode.Record) }
            var view by remember { mutableStateOf(PebbleLibraryView.Timeline) }
            var text by remember { mutableStateOf("今天加班，晚饭想省事一点。") }
            var hasMaterial by remember { mutableStateOf(false) }
            PebbleTopBar("叠石",
                leading = { PebbleIconButton(PebbleGlyph.Library, "记录库", {}, tint = PebbleVisuals.Moss) },
                trailing = { PebbleIconButton(PebbleGlyph.Settings, "设置", {}) })
            PebbleCaptureModeSelector(mode, { mode = it })
            PebbleComposer(text, { text = it }, mode, { submitted = mode.label + text },
                onAdd = { attachments++; hasMaterial = true }, onVoice = { voices++ },
                canSubmit = text.isNotBlank() || hasMaterial)
            PebbleLibraryViewSelector(view, { view = it })
        }.use {
            ui.onNodeWithText("记录").assertIsSelected()
            ui.onNodeWithText("时间线").assertIsSelected()
            saveScreen("capture")
            ui.onNodeWithContentDescription("保存").performClick()
            assertEquals("记录今天加班，晚饭想省事一点。", submitted)
            ui.onNodeWithText("对话").performClick().assertIsSelected()
            val field = ui.onNode(hasSetTextAction())
            field.assertTextContains("今天加班，晚饭想省事一点。")
            ui.onNodeWithContentDescription("发送").performClick()
            assertEquals("对话今天加班，晚饭想省事一点。", submitted)
            ui.onNodeWithContentDescription("语音输入").performClick()
            assertEquals(1, voices)
            ui.onNodeWithText("记录本").performScrollTo().performClick().assertIsSelected()
            field.performTextReplacement(" ")
            ui.onNodeWithContentDescription("发送").assertIsNotEnabled()
            ui.onNodeWithContentDescription("添加材料").performClick()
            assertEquals(1, attachments)
            ui.onNodeWithContentDescription("发送").assertIsEnabled()
        }
    }

    @Test fun filteringOpeningEvidenceAndReadingTagsHaveDistinctActions() {
        var opened = 0
        componentColumn {
            var selected by remember { mutableStateOf(false) }
            PebbleTagGroup {
                PebbleTag("加班晚餐", selected, { selected = it }, icon = PebbleGlyph.Tag)
                PebbleTag("省事", Modifier.testTag("pending"), needsReview = true, onClick = { opened++ })
                PebbleTag("实际经历", origin = "本人")
            }
            PebbleToast("反馈已保存", PebbleGlyph.Check)
            PebbleBanner("等待网络", PebbleGlyph.Clock, detail = "原话已保存，联网后继续整理。", tone = PebbleVisuals.Warning)
            PebbleCard {
                PebbleStatusBadge(PebbleFeedbackState.REVIEW)
                PebbleEvidenceSource("今天加班，晚饭想省事一点。", "本人的原话 · 11/09", {})
            }
        }.use {
            val filter = ui.onNodeWithText("加班晚餐")
            filter.assertIsOff().performClick().assertIsOn()
            val pending = ui.onNodeWithTag("pending")
            pending.assertHasClickAction().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            val surface = pending.captureToImage().toPixelMap()
            assertEquals(PebbleVisuals.WarningSoft.toArgb(), surface[surface.width / 2, with(ui.density) { 4.dp.roundToPx() }].toArgb())
            pending.performClick()
            assertEquals(1, opened)
            ui.onNodeWithText("实际经历").assertHasNoClickAction()
            ui.onNodeWithText("等待网络").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            saveScreen("feedback")
        }
    }

    @Test fun controlsExposeTheirValuesAndKeepTheBusyButtonWidth() {
        componentColumn {
            var checked by remember { mutableStateOf(false) }
            var toggle by remember { mutableStateOf(true) }
            var size by remember { mutableIntStateOf(0) }
            var value by remember { mutableFloatStateOf(.5f) }
            var busy by remember { mutableStateOf(false) }
            PebbleSegmented(listOf("标准", "较大"), size, { size = it })
            PebbleCheckbox("这件事已完成", checked, { checked = it })
            PebbleRadio("仅本次", checked, { checked = true })
            PebbleSwitch("自动整理", toggle, { toggle = it })
            PebbleSlider("进度", value, { value = it }, "${(value * 100).toInt()}%", steps = 3)
            PebbleButton("保存反馈", { busy = true }, Modifier.testTag("save"), busy = busy, busyLabel = "正在保存")
            PebbleButton("取消", {}, style = PebbleButtonStyle.Secondary)
            PebbleButton("删除记录", {}, style = PebbleButtonStyle.Danger)
        }.use {
            ui.onNodeWithText("较大").performClick().assertIsSelected()
            ui.onNodeWithText("这件事已完成").performClick().assertIsOn()
            ui.onNodeWithText("仅本次").assertIsSelected()
            val switch = ui.onNodeWithText("自动整理")
            switch.performClick().assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已关闭"))
            ui.onNodeWithContentDescription("进度").performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
            ui.onNodeWithText("75%").assertExists()
            val button = ui.onNodeWithTag("save").performScrollTo()
            val width = button.getUnclippedBoundsInRoot().let { it.right - it.left }
            button.performClick().assertIsNotEnabled().assertTextContains("正在保存")
            assertEquals(width, button.getUnclippedBoundsInRoot().let { it.right - it.left })
            saveScreen("selection")
        }
    }

    @Test fun menuDialogAndSheetDispatchAndDismissIndependently() {
        var confirmations = 0
        var disabledClicks = 0
        componentColumn {
            var menu by remember { mutableStateOf(false) }
            var dialog by remember { mutableStateOf(false) }
            var sheet by remember { mutableStateOf(false) }
            Box {
                PebbleIconButton(PebbleGlyph.More, "更多", { menu = true })
                PebbleMenu(menu, { menu = false }, listOf(
                    PebbleMenuAction("查看依据", { sheet = true }, PebbleGlyph.Sources, current = true),
                    PebbleMenuAction("恢复备份", { disabledClicks++ }, PebbleGlyph.Restore, enabled = false),
                    PebbleMenuAction("删除记录", { dialog = true }, PebbleGlyph.Delete, danger = true)))
            }
            if (dialog) PebbleDialog("删除这条记录？", { dialog = false }, "确认删除", { confirmations++; dialog = false },
                danger = true) { PebbleText("本次操作会移除当前记录。") }
            if (sheet) PebbleSheet("标签依据", { sheet = false }) {
                var once by remember { mutableStateOf(true) }
                PebbleQuote("今天加班，晚饭想省事一点。", source = "本人原话")
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PebbleChoiceTile("仅本次", once, { once = true })
                    PebbleChoiceTile("以后按此条件", !once, { once = false })
                }
            }
        }.use {
            val more = ui.onNodeWithContentDescription("更多")
            more.performClick()
            ui.onNodeWithText("恢复备份").assertIsNotEnabled().performTouchInput { click() }
            assertEquals(0, disabledClicks)
            saveScreen("menu")
            ui.onNodeWithText("查看依据").performClick()
            ui.onNodeWithText("标签依据").assertIsDisplayed()
            ui.onNodeWithText("以后按此条件").performClick().assertIsSelected()
            saveScreen("sheet")
            ui.onNodeWithContentDescription("关闭").performClick()
            more.performClick()
            ui.onNodeWithText("删除记录").performClick()
            ui.onNodeWithText("取消").performClick()
            assertEquals(0, confirmations)
            more.performClick()
            ui.onNodeWithText("删除记录").performClick()
            saveScreen("dialog")
            ui.onNodeWithText("确认删除").performClick()
            assertEquals(1, confirmations)
            ui.onNode(isDialog()).assertDoesNotExist()
        }
    }

    @Test fun keyboardMenuSkipsDisabledItemsAndReturnsFocusToItsTrigger() {
        componentColumn {
            var expanded by remember { mutableStateOf(false) }
            Box {
                PebbleIconButton(PebbleGlyph.More, "更多", { expanded = true })
                PebbleMenu(expanded, { expanded = false }, listOf(
                    PebbleMenuAction("查看依据", {}),
                    PebbleMenuAction("恢复备份", {}, enabled = false),
                    PebbleMenuAction("删除记录", {}, danger = true)))
            }
        }.use {
            val device = InstrumentationRegistry.getInstrumentation()
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            val trigger = ui.onNodeWithContentDescription("更多")
            trigger.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            ui.waitUntil(5_000) { ui.onAllNodesWithText("查看依据").fetchSemanticsNodes().isNotEmpty() }
            val first = ui.onNodeWithText("查看依据").assertIsFocused()
            val popup = ui.onNode(isPopup())
            val row = first.getUnclippedBoundsInRoot()
            val origin = popup.getUnclippedBoundsInRoot()
            val pixels = popup.captureToImage().toPixelMap()
            // The next menu row must leave the lower half of the keyboard focus ring visible.
            with(ui.density) {
                val x = (row.left + (row.right - row.left) / 2f - origin.left).roundToPx()
                val y = (row.bottom - origin.top + 4.dp).roundToPx()
                assertEquals(PebbleVisuals.Focus.toArgb(), pixels[x, y].toArgb())
            }
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            ui.onNodeWithText("删除记录").assertIsFocused()
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
            ui.onNodeWithText("查看依据").assertDoesNotExist()
            trigger.assertIsFocused()
        }
    }

    @Test fun raisedButtonAndInsetInputHaveOppositeLighting() {
        var searches = 0
        componentColumn {
            var query by remember { mutableStateOf("") }
            Box(Modifier.fillMaxWidth().testTag("raised").padding(20.dp)) {
                PebbleButton("取消", {}, Modifier.fillMaxWidth(), PebbleButtonStyle.Secondary)
            }
            PebbleInput(query, { query = it }, "条件", errorMessage = "请补充适用条件", kind = PebbleFieldKind.SingleLine)
            PebbleSearchField(query, { query = it }, { searches++ })
        }.use { scenario ->
            val pixels = with(ui.density) { 4.dp.roundToPx() }
            val margin = with(ui.density) { 20.dp.roundToPx() }
            val raised = ui.onNodeWithTag("raised").captureToImage().toPixelMap()
            val x = raised.width / 2
            assertTrue("raised top must be lighter than its bottom",
                raised[x, margin - pixels].luminance() > raised[x, raised.height - margin + pixels].luminance() + .015f)
            val field = ui.onNodeWithContentDescription("条件")
            val inset = field.captureToImage().toPixelMap()
            assertTrue("inset top must be darker than its bottom",
                inset[inset.width / 2, pixels].luminance() + .015f < inset[inset.width / 2, inset.height - pixels].luminance())
            saveScreen("fields")
            field.performTouchInput { click() }.assertIsFocused()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "请补充适用条件"))
            if (InstrumentationRegistry.getArguments().getString("viScreenshots") == "true") {
                ui.waitUntil(10_000) {
                    var keyboardVisible = false
                    scenario.onActivity { activity ->
                        keyboardVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    keyboardVisible
                }
                saveScreen("keyboard")
            }
            val search = ui.onNodeWithContentDescription("搜索记录")
            search.performScrollTo().performClick().performTextReplacement("晚餐")
            search.performImeAction()
            assertEquals(1, searches)
        }
    }
}
