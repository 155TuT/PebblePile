package com.pebblepile.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
            ui.onNodeWithText("重试保存").performScrollTo().performClick()
            ui.onNodeWithText("已保存").assertExists()
            ui.onNodeWithText("整理失败").assertExists()
            ui.onNodeWithText("重试整理").performScrollTo().performClick()
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
                val field = ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
                field.assertTextContains("加班时优先省事").performTextReplacement(draft)
                ui.onNodeWithText(if (confirm) "确认" else "取消").performClick()
                ui.onNodeWithText(if (confirm) draft else "加班时优先省事").assertExists()
            }
        }
    }
}
