package com.pebblepile.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.model.CorrectionScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordFlowTest {
    @get:Rule val ui = createEmptyComposeRule()

    private fun openShare(text: String): ActivityScenario<MainActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        return ActivityScenario.launch(intent)
    }

    private fun waitForText(text: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun shareConfirmationSurvivesRecreationUntilConsumed() {
        val text = "Shared dinner ${UUID.randomUUID()}"
        openShare(text).use { activity ->
            waitForText("确认保存")
            activity.onActivity {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, it.requestedOrientation)
                assertEquals(Configuration.ORIENTATION_PORTRAIT, it.resources.configuration.orientation)
            }
            activity.recreate()
            ui.onNodeWithText("确认保存").performClick()
            waitForText("原始记录")
            activity.recreate()
            waitForText("原始记录")
            ui.onNodeWithText("保存分享内容").assertDoesNotExist()
        }
        openShare(text).use { activity ->
            ui.onNodeWithText("取消").performClick()
            activity.recreate()
            waitForText("留下今天的原话")
            ui.onNodeWithText("保存分享内容").assertDoesNotExist()
        }
    }

    @Test fun evidenceOpensItsSourceAndReturnsToTheRecord() {
        val text = "🍜加班晚餐希望省事 ${UUID.randomUUID()} " + "hot soup ".repeat(16) + "end of source"
        openShare(text).use {
            ui.onNodeWithText("确认保存").performScrollTo().assertIsDisplayed().performClick()
            waitForText("原始记录")
            val api = ApplicationProvider.getApplicationContext<PebblePileApplication>().coreApi
            val quote = runBlocking {
                api.recentRecords().single { it.rawText == text }.suggestions.first().evidence.first().quote
            }
            ui.onNodeWithText("“$quote”", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                .performTouchInput { click() }
            waitForText("来源与原话")
            ui.onNode(hasText(text) and hasAnyAncestor(isDialog())).assertExists()
            ui.onNode(hasText("版本 1", substring = true) and hasAnyAncestor(isDialog())).assertExists()
            ui.onNode(hasText("码点 [", substring = true) and hasAnyAncestor(isDialog())).assertExists()
            ui.onNodeWithText("关闭").performScrollTo().assertIsDisplayed().performClick()
            ui.onNodeWithText("原始记录").assertExists()
        }
    }

    @Test fun correctionKeepsTheEditedTextAcrossRecreation() {
        openShare("Correction ${UUID.randomUUID()}").use { activity ->
            ui.onNodeWithText("确认保存").performClick()
            waitForText("原始记录")
            ui.onNodeWithText("修改条件").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForText("纠正条件")
            val field = ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
            val edited = hasSetTextAction() and hasText("only on weekdays") and hasAnyAncestor(isDialog())
            field.performTextReplacement("only on weekdays")
            ui.waitUntil(10_000) { ui.onAllNodes(edited).fetchSemanticsNodes().size == 1 }
            activity.recreate()
            ui.waitUntil(10_000) { ui.onAllNodes(edited).fetchSemanticsNodes().size == 1 }
            ui.onNodeWithText("以后按此条件").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            ui.onNodeWithText("确认修改").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForText("适用范围：以后按此条件")
            ui.onNodeWithText("尚未补充当前目标条件。").assertExists()
            ui.onNodeWithText("长期条件：only on weekdays", substring = true).assertExists()
        }
    }

    @Test fun revisionConflictReloadsTheSavedConditionAndKeepsTheDraftForRetry() {
        val text = "Conflict ${UUID.randomUUID()}"
        val api = ApplicationProvider.getApplicationContext<PebblePileApplication>().coreApi
        openShare(text).use {
            ui.onNodeWithText("确认保存").performClick()
            waitForText("原始记录")
            ui.onNodeWithText("修改条件").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForText("纠正条件")
            val field = ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
            field.performTextReplacement("workday overtime")
            val original = runBlocking { api.recentRecords().single { it.rawText == text } }
            val competing = runBlocking {
                api.reviseMemory(original.id, original.revision, "weekends", CorrectionScope.THIS_TIME, UUID.randomUUID().toString())
            }
            assertTrue(competing.success)
            ui.onNodeWithText("确认修改").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForText("查看最新版本")
            ui.onNodeWithText("查看最新版本").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            ui.waitUntil(10_000) {
                ui.onAllNodes(hasText("本次目标条件：weekends") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNode(hasSetTextAction() and hasText("workday overtime")).assertExists()
            ui.onNodeWithText("以后按此条件").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            ui.onNodeWithText("确认修改").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForText("适用范围：以后按此条件")
            val updated = runBlocking { requireNotNull(api.getRecord(original.id)) }
            assertEquals("workday overtime", updated.currentCondition)
            assertEquals(CorrectionScope.FUTURE, updated.conditionScope)
            assertEquals(original.revision + 2, updated.revision)
        }
    }
}
