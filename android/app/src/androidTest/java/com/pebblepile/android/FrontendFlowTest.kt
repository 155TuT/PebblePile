package com.pebblepile.android

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.model.ItemStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FrontendFlowTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<PebblePileApplication>()
    private val api get() = context.coreApi
    private val settingsStore get() = PebbleSettingsStore(context)

    @Before
    fun resetSettings() {
        settingsStore.save(PebbleSettings())
        api.setAutoOrganize(true)
    }

    @After
    fun restoreSettings() {
        settingsStore.save(PebbleSettings())
        api.setAutoOrganize(true)
    }

    private fun waitForText(text: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollLibraryUntilTag(tag: String) {
        repeat(6) {
            if (ui.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()) return
            ui.onNode(hasScrollAction()).performTouchInput { swipeUp() }
            ui.waitForIdle()
        }
        ui.onNodeWithTag(tag).assertExists()
    }

    private fun scrollLibraryToSearch() {
        repeat(6) {
            if (ui.onAllNodes(
                    hasSetTextAction() and hasAnyAncestor(hasTestTag("library_search"))
                ).fetchSemanticsNodes().isNotEmpty()
            ) return
            ui.onNode(hasScrollAction()).performTouchInput { swipeDown() }
            ui.waitForIdle()
        }
    }

    @Test fun settingsPersistAndChangeTheVisibleTextScale() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            waitForText("留下今天的原话")
            ui.onNodeWithContentDescription("设置").performClick()
            waitForText("字号与外观")
            ui.onNodeWithText("自动整理").performClick()
            ui.waitUntil(5_000) { !settingsStore.load().autoOrganize }
            ui.onNodeWithText("特大").performScrollTo().performClick()
            ui.waitUntil(5_000) { settingsStore.load().textScale == PebbleTextScale.EXTRA_LARGE }
            activity.recreate()
            waitForText("字号与外观")
            ui.onNodeWithText("特大").assertIsSelected()
            assertFalse(settingsStore.load().autoOrganize)
        }
    }

    @Test fun materialEntryAddsTextAndModeSwitchKeepsTheSameDraft() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("留下今天的原话")
            ui.onNode(hasSetTextAction()).performTextReplacement("原始草稿")
            ui.onNodeWithContentDescription("添加材料").performClick()
            waitForText("添加到草稿")
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
                .performTextReplacement("补充材料")
            ui.onNodeWithText("添加到草稿").performClick()
            ui.onNode(hasSetTextAction() and hasText("原始草稿\n补充材料")).assertExists()
            ui.onNodeWithText("对话").performClick()
            ui.onNode(hasSetTextAction() and hasText("原始草稿\n补充材料")).assertExists()
        }
    }

    @Test fun savedReceiptAndTaskProgressRemainOnHomeAfterReturningFromDetail() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("留下今天的原话")
            ui.onNode(hasSetTextAction()).performTextReplacement("首页回执 ${UUID.randomUUID()}")
            ui.onNodeWithContentDescription("保存").performClick()
            waitForText("当前事项")
            ui.onNodeWithContentDescription("返回").performClick()
            waitForText("正在整理")
            ui.onAllNodes(hasText("已保存 ·", substring = true))[0].assertExists()
            ui.onNodeWithText("等待后台模块推进").assertExists()
        }
    }

    @Test fun notebookAndCombinedFiltersSurviveOpeningTheRecord() {
        api.setAutoOrganize(false)
        val suffix = UUID.randomUUID().toString().take(8)
        val person = "person$suffix"
        val title = "记录本-$suffix"
        val expectedText = "@$person 今天加班，晚饭想省事一点 $suffix"
        val receipt = runBlocking {
            api.recordExperience(expectedText, "app_home_record", "library-${UUID.randomUUID()}")
        }
        val id = requireNotNull(receipt.recordId)
        val revision = requireNotNull(receipt.revision)
        runBlocking {
            api.updateRecordGoal(id, revision, title, ItemStatus.WAITING, "goal-${UUID.randomUUID()}")
            api.recordExperience("没有人物标记的另一条记录", "app_home_record", "library-${UUID.randomUUID()}")
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("留下今天的原话")
            ui.onNodeWithContentDescription("记录库").performClick()
            waitForText("时间线")
            ui.onNode(hasText("记录本") and hasClickAction()).performClick()
            ui.onNode(hasText("记录本") and isSelected()).assertExists()
            scrollLibraryUntilTag("filter_person_$person")
            ui.onNodeWithTag("filter_person_$person").performClick()
            scrollLibraryUntilTag("filter_status_WAITING")
            ui.onNodeWithTag("filter_status_WAITING").performClick()
            scrollLibraryUntilTag("filter_tag_饮食")
            ui.onNodeWithTag("filter_tag_饮食").performClick()
            scrollLibraryToSearch()
            ui.onNode(
                hasSetTextAction() and hasAnyAncestor(hasTestTag("library_search"))
            ).performTextReplacement(person)
            scrollLibraryUntilTag("library_record_$id")
            ui.onNodeWithTag("library_record_$id").performClick()
            waitForText("当前事项")
            ui.onNodeWithContentDescription("返回").performClick()
            waitForText("时间线")
            ui.onNode(hasText("记录本") and isSelected()).assertExists()
            scrollLibraryToSearch()
            ui.onNode(
                hasSetTextAction() and
                    hasAnyAncestor(hasTestTag("library_search")) and
                    hasText(person)
            ).assertExists()
            scrollLibraryUntilTag("filter_person_$person")
            ui.onNodeWithTag("filter_person_$person").assertIsOn()
            scrollLibraryUntilTag("filter_status_WAITING")
            ui.onNodeWithTag("filter_status_WAITING").assertIsOn()
            scrollLibraryUntilTag("filter_tag_饮食")
            ui.onNodeWithTag("filter_tag_饮食").assertIsOn()
        }
    }

    @Test fun goalAndTagActionsUpdateTheSameRecord() {
        val text = "今天加班，晚饭吃汤面 ${UUID.randomUUID()}"
        val receipt = runBlocking {
            api.recordExperience(text, "app_home_record", "detail-${UUID.randomUUID()}")
        }
        val id = requireNotNull(receipt.recordId)

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("留下今天的原话")
            ui.onNodeWithContentDescription("记录库").performClick()
            waitForText(text)
            ui.onNodeWithText(text).performClick()
            waitForText("当前事项")

            ui.onNodeWithText("编辑事项信息").performScrollTo().performClick()
            waitForText("保存事项")
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
                .performTextReplacement("周末晚餐")
            ui.onNode(hasText("等待条件") and hasAnyAncestor(isDialog())).performClick()
            ui.onNodeWithText("保存事项").performClick()
            waitForText("周末晚餐")

            ui.onAllNodesWithText("确认")[0].performScrollTo().performClick()
            ui.waitUntil(5_000) {
                runBlocking { api.getRecord(id)!!.tags.any { it.reviewState.name == "CONFIRMED" } }
            }
            ui.onAllNodesWithText("修改")[0].performScrollTo().performClick()
            waitForText("确认修改")
            ui.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0]
                .performTextReplacement("通勤")
            ui.onNodeWithText("确认修改").performClick()
            waitForText("通勤")
            ui.onAllNodesWithText("排除")[0].performScrollTo().performClick()
            waitForText("通勤 · 已排除")
        }
    }

    @Test fun taskCanPauseAndContinueWithoutDeletingTheRecord() {
        val text = "需要暂停整理的原话 ${UUID.randomUUID()}"
        val receipt = runBlocking {
            api.recordExperience(text, "app_home_record", "task-${UUID.randomUUID()}")
        }
        val id = requireNotNull(receipt.recordId)

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("留下今天的原话")
            ui.onNodeWithContentDescription("记录库").performClick()
            waitForText(text)
            ui.onNodeWithText(text).performClick()
            waitForText("整理任务")
            ui.onNodeWithText("暂停").performScrollTo().performClick()
            waitForText("继续")
            ui.onNodeWithText("继续").performScrollTo().performClick()
            waitForText("暂停")
            assertTrue(runBlocking { api.getRecord(id) != null })
            ui.onNodeWithContentDescription("返回").performClick()
            waitForText(text)
        }
    }
}
