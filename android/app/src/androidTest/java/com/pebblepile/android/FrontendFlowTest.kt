package com.pebblepile.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.test.espresso.Espresso
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.db.SnapshotCoreRepository
import com.pebblepile.android.model.ItemStatus
import com.pebblepile.android.service.LocalCoreApi
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

    private fun scrollLibraryTo(key: String) {
        val list = ui.onNode(hasScrollToIndexAction())
        ui.waitUntil(10_000) {
            // The header is available while the repository loads the record and filter keys.
            try {
                list.performScrollToKey(key)
                true
            } catch (_: AssertionError) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }

    private fun SemanticsNodeInteraction.activate() = performSemanticsAction(SemanticsActions.OnClick)

    @Test fun settingsPersistAndChangeTheVisibleTextScale() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            waitForText("留下今天的原话")
            ui.onNodeWithContentDescription("设置").activate()
            waitForText("文字大小")
            ui.onNodeWithText("自动整理").activate()
            ui.waitUntil(5_000) { !settingsStore.load().autoOrganize }
            ui.onNodeWithText("特大").performScrollTo().activate()
            ui.waitUntil(5_000) { settingsStore.load().textScale == PebbleTextScale.EXTRA_LARGE }
            activity.recreate()
            waitForText("文字大小")
            ui.onNodeWithText("特大").assertIsSelected()
            assertFalse(settingsStore.load().autoOrganize)
        }
    }

    @Test fun materialEntryAddsTextAndModeSwitchKeepsTheSameDraft() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("留下今天的原话")
            ui.onNode(hasSetTextAction()).performTextReplacement("原始草稿")
            Espresso.closeSoftKeyboard()
            ui.onNodeWithContentDescription("添加材料").activate()
            waitForText("添加到草稿")
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
                .performTextReplacement("补充材料")
            Espresso.closeSoftKeyboard()
            ui.onNodeWithText("添加到草稿").performScrollTo().activate()
            ui.onNode(hasSetTextAction() and hasText("原始草稿\n补充材料")).assertExists()
            ui.onNodeWithText("对话").activate()
            ui.onNode(hasSetTextAction() and hasText("原始草稿\n补充材料")).assertExists()
        }
    }

    @Test fun savedReceiptAndTaskProgressRemainOnHomeAfterReturningFromDetail() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("留下今天的原话")
            ui.onNode(hasSetTextAction()).performTextReplacement("首页回执 ${UUID.randomUUID()}")
            Espresso.closeSoftKeyboard()
            ui.onNodeWithContentDescription("保存").activate()
            waitForText("当前事项")
            ui.onNodeWithContentDescription("返回").activate()
            waitForText("等待整理算法就绪。")
            ui.onAllNodes(hasText("已保存 ·", substring = true))[0].assertExists()
            ui.onNodeWithText("等待条件").assertExists()
        }
    }

    @Test fun notebookAndCombinedFiltersSurviveOpeningTheRecord() {
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
            ui.onNodeWithContentDescription("记录库").activate()
            scrollLibraryTo("library_header")
            waitForText("时间线")
            ui.onNode(hasText("记录本") and hasClickAction()).activate()
            ui.onNode(hasText("记录本") and isSelected()).assertExists()
            scrollLibraryTo("people_filters")
            ui.onNodeWithTag("filter_person_$person").activate()
            scrollLibraryTo("status_filters")
            ui.onNodeWithTag("filter_status_WAITING").activate()
            scrollLibraryTo("tag_filters")
            ui.onNodeWithTag("filter_tag_饮食").activate()
            scrollLibraryTo("library_header")
            val search = ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("library_search")))
            search.performTextReplacement(person)
            search.performImeAction()
            scrollLibraryTo("notebook_$id")
            ui.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("library_record_$id"))).activate()
            waitForText("当前事项")
            ui.onNodeWithContentDescription("返回").activate()
            scrollLibraryTo("library_header")
            waitForText("时间线")
            ui.onNode(hasText("记录本") and isSelected()).assertExists()
            ui.onNode(
                hasSetTextAction() and
                    hasAnyAncestor(hasTestTag("library_search")) and
                    hasText(person)
            ).assertExists()
            scrollLibraryTo("people_filters")
            ui.onNodeWithTag("filter_person_$person").assertIsOn()
            scrollLibraryTo("status_filters")
            ui.onNodeWithTag("filter_status_WAITING").assertIsOn()
            scrollLibraryTo("tag_filters")
            ui.onNodeWithTag("filter_tag_饮食").assertIsOn()
        }
    }

    @Test fun goalAndTagActionsUpdateTheSameRecord() {
        val text = "今天加班，晚饭吃汤面 ${UUID.randomUUID()}"
        val receipt = runBlocking {
            api.recordExperience(text, "app_home_record", "detail-${UUID.randomUUID()}")
        }
        val id = requireNotNull(receipt.recordId)
        val tag = runBlocking { api.getRecord(id)!!.tags.first() }
        org.junit.Assert.assertEquals(receipt.source, tag.sourceRef)

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("留下今天的原话")
            ui.onNodeWithContentDescription("记录库").activate()
            scrollLibraryTo("timeline_$id")
            ui.onNodeWithText(text).activate()
            waitForText("当前事项")

            ui.onNodeWithText("编辑事项信息").performScrollTo().activate()
            waitForText("保存事项")
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
                .performTextReplacement("周末晚餐")
            Espresso.closeSoftKeyboard()
            ui.onNode(hasText("等待条件") and hasAnyAncestor(isDialog())).activate()
            ui.onNodeWithText("保存事项").activate()
            waitForText("周末晚餐")

            ui.onNodeWithTag("record_tag_${tag.id}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForText("标签依据")
            ui.waitUntil(5_000) { ui.onAllNodesWithText("原话版本 v1", substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick)
            ui.waitUntil(5_000) {
                runBlocking { api.getRecord(id)!!.tags.any { it.reviewState.name == "CONFIRMED" } }
            }
            ui.onNodeWithTag("record_tag_${tag.id}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForText("标签依据")
            ui.onNodeWithText("修改").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("确认修改")
            ui.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0]
                .performTextReplacement("通勤")
            Espresso.closeSoftKeyboard()
            ui.onNodeWithText("确认修改").activate()
            waitForText("通勤")
            ui.onNodeWithTag("record_tag_${tag.id}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForText("标签依据")
            ui.onNodeWithText("排除").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("通勤 · 已排除")
        }
    }

    @Test fun taskCanPauseAndContinueWithoutDeletingTheRecord() {
        val text = "需要暂停整理的原话 ${UUID.randomUUID()}"
        // Hold the job in QUEUED so the manual controls can be exercised deterministically.
        val localApi = LocalCoreApi(SnapshotCoreRepository())
        val receipt = runBlocking {
            localApi.recordExperience(text, "app_home_record", "task-${UUID.randomUUID()}")
        }
        val id = requireNotNull(receipt.recordId)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PebbleComponentTheme {
                        RecordDetailScreen(localApi, id, true, {}, { _, _ -> })
                    }
                }
            }
            waitForText("整理任务")
            ui.onNodeWithText("暂停").performScrollTo().activate()
            waitForText("继续")
            ui.onNodeWithText("选择“继续”可恢复任务。").assertExists()
            ui.onNodeWithText("继续").performScrollTo().activate()
            waitForText("暂停")
            ui.onNodeWithText("等待开始处理。").assertExists()
            ui.onNodeWithText("取消任务").performScrollTo().activate()
            waitForText("本次任务已取消。")
            assertTrue(runBlocking { localApi.getRecord(id)?.rawText == text })
        }
    }
}
