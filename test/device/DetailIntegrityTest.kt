package com.pebblepile.android

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.DinnerDecision
import com.pebblepile.android.model.OutcomeItem
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.SuggestionItem
import com.pebblepile.android.service.CoreApi
import com.pebblepile.android.service.RecordDetail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DetailIntegrityTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<PebblePileApplication>()

    private fun waitForText(text: String) =
        ui.waitUntil(10_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun recreatedEditorChecksTheRevisionThatTheUserStartedEditing() {
        val raw = "周一加班 ${UUID.randomUUID()}"
        val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, raw)
        ActivityScenario.launch<MainActivity>(intent).use { activity ->
            waitForText("确认保存")
            ui.onNodeWithText("确认保存").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("原始记录")
            ui.onNodeWithText("修改条件").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            val draft = ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
            draft.performTextReplacement("工作日")
            draft.assertTextContains("工作日")
            val api = app.coreApi
            val record = runBlocking { api.recentRecords().single { it.rawText == raw } }
            assertTrue(runBlocking {
                api.reviseMemory(record.id, record.revision, "周末", CorrectionScope.THIS_TIME, UUID.randomUUID().toString())
            }.success)
            activity.recreate()
            waitForText("确认修改")
            draft.assertTextContains("工作日")
            ui.onNode(hasText("确认修改") and hasAnyAncestor(isDialog())).assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForText("查看最新版本")
            assertEquals("周末", runBlocking { api.getRecord(record.id) }?.currentCondition)
            ui.onNodeWithText("查看最新版本").performSemanticsAction(SemanticsActions.OnClick)
            ui.waitUntil(10_000) {
                ui.onAllNodes(hasSetTextAction() and hasText("工作日")).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private class FeedbackApi(delegate: CoreApi) : CoreApi by delegate {
        val first = decision("diet-first", "盖饭")
        val second = decision("diet-second", "轻食便当")
        @Volatile var record = PebbleRecord("test-record", "晚饭吃什么？", "2026-11-23T20:00", "app",
            dinnerDecisions = listOf(first), suggestions = first.suggestions)
        val submittedOperations = java.util.concurrent.CopyOnWriteArrayList<String>()

        private fun decision(id: String, dish: String): DinnerDecision =
            DinnerDecision(id, "job-$id", 1, 0, "snapshot-$id", null, null,
                listOf(SuggestionItem("$id-item", dish, "预算二十元内。", emptyList())))

        override fun getRecordDetail(recordId: String, sourceRef: SourceRef?): RecordDetail? {
            if (recordId != record.id || sourceRef != null) return null
            val current = record
            return RecordDetail(current, emptyList(), emptyList(), current.revision, null)
        }

        override suspend fun recordOutcome(
            recordId: String, baseRevision: Long, choice: String, experience: String,
            operationId: String, decisionId: String?,
        ): ApiReceipt {
            submittedOperations.add(operationId)
            val current = record
            if (baseRevision != current.revision) return ApiReceipt(false, conflict = true,
                currentRecord = current, currentRevision = current.revision)
            val result = OutcomeItem("outcome-" + current.revision, choice, experience, "2026-11-23T21:00", decisionId)
            record = current.copy(outcomes = current.outcomes + result, revision = current.revision + 1)
            return ApiReceipt(true, message = "实际反馈已保存", outcomeId = result.id, decisionId = decisionId)
        }
    }

    private fun withFeedbackScreen(api: FeedbackApi, block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PebbleComponentTheme { RecordDetailScreen(api, api.record.id, true, {}, { _, _ -> }) }
                }
            }
            waitForText("实际选择与结果")
            block()
        }
    }

    @Test fun feedbackKeepsTheDecisionIdOfTheVisibleSuggestion() {
        val api = FeedbackApi(app.coreApi)
        withFeedbackScreen(api) {
            ui.onNodeWithTag("decision_choice_0").performScrollTo().performClick()
            ui.onNodeWithContentDescription("实际体验／结果").performScrollTo().performTextReplacement("取餐快")
            ui.onNodeWithText("保存实际反馈").performScrollTo().performClick()
            waitForText("当时建议：盖饭")
            assertEquals("盖饭", api.record.outcomes.single().choice)
            assertEquals(api.first.id, api.record.outcomes.single().decisionId)
        }
    }

    @Test fun otherDishStaysWithTheOriginalDecisionThroughRevisionConflict() {
        val api = FeedbackApi(app.coreApi)
        withFeedbackScreen(api) {
            ui.onNodeWithTag("decision_choice_other").performScrollTo().performClick()
            ui.onNodeWithContentDescription("实际选择").performScrollTo().performTextReplacement("热汤面")
            api.record = api.record.copy(revision = 2, dinnerDecisions = listOf(api.first, api.second),
                suggestions = api.second.suggestions)
            ui.onNodeWithText("保存实际反馈").performScrollTo().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForText("查看最新版本")
            ui.onNodeWithText("查看最新版本").performClick()
            waitForText("当时的建议")
            ui.onNodeWithContentDescription("实际选择").assertTextContains("热汤面")
            ui.onNodeWithText("保存实际反馈").performScrollTo().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForText("当时建议：盖饭")
            assertEquals("热汤面", api.record.outcomes.single().choice)
            assertEquals(api.first.id, api.record.outcomes.single().decisionId)
            assertEquals(2, api.submittedOperations.distinct().size)
        }
    }

    @Test fun correctedDecisionCanReceiveItsFirstFeedbackAndIndependentFeedbackStaysSeparate() {
        val api = FeedbackApi(app.coreApi)
        api.record = api.record.copy(supersededDinnerDecisionId = api.first.id, suggestions = emptyList())
        withFeedbackScreen(api) {
            ui.onNodeWithText("选择此前的建议").performScrollTo().performClick()
            ui.onNodeWithTag("feedback_history_${api.first.id}").performScrollTo().performClick()
            ui.onNodeWithContentDescription("实际选择").performScrollTo().performTextReplacement("热汤面")
            ui.onNodeWithText("保存实际反馈").performScrollTo().performClick()
            waitForText("当时建议：盖饭")
            assertEquals(api.first.id, api.record.outcomes.single().decisionId)
            ui.onNodeWithText("选择此前的建议").performScrollTo().performClick()
            ui.onNodeWithTag("feedback_history_${api.first.id}").performScrollTo().performClick()
            ui.onNodeWithText("改为独立体验").performScrollTo().performClick()
            ui.onNodeWithContentDescription("实际选择").performScrollTo().performTextReplacement("吐司")
            ui.onNodeWithText("保存实际反馈").performScrollTo().performClick()
            ui.waitUntil(10_000) { api.record.outcomes.size == 2 }
            assertEquals(null, api.record.outcomes.last().decisionId)
            assertEquals("吐司", api.record.outcomes.last().choice)
        }
    }

    @Test fun openingAnEvidenceCardRechecksTheSourceVersion() {
        val api = app.coreApi
        val recordId = requireNotNull(runBlocking {
            api.recordExperience("周一加班，晚饭想省事 ${UUID.randomUUID()}", "android_share", UUID.randomUUID().toString())
        }.recordId)
        val available = AtomicBoolean(true)
        val controlled = object : CoreApi by api {
            override fun getRecordDetail(recordId: String, sourceRef: SourceRef?) =
                if (sourceRef != null && !available.get()) null else api.getRecordDetail(recordId, sourceRef)?.let { detail ->
                    if (sourceRef == null) detail.copy(record = detail.record.copy(
                        suggestions = listOf(detail.sources.first().legacySuggestionFixture()),
                    )) else detail
                }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                PebbleComponentTheme { RecordDetailScreen(controlled, recordId, true, {}, { _, _ -> }) }
            } }
            waitForText("查看原文")
            available.set(false)
            ui.onNodeWithText("查看原文").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForText("来源版本已失效或被移除")
            ui.onNode(hasText("来源版本已失效或被移除") and hasAnyAncestor(isDialog())).assertExists()
            ui.onNodeWithText("关闭").performSemanticsAction(SemanticsActions.OnClick)
            ui.onNodeWithText("查看原文").assertIsNotEnabled()
        }
    }
}
