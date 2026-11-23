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
import com.pebblepile.android.model.EvidenceItem
import com.pebblepile.android.model.OutcomeItem
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

    @Test fun feedbackKeepsTheDecisionIdOfTheVisibleSuggestion() {
        val api = app.coreApi
        val raw = "周四加班，预算二十元 ${UUID.randomUUID()}"
        val recordId = requireNotNull(runBlocking {
            api.recordExperience(raw, "app", UUID.randomUUID().toString())
        }.recordId)
        val decisionId = "diet-test-$recordId"
        val suggestion = SuggestionItem(
            "$decisionId:gaifan",
            "盖饭",
            "工作日加班时优先省事，预算二十元内。",
            listOf(
                EvidenceItem(
                    "$decisionId:evidence",
                    "饮食经历",
                    "周四",
                    "2026-11-19",
                    recordId,
                    SourceRef(recordId, 1),
                    0,
                    2,
                )
            ),
        )
        val decision = DinnerDecision(
            decisionId, "job-test", 1, 0, "snapshot-test", null, null, listOf(suggestion),
        )
        var capturedDecisionId: String? = null
        var capturedChoice: String? = null
        val controlled = object : CoreApi by api {
            override fun getRecordDetail(recordId: String, sourceRef: SourceRef?): RecordDetail? {
                val detail = api.getRecordDetail(recordId, sourceRef) ?: return null
                if (sourceRef != null) return detail
                val created = capturedDecisionId?.let {
                    OutcomeItem("outcome-test", capturedChoice.orEmpty(), "最后吃了热汤面", "2026-11-23T20:00", it)
                }
                return detail.copy(
                    record = detail.record.copy(
                        suggestions = listOf(suggestion),
                        dinnerDecisions = listOf(decision),
                        outcomes = detail.record.outcomes + listOfNotNull(created),
                    ),
                )
            }

            override suspend fun recordOutcome(
                recordId: String, baseRevision: Long, choice: String, experience: String,
                operationId: String, decisionId: String?,
            ): ApiReceipt {
                capturedDecisionId = decisionId
                capturedChoice = choice
                return ApiReceipt(
                    success = true,
                    message = "实际反馈已保存",
                    outcomeId = "outcome-test",
                    decisionId = decisionId,
                )
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    PebbleComponentTheme { RecordDetailScreen(controlled, recordId, true, {}, { _, _ -> }) }
                }
            }
            waitForText("本次建议")
            ui.onNodeWithTag("decision_choice_0").performScrollTo().performClick()
            ui.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("最后吃了热汤面")
            ui.onNodeWithText("保存实际反馈").performScrollTo().performClick()
            ui.waitUntil(10_000) { capturedDecisionId != null }
            assertEquals(decisionId, capturedDecisionId)
            assertEquals("盖饭", capturedChoice)
            waitForText("当时建议：盖饭")
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
                if (sourceRef != null && !available.get()) null else api.getRecordDetail(recordId, sourceRef)
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