package com.pebblepile.android

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.model.*
import com.pebblepile.android.service.CoreApi
import com.pebblepile.android.service.RecordDetail
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class HistoricalDinnerEvidenceTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun historyRechecksVersionRangeAndTheConsumingRecordsReference() {
        val app = ApplicationProvider.getApplicationContext<PebblePileApplication>()
        val source = CapturedSource(SourceRef("shared", 2), "experience", "🍜我吃了热汤面", "app", "2026-11-19")
        val evidence = EvidenceItem("quoted-meal", "饮食经历", "热汤面", "2026-11-19", source.recordId, source.ref, 4, 7)
        val mismatch = evidence.copy(id = "invalid-span", quote = "错误的旧引文")
        val suggestion = SuggestionItem("first", "盖饭", "预算 20 元。", listOf(evidence, mismatch))
        val decision = DinnerDecision("original", "job", 1, 0, "snapshot", null, null, listOf(suggestion))
        val outcome = OutcomeItem("feedback", "热汤面", "上菜快", "2026-11-19", decision.id)
        val consumer = PebbleRecord("question", "晚饭吃什么", "2026-11-19", "app",
            dinnerDecisions = listOf(decision), supersededDinnerDecisionId = decision.id, outcomes = listOf(outcome))
        val linked = AtomicBoolean(true)
        val controlled = object : CoreApi by app.coreApi {
            override fun observeRecordChanges(recordId: String) = flowOf(Unit)
            override fun getDinnerDecision(recordId: String, decisionId: String) =
                decision.takeIf { recordId == consumer.id && decisionId == it.id }
            override fun getRecordDetail(recordId: String, sourceRef: SourceRef?): RecordDetail? {
                // The owner retains the source even after the consuming record unlinks it.
                val sources = if (recordId == source.recordId || linked.get()) listOf(source) else emptyList()
                if (sourceRef != null && sources.none { it.ref == sourceRef }) return null
                return RecordDetail(consumer, sources, emptyList(), 1L, null)
            }
        }

        fun waitFor(text: String) = ui.waitUntil(10_000) {
            ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        fun openValidSource() = ui.onNode(hasText("查看原文") and isEnabled() and hasAnyAncestor(isDialog()))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        fun closeDialog() = ui.onNode(hasText("关闭") and hasAnyAncestor(isDialog()))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                PebbleComponentTheme { RecordDetailScreen(controlled, consumer.id, true, {}, { _, _ -> }) }
            } }
            waitFor("查看当时的候选与依据")
            ui.onNodeWithText("查看当时的候选与依据").performScrollTo().performClick()
            waitFor("引文与该版本原话不一致")
            ui.onAllNodesWithText("错误的旧引文", substring = true).assertCountEquals(0)
            openValidSource()
            waitFor(source.text)
            ui.onNodeWithText("来源 shared · 版本 2 · 码点 [4,7)").assertExists()
            closeDialog()
            waitFor("“热汤面”")
            linked.set(false)
            openValidSource()
            waitFor("来源版本已失效或被移除")
            ui.onAllNodesWithText(source.text).assertCountEquals(0)
            closeDialog()
            waitFor("来源版本已失效或被移除")
            ui.onAllNodes(hasText("查看原文") and isEnabled() and hasAnyAncestor(isDialog())).assertCountEquals(0)
        }
    }
}
