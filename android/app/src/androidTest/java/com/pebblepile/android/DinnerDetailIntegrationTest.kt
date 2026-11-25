package com.pebblepile.android

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.db.DbJson
import com.pebblepile.android.db.RoomStore
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.DeleteRequest
import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeleteTargetType
import com.pebblepile.android.model.TaskState
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DinnerDetailIntegrationTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<PebblePileApplication>()
    private val api get() = app.coreApi

    private fun waitFor(text: String) {
        ui.waitUntil(30_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun SemanticsNodeInteraction.activate() = performSemanticsAction(SemanticsActions.OnClick)

    private fun submitHome(text: String, chat: Boolean = false) {
        waitFor("留下今天的原话")
        if (chat) ui.onNodeWithText("对话").activate()
        ui.onNode(hasSetTextAction()).performTextReplacement(text)
        Espresso.closeSoftKeyboard()
        ui.onNodeWithContentDescription(if (chat) "发送" else "保存").activate()
        waitFor("原始记录")
    }

    private fun savedRecordId(text: String, previousIds: Set<String>): String =
        api.queryRecords().records.single { it.rawText == text && it.id !in previousIds }.id

    private fun waitForWorker(id: String) {
        ui.waitUntil(30_000) {
            val jobs = api.getRecordDetail(id)?.jobIds.orEmpty()
            jobs.isNotEmpty() && jobs.all { api.getTask(it)?.state == TaskState.COMPLETED }
        }
    }

    @Test fun publishedDinnerKeepsItsEvidenceAndFeedbackAcrossCorrectionAndReopen() =
        runDinnerScenario(feedbackAfterCorrection = false)

    @Test fun correctedDinnerAcceptsFirstOutcomeOnOriginalDecision() =
        runDinnerScenario(feedbackAfterCorrection = true)

    private fun runDinnerScenario(feedbackAfterCorrection: Boolean) {
        val monday = "今天周一加班到八点半，晚饭预算二十元。我吃了十八元的热汤面，十分钟就吃上了，不用洗锅。"
        val wednesday = "今天周三又加班了，晚饭还是想控制在二十元以内。我吃了十六元的盖饭，不用准备食材，也不用洗锅，省事让我很满意。"
        val query = "今天周四加班，预算二十元，晚饭吃什么？请说明依据，并指出来自哪两次经历的原话。"
        val correction = "以后只在工作日加班时，优先给我推荐省事的晚饭。"
        val previousIds = api.queryRecords().records.map { it.id }.toSet()
        val ownRecords = mutableSetOf<String>()
        fun newId(text: String) = savedRecordId(text, previousIds + ownRecords).also(ownRecords::add)
        val db = RoomStore.database(app)
        val previousPreferenceJson = db.libraryDao().get()?.futureConditionJson
        val settings = PebbleSettingsStore(app)
        val originalSettings = settings.load()
        settings.save(PebbleSettings())
        api.setAutoOrganize(true)
        try {
            val mondayId = ActivityScenario.launch(MainActivity::class.java).use {
                submitHome(monday)
                newId(monday).also(::waitForWorker)
            }
            // Other instrumentation classes can leave a library preference. Establish this scenario's input.
            if (previousPreferenceJson != null) {
                val owner = requireNotNull(api.getRecordDetail(mondayId))
                assertTrue(runBlocking {
                    api.reviseMemory(mondayId, owner.record.revision, correction, CorrectionScope.FUTURE,
                        UUID.randomUUID().toString(), owner.futureCondition?.revision)
                }.success)
            }
            val share = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, wednesday)
            val wednesdayId = ActivityScenario.launch<MainActivity>(share).use {
                waitFor("确认保存")
                ui.onNodeWithText("确认保存").activate()
                waitFor("原始记录")
                newId(wednesday).also(::waitForWorker)
            }
            val (recordId, decisionId) = ActivityScenario.launch(MainActivity::class.java).use {
                submitHome(query, chat = true)
                val id = newId(query)
                waitFor("首选 · 盖饭")
                val decision = requireNotNull(api.getRecordDetail(id)?.record?.currentDinnerDecision)
                assertEquals(20, decision.constraint?.budgetCny)
                val expand = "查看盖饭的全部依据"
                ui.onNodeWithText(expand, substring = true).performScrollTo().activate()
                waitFor("收起盖饭的依据")
                val proof = decision.suggestions.first().evidence
                for ((sourceId, raw, quote) in listOf(
                    Triple(mondayId, monday, "加班到八点半"), Triple(wednesdayId, wednesday, "又加班了")
                )) {
                    val ref = proof.first { it.sourceRecordId == sourceId && it.quote == quote }
                    val card = hasText("“$quote”") and hasText(ref.sourceRef.sourceId, substring = true)
                    ui.onAllNodes(card)[0].performScrollTo().activate()
                    waitFor("来源与原话")
                    ui.onNode(hasText(raw) and hasAnyAncestor(isDialog())).assertExists()
                    ui.onNode(hasText("码点 [${ref.start},${ref.end})", substring = true) and hasAnyAncestor(isDialog())).assertExists()
                    ui.onNodeWithText("关闭").activate()
                }

                ui.onNodeWithText("收起盖饭的依据").performScrollTo().activate()
                ui.onNodeWithText(expand, substring = true).assertExists()
                fun submitOutcome() {
                    val soup = decision.suggestions.indexOfFirst { it.title == "热汤面" }
                    assertTrue(soup >= 0)
                    ui.onNodeWithTag("decision_choice_$soup").performScrollTo().activate()
                    ui.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("省时也省事")
                    Espresso.closeSoftKeyboard()
                    ui.onNodeWithText("保存实际反馈").performScrollTo().activate()
                    waitFor("当时建议：盖饭")
                    val feedback = requireNotNull(api.getRecordDetail(id)).record.outcomes.single()
                    assertEquals("热汤面", feedback.choice)
                    assertEquals("省时也省事", feedback.experience)
                    assertEquals(decision.id, feedback.decisionId)
                }
                if (!feedbackAfterCorrection) submitOutcome()

                ui.onNodeWithText("修改条件").performScrollTo().activate()
                ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(correction)
                Espresso.closeSoftKeyboard()
                ui.onNodeWithText("以后按此条件").performScrollTo().activate()
                ui.onNodeWithText("确认修改").activate()
                waitFor("适用范围：以后按此条件")
                ui.onNodeWithTag("dinner_decision_id").assertDoesNotExist()
                assertEquals(correction, api.getRecordDetail(id)?.futureCondition?.text)
                if (feedbackAfterCorrection) {
                    ui.onNodeWithText("选择此前的建议").performScrollTo().activate()
                    ui.onNodeWithTag("feedback_history_${decision.id}").performScrollTo().activate()
                    waitFor("当时的建议")
                    submitOutcome()
                }
                id to decision.id
            }

            for ((question, expectedFirst, explicitApplies) in listOf(
                Triple("今天周六也要加班，晚饭预算二十元，吃什么好？", "轻食便当", false),
                Triple(query, "盖饭", true),
            )) {
                ActivityScenario.launch(MainActivity::class.java).use {
                    submitHome(question, chat = true)
                    val id = newId(question)
                    waitFor("首选 · $expectedFirst")
                    val decision = requireNotNull(api.getRecordDetail(id)?.record?.currentDinnerDecision)
                    val basis = requireNotNull(decision.rankingBasis)
                    val preference = "pref-explicit-weekday_overtime_convenience"
                    assertEquals(explicitApplies, preference in basis.appliedPreferences)
                    assertEquals(!explicitApplies, preference in basis.unmatchedPreferences)
                }
            }

            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("留下今天的原话")
                ui.onNodeWithContentDescription("记录库").activate()
                waitFor("时间线")
                ui.waitUntil(15_000) {
                    runCatching { ui.onNode(hasScrollToIndexAction()).performScrollToKey("timeline_$recordId") }.isSuccess
                }
                ui.onNodeWithTag("library_record_$recordId").activate()
                waitFor("当时建议：盖饭")
                ui.onNodeWithText(query).assertExists()
                ui.onNodeWithText("热汤面").assertExists()
                ui.onNodeWithText("查看当时的候选与依据").performScrollTo().activate()
                waitFor("当时的建议")
                ui.waitUntil(15_000) {
                    ui.onAllNodes(hasText("建议编号 $decisionId") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
                }
                ui.onNode(hasText("首选 · 盖饭") and hasAnyAncestor(isDialog())).assertExists()
            }
        } finally {
            // Restore only the shared preference replaced by this scenario, retaining the library revision.
            db.runInTransaction {
                val library = db.libraryDao().get() ?: return@runInTransaction
                val current = DbJson.decode<ConditionSetting>(library.futureConditionJson)
                if (current?.recordId in ownRecords) {
                    db.libraryDao().put(library.copy(futureConditionJson = previousPreferenceJson))
                    db.libraryDao().advance()
                }
            }
            for (id in ownRecords.toList().asReversed()) {
                val target = DeleteTarget(DeleteTargetType.RECORD, id)
                val preview = requireNotNull(api.previewDeleteMemory(target).deletePreview)
                val deleted = api.deleteMemory(DeleteRequest(target, preview.previewId, preview.baseRevision,
                    UUID.randomUUID().toString()))
                assertTrue(deleted.message, deleted.success)
                deleted.jobId?.let { cleanup ->
                    ui.waitUntil(30_000) { api.getTask(cleanup)?.state == TaskState.COMPLETED }
                }
            }
            settings.save(originalSettings)
            api.setAutoOrganize(originalSettings.autoOrganize)
        }
    }
}
