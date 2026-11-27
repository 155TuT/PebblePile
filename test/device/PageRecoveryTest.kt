package com.pebblepile.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertTextContains
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.ItemStatus
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.service.CoreApi
import com.pebblepile.android.service.RecordQueryResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PageRecoveryTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val core get() = ApplicationProvider.getApplicationContext<PebblePileApplication>().coreApi

    @Test fun retryingInterruptedSaveReusesTheRequestAndDraft() {
        val ids = CopyOnWriteArrayList<String>()
        val recovering = object : CoreApi by core {
            override suspend fun recordExperience(text: String, captureMethod: String, operationId: String,
                recordId: String?, baseRevision: Long?): ApiReceipt {
                ids.add(operationId)
                if (ids.size == 1) throw IOException("读取回执失败")
                return ApiReceipt(true, recordId = "local-record")
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PebbleComponentTheme {
                        HomeEntryScreen(recovering, "同一份草稿", {}, PebbleCaptureMode.Record, {},
                            onOpenRecord = {}, onMessage = { _, _ -> }, onSaved = {})
                    }
                }
            }
            ui.onNodeWithContentDescription("保存").performClick()
            waitForText("重试保存")
            ui.onNode(hasSetTextAction()).assertTextContains("同一份草稿")
            ui.onNodeWithText("重试保存").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("已保存 · local-record")
            assertEquals(2, ids.size)
            assertEquals(ids[0], ids[1])
        }
    }

    @Test fun retryingLibraryReadClearsAnUnavailableStatusAndShowsTheRecord() {
        val calls = AtomicInteger(0)
        val record = PebbleRecord("id", "记录库重读原话", "2026-11-21T12:00:00", "app_home_record")
        val recovering = object : CoreApi by core {
            override fun queryRecords(tags: Set<String>): RecordQueryResult {
                if (calls.incrementAndGet() == 1) throw IOException("暂时无法读取")
                return RecordQueryResult(listOf(record), 1)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var status by remember { mutableStateOf<ItemStatus?>(ItemStatus.WAITING) }
                    PebbleComponentTheme {
                        LibraryScreen(recovering, 0, PebbleLibraryView.Timeline, {}, "", {}, emptySet(), {},
                            null, {}, status, { status = it }, {}, { _, _ -> })
                    }
                }
            }
            waitForText("重新读取")
            ui.onNodeWithText("重新读取").performSemanticsAction(SemanticsActions.OnClick)
            ui.waitUntil(10_000) { ui.onAllNodesWithText("待开始").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("filter_status_all").assertIsOn()
            ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(record.rawText))
            assertEquals(2, calls.get())
        }
    }

    private fun waitForText(text: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
