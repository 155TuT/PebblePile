package com.pebblepile.android.db

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pebblepile.android.PebblePileApplication
import com.pebblepile.android.algorithm.AlgorithmEngine
import com.pebblepile.android.algorithm.dietRankCheckpoint
import com.pebblepile.android.algorithm.DietTaskOutcome
import com.pebblepile.scheduler.TaskStage
import com.pebblepile.scheduler.work.PersistentTaskWorker
import com.pebblepile.android.model.CorrectionScope
import kotlinx.coroutines.runBlocking
import com.pebblepile.android.model.DeleteRequest
import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeleteTargetType
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.service.CaptureRequest
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkDispatchIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<PebblePileApplication>()
    private val api get() = app.coreApi

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(label, condition())
    }

    private fun workSucceeded(jobId: String): Boolean =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork("pebblepile-task-$jobId")
            .get(5, TimeUnit.SECONDS).any { it.state == WorkInfo.State.SUCCEEDED }

    @Test fun dinnerQueryPersistsRankedCandidatesAndSurvivesAnotherSystemWake() {
        api.setAutoOrganize(true)
        val token = UUID.randomUUID().toString()
        val monday = "🍜今天周一加班到八点半，晚饭预算二十元。我吃了十八元的热汤面，十分钟就吃上了，不用洗锅。"
        val wednesday = "今天周三又加班了，晚饭还是想控制在二十元以内。我吃了十六元的盖饭，不用准备食材，也不用洗锅，省事让我很满意。"
        val captures = listOf(monday to "app_home_record", wednesday to "android_share").mapIndexed { index, (text, entry) ->
            val saved = api.recordExperience(CaptureRequest(text, entry, "$token-history-$index"))
            assertTrue(saved.success)
            await("经历整理完成") { api.getTask(requireNotNull(saved.jobId))?.state == TaskState.COMPLETED }
            saved to text
        }
        val owner = requireNotNull(captures.last().first.recordId)
        val detail = requireNotNull(api.getRecordDetail(owner))
        val corrected = runBlocking {
            api.reviseMemory(owner, detail.record.revision, "以后只在工作日加班时，优先给我推荐省事的晚饭。",
                CorrectionScope.FUTURE, "$token-condition", detail.futureCondition?.revision ?: 0)
        }
        assertTrue(corrected.success)
        val query = CaptureRequest("今天周四加班，预算二十元，晚饭吃什么？请说明依据，并指出来自哪两次经历的原话。",
            "app_home_dialog", "$token-query")
        val saved = api.recordExperience(query)
        val id = requireNotNull(saved.jobId)
        assertEquals(saved, api.recordExperience(query))
        await("晚餐提问的排序已提交") { workSucceeded(id) && api.getTask(id)?.state == TaskState.COMPLETED }

        fun readJob() = PebbleDatabase.build(app, "pebblepile.sqlite").let { db ->
            try { requireNotNull(RoomTaskStore(db).get(id)) } finally { db.close() }
        }
        val before = readJob()
        assertEquals(setOf(TaskStage.EXTRACT, TaskStage.UPDATE, TaskStage.CONTEXT, TaskStage.RANK), before.checkpoints.keys)
        val rank = before.checkpoints.getValue(TaskStage.RANK).dietRankCheckpoint()
        assertEquals(DietTaskOutcome.RANKED, rank.outcome)
        assertEquals(AlgorithmEngine().manifest().algorithmVersion, rank.manifest.algorithmVersion)
        assertEquals(0, rank.manifest.inferenceCalls)
        assertEquals(20, rank.constraint?.budgetCny)
        val result = requireNotNull(rank.result)
        assertTrue(result.items.isNotEmpty())
        assertTrue("hotpot" in result.filteredOut)
        assertTrue(result.items.none { it.candidateId == "hotpot" })
        val evidence = result.items.flatMap { it.evidence }
        for ((receipt, text) in captures) {
            val source = requireNotNull(receipt.source)
            val supportingQuotes = evidence.filter { it.source.sourceId == source.sourceId && it.source.sourceVersion == source.sourceVersion }
            assertTrue("每次独立经历都有可回查的原文", supportingQuotes.isNotEmpty())
            val overtimeQuote = if (text == monday) "加班到八点半" else "又加班了"
            assertTrue(supportingQuotes.any { it.role == "overtime" && it.span.quoteIn(text) == overtimeQuote })
        }
        assertTrue(before.decisionId?.isNotBlank() == true)

        val manager = WorkManager.getInstance(app)
        val name = "pebblepile-task-$id"
        val prior = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).map { it.id }.toSet()
        PersistentTaskWorker.enqueue(app, id).result.get(5, TimeUnit.SECONDS)
        await("再次系统唤醒同一任务完成") {
            manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
                .any { it.id !in prior && it.state == WorkInfo.State.SUCCEEDED }
        }
        assertEquals(before, readJob())
        assertEquals(listOf(id), api.getRecordDetail(requireNotNull(saved.recordId))?.jobIds)
        assertEquals(saved, api.recordExperience(query))
    }

    @Test fun originalReceiptsAddressTheWorkersThatOrganizeAndCleanTheRecord() {
        api.setAutoOrganize(true)
        val token = UUID.randomUUID().toString()
        val request = CaptureRequest("后台整理联调 $token", "android_share", "capture-$token")
        val receipt = api.recordExperience(request)
        assertTrue(receipt.success)
        val recordId = requireNotNull(receipt.recordId)
        val organize = requireNotNull(receipt.jobId)
        val source = requireNotNull(receipt.source)
        assertEquals(receipt, api.recordExperience(request))
        await("整理 Worker 完成最初回执中的任务") {
            api.getTask(organize)?.state == TaskState.COMPLETED && workSucceeded(organize)
        }
        assertEquals(listOf(organize), requireNotNull(api.getRecordDetail(recordId)).jobIds)
        val target = DeleteTarget(DeleteTargetType.RECORD, recordId)
        val preview = requireNotNull(api.previewDeleteMemory(target).deletePreview)
        val deletion = DeleteRequest(target, preview.previewId, preview.baseRevision, "delete-$token")
        val deleted = api.deleteMemory(deletion)
        assertTrue(deleted.success)
        val cleanup = requireNotNull(deleted.jobId)
        assertNull(api.getRecordDetail(recordId))
        await("清理 Worker 提交最终检查点") {
            api.getTask(cleanup)?.state == TaskState.COMPLETED && workSucceeded(cleanup)
        }
        val finished = requireNotNull(api.getTask(cleanup))
        assertEquals("complete", finished.phase)
        assertEquals(TaskState.STALE, api.getTask(organize)?.state)
        assertEquals(deleted, api.deleteMemory(deletion))
        assertEquals(finished, api.getTask(cleanup))

        val db = PebbleDatabase.build(app, "pebblepile.sqlite")
        try {
            assertNull(db.recordDao().get(recordId))
            assertNull(db.sourceDao().get(source.sourceId, source.sourceVersion))
            assertEquals("complete", db.checkpointDao().get(cleanup)?.stage)
            assertEquals(setOf(organize, cleanup), db.taskDao().forRecord(recordId).map { it.id }.toSet())
        } finally {
            db.close()
        }
    }
}
