package com.pebblepile.scheduler

import kotlin.system.exitProcess

private class MutableRevisions(var value: RevisionSnapshot?) : RevisionProvider {
    override fun currentRevision(recordId: String): RevisionSnapshot? = value
}

private class FakePipeline : AgentTaskPipeline {
    val calls = mutableListOf<TaskStage>()
    var retryStage: TaskStage? = null
    var fatalStage: TaskStage? = null
    var beforeRank: (() -> Unit)? = null
    var commitCalls = 0

    override fun runStage(
        stage: TaskStage,
        input: TaskInput,
        completed: Map<TaskStage, StageOutput>,
    ): StageOutput {
        calls += stage
        if (stage == TaskStage.RANK) beforeRank?.invoke()
        if (retryStage == stage) {
            retryStage = null
            throw RetryableTaskException("算法暂时不可用")
        }
        if (fatalStage == stage) throw IllegalStateException("算法返回非法结果")
        return StageOutput("$stage:${input.recordId}", listOf("evidence:${stage.name}"))
    }

    override fun commit(input: TaskInput, completed: Map<TaskStage, StageOutput>): CommitOutput {
        commitCalls += 1
        return CommitOutput("decision-1", listOf("evidence:commit"))
    }
}

private val baseInput = TaskInput(
    recordId = "record-1",
    revision = 7,
    preferenceRevision = 3,
    text = "加班回来只想快点吃饭，最后煮了热汤面",
    algorithmVersion = "food-rules-v1",
)

private fun fixture(): Triple<PersistentTaskCoordinator, InMemoryTaskStore, FakePipeline> {
    val revisions = MutableRevisions(RevisionSnapshot(7, 3))
    val store = InMemoryTaskStore()
    val pipeline = FakePipeline()
    var now = 1_000L
    val coordinator = PersistentTaskCoordinator(store, revisions, pipeline, now = { now }, leaseMillis = 100)
    return Triple(coordinator, store, pipeline)
}

private var passed = 0

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed += 1
        println("PASS  $name")
    } catch (cause: Throwable) {
        println("FAIL  $name")
        cause.printStackTrace()
        exitProcess(1)
    }
}

private fun expect(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

fun main() {
    test("相同输入幂等创建，偏好版本变化创建新任务") {
        val (coordinator, _, _) = fixture()
        val first = coordinator.submit(baseInput)
        val second = coordinator.submit(baseInput.copy(text = "另一段文本"))
        expect(first.jobId == second.jobId, "相同 record/revision/preference/algorithm 应复用任务")
        val newPreference = coordinator.submit(baseInput.copy(preferenceRevision = 4))
        expect(first.jobId != newPreference.jobId, "偏好变化应创建新任务")
    }

    test("完整流程按 EXTRACT→UPDATE→CONTEXT→RANK→COMMIT 执行") {
        val (coordinator, _, pipeline) = fixture()
        val submitted = coordinator.submit(baseInput)
        expect(coordinator.runJob(submitted.jobId) == TaskRunOutcome.COMPLETED, "任务应完成")
        expect(pipeline.calls == TaskStage.entries.filter { it != TaskStage.COMMIT }, "阶段顺序错误：${pipeline.calls}")
        val view = coordinator.getTask(submitted.jobId)!!
        expect(view.status == TaskStatus.COMPLETED, "状态应为 completed")
        expect(view.decisionId == "decision-1", "缺少 decisionId")
        expect(view.evidenceRefs.contains("evidence:commit"), "缺少提交证据")
        expect(view.checkpoint == TaskStage.RANK, "最后检查点应为 RANK")
    }

    test("可重试错误进入 waiting，重试复用已提交检查点") {
        val (coordinator, _, pipeline) = fixture()
        pipeline.retryStage = TaskStage.CONTEXT
        val job = coordinator.submit(baseInput)
        expect(coordinator.runJob(job.jobId) == TaskRunOutcome.WAITING, "首次应等待重试")
        var view = coordinator.getTask(job.jobId)!!
        expect(view.waitingReason == WAITING_RETRY_PENDING, "等待原因错误")
        expect(view.actions.contains(TaskAction.RETRY), "应提供重试操作")
        expect(coordinator.controlTask(job.jobId, TaskAction.RETRY).status == TaskStatus.QUEUED, "重试应重新排队")
        expect(coordinator.runJob(job.jobId) == TaskRunOutcome.COMPLETED, "重试后应完成")
        expect(pipeline.calls.count { it == TaskStage.EXTRACT } == 1, "已完成阶段不应重复执行")
        expect(pipeline.calls.count { it == TaskStage.CONTEXT } == 2, "失败阶段应重试一次")
    }

    test("运行中偏好变化会在提交前转为 stale") {
        val (coordinator, _, pipeline) = fixture()
        val revisions = MutableRevisions(RevisionSnapshot(7, 3))
        val store = InMemoryTaskStore()
        var now = 1_000L
        val local = PersistentTaskCoordinator(store, revisions, pipeline, now = { now }, leaseMillis = 100)
        pipeline.beforeRank = { revisions.value = RevisionSnapshot(7, 4) }
        val job = local.submit(baseInput)
        expect(local.runJob(job.jobId) == TaskRunOutcome.STALE, "偏好变化后旧任务应过期")
        expect(pipeline.commitCalls == 0, "过期任务不应提交")
        expect(local.getTask(job.jobId)!!.status == TaskStatus.STALE, "状态应为 stale")
    }

    test("暂停会撤销租约，旧执行者不能写入检查点") {
        val (coordinator, store, _) = fixture()
        val job = coordinator.submit(baseInput)
        val claimed = store.claim(job.jobId, 1_000L, 100L)!!
        val paused = coordinator.controlTask(job.jobId, TaskAction.PAUSE)
        expect(paused.status == TaskStatus.WAITING, "暂停后应等待")
        expect(paused.waitingReason == WAITING_USER_PAUSED, "等待原因应为 user_paused")
        expect(paused.actions.contains(TaskAction.RESUME), "应提供继续操作")
        var lost = false
        try {
            store.recordCheckpoint(job.jobId, claimed.leaseToken, TaskStage.EXTRACT, StageOutput("late"), 1_010L)
        } catch (_: LeaseLostException) {
            lost = true
        }
        expect(lost, "旧租约必须失效")
        expect(coordinator.controlTask(job.jobId, TaskAction.RESUME).status == TaskStatus.QUEUED, "继续后应重新排队")
    }

    test("进程中断后从已提交检查点恢复") {
        val revisions = MutableRevisions(RevisionSnapshot(7, 3))
        val store = InMemoryTaskStore()
        val pipeline = FakePipeline()
        var now = 1_000L
        val coordinator = PersistentTaskCoordinator(store, revisions, pipeline, now = { now }, leaseMillis = 10L)
        val job = coordinator.submit(baseInput)
        val claimed = store.claim(job.jobId, now, 10L)!!
        store.recordCheckpoint(job.jobId, claimed.leaseToken, TaskStage.EXTRACT, StageOutput("saved-extract"), 1_002L)
        store.recordCheckpoint(job.jobId, claimed.leaseToken, TaskStage.UPDATE, StageOutput("saved-update"), 1_004L)
        now = 1_020L
        expect(coordinator.runJob(job.jobId) == TaskRunOutcome.COMPLETED, "过期租约应恢复并完成")
        expect(pipeline.calls.first() == TaskStage.CONTEXT, "恢复时应跳过已提交的 EXTRACT/UPDATE")
        expect(pipeline.calls.contains(TaskStage.EXTRACT).not(), "不应重复执行抽取")
        expect(pipeline.calls.contains(TaskStage.UPDATE).not(), "不应重复执行更新")
    }
    test("同一活动租约不能被重复领取") {
        val (coordinator, store, _) = fixture()
        val job = coordinator.submit(baseInput)
        expect(store.claim(job.jobId, 1_000L, 100L) != null, "首次领取应成功")
        expect(store.claim(job.jobId, 1_005L, 100L) == null, "活动租约不应重复领取")
    }

    test("过期租约可以被恢复执行者重新领取") {
        val (coordinator, store, _) = fixture()
        val job = coordinator.submit(baseInput)
        val first = store.claim(job.jobId, 1_000L, 10L)!!
        expect(store.claim(job.jobId, 1_005L, 100L) == null, "未过期前不能抢占")
        val recovered = store.claim(job.jobId, 1_011L, 100L)!!
        expect(recovered.leaseToken > first.leaseToken, "恢复应递增租约令牌")
        expect(recovered.attempts == 2, "恢复应记录尝试次数")
    }

    test("取消后任务进入终态并可查询") {
        val (coordinator, _, _) = fixture()
        val job = coordinator.submit(baseInput)
        val view = coordinator.controlTask(job.jobId, TaskAction.CANCEL)
        expect(view.status == TaskStatus.CANCELLED, "取消状态错误")
        expect(view.actions.isEmpty(), "终态不应有操作")
    }

    test("不可重试异常保留 failed 并可手动重试") {
        val (coordinator, _, pipeline) = fixture()
        pipeline.fatalStage = TaskStage.UPDATE
        val job = coordinator.submit(baseInput)
        expect(coordinator.runJob(job.jobId) == TaskRunOutcome.FAILED, "应进入 failed")
        val failed = coordinator.getTask(job.jobId)!!
        expect(failed.retryable, "失败应允许人工重试")
        expect(failed.actions.contains(TaskAction.RETRY), "应提供重试操作")
        pipeline.fatalStage = null
        coordinator.controlTask(job.jobId, TaskAction.RETRY)
        expect(coordinator.runJob(job.jobId) == TaskRunOutcome.COMPLETED, "人工重试后应完成")
    }

    test("runNext 只领取一个排队任务并完成") {
        val (coordinator, store, _) = fixture()
        val first = coordinator.submit(baseInput)
        coordinator.submit(baseInput.copy(recordId = "record-2"))
        expect(store.list().size == 2, "应有两个任务")
        expect(coordinator.runNext() == TaskRunOutcome.COMPLETED, "runNext 应完成一个任务")
        expect(store.list().count { it.status == TaskStatus.COMPLETED } == 1, "一次只应完成一个任务")
        expect(coordinator.getTask(first.jobId)!!.status != TaskStatus.QUEUED, "最老任务应被领取")
    }

    test("输入修订变化会拒绝旧任务") {
        val revisions = MutableRevisions(RevisionSnapshot(8, 3))
        val store = InMemoryTaskStore()
        val pipeline = FakePipeline()
        val coordinator = PersistentTaskCoordinator(store, revisions, pipeline, now = { 2_000L })
        val job = coordinator.submit(baseInput)
        expect(coordinator.runJob(job.jobId) == TaskRunOutcome.STALE, "记录修订变化应使任务 stale")
        expect(coordinator.getTask(job.jobId)!!.status == TaskStatus.STALE, "状态应为 stale")
    }

    test("检查点只允许当前阶段写入") {
        val (coordinator, store, _) = fixture()
        val job = coordinator.submit(baseInput)
        val claimed = store.claim(job.jobId, 1_000L, 100L)!!
        var rejected = false
        try {
            store.recordCheckpoint(job.jobId, claimed.leaseToken, TaskStage.RANK, StageOutput("skip"), 1_010L)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        expect(rejected, "不能跳过前置阶段")
    }

    println("\n${passed} scheduler tests passed")
}

