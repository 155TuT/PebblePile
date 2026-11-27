package com.pebblepile.scheduler.work

import androidx.work.ListenableWorker.Result
import com.pebblepile.scheduler.TaskRunOutcome
import com.pebblepile.scheduler.WAITING_RETRY_PENDING
import com.pebblepile.scheduler.WAITING_USER_PAUSED
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerResultTest {
    @Test
    fun busyAndRetryableWaitScheduleAnotherAttempt() {
        assertEquals(Result.retry(), workerResult(TaskRunOutcome.BUSY, null))
        assertEquals(Result.retry(), workerResult(TaskRunOutcome.WAITING, WAITING_RETRY_PENDING))
    }

    @Test
    fun userPauseAndCancelledJobFinishTheWakeUp() {
        assertEquals(Result.success(), workerResult(TaskRunOutcome.WAITING, WAITING_USER_PAUSED))
        assertEquals(Result.success(), workerResult(TaskRunOutcome.CANCELLED, null))
        assertEquals(Result.failure(), workerResult(TaskRunOutcome.FAILED, null))
    }
}
