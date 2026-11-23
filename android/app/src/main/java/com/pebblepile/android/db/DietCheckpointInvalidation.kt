package com.pebblepile.android.db

import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.DeletionObject
import com.pebblepile.scheduler.StageSource
import com.pebblepile.scheduler.TaskJob
import com.pebblepile.scheduler.TaskStatus
import java.time.Instant

/** Revoke materialized cross-record outputs inside the source isolation transaction. */
internal fun invalidateDietCheckpoints(database: PebbleDatabase, removed: Set<DeletionObject>): Set<String> {
    val deletedRecords = removed.filter { it.type == "record" }.map { it.id }.toSet()
    fun lost(source: StageSource, consumer: String) =
        source.recordId in deletedRecords ||
            DeletionObject("source", source.sourceId) in removed ||
            DeletionObject("source_version", source.sourceId, source.sourceVersion) in removed ||
            DeletionObject("record_source", source.sourceId, source.sourceVersion, consumer) in removed

    val changed = mutableSetOf<String>()
    val dao = database.schedulerDao()
    var after: String? = null
    while (true) {
        val rows = dao.nextSnapshots(after)
        if (rows.isEmpty()) return changed
        for (row in rows) {
            val job = checkNotNull(DbJson.decode<TaskJob>(row.snapshotJson))
            val firstLost = job.checkpoints.filterValues { output ->
                output.conditionRecordId in deletedRecords ||
                    output.inputSources.orEmpty().any { lost(it, job.recordId) }
            }.keys.minByOrNull { it.sequence } ?: continue
            val taskRow = checkNotNull(database.taskDao().get(job.jobId))
            val task = checkNotNull(DbJson.decode<CoreTask>(taskRow.taskJson))
            val retained = job.checkpoints.filterKeys { it.sequence < firstLost.sequence }
            val last = retained.maxByOrNull { it.key.sequence }
            val state = if (task.state == TaskState.CANCELLED) TaskState.CANCELLED else TaskState.STALE
            val now = Instant.now()
            val clean = job.copy(
                status = TaskStatus.valueOf(state.name), currentStage = firstLost, checkpoints = retained,
                decisionId = null, evidenceRefs = emptyList(), waitingReason = null, retryable = false,
                leaseToken = job.leaseToken + 1, leaseUntil = 0, updatedAt = now.toEpochMilli(),
            )
            dao.put(row.copy(snapshotJson = DbJson.encode(clean)))
            val phase = last?.key?.name?.lowercase()
            val updated = task.copy(state = state, phase = phase, waitReason = null, revision = task.revision + 1)
            database.taskDao().put(taskRow.copy(taskJson = DbJson.encode(updated), state = state.name, revision = updated.revision))
            if (last == null) {
                database.checkpointDao().remove(job.jobId)
            } else {
                database.checkpointDao().put(TaskCheckpointEntity(job.jobId, requireNotNull(phase),
                    DbJson.encode(last.value), now.toString()))
            }
            changed += job.jobId
        }
        after = rows.last().jobId
    }
}
