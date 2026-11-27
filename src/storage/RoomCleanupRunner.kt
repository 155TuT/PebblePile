package com.pebblepile.android.db

import com.pebblepile.android.model.TaskKind
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.DeletionObject
import com.pebblepile.android.repository.DeletionPlan
import com.pebblepile.android.repository.RepositoryWriteException

/** Advances one durable step per call. A second worker observes the checkpoint of the first. */
internal class RoomCleanupRunner(private val database: PebbleDatabase) {
    private val repository = RoomCoreRepository(database)

    fun advance(jobId: String): Boolean {
        var observedRevision: Long? = null
        return try {
            repository.write { tx ->
                val task = tx.task(jobId) ?: return@write true
                require(task.kind == TaskKind.DELETE_CLEANUP)
                if (task.state == TaskState.COMPLETED) return@write true
                observedRevision = task.revision
                val job = checkNotNull(database.deletionDao().job(jobId))
                val plan = checkNotNull(DbJson.decode<DeletionPlan>(job.planJson))
                // Older file databases may only store the initial phase on the business task.
                val checkpoint = database.checkpointDao().get(jobId)?.stage ?: task.phase
                val next = runStage(checkpoint, plan)
                val done = next == "complete"
                tx.putTask(task.copy(
                    phase = next, state = if (done) TaskState.COMPLETED else TaskState.RUNNING,
                    waitReason = null, revision = task.revision + 1,
                ))
                tx.advanceRevision()
                done
            }
        } catch (failure: RepositoryWriteException) {
            // A failed step rolled back both the purge and its checkpoint.
            try {
                repository.write { tx ->
                    val task = tx.task(jobId)
                    if (task != null && task.revision == observedRevision && task.state != TaskState.WAITING) {
                        tx.putTask(task.copy(
                            state = TaskState.WAITING, waitReason = "retry_pending", revision = task.revision + 1,
                        ))
                        tx.advanceRevision()
                    }
                }
            } catch (unavailable: RepositoryWriteException) {
                failure.addSuppressed(unavailable)
            }
            throw failure
        }
    }

    private fun runStage(checkpoint: String?, plan: DeletionPlan): String = when (checkpoint) {
        "isolated" -> {
            // Scan only the exact versions in this plan. Unlinking never erases the owner's source.
            for ((ref) in plan.remove) {
                when (ref.type) {
                    "source_version" -> {
                        requireMarker(ref)
                        database.sourceDao().delete(ref.id, requireNotNull(ref.version))
                    }
                    // The owning record stores these links in its projection; isolation removed them.
                    "source", "record_source" -> requireMarker(ref)
                    "record" -> Unit
                    else -> error("Unsupported cleanup object: ${ref.type}")
                }
            }
            "sources_removed"
        }
        "sources_removed" -> {
            for ((ref) in plan.remove) {
                if (ref.type == "record") {
                    requireMarker(ref)
                    database.recordDao().delete(ref.id)
                }
            }
            "records_removed"
        }
        "records_removed" -> {
            val store = RoomTaskStore(database)
            for (item in plan.remove + plan.invalidate) {
                if (item.ref.type == "record") {
                    store.purgeIsolatedInputs(item.ref.id, item.revision)
                }
            }
            "complete"
        }
        else -> error("Unknown cleanup checkpoint: $checkpoint")
    }

    private fun requireMarker(ref: DeletionObject) {
        check(database.deletionDao().isDeleted(ref.type, ref.id, ref.version, ref.relatedId)) {
            "Missing isolation marker for ${ref.type}"
        }
    }
}
