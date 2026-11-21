package com.pebblepile.android.db

import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.DeletionAuthority
import com.pebblepile.android.repository.DeletionItem
import com.pebblepile.android.repository.DeletionJobAccess
import com.pebblepile.android.repository.DeletionLink
import com.pebblepile.android.repository.DeletionLinkKind
import com.pebblepile.android.repository.DeletionObject
import com.pebblepile.android.repository.DeletionPermission
import com.pebblepile.android.repository.DeletionPlan
import com.pebblepile.android.repository.DeletionReader
import com.pebblepile.android.repository.DeletionTraversal
import com.pebblepile.android.repository.DeletionWriter

/**
 * Room-backed deletion capability, bound to the enclosing CoreRepository write transaction.
 *
 * The dependency graph is derived from persisted records, sources and tasks rather than
 * hard-coded fixtures (as in the contract test's in-memory repository). For a single-user local
 * library, authority is fixed and every in-scope object is allowed; tombstones are persisted so
 * readers omit isolated rows after reopen.
 */
internal class RoomDeletionWriter(private val database: PebbleDatabase) : DeletionWriter {

    override fun authority(clientId: String): DeletionAuthority = DeletionAuthority(
        authorityInstanceId = "local-demo",
        libraryId = "library-local",
        generation = "current",
        principalId = "owner",
        authorizationRevision = 1,
    )

    override fun allows(
        authority: DeletionAuthority,
        permission: DeletionPermission,
        objects: Set<DeletionObject>
    ): Boolean = authority.principalId == "owner"

    override fun isDeleted(ref: DeletionObject): Boolean =
        database.deletionDao().marker(DeletionKeys.of(ref)) != null

    override fun item(ref: DeletionObject): DeletionItem? = when (ref.type) {
        "record" -> database.recordDao().get(ref.id)?.let { DeletionItem(ref, it.revision) }
        "source" -> {
            val versions = database.sourceDao().all().filter { it.sourceId == ref.id }
            versions.firstOrNull()?.let { DeletionItem(ref, 1) }
        }
        "source_version" -> database.sourceDao().get(ref.id, ref.version ?: -1)
            ?.let { DeletionItem(ref, 1) }
        "job" -> database.taskDao().get(ref.id)?.let { DeletionItem(ref, it.revision) }
        else -> null
    }

    override fun dependents(ref: DeletionObject, traversal: DeletionTraversal): List<DeletionLink> {
        val links = mutableListOf<DeletionLink>()
        when (ref.type) {
            "record" -> {
                // Owned source associations and exact versions of self-owned sources.
                if (traversal == DeletionTraversal.FULL_OBJECT) {
                    database.sourceDao().forRecord(ref.id).forEach { source ->
                        links += DeletionLink(
                            DeletionItem(
                                DeletionObject("source_version", source.sourceId, source.sourceVersion),
                                1,
                            ),
                            DeletionLinkKind.REMOVE_WITH_INPUT,
                        )
                    }
                    database.taskDao().forRecord(ref.id).forEach { task ->
                        links += DeletionLink(
                            DeletionItem(DeletionObject("job", task.id), task.revision),
                            DeletionLinkKind.ACTIVE_JOB,
                        )
                    }
                }
            }
            "source" -> {
                database.sourceDao().all().filter { it.sourceId == ref.id }.forEach { version ->
                    links += DeletionLink(
                        DeletionItem(
                            DeletionObject("source_version", version.sourceId, version.sourceVersion),
                            1,
                        ),
                        DeletionLinkKind.REMOVE_WITH_INPUT,
                    )
                }
            }
            "source_version" -> {
                // Other records referencing this exact version see a changed projection.
                val sourceId = ref.id
                val version = ref.version
                database.sourceDao().all()
                    .filter { it.sourceId == sourceId && it.sourceVersion == version && it.recordId != ref.relatedId }
                    .forEach { reference ->
                        val record = database.recordDao().get(reference.recordId)
                        links += DeletionLink(
                            DeletionItem(
                                DeletionObject("record", reference.recordId),
                                record?.revision ?: 1,
                            ),
                            DeletionLinkKind.RECORD_VIEW,
                        )
                    }
            }
        }
        return links.filterNot { isDeleted(it.item.ref) }
    }

    override fun supportGroups(ref: DeletionObject): List<Set<DeletionObject>> {
        // A source version has independent support from the record that owns it.
        return when (ref.type) {
            "source_version" -> database.sourceDao().get(ref.id, ref.version ?: -1)
                ?.let { listOf(setOf(DeletionObject("record", it.recordId))) }
                ?: emptyList()
            else -> emptyList()
        }
    }

    override fun sourceReference(recordId: String, source: SourceRef): DeletionItem? {
        val found = database.sourceDao().forRecord(recordId)
            .firstOrNull { it.sourceId == source.sourceId && it.sourceVersion == source.sourceVersion }
            ?: return null
        if (isDeleted(DeletionObject("source_version", source.sourceId, source.sourceVersion))) return null
        return DeletionItem(
            DeletionObject("record_source", source.sourceId, source.sourceVersion, recordId),
            1L,
        )
    }

    override fun cleanupAccess(jobId: String): DeletionJobAccess? =
        database.deletionDao().job(jobId)?.let { DbJson.decode<DeletionJobAccess>(it.accessJson) }

    override fun isolate(plan: DeletionPlan, deletionRevision: Long): Boolean {
        plan.remove.forEach { item ->
            database.deletionDao().putMarker(
                DeletionMarkerEntity(
                    key = DeletionKeys.of(item.ref),
                    type = item.ref.type,
                    id = item.ref.id,
                    version = item.ref.version,
                    relatedId = item.ref.relatedId,
                    deletedRevision = deletionRevision,
                )
            )
        }
        // Surviving records whose projection changes have their revision bumped.
        plan.invalidate.filter { it.ref.type == "record" }.forEach { item ->
            database.recordDao().get(item.ref.id)?.let { entity ->
                val record = DbJson.decode<com.pebblepile.android.model.PebbleRecord>(entity.recordJson)
                if (record != null) {
                    val redacted = record.copy(revision = record.revision + 1)
                    database.recordDao().put(entity.copy(recordJson = DbJson.encode(redacted), revision = redacted.revision))
                }
            }
        }
        plan.cancel.forEach { item ->
            database.taskDao().get(item.ref.id)?.let { entity ->
                val task = DbJson.decode<CoreTask>(entity.taskJson)
                if (task != null) {
                    val cancelled = task.copy(state = TaskState.CANCELLED, revision = task.revision + 1)
                    database.taskDao().put(entity.copy(taskJson = DbJson.encode(cancelled), state = cancelled.state.name, revision = cancelled.revision))
                }
            }
        }
        return true
    }

    override fun putCleanupTask(task: CoreTask, plan: DeletionPlan, access: DeletionJobAccess) {
        database.taskDao().put(
            TaskEntity(
                id = task.id,
                taskJson = DbJson.encode(task),
                recordId = task.recordId,
                kind = task.kind.name,
                state = task.state.name,
                revision = task.revision,
                inputRevision = task.inputRevision,
            )
        )
        database.deletionDao().putJob(
            DeletionJobEntity(
                jobId = task.id,
                planJson = DbJson.encode(plan),
                accessJson = DbJson.encode(access),
            )
        )
    }
}
