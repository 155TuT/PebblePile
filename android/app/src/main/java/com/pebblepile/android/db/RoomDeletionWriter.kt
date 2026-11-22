package com.pebblepile.android.db

import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.TaskKind
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
import com.pebblepile.android.repository.DeletionTraversal
import com.pebblepile.android.repository.DeletionWriter

/** Dependency traversal and projection changes share the enclosing SQLite transaction. */
internal class RoomDeletionWriter(
    private val database: PebbleDatabase,
    private val saveTask: (CoreTask) -> Unit,
) : DeletionWriter {

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
    ): Boolean = authority == authority("")

    override fun isDeleted(ref: DeletionObject): Boolean =
        database.deletionDao().marker(DeletionKeys.of(ref)) != null

    override fun item(ref: DeletionObject): DeletionItem? {
        if (isDeleted(ref)) return null
        val revision = when (ref.type) {
            "record" -> database.recordDao().visible(ref.id)?.revision
            "source" -> database.sourceDao().all().filter { it.sourceId == ref.id && !isDeleted(it.versionRef) }
                .maxOfOrNull { it.sourceVersion }
            "source_version" -> database.sourceDao().get(ref.id, ref.version ?: return null)?.sourceVersion
            "job" -> database.taskDao().get(ref.id)?.revision
            else -> null
        }
        return revision?.let { DeletionItem(ref, it) }
    }

    private val SourceVersionEntity.versionRef
        get() = DeletionObject("source_version", sourceId, sourceVersion)

    private fun PebbleRecord.references(source: SourceRef) =
        tags.any { it.sourceRef == source } || suggestions.any { entry -> entry.evidence.any { it.sourceRef == source } }

    override fun dependents(ref: DeletionObject, traversal: DeletionTraversal): List<DeletionLink> = buildList {
        fun link(target: DeletionObject, revision: Long, kind: DeletionLinkKind) {
            if (!isDeleted(target)) add(DeletionLink(DeletionItem(target, revision), kind))
        }
        fun view(recordId: String) {
            database.recordDao().visible(recordId)?.let {
                link(DeletionObject("record", it.id), it.revision, DeletionLinkKind.RECORD_VIEW)
            }
        }
        when (ref.type) {
            "record" -> {
                if (traversal == DeletionTraversal.FULL_OBJECT) {
                    val versions = database.sourceDao().visibleSources(ref.id)
                    for (source in versions) {
                        link(source.versionRef, source.sourceVersion, DeletionLinkKind.REMOVE_WITH_INPUT)
                    }
                    for (sourceId in versions.map { it.sourceId }.distinct()) {
                        val live = database.sourceDao().all().filter { it.sourceId == sourceId && !isDeleted(it.versionRef) }
                        if (live.isNotEmpty() && live.all { it.recordId == ref.id }) {
                            link(DeletionObject("source", sourceId), live.maxOf { it.sourceVersion }, DeletionLinkKind.REMOVE_WITH_INPUT)
                        }
                    }
                }
                for (row in database.taskDao().forRecord(ref.id)) {
                    val task = checkNotNull(DbJson.decode<CoreTask>(row.taskJson))
                    if (task.kind != TaskKind.DELETE_CLEANUP && task.allowedActions.isNotEmpty()) {
                        link(DeletionObject("job", task.id), task.revision, DeletionLinkKind.ACTIVE_JOB)
                    }
                }
            }
            "source" -> database.sourceDao().all().filter { it.sourceId == ref.id }.forEach {
                link(it.versionRef, it.sourceVersion, DeletionLinkKind.REMOVE_WITH_INPUT)
            }
            "source_version" -> {
                val source = SourceRef(ref.id, requireNotNull(ref.version))
                val owner = database.sourceDao().get(source.sourceId, source.sourceVersion)?.recordId
                if (owner != null) view(owner)
                for (row in database.recordDao().visibleRecords()) {
                    val record = checkNotNull(DbJson.decode<PebbleRecord>(row.recordJson))
                    if (row.id != owner && record.references(source)) {
                        link(DeletionObject("record_source", ref.id, ref.version, row.id),
                            row.revision, DeletionLinkKind.REMOVE_WITH_INPUT)
                    }
                }
            }
            "record_source" -> ref.relatedId?.let(::view)
        }
    }

    // This schema stores captures and record projections. Separate algorithm artifacts will
    // supply their own support groups when they enter the repository.
    override fun supportGroups(ref: DeletionObject): List<Set<DeletionObject>> = emptyList()

    override fun sourceReference(recordId: String, source: SourceRef): DeletionItem? {
        val row = database.recordDao().visible(recordId) ?: return null
        val owner = database.sourceDao().get(source.sourceId, source.sourceVersion) ?: return null
        val ref = DeletionObject("record_source", source.sourceId, source.sourceVersion, recordId)
        if (owner.recordId == recordId || isDeleted(owner.versionRef) || isDeleted(ref)) return null
        val record = checkNotNull(DbJson.decode<PebbleRecord>(row.recordJson))
        return DeletionItem(ref, row.revision).takeIf { record.references(source) }
    }

    override fun cleanupAccess(jobId: String): DeletionJobAccess? =
        database.deletionDao().job(jobId)?.let { DbJson.decode<DeletionJobAccess>(it.accessJson) }

    override fun isolate(plan: DeletionPlan, deletionRevision: Long): Boolean {
        val removed = plan.remove.map { it.ref }.toSet()
        for (ref in removed) {
            database.deletionDao().putMarker(DeletionMarkerEntity(
                key = DeletionKeys.of(ref), type = ref.type, id = ref.id, version = ref.version,
                relatedId = ref.relatedId, deletedRevision = deletionRevision,
            ))
        }
        val changedRecords = plan.invalidate.filter { it.ref.type == "record" }.map { it.ref.id }.toSet()
        for (id in changedRecords) redactRecord(id, removed)
        val cancelledJobs = plan.cancel.map { it.ref.id }.toSet()
        for (item in plan.cancel) {
            val row = checkNotNull(database.taskDao().get(item.ref.id))
            val task = checkNotNull(DbJson.decode<CoreTask>(row.taskJson))
            val cancelled = task.copy(state = TaskState.CANCELLED, waitReason = null, revision = task.revision + 1)
            database.taskDao().put(row.copy(taskJson = DbJson.encode(cancelled), state = cancelled.state.name,
                revision = cancelled.revision))
        }
        val deletedRecords = removed.filter { it.type == "record" }.map { it.id }.toSet()
        database.libraryDao().get()?.let { library ->
            val setting = DbJson.decode<ConditionSetting>(library.futureConditionJson)
            if (setting?.recordId in deletedRecords) database.libraryDao().put(library.copy(futureConditionJson = null))
        }
        for (row in database.receiptDao().all()) {
            val old = checkNotNull(DbJson.decode<ApiReceipt>(row.receiptJson))
            val updated = old.copy(
                title = old.title.takeUnless { old.recordId in deletedRecords },
                currentRecord = old.currentRecord?.takeUnless { it.id in deletedRecords || it.id in changedRecords },
                currentPreference = old.currentPreference?.takeUnless { it.recordId in deletedRecords },
                source = old.source?.takeUnless { DeletionObject("source_version", it.sourceId, it.sourceVersion) in removed },
                task = old.task?.let { task ->
                    if (task.id in cancelledJobs) database.taskDao().get(task.id)?.let { DbJson.decode<CoreTask>(it.taskJson) }
                    else task
                },
            )
            if (updated != old) database.receiptDao().put(row.copy(receiptJson = DbJson.encode(updated)))
        }
        return true
    }

    private fun redactRecord(id: String, removed: Set<DeletionObject>) {
        val row = checkNotNull(database.recordDao().get(id))
        val old = checkNotNull(DbJson.decode<PebbleRecord>(row.recordJson))
        fun loses(source: SourceRef?) = source != null && (
            DeletionObject("source_version", source.sourceId, source.sourceVersion) in removed ||
                DeletionObject("record_source", source.sourceId, source.sourceVersion, id) in removed
            )

        val owned = database.sourceDao().forRecord(id)
        val surviving = database.sourceDao().visibleSources(id)
        val losesPrimary = owned.any { it.versionRef in removed && it.text == old.rawText } &&
            surviving.none { it.text == old.rawText }
        val replacement = if (losesPrimary) surviving.firstOrNull()?.text.orEmpty() else old.rawText
        // Android's capture decorator derives the initial title and @people from the primary text.
        fun captureTitle(text: String): String = text.substring(0,
            text.offsetByCodePoints(0, minOf(18, text.codePointCount(0, text.length))))
        val updated = old.copy(
            revision = old.revision + 1,
            title = if (losesPrimary && old.title == captureTitle(old.rawText)) captureTitle(replacement) else old.title,
            people = if (losesPrimary) old.people.filter { person ->
                surviving.any { source -> Regex("""@([\p{L}\p{N}_]{1,20})""").findAll(source.text)
                    .any { it.groupValues[1] == person } }
            } else old.people,
            rawText = replacement,
            tags = old.tags.filterNot { loses(it.sourceRef) || (losesPrimary && it.sourceRef == null) },
            // The title and reason of a suggestion can repeat a removed quote.
            suggestions = old.suggestions.filterNot { suggestion ->
                suggestion.evidence.any { loses(it.sourceRef) } || (losesPrimary && suggestion.evidence.isEmpty())
            },
        )
        database.recordDao().put(row.copy(recordJson = DbJson.encode(updated), revision = updated.revision, title = updated.title))
    }

    override fun putCleanupTask(task: CoreTask, plan: DeletionPlan, access: DeletionJobAccess) {
        saveTask(task)
        database.deletionDao().putJob(
            DeletionJobEntity(
                jobId = task.id,
                planJson = DbJson.encode(plan),
                accessJson = DbJson.encode(access),
            )
        )
    }
}
