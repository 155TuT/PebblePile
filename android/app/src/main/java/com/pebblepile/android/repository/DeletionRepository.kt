package com.pebblepile.android.repository

import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.SourceRef

/** Stable reference or exact version. For a record_source, relatedId is the referencing record. */
data class DeletionObject(
    val type: String,
    val id: String,
    val version: Long? = null,
    val relatedId: String? = null
)

data class DeletionItem(val ref: DeletionObject, val revision: Long)

enum class DeletionLinkKind {
    /** Owned rows, associations and materialized content that must be removed with the input. */
    REMOVE_WITH_INPUT,
    /** Derived version that must be invalidated and may be rebuilt from independent inputs. */
    DERIVED,
    /** Surviving record whose projection must change, without propagating through its own sources. */
    RECORD_VIEW,
    /** Nonterminal job to cancel. */
    ACTIVE_JOB,
    /** Attachment whose other live references must be checked. */
    SHARED_ATTACHMENT
}

data class DeletionLink(val item: DeletionItem, val kind: DeletionLinkKind)

data class DeletionAuthority(
    val authorityInstanceId: String,
    val libraryId: String,
    val generation: String,
    val principalId: String,
    val authorizationRevision: Long
)

enum class DeletionPermission { DELETE, REVISE }

data class DeletionPlan(
    val remove: Set<DeletionItem>,
    val invalidate: Set<DeletionItem>,
    val cancel: Set<DeletionItem>,
    val rebuild: Set<DeletionItem>
) {
    val affectedObjects: Set<DeletionObject>
        get() = (remove + invalidate + cancel + rebuild).mapTo(mutableSetOf()) { it.ref }
}

/** Optional storage capabilities, bound to the enclosing CoreRepository transaction. */
interface DeletionReader {
    fun authority(clientId: String): DeletionAuthority?

    /** Resolve scopes even for tombstones, without exposing existence to the caller. */
    fun allows(
        authority: DeletionAuthority,
        permission: DeletionPermission,
        objects: Set<DeletionObject>
    ): Boolean

    fun isDeleted(ref: DeletionObject): Boolean
    fun item(ref: DeletionObject): DeletionItem?

    /**
     * Immediate outgoing dependencies from the same read view. Sources enumerate all versions
     * and associations. A cross-record association points to its record view, while that view
     * leaves the record's independent sources intact. A shared attachment is a terminal node.
     */
    fun dependents(ref: DeletionObject): List<DeletionLink>

    /**
     * Alternatives whose inputs are currently effective. Each group requires all its members.
     * For an attachment, each live reference is a singleton group.
     */
    fun supportGroups(ref: DeletionObject): List<Set<DeletionObject>>

    /** The exact cross-record reference for this version, if present. */
    fun sourceReference(recordId: String, source: SourceRef): DeletionItem?

    fun cleanupAccess(jobId: String): DeletionJobAccess?
}

data class DeletionJobAccess(val permission: DeletionPermission, val objects: Set<DeletionObject>)

interface DeletionWriter : DeletionReader {
    /**
     * Atomically isolate the plan and return whether deferred work remains. Record markers, cancel
     * jobs, invalidate indexes/snapshots and update affected object revisions. Readers must omit
     * isolated rows and redact copies in record/context projections and earlier receipts. For a
     * surviving record, update its view without removing independent content.
     */
    fun isolate(plan: DeletionPlan, deletionRevision: Long): Boolean

    /**
     * Persist the cleanup task, its authorization scope and the plan needed for its checkpoints.
     * CoreReader.task must expose the job by id, including its phase. Deferred cleanup retries
     * must reuse this job. Recheck live references before physically removing shared files.
     */
    fun putCleanupTask(task: CoreTask, plan: DeletionPlan, access: DeletionJobAccess)
}
