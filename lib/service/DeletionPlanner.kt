package com.pebblepile.android.service

import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeletionImpact
import com.pebblepile.android.repository.DeletionAuthority
import com.pebblepile.android.repository.DeletionItem
import com.pebblepile.android.repository.DeletionLinkKind
import com.pebblepile.android.repository.DeletionObject
import com.pebblepile.android.repository.DeletionPlan
import com.pebblepile.android.repository.DeletionReader
import com.pebblepile.android.repository.DeletionTraversal
import com.pebblepile.android.repository.DeletionTraversal.FULL_OBJECT
import com.pebblepile.android.repository.DeletionTraversal.RECORD_VIEW
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun DeleteTarget.ref() = DeletionObject(type.wireName, id)

internal fun planDeletion(reader: DeletionReader, root: DeletionItem): DeletionPlan {
    val remove = linkedMapOf(root.ref to root)
    val derived = linkedMapOf<DeletionObject, DeletionItem>()
    val recordViews = linkedMapOf<DeletionObject, DeletionItem>()
    val cancel = linkedMapOf<DeletionObject, DeletionItem>()
    val attachments = linkedMapOf<DeletionObject, DeletionItem>()
    val pending = ArrayDeque(listOf(root.ref to FULL_OBJECT))
    val visited = pending.toMutableSet()
    fun enqueue(ref: DeletionObject, traversal: DeletionTraversal) {
        val visit = ref to traversal
        if (visited.add(visit)) pending.addLast(visit)
    }

    while (pending.isNotEmpty()) {
        val (input, traversal) = pending.removeFirst()
        for ((item, kind) in reader.dependents(input, traversal)) {
            val ref = item.ref
            when (kind) {
                DeletionLinkKind.REMOVE_WITH_INPUT -> {
                    remove[ref] = item
                    enqueue(ref, FULL_OBJECT)
                }
                DeletionLinkKind.DERIVED -> {
                    derived[ref] = item
                    enqueue(ref, FULL_OBJECT)
                }
                DeletionLinkKind.RECORD_VIEW -> {
                    recordViews[ref] = item
                    enqueue(ref, RECORD_VIEW)
                }
                DeletionLinkKind.ACTIVE_JOB -> cancel[ref] = item
                DeletionLinkKind.SHARED_ATTACHMENT -> attachments[ref] = item
            }
        }
    }

    val lostInputs = (remove.keys + derived.keys + recordViews.keys + cancel.keys).toMutableSet()
    fun hasIndependentSupport(ref: DeletionObject): Boolean =
        reader.supportGroups(ref).any { group -> group.isNotEmpty() && group.none(lostInputs::contains) }

    // Resolve shared files first: rebuilding a derived item may depend on one of them.
    val orphanedAttachments = attachments.filterKeys { !hasIndependentSupport(it) }
    remove.putAll(orphanedAttachments)
    lostInputs.addAll(orphanedAttachments.keys)
    val rebuild = derived.filterKeys { it !in remove && hasIndependentSupport(it) }
    val invalidate = (derived + recordViews).filterKeys { it !in remove }
    return DeletionPlan(remove.values.toSet(), invalidate.values.toSet(), cancel.values.toSet(), rebuild.values.toSet())
}

internal fun DeletionPlan.impact() = DeletionImpact(
    remove = remove.countByType(),
    invalidate = invalidate.countByType(),
    cancel = cancel.countByType(),
    rebuild = rebuild.countByType(),
    affectedRecordIds = invalidate.filter { it.ref.type == "record" }.map { it.ref.id }.distinct().sorted()
)

private fun Set<DeletionItem>.countByType() = groupingBy { it.ref.type }.eachCount().toSortedMap()

/** The instance key expires unconfirmed previews on restart. Committed operation receipts survive. */
internal class DeletionPreviewTokens {
    private val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "HmacSHA256")

    fun issue(
        authority: DeletionAuthority,
        clientId: String,
        target: DeleteTarget,
        revision: Long,
        plan: DeletionPlan
    ): String {
        val mac = Mac.getInstance(key.algorithm).apply { init(key) }
        fun append(value: String?) {
            val bytes = value?.toByteArray(Charsets.UTF_8)
            mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
            if (bytes != null) mac.update(bytes)
        }
        fun appendItems(action: String, items: Set<DeletionItem>) {
            append(action)
            append(items.size.toString())
            for (item in items.sortedWith(itemOrder)) {
                append(item.ref.type)
                append(item.ref.id)
                append(item.ref.version?.toString())
                append(item.ref.relatedId)
                append(item.revision.toString())
            }
        }
        listOf(
            authority.authorityInstanceId, authority.libraryId, authority.generation,
            authority.principalId, clientId, authority.authorizationRevision.toString(),
            target.type.wireName, target.id, revision.toString()
        ).forEach(::append)
        appendItems("remove", plan.remove)
        appendItems("invalidate", plan.invalidate)
        appendItems("cancel", plan.cancel)
        appendItems("rebuild", plan.rebuild)
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    fun matches(previewId: String, expected: String): Boolean =
        MessageDigest.isEqual(previewId.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    private companion object {
        val itemOrder = compareBy<DeletionItem>({ it.ref.type }, { it.ref.id }, { it.ref.version }, { it.ref.relatedId }, { it.revision })
    }
}
