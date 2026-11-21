package com.pebblepile.android.service

import com.pebblepile.android.model.DeleteTarget
import com.pebblepile.android.model.DeletionImpact
import com.pebblepile.android.repository.DeletionAuthority
import com.pebblepile.android.repository.DeletionItem
import com.pebblepile.android.repository.DeletionLinkKind
import com.pebblepile.android.repository.DeletionObject
import com.pebblepile.android.repository.DeletionPlan
import com.pebblepile.android.repository.DeletionReader
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
    val visited = mutableSetOf<DeletionObject>()
    val pending = ArrayDeque(listOf(root.ref))

    while (pending.isNotEmpty()) {
        val input = pending.removeFirst()
        if (!visited.add(input)) continue
        for ((item, kind) in reader.dependents(input)) {
            val ref = item.ref
            when (kind) {
                DeletionLinkKind.REMOVE_WITH_INPUT -> {
                    remove[ref] = item
                    pending.addLast(ref)
                }
                DeletionLinkKind.DERIVED -> {
                    derived[ref] = item
                    pending.addLast(ref)
                }
                DeletionLinkKind.RECORD_VIEW -> recordViews[ref] = item
                DeletionLinkKind.ACTIVE_JOB -> cancel[ref] = item
                DeletionLinkKind.SHARED_ATTACHMENT -> attachments[ref] = item
            }
        }
    }

    fun hasIndependentSupport(ref: DeletionObject): Boolean =
        reader.supportGroups(ref).any { group -> group.isNotEmpty() && group.none { it in visited } }

    // Evaluate alternatives after the full closure so a later branch cannot preserve a copy.
    remove.putAll(attachments.filterKeys { !hasIndependentSupport(it) })
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
