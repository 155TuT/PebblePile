package com.pebblepile.android.db

import com.pebblepile.android.repository.DeletionObject

/** Stable key format for deletion tombstones, shared by the writer and the read views. */
internal object DeletionKeys {
    fun of(ref: DeletionObject): String =
        listOf(ref.type, ref.id, ref.version?.toString().orEmpty(), ref.relatedId.orEmpty())
            .joinToString("|")
}
