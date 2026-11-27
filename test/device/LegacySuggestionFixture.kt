package com.pebblepile.android

import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.EvidenceItem
import com.pebblepile.android.model.SuggestionItem

/** A persisted suggestion from the schema preceding dinner decisions. */
internal fun CapturedSource.legacySuggestionFixture(): SuggestionItem {
    val length = text.codePointCount(0, text.length)
    val start = if (length >= 2) 1 else 0
    val end = minOf(length, start + 6)
    val quote = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
    val evidence = EvidenceItem("$recordId:historic-evidence", "本条记录", quote, capturedAt.take(10),
        recordId, ref, start, end)
    return SuggestionItem("$recordId:historic-suggestion", "热汤面", "上次的选择", listOf(evidence))
}
