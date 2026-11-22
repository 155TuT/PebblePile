package com.pebblepile.android

import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.EvidenceItem
import com.pebblepile.android.model.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EvidenceResolutionTest {
    private val ref = SourceRef("dinner-source", 2)
    private val source = CapturedSource(ref, "dinner", "🍜热汤面", "android_share", "2026-11-09")
    private val evidence = EvidenceItem("quote", "晚餐", "热汤", "2026-11-09", "dinner", ref, 1, 3)

    @Test fun codePointRangeSelectsTheExactQuoteAfterAnEmoji() {
        assertEquals(2 until 4, evidence.resolve(source).range)
    }

    @Test fun absentVersionsAndInvalidOrMismatchedQuotesHaveNoReadableSource() {
        val cases = listOf(
            evidence to null,
            evidence to source.copy(ref = ref.copy(sourceVersion = 1)),
            evidence.copy(start = -1) to source,
            evidence.copy(end = 8) to source,
            evidence.copy(end = 1) to source,
            evidence.copy(quote = "冷面") to source,
            evidence.copy(unavailableReason = "已撤回") to source,
        )
        cases.forEach { (item, captured) ->
            val result = item.resolve(captured)
            assertNull(result.source)
            assertNull(result.range)
            assertNotNull(result.reason)
        }
    }
}