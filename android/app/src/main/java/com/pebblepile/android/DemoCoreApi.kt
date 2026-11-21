package com.pebblepile.android

import android.content.Context
import com.pebblepile.android.db.DemoLibraryStore
import com.pebblepile.android.model.EvidenceItem
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.RecordTag
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.SuggestionItem
import com.pebblepile.android.model.TagReviewState
import com.pebblepile.android.service.CoreApi
import com.pebblepile.android.service.LocalCoreApi

/** Android assembly for the demo. SQLite can use the same CoreRepository interface. */
class DemoCoreApi(context: Context) : CoreApi by coreFor(context) {
    private companion object {
        fun coreFor(context: Context): LocalCoreApi {
            val preferences = context.getSharedPreferences("pebblepile_issue4_demo", Context.MODE_PRIVATE)
            val store = DemoLibraryStore(preferences)
            return LocalCoreApi(
                repository = store.repository(),
                decorateCreatedRecord = ::withDemoContent,
            )
        }

        fun withDemoContent(record: PebbleRecord, sourceRef: SourceRef): PebbleRecord {
            val snippetLength = record.rawText.codePointCount(0, record.rawText.length)
            fun snippet(limit: Int): String = record.rawText.substring(
                0, record.rawText.offsetByCodePoints(0, minOf(limit, snippetLength))
            )
            val people = Regex("""@([\p{L}\p{N}_]{1,20})""")
                .findAll(record.rawText)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            val tagNames = buildList {
                if (record.rawText.contains(Regex("吃|饭|汤|晚餐|预算"))) add("饮食")
                if (record.rawText.contains(Regex("加班|工作|忙|省事"))) add("加班")
                if (isEmpty()) add("待整理")
            }
            val hint = listOf("省事", "加班", "时间紧").firstOrNull(record.rawText::contains)
            val charStart = hint?.let(record.rawText::indexOf)?.takeIf { it >= 0 } ?: 0
            val start = record.rawText.codePointCount(0, charStart)
            val quoteLength = hint?.codePointCount(0, hint.length) ?: minOf(24, snippetLength)
            val end = (start + quoteLength).coerceAtMost(snippetLength)
            val quote = record.rawText.substring(
                record.rawText.offsetByCodePoints(0, start),
                record.rawText.offsetByCodePoints(0, end),
            )
            return record.copy(
                title = record.title.ifBlank { snippet(18) },
                people = record.people.ifEmpty { people },
                tags = tagNames.mapIndexed { index, name ->
                    RecordTag(
                        id = "${record.id}:rule-$index",
                        name = name,
                        sourceQuote = snippet(36),
                        algorithmVersion = "local-rules-0.1",
                        reviewState = TagReviewState.NEEDS_REVIEW,
                        sourceRef = sourceRef,
                    )
                },
                suggestions = if ("饮食" in tagNames && hint != null) listOf(
                    SuggestionItem(
                        "${record.id}:suggestion", "优先选择准备简单的晚餐",
                        "示例依据：记录中提到了时间紧张或希望省事。正式建议由团队任务模块提供。",
                        listOf(
                            EvidenceItem(
                                id = "${record.id}:evidence",
                                title = "本条记录",
                                quote = quote,
                                recordDate = record.capturedAt.take(10),
                                sourceRecordId = record.id,
                                sourceRef = sourceRef,
                                start = start,
                                end = end,
                            )
                        )
                    )
                ) else emptyList()
            )
        }
    }
}
