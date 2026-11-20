package com.pebblepile.android

import android.content.Context
import com.pebblepile.android.db.DemoLibraryStore
import com.pebblepile.android.model.EvidenceItem
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.RecordTag
import com.pebblepile.android.model.SuggestionItem
import com.pebblepile.android.service.CoreApi
import com.pebblepile.android.service.LocalCoreApi

/** Android assembly for the demo. SQLite can use the same CoreRepository interface. */
class DemoCoreApi(context: Context) : CoreApi by coreFor(context) {
    private companion object {
        fun coreFor(context: Context): LocalCoreApi {
            val preferences = context.getSharedPreferences("pebblepile_issue4_demo", Context.MODE_PRIVATE)
            val store = DemoLibraryStore(preferences)
            return LocalCoreApi(store.repository(), decorateSampleRecord = ::withDemoContent)
        }

        fun withDemoContent(record: PebbleRecord): PebbleRecord {
            val snippetLength = record.rawText.codePointCount(0, record.rawText.length)
            fun snippet(limit: Int): String = record.rawText.substring(
                0, record.rawText.offsetByCodePoints(0, minOf(limit, snippetLength))
            )
            return record.copy(
                tags = listOf(
                    RecordTag("${record.id}:dinner", "饮食", snippet(36), "local-rules-0.1"),
                    RecordTag("${record.id}:overtime", "加班", snippet(36), "local-rules-0.1")
                ),
                suggestions = listOf(
                    SuggestionItem(
                        "${record.id}:suggestion", "优先选择准备简单的晚餐",
                        "示例依据：记录中提到了时间紧张或希望省事。正式建议由团队任务模块提供。",
                        listOf(EvidenceItem("${record.id}:evidence", "本条记录", snippet(100), record.capturedAt.take(10), record.id))
                    )
                )
            )
        }
    }
}
