package com.pebblepile.android

import android.content.Context
import com.pebblepile.android.db.RoomStore
import com.pebblepile.android.model.CoreTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.pebblepile.android.repository.CoreRepository
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.RecordTag
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.TagReviewState
import com.pebblepile.android.service.CoreApi
import com.pebblepile.android.service.LocalCoreApi

/** Android assembly. Uses the Room-backed CoreRepository by default (Issue #7). */
class DemoCoreApi internal constructor(
    repository: CoreRepository,
    onQueued: (CoreTask, Boolean) -> Unit = { _, _ -> },
    private val revisions: ((List<String>) -> Flow<List<Long>>)? = null,
    private val detailChanges: ((String) -> Flow<Unit>)? = null,
) : CoreApi by LocalCoreApi(
    onTaskQueued = onQueued,
    repository = repository,
    decorateCreatedRecord = Companion::withCaptureMetadata,
) {
    constructor(context: Context) : this(
        RoomStore.repository(context),
        (context.applicationContext as PebblePileApplication).taskRuntime::enqueue,
        RoomStore.database(context).taskDao()::observedRevisions,
        { id -> RoomStore.database(context).recordDao().observeDetailRevision(id).distinctUntilChanged().map { Unit } },
    )

    override fun observeRecordChanges(recordId: String): Flow<Unit> = detailChanges?.invoke(recordId) ?: flowOf(Unit)

    override fun observeTasks(ids: List<String>): Flow<List<CoreTask>> =
        (revisions?.invoke(ids) ?: flowOf(emptyList())).distinctUntilChanged().map {
            ids.mapNotNull(::getTask)
        }.flowOn(Dispatchers.IO)

    private companion object {
        fun withCaptureMetadata(record: PebbleRecord, sourceRef: SourceRef): PebbleRecord {
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
            )
        }
    }
}
