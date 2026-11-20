package com.pebblepile.android.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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

private class DemoLibraryStore(private val preferences: SharedPreferences) {
    fun repository(): CoreRepository {
        val loaded = try {
            load()
        } catch (_: JSONException) {
            null
        }
        return SnapshotCoreRepository(loaded ?: DemoLibrary()) { library ->
            // Keep unreadable data intact so a later save cannot overwrite it.
            if (loaded == null) throw RepositoryWriteException()
            persist(library)
        }
    }

    private fun load(): DemoLibrary {
        val records = JSONArray(preferences.getString("records", null) ?: "[]").objects()
            .map(::decodeRecord).associateByTo(linkedMapOf()) { it.id }
        val state = preferences.getString("core_state", null)?.let { JSONObject(it) }
        if (state == null) {
            // Records written by the original demo had only one source and no stored receipts.
            val sources = records.values.map { record ->
                val ref = SourceRef("${record.id}:source", 1)
                CapturedSource(ref, record.id, record.rawText, record.captureMethod, record.capturedAt)
            }
            return DemoLibrary(
                revision = records.values.maxOfOrNull { it.revision } ?: 0,
                records = records,
                sources = sources.associateByTo(linkedMapOf()) { it.ref }
            )
        }
        val sources = state.objects("sources").map { source ->
            CapturedSource(
                source.sourceRef(), source.getString("recordId"), source.getString("text"),
                source.getString("captureMethod"), source.getString("capturedAt")
            )
        }.associateByTo(linkedMapOf()) { it.ref }
        val tasks = state.objects("tasks").map(::decodeTask).associateByTo(linkedMapOf()) { it.id }
        val receipts = state.objects("receipts").associateTo(linkedMapOf()) { stored ->
            val key = OperationKey(stored.getString("clientId"), stored.getString("operationId"))
            key to StoredReceipt(stored.getString("requestHash"), decodeReceipt(stored.getJSONObject("receipt")))
        }
        val condition = state.optJSONObject("futureCondition")?.let {
            ConditionSetting(it.getString("text"), it.getString("recordId"), it.getLong("revision"))
        }
        return DemoLibrary(state.getLong("revision"), records, sources, tasks, receipts, condition)
    }

    private fun persist(library: DemoLibrary) {
        val state = JSONObject()
            .put("revision", library.revision)
            .put("sources", library.sources.values.jsonArray { source ->
                JSONObject()
                    .put("sourceId", source.ref.sourceId).put("sourceVersion", source.ref.sourceVersion)
                    .put("recordId", source.recordId).put("text", source.text)
                    .put("captureMethod", source.captureMethod).put("capturedAt", source.capturedAt)
            })
            .put("tasks", library.tasks.values.jsonArray(::encodeTask))
            .put("receipts", library.receipts.entries.jsonArray { (key, stored) ->
                JSONObject().put("clientId", key.clientId).put("operationId", key.operationId)
                    .put("requestHash", stored.requestHash).put("receipt", encodeReceipt(stored.receipt))
            })
            .put("futureCondition", library.futureCondition?.let {
                JSONObject().put("text", it.text).put("recordId", it.recordId).put("revision", it.revision)
            })
        val committed = preferences.edit()
            .putString("records", library.records.values.jsonArray(::encodeRecord).toString())
            .putString("core_state", state.toString())
            .commit()
        if (!committed) throw RepositoryWriteException()
    }

    private fun decodeRecord(obj: JSONObject): PebbleRecord {
        val id = obj.getString("id")
        val condition = obj.optString("condition")
        val conditionScope = CorrectionScope.entries.firstOrNull { it.name == obj.optString("conditionScope") }
        return PebbleRecord(
            id = id,
            rawText = obj.getString("rawText"),
            capturedAt = obj.optString("capturedAt"),
            captureMethod = obj.optString("captureMethod"),
            revision = obj.optLong("revision", 1),
            tags = obj.objects("tags").map {
                RecordTag(it.getString("id"), it.getString("name"), it.optString("quote"), it.optString("algorithm"), it.optBoolean("excluded"))
            },
            suggestions = obj.objects("suggestions").map { suggestion ->
                SuggestionItem(
                    suggestion.getString("id"), suggestion.optString("title"), suggestion.optString("reason"),
                    suggestion.objects("evidence").map {
                        EvidenceItem(
                            it.getString("id"), it.optString("title"), it.optString("quote"),
                            it.optString("date"), it.optString("sourceRecordId", id)
                        )
                    }
                )
            },
            outcomes = obj.objects("outcomes").map {
                OutcomeItem(it.getString("id"), it.optString("choice"), it.optString("experience"), it.optString("createdAt"))
            },
            currentCondition = condition,
            status = obj.optString("status", "已保存"),
            conditionScope = conditionScope,
            title = obj.optString("title"),
            currentGoalCondition = obj.optionalString("currentGoalCondition") ?: condition.takeIf {
                it.isNotBlank() && conditionScope != CorrectionScope.FUTURE
            }
        )
    }

    private fun encodeRecord(record: PebbleRecord): JSONObject = JSONObject()
        .put("id", record.id).put("rawText", record.rawText).put("capturedAt", record.capturedAt)
        .put("captureMethod", record.captureMethod).put("revision", record.revision).put("title", record.title)
        .put("condition", record.currentCondition).put("status", record.status)
        .put("conditionScope", record.conditionScope?.name).put("currentGoalCondition", record.currentGoalCondition)
        .put("tags", record.tags.jsonArray {
            JSONObject().put("id", it.id).put("name", it.name).put("quote", it.sourceQuote)
                .put("algorithm", it.algorithmVersion).put("excluded", it.excluded)
        })
        .put("outcomes", record.outcomes.jsonArray {
            JSONObject().put("id", it.id).put("choice", it.choice)
                .put("experience", it.experience).put("createdAt", it.createdAt)
        })
        .put("suggestions", record.suggestions.jsonArray { suggestion ->
            JSONObject().put("id", suggestion.id).put("title", suggestion.title).put("reason", suggestion.reason)
                .put("evidence", suggestion.evidence.jsonArray {
                    JSONObject().put("id", it.id).put("title", it.title).put("quote", it.quote)
                        .put("date", it.recordDate).put("sourceRecordId", it.sourceRecordId)
                })
        })

    private fun decodeTask(task: JSONObject): CoreTask = CoreTask(
        task.getString("id"), task.getString("recordId"), readEnum<TaskKind>(task, "kind"),
        task.getLong("inputRevision"), task.getLong("revision"), readEnum<TaskState>(task, "state"),
        task.optionalString("waitReason")
    )

    private fun encodeTask(task: CoreTask): JSONObject = JSONObject()
        .put("id", task.id).put("recordId", task.recordId).put("kind", task.kind.name)
        .put("inputRevision", task.inputRevision).put("revision", task.revision)
        .put("state", task.state.name).put("waitReason", task.waitReason)

    private fun decodeReceipt(obj: JSONObject): ApiReceipt = ApiReceipt(
        success = true,
        recordId = obj.optionalString("recordId"),
        revision = if (obj.isNull("recordRevision")) null else obj.getLong("recordRevision"),
        message = obj.optString("message"),
        libraryRevision = obj.getLong("libraryRevision"),
        source = if (obj.isNull("sourceId")) null else obj.sourceRef(),
        jobId = obj.optionalString("jobId"),
        outcomeId = obj.optionalString("outcomeId"),
        task = obj.optJSONObject("task")?.let(::decodeTask)
    )

    private fun encodeReceipt(receipt: ApiReceipt): JSONObject = JSONObject()
        .put("recordId", receipt.recordId).put("recordRevision", receipt.revision).put("message", receipt.message)
        .put("libraryRevision", receipt.libraryRevision).put("jobId", receipt.jobId).put("outcomeId", receipt.outcomeId)
        .put("sourceId", receipt.source?.sourceId).put("sourceVersion", receipt.source?.sourceVersion)
        .put("task", receipt.task?.let(::encodeTask))

    private fun JSONObject.sourceRef() = SourceRef(getString("sourceId"), getLong("sourceVersion"))

    private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.objects().orEmpty()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun <T> Iterable<T>.jsonArray(encode: (T) -> JSONObject): JSONArray =
        JSONArray().also { array -> forEach { array.put(encode(it)) } }

    private inline fun <reified E : Enum<E>> readEnum(obj: JSONObject, key: String): E {
        val value = obj.getString(key)
        return enumValues<E>().firstOrNull { it.name == value } ?: throw JSONException("Invalid value for $key")
    }
}
