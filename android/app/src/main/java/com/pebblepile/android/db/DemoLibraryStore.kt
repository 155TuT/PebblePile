package com.pebblepile.android.db

import android.content.SharedPreferences
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.EvidenceItem
import com.pebblepile.android.model.OutcomeItem
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.RecordTag
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.SuggestionItem
import com.pebblepile.android.model.TaskKind
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.repository.CoreRepository
import com.pebblepile.android.repository.OperationKey
import com.pebblepile.android.repository.RepositoryWriteException
import com.pebblepile.android.repository.StoredReceipt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class DemoLibraryStore(private val preferences: SharedPreferences) {
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
            val revision = records.values.maxOfOrNull { it.revision } ?: 0
            val future = records.values.filter { it.conditionScope == CorrectionScope.FUTURE && it.currentCondition.isNotBlank() }
                .distinctBy { it.currentCondition }.singleOrNull()
            return DemoLibrary(
                revision = revision,
                futureCondition = future?.let { ConditionSetting(it.currentCondition, it.id, revision) },
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
        task = obj.optJSONObject("task")?.let(::decodeTask),
        title = obj.optionalString("title")
    )

    private fun encodeReceipt(receipt: ApiReceipt): JSONObject = JSONObject()
        .put("recordId", receipt.recordId).put("recordRevision", receipt.revision).put("message", receipt.message)
        .put("title", receipt.title)
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
