package com.pebblepile.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Local demo adapter only. It persists sample records in SharedPreferences for UI testing.
 * It is NOT the final team SQLite/CoreAPI implementation. */
class DemoCoreApi(context: Context) : CoreApi {
    private val prefs = context.getSharedPreferences("pebblepile_issue4_demo", Context.MODE_PRIVATE)
    private val records = LinkedHashMap<String, PebbleRecord>()
    private val receipts = HashMap<String, ApiReceipt>()
    private val lock = Any()

    init { load() }

    override suspend fun recordExperience(text: String, captureMethod: String, operationId: String, recordId: String?): ApiReceipt = synchronized(lock) {
        receipts[operationId]?.let { return it }
        if (text.isBlank()) return ApiReceipt(false, message = "内容不能为空")
        val id = recordId ?: UUID.randomUUID().toString()
        val record = PebbleRecord(
            id = id,
            rawText = text,
            capturedAt = now(),
            captureMethod = captureMethod,
            tags = listOf(
                RecordTag(UUID.randomUUID().toString(), "饮食", text.take(36), "local-rules-0.1"),
                RecordTag(UUID.randomUUID().toString(), "加班", text.take(36), "local-rules-0.1")
            ),
            suggestions = listOf(
                SuggestionItem(
                    UUID.randomUUID().toString(), "优先选择准备简单的晚餐",
                    "示例依据：记录中提到了时间紧张或希望省事。正式建议由团队任务模块提供。",
                    listOf(EvidenceItem(UUID.randomUUID().toString(), "本条记录", text.take(100), nowDate(), id))
                )
            )
        )
        records[id] = record
        if (!persist()) {
            records.remove(id)
            return ApiReceipt(false, message = "保存失败，请重试")
        }
        return ApiReceipt(true, id, record.revision, "已保存") .also { receipts[operationId] = it }
    }

    override suspend fun getRecord(recordId: String): PebbleRecord? = synchronized(lock) { records[recordId] }

    override suspend fun reviseTags(recordId: String, baseRevision: Long, tagId: String, operationId: String): ApiReceipt = synchronized(lock) {
        receipts[operationId]?.let { return it }
        val old = records[recordId] ?: return ApiReceipt(false, message = "记录不存在")
        if (old.revision != baseRevision) return ApiReceipt(false, message = "记录已被其他操作修改，请检查当前版本。", conflict = true, currentRevision = old.revision)
        val changed = old.copy(revision = old.revision + 1, tags = old.tags.map { if (it.id == tagId) it.copy(excluded = true) else it })
        records[recordId] = changed
        if (!persist()) {
            records[recordId] = old
            return ApiReceipt(false, message = "标签保存失败，请重试", currentRevision = old.revision)
        }
        return ApiReceipt(true, recordId, changed.revision, "已排除标签").also { receipts[operationId] = it }
    }

    override suspend fun recordOutcome(recordId: String, baseRevision: Long, choice: String, experience: String, operationId: String): ApiReceipt = synchronized(lock) {
        receipts[operationId]?.let { return it }
        val old = records[recordId] ?: return ApiReceipt(false, message = "记录不存在")
        if (old.revision != baseRevision) return ApiReceipt(false, message = "记录已更新，反馈草稿已保留。", conflict = true, currentRevision = old.revision)
        val outcome = OutcomeItem(UUID.randomUUID().toString(), choice, experience, now())
        val changed = old.copy(revision = old.revision + 1, outcomes = old.outcomes + outcome)
        records[recordId] = changed
        if (!persist()) {
            records[recordId] = old
            return ApiReceipt(false, message = "反馈保存失败，请重试", currentRevision = old.revision)
        }
        return ApiReceipt(true, recordId, changed.revision, "实际反馈已保存").also { receipts[operationId] = it }
    }

    override suspend fun reviseMemory(recordId: String, baseRevision: Long, condition: String, scope: CorrectionScope, operationId: String): ApiReceipt = synchronized(lock) {
        receipts[operationId]?.let { return it }
        val old = records[recordId] ?: return ApiReceipt(false, message = "记录不存在")
        if (old.revision != baseRevision) return ApiReceipt(false, message = "条件已被其他操作修改。草稿已保留，请核对当前条件后重试。", conflict = true, currentRevision = old.revision)
        val changed = old.copy(revision = old.revision + 1, currentCondition = condition, conditionScope = scope)
        records[recordId] = changed
        if (!persist()) {
            records[recordId] = old
            return ApiReceipt(false, message = "条件保存失败，请重试", currentRevision = old.revision)
        }
        return ApiReceipt(true, recordId, changed.revision, "已保存条件 · ${scope.label}").also { receipts[operationId] = it }
    }

    override suspend fun recentRecords(): List<PebbleRecord> = synchronized(lock) {
        records.values.toList().asReversed().sortedByDescending { it.capturedAt }
    }

    private fun load() {
        val raw = prefs.getString("records", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val recordId = obj.getString("id")
                val tags = obj.optJSONArray("tags") ?: JSONArray()
                val tagList = (0 until tags.length()).map { j -> tags.getJSONObject(j).let { RecordTag(it.getString("id"), it.getString("name"), it.optString("quote"), it.optString("algorithm"), it.optBoolean("excluded")) } }
                val outcomes = obj.optJSONArray("outcomes") ?: JSONArray()
                val outcomeList = (0 until outcomes.length()).map { j -> outcomes.getJSONObject(j).let { OutcomeItem(it.getString("id"), it.optString("choice"), it.optString("experience"), it.optString("createdAt")) } }
                val suggestions = obj.optJSONArray("suggestions") ?: JSONArray()
                val suggestionList = (0 until suggestions.length()).map { j ->
                    val s = suggestions.getJSONObject(j); val ev = s.optJSONArray("evidence") ?: JSONArray()
                    val evidence = (0 until ev.length()).map { k ->
                        val entry = ev.getJSONObject(k)
                        EvidenceItem(entry.getString("id"), entry.optString("title"), entry.optString("quote"),
                            entry.optString("date"), entry.optString("sourceRecordId", recordId))
                    }
                    SuggestionItem(s.getString("id"), s.optString("title"), s.optString("reason"), evidence)
                }
                val conditionScope = CorrectionScope.entries.firstOrNull { it.name == obj.optString("conditionScope") }
                val r = PebbleRecord(recordId, obj.getString("rawText"), obj.optString("capturedAt"), obj.optString("captureMethod"), obj.optLong("revision", 1), tagList, suggestionList, outcomeList, obj.optString("condition"), obj.optString("status", "已保存"), conditionScope)
                records[r.id] = r
            }
        }
    }

    private fun persist(): Boolean {
        val arr = JSONArray()
        records.values.forEach { r ->
            val obj = JSONObject().put("id", r.id).put("rawText", r.rawText).put("capturedAt", r.capturedAt).put("captureMethod", r.captureMethod).put("revision", r.revision).put("condition", r.currentCondition).put("status", r.status)
            r.conditionScope?.let { obj.put("conditionScope", it.name) }
            obj.put("tags", JSONArray().also { a -> r.tags.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("quote", it.sourceQuote).put("algorithm", it.algorithmVersion).put("excluded", it.excluded)) } })
            obj.put("outcomes", JSONArray().also { a -> r.outcomes.forEach { a.put(JSONObject().put("id", it.id).put("choice", it.choice).put("experience", it.experience).put("createdAt", it.createdAt)) } })
            obj.put("suggestions", JSONArray().also { a -> r.suggestions.forEach { s ->
                val evidence = JSONArray()
                s.evidence.forEach { item ->
                    evidence.put(JSONObject().put("id", item.id).put("title", item.title).put("quote", item.quote)
                        .put("date", item.recordDate).put("sourceRecordId", item.sourceRecordId))
                }
                a.put(JSONObject().put("id", s.id).put("title", s.title).put("reason", s.reason).put("evidence", evidence))
            } })
            arr.put(obj)
        }
        return prefs.edit().putString("records", arr.toString()).commit()
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
    private fun nowDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
}
