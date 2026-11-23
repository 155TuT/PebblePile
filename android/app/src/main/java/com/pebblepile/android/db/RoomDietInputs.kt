package com.pebblepile.android.db

import com.pebblepile.android.algorithm.DietTaskContext
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.SourceRef
import com.pebblepile.scheduler.StaleInputException
import com.pebblepile.scheduler.TaskInput
import java.security.MessageDigest

/** Reads within the caller's transaction. Stage guards project only IDs and revisions. */
internal class RoomDietInputs(private val database: PebbleDatabase) {
    private val sources get() = database.sourceDao()

    private fun capture(input: TaskInput): SourceRef {
        val id = input.captureSourceId
        val version = input.captureSourceVersion
        if (id != null && version != null) return SourceRef(id, version)
        // Jobs predating an exact source binding must match the unchanged capture.
        val row = sources.latestVisibleCapture(input.recordId)
        if (row == null || row.text != input.text) throw StaleInputException("任务来源已变化")
        return SourceRef(row.sourceId, row.sourceVersion)
    }

    private fun futureCondition() = DbJson.decode<ConditionSetting>(database.libraryDao().get()?.futureConditionJson)

    fun key(input: TaskInput): String = key(input, capture(input), futureCondition())

    private fun key(input: TaskInput, target: SourceRef, condition: ConditionSetting?): String {
        if ((condition?.revision ?: 0L) != input.preferenceRevision) throw StaleInputException("偏好已变化")
        val versions = sources.dietVersions(input.recordId, target.sourceId, target.sourceVersion)
        if (versions.none { it.recordId == input.recordId && it.recordRevision == input.revision &&
                it.sourceId == target.sourceId && it.sourceVersion == target.sourceVersion }) {
            throw StaleInputException("任务原始来源版本或记录修订已变化")
        }
        // Job claims also advance the library revision, so hash only visible algorithm inputs.
        val digest = MessageDigest.getInstance("SHA-256")
        fun part(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        part("diet-context-1")
        part(input.preferenceRevision.toString())
        for (row in versions) {
            part(row.recordId)
            part(row.recordRevision.toString())
            part(row.sourceId)
            part(row.sourceVersion.toString())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun read(input: TaskInput): DietTaskContext {
        val target = capture(input)
        val condition = futureCondition()
        val key = key(input, target, condition)
        val records = sources.dietRecords(input.recordId, target.sourceId, target.sourceVersion)
            .map { checkNotNull(DbJson.decode<PebbleRecord>(it)) }
        val history = sources.dietSources(input.recordId, target.sourceId, target.sourceVersion)
            .map { checkNotNull(DbJson.decode<CapturedSource>(it)) }
        val captured = history.single { it.recordId == input.recordId && it.ref == target }
        if (captured.text != input.text) throw StaleInputException("任务原文已变化")
        val exclusions = records.associate { record ->
            record.id to record.tags.filter { it.excluded }.mapTo(sortedSetOf()) { it.name }
        }.filterValues { it.isNotEmpty() }
        return DietTaskContext(captured, history, exclusions, condition,
            records.single { it.id == input.recordId }.currentGoalCondition, key)
    }
}
