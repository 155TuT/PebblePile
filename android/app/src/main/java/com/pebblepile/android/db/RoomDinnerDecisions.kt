package com.pebblepile.android.db

import com.pebblepile.android.algorithm.DefaultDietScenePackage
import com.pebblepile.android.algorithm.DietTaskContext
import com.pebblepile.android.algorithm.DietTaskOutcome
import com.pebblepile.android.algorithm.dietRankCheckpoint
import com.pebblepile.android.model.DinnerDecision
import com.pebblepile.android.model.EvidenceItem
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.SourceRef
import com.pebblepile.android.model.SuggestionItem
import com.pebblepile.scheduler.StageOutput
import com.pebblepile.scheduler.StageSource
import com.pebblepile.scheduler.TaskJob
import com.pebblepile.scheduler.taskKey

/** Resolve evidence from the same Room snapshot used to authorize the commit. */
internal fun prepareDinnerDecision(
    job: TaskJob,
    decisionId: String,
    rank: StageOutput,
    context: DietTaskContext,
): DinnerDecision? {
    val checkpoint = rank.dietRankCheckpoint()
    val ranked = checkpoint.result?.items.orEmpty()
    if (checkpoint.outcome != DietTaskOutcome.RANKED || ranked.isEmpty() || checkpoint.constraint == null ||
        decisionId != "diet-" + taskKey(job.input()) ||
        checkpoint.manifest.algorithmVersion != job.algorithmVersion ||
        checkpoint.manifest.scenePackVersion != DefaultDietScenePackage.scenePackage.version ||
        ranked.map { it.candidateId }.distinct().size != ranked.size ||
        "饮食" in context.excludedTagsByRecord[job.recordId].orEmpty()
    ) return null
    val condition = context.futureCondition?.takeIf { it.recordId == rank.conditionRecordId }
    if (condition?.recordId != rank.conditionRecordId || condition?.revision != checkpoint.settingRevision) return null

    val menu = DefaultDietScenePackage.scenePackage.menu.associateBy { it.id }
    val authorized = context.sources.associateBy { it.ref }
    val usedSources = rank.inputSources.orEmpty().toSet()
    val suggestions = ranked.map { candidate ->
        val title = menu[candidate.candidateId]?.name ?: return null
        val suggestionId = "$decisionId:${candidate.candidateId}"
        val evidence = candidate.evidence.distinct().mapIndexed { index, ref ->
            val sourceRef = SourceRef(ref.source.sourceId, ref.source.sourceVersion)
            val source = authorized[sourceRef]?.takeIf { it.recordId == ref.source.recordId } ?: return null
            if (StageSource(source.recordId, sourceRef.sourceId, sourceRef.sourceVersion) !in usedSources ||
                "饮食" in context.excludedTagsByRecord[source.recordId].orEmpty() ||
                ref.span.start < 0 || ref.span.end <= ref.span.start ||
                ref.span.end > source.text.codePointCount(0, source.text.length)
            ) return null
            EvidenceItem("$suggestionId:$index", "饮食经历", ref.span.quoteIn(source.text), source.capturedAt.take(10),
                source.recordId, source.ref, ref.span.start, ref.span.end)
        }
        SuggestionItem(suggestionId, title, candidate.reason, evidence)
    }
    return DinnerDecision(decisionId, job.jobId, job.inputRevision, job.preferenceRevision, context.contextKey,
        checkpoint.manifest, checkpoint.constraint, suggestions)
}

internal fun RecordDao.publishDinnerDecision(recordId: String, decision: DinnerDecision) {
    val row = checkNotNull(visible(recordId))
    val record = checkNotNull(DbJson.decode<PebbleRecord>(row.recordJson))
    val decisions = record.dinnerDecisions.orEmpty()
    if (decisions.any { it.id == decision.id }) return
    val updated = record.copy(revision = record.revision + 1, dinnerDecisions = decisions + decision)
    put(row.copy(recordJson = DbJson.encode(updated), revision = updated.revision))
}
