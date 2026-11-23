package com.pebblepile.android.algorithm

import com.google.gson.Gson
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.scheduler.AgentTaskPipeline
import com.pebblepile.scheduler.CommitOutput
import com.pebblepile.scheduler.StageOutput
import com.pebblepile.scheduler.StageSource
import com.pebblepile.scheduler.StaleInputException
import com.pebblepile.scheduler.TaskInput
import com.pebblepile.scheduler.TaskStage
import com.pebblepile.scheduler.taskKey

/** One consistent read from Room. The algorithm computes after this snapshot has been returned. */
internal data class DietTaskContext(
    val target: CapturedSource,
    val sources: List<CapturedSource>,
    val excludedTagsByRecord: Map<String, Set<String>>,
    val futureCondition: ConditionSetting?,
    val currentGoalCondition: String?,
    val contextKey: String,
)

internal enum class DietTaskOutcome { ORGANIZED, MISSING_CONDITIONS, UNRECOGNIZED_CORRECTION, NO_CANDIDATES, RANKED }

internal data class DietExtractCheckpoint(val event: ExtractedEvent, val dinnerQuery: Boolean, val manifest: AlgorithmManifest)
internal data class DietUpdateCheckpoint(val preferences: List<PreferenceCandidate>, val unrecognizedCorrection: Boolean)
internal data class DietContextCheckpoint(val constraint: QueryConstraint?, val outcome: DietTaskOutcome)
internal data class DietRankCheckpoint(
    val manifest: AlgorithmManifest,
    val outcome: DietTaskOutcome,
    val constraint: QueryConstraint?,
    val result: RankResult?,
    val settingRevision: Long?,
)

private val checkpointJson = Gson()

/** Read the #9 manifest, outcome, constraints, ranked candidates and their versioned code-point evidence. */
internal fun StageOutput.dietRankCheckpoint(): DietRankCheckpoint =
    requireNotNull(checkpointJson.fromJson(payload, DietRankCheckpoint::class.java))

/** Checkpoints contain structured facts and source locators. Original text stays in the source table. */
internal class DietTaskPipeline(
    private val load: (TaskInput) -> DietTaskContext,
    private val engine: AlgorithmEngine = AlgorithmEngine(),
) : AgentTaskPipeline {

    override fun runStage(stage: TaskStage, input: TaskInput, completed: Map<TaskStage, StageOutput>): StageOutput {
        val context = load(input)
        if (context.target.recordId != input.recordId || context.target.text != input.text ||
            completed.values.any { it.contextKey != context.contextKey } ||
            input.algorithmVersion != engine.manifest().algorithmVersion
        ) throw StaleInputException("整理任务的材料或算法版本已变化")

        if (stage != TaskStage.EXTRACT) verifyManifest(checkpoint<DietExtractCheckpoint>(completed, TaskStage.EXTRACT).manifest)
        val target = context.target
        val targetRef = target.stageSource()
        fun output(value: Any, sources: List<StageSource>, refs: List<String> = emptyList(), conditionOwner: String? = null) =
            StageOutput(payload = checkpointJson.toJson(value), evidenceRefs = refs, contextKey = context.contextKey,
                inputSources = sources.distinct(), conditionRecordId = conditionOwner)

        return when (stage) {
            TaskStage.EXTRACT -> {
                val event = extract(target)
                val tags = event.tags - context.excludedTagsByRecord[target.recordId].orEmpty()
                output(DietExtractCheckpoint(event.copy(tags = tags, rawText = ""),
                    "饮食" in tags && isDinnerQuestion(target.text), engine.manifest()), listOf(targetRef))
            }
            TaskStage.UPDATE -> {
                val extracted = checkpoint<DietExtractCheckpoint>(completed, TaskStage.EXTRACT)
                if ("饮食" !in extracted.event.tags) return output(DietUpdateCheckpoint(emptyList(), false), listOf(targetRef))
                val history = history(context, extracted)
                val setting = context.futureCondition
                val correction = setting?.let { explicitPreference(it.text, context, it.recordId) }
                val appliesToDiet = setting != null && (
                    dietWords.containsMatchIn(setting.text) ||
                        history.any { it.source?.recordId == setting.recordId }
                )
                val temporary = context.currentGoalCondition
                val temporaryPreference = temporary?.let { explicitPreference(it, context, target.recordId) }
                val revisions = ManualRevisions(
                    excludedTagsByRecord = context.excludedTagsByRecord,
                    explicit = listOfNotNull(correction?.takeIf { appliesToDiet }, temporaryPreference),
                )
                val preferences = engine.update(history, revisions)
                // A setting is bound by its own revision. Cite a capture only when its text matches.
                val dependencies = history.mapNotNull { it.source?.stageSource() } +
                    context.sources.filter { appliesToDiet && it.recordId == setting?.recordId }.map { it.stageSource() }
                val unresolved = (appliesToDiet && correction == null && temporaryPreference == null) ||
                    (temporary != null && preferenceWords.containsMatchIn(temporary) && temporaryPreference == null)
                output(DietUpdateCheckpoint(preferences, unresolved), dependencies + targetRef,
                    conditionOwner = setting?.recordId?.takeIf { appliesToDiet })
            }
            TaskStage.CONTEXT -> {
                val extracted = checkpoint<DietExtractCheckpoint>(completed, TaskStage.EXTRACT)
                val updated = checkpoint<DietUpdateCheckpoint>(completed, TaskStage.UPDATE)
                val constraint = if (extracted.dinnerQuery) {
                    dinnerConstraint(extracted.event, target, context.currentGoalCondition)
                } else null
                val outcome = when {
                    !extracted.dinnerQuery -> DietTaskOutcome.ORGANIZED
                    updated.unrecognizedCorrection -> DietTaskOutcome.UNRECOGNIZED_CORRECTION
                    constraint == null -> DietTaskOutcome.MISSING_CONDITIONS
                    else -> DietTaskOutcome.RANKED
                }
                val update = completed.getValue(TaskStage.UPDATE)
                output(DietContextCheckpoint(constraint, outcome), update.inputSources.orEmpty(), conditionOwner = update.conditionRecordId)
            }
            TaskStage.RANK -> {
                val query = checkpoint<DietContextCheckpoint>(completed, TaskStage.CONTEXT)
                val result = if (query.outcome == DietTaskOutcome.RANKED) {
                    val extracted = checkpoint<DietExtractCheckpoint>(completed, TaskStage.EXTRACT)
                    val updated = checkpoint<DietUpdateCheckpoint>(completed, TaskStage.UPDATE)
                    engine.rank(requireNotNull(query.constraint), updated.preferences, history(context, extracted))
                } else null
                val outcome = if (result != null && result.items.isEmpty()) DietTaskOutcome.NO_CANDIDATES else query.outcome
                val refs = result?.items.orEmpty().flatMap { it.evidence }.distinct().map(checkpointJson::toJson)
                val contextStage = completed.getValue(TaskStage.CONTEXT)
                val settingRevision = context.futureCondition?.takeIf { it.recordId == contextStage.conditionRecordId }?.revision
                output(DietRankCheckpoint(engine.manifest(), outcome, query.constraint, result, settingRevision),
                    contextStage.inputSources.orEmpty(), refs, contextStage.conditionRecordId)
            }
            TaskStage.COMMIT -> error("提交前调用 prepareCommit")
        }
    }

    override fun prepareCommit(input: TaskInput, completed: Map<TaskStage, StageOutput>): CommitOutput {
        val rank = completed.getValue(TaskStage.RANK).dietRankCheckpoint()
        verifyManifest(checkpoint<DietExtractCheckpoint>(completed, TaskStage.EXTRACT).manifest)
        verifyManifest(rank.manifest)
        return CommitOutput(
            decisionId = if (rank.outcome == DietTaskOutcome.RANKED) "diet-" + taskKey(input) else null,
            evidenceRefs = completed.getValue(TaskStage.RANK).evidenceRefs,
            needsReview = rank.outcome in setOf(DietTaskOutcome.MISSING_CONDITIONS,
                DietTaskOutcome.UNRECOGNIZED_CORRECTION, DietTaskOutcome.NO_CANDIDATES),
        )
    }

    private fun verifyManifest(saved: AlgorithmManifest?) {
        val current = engine.manifest()
        if (saved == null || saved.copy(inferenceCalls = 0) != current.copy(inferenceCalls = 0)) {
            throw StaleInputException("算法或场景包版本已更新")
        }
    }

    private inline fun <reified T> checkpoint(completed: Map<TaskStage, StageOutput>, stage: TaskStage): T =
        checkpointJson.fromJson(completed.getValue(stage).payload, T::class.java)

    private fun extract(source: CapturedSource) =
        engine.extract(EvidenceSource(source.ref.sourceId, source.ref.sourceVersion, source.recordId), source.text)

    private fun history(context: DietTaskContext, extracted: DietExtractCheckpoint): List<ExtractedEvent> =
        context.sources.sortedWith(compareBy({ it.ref.sourceId }, { it.ref.sourceVersion })).mapNotNull { source ->
            if (isDinnerQuestion(source.text)) return@mapNotNull null
            val event = if (source.ref == context.target.ref) extracted.event else extract(source)
            event.takeIf { "饮食" in it.tags }
        }

    private fun explicitPreference(text: String, context: DietTaskContext, owner: String): ExplicitPreference? {
        val scope = DietVocabulary.parseCorrectionScope(text) ?: return null
        val direction = DietVocabulary.correctionDirection(text) ?: return null
        val source = context.sources.singleOrNull { it.recordId == owner && it.text == text }
        val evidence = source?.let {
            EvidenceRef(EvidenceSource(it.ref.sourceId, it.ref.sourceVersion, owner),
                CodePointSpan(0, text.codePointCount(0, text.length)), "explicit_rule")
        }
        return ExplicitPreference("weekday_overtime_convenience", direction, scope, evidence)
    }

    private fun CapturedSource.stageSource() = StageSource(recordId, ref.sourceId, ref.sourceVersion)
    private fun EvidenceSource.stageSource() = StageSource(recordId, sourceId, sourceVersion)
}
