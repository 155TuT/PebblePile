package com.pebblepile.android.algorithm

/**
 * #9 装配入口：把 [RuleEventExtractor]、[PreferenceUpdater]、[CandidateRanker]
 * 组合成可单独替换、单独测试的算法管线。调度方（#8）只调用这里的方法，
 * 结果附原话位置、场景包与算法版本，耗时和模型调用量随产物登记。
 */
class AlgorithmEngine(
    val scene: ScenePackage = DefaultDietScenePackage.scenePackage,
    private val inference: InferencePort = OfflineInferencePort,
    private val datasetVersion: String = "issue10-dinner-v1@0.1.1",
) {
    private val extractor = RuleEventExtractor(scene, inference)
    private val updater = PreferenceUpdater(scene)
    private val ranker = CandidateRanker(scene)

    /** 本次处理实际发生的远程模型调用次数；离线规则恒为 0。 */
    var inferenceCalls: Int = 0
        private set

    /** 对应 `extract(来源版本, 原文, 场景包)`。 */
    fun extract(source: EvidenceSource, text: String): ExtractedEvent {
        if (inference !== OfflineInferencePort) inferenceCalls++
        return extractor.extract(source, text)
    }

    /** 对应 `update(有效证据, 人工修订, 当前快照)`。 */
    fun update(
        events: List<ExtractedEvent>,
        revisions: ManualRevisions,
        snapshot: PreferenceSnapshot? = null,
    ): List<PreferenceCandidate> = updater.update(events, revisions, snapshot)

    /** 对应 `rank(当前约束, 有效偏好, 候选清单)`。 */
    fun rank(
        constraint: QueryConstraint,
        preferences: List<PreferenceCandidate>,
        historyMeals: List<String> = emptyList(),
    ): RankResult = ranker.rank(constraint, preferences, historyMeals = historyMeals)

    /** 一站式晚餐查询：更新偏好 → 排序 → 记录版本、耗时与调用量。 */
    fun dinnerQuery(
        constraint: QueryConstraint,
        events: List<ExtractedEvent>,
        revisions: ManualRevisions,
        snapshot: PreferenceSnapshot? = null,
    ): DinnerQueryResult {
        val started = System.nanoTime()
        val preferences = update(events, revisions, snapshot)
        val historyMeals = events.mapNotNull { it.facts.meal }
        val result = rank(constraint, preferences, historyMeals)
        val elapsedMillis = (System.nanoTime() - started) / NANOS_PER_MILLIS
        return DinnerQueryResult(
            result = result,
            manifest = manifest(),
            elapsedMillis = elapsedMillis,
            inferenceCalls = inferenceCalls,
        )
    }

    fun manifest(): AlgorithmManifest = AlgorithmManifest(
        algorithmVersion = ALGORITHM_VERSION,
        scenePackVersion = scene.version,
        datasetVersion = datasetVersion,
        extractor = EXTRACTOR_VERSION,
        updater = UPDATER_VERSION,
        ranker = RANKER_VERSION,
        inferenceModel = inference.modelId,
        inferenceCalls = inferenceCalls,
        description = "本地词表/别名/句式规则抽取，条件计分排序；离线可走完整晚餐流程。",
    )

    private companion object {
        const val ALGORITHM_VERSION = "local-rules-0.1.0"
        const val EXTRACTOR_VERSION = "rule-extractor-0.1.0"
        const val UPDATER_VERSION = "evidence-updater-0.1.0"
        const val RANKER_VERSION = "condition-score-ranker-0.1.0"
        const val NANOS_PER_MILLIS = 1_000_000L
    }
}
