package com.pebblepile.android.algorithm

import java.util.concurrent.atomic.AtomicInteger

/** 调度模块分别调用抽取、更新和排序，回放时可单独替换其中一项。 */
class AlgorithmEngine(
    val scene: ScenePackage = DefaultDietScenePackage.scenePackage,
    private val inference: InferencePort = OfflineInferencePort,
    private val datasetVersion: String = "issue10-dinner-v1@0.1.1",
    extractorFactory: (ScenePackage, InferencePort) -> EventExtractor = ::RuleEventExtractor,
    private val updater: PreferenceUpdate = PreferenceUpdater(scene),
    private val ranker: DinnerRanker = CandidateRanker(scene),
) {
    private val calls = AtomicInteger()
    /** 此引擎实际向已配置的模型端口发起的累计请求。 */
    val inferenceCalls: Int get() = calls.get()

    private val extractor = extractorFactory(scene, if (inference === OfflineInferencePort) inference else
        object : InferencePort by inference {
            override fun suggestExtraction(source: EvidenceSource, text: String, scene: ScenePackage): ExtractedEvent? {
                calls.incrementAndGet()
                return inference.suggestExtraction(source, text, scene)
            }
        }
    )

    fun extract(source: EvidenceSource, text: String): ExtractedEvent = extractor.extract(source, text)

    fun update(
        events: List<ExtractedEvent>,
        revisions: ManualRevisions,
        snapshot: PreferenceSnapshot? = null,
    ): List<PreferenceCandidate> = updater.update(events, revisions, snapshot)

    fun rank(
        constraint: QueryConstraint,
        preferences: List<PreferenceCandidate>,
        history: List<ExtractedEvent> = emptyList(),
    ): RankResult = ranker.rank(constraint, preferences, scene.menu, history)

    /** 已抽取的事件可反复查询。更新和排序在本地执行，本次查询的模型调用数为零。 */
    fun dinnerQuery(
        constraint: QueryConstraint,
        events: List<ExtractedEvent>,
        revisions: ManualRevisions,
        snapshot: PreferenceSnapshot? = null,
    ): DinnerQueryResult {
        val started = System.nanoTime()
        val result = rank(constraint, update(events, revisions, snapshot), events)
        return DinnerQueryResult(
            result = result,
            manifest = manifest(callCount = 0),
            elapsedMillis = (System.nanoTime() - started) / 1_000_000L,
            inferenceCalls = 0,
        )
    }

    fun manifest(): AlgorithmManifest = manifest(inferenceCalls)

    private fun manifest(callCount: Int) = AlgorithmManifest(
        algorithmVersion = "local-rules-0.1.0",
        scenePackVersion = scene.version,
        datasetVersion = datasetVersion,
        extractor = extractor.version,
        updater = updater.version,
        ranker = ranker.version,
        inferenceModel = inference.modelId,
        inferenceCalls = callCount,
        description = "饮食经历抽取、条件偏好更新与候选排序",
        minIndependentSupports = scene.minIndependentSupports,
    )
}
