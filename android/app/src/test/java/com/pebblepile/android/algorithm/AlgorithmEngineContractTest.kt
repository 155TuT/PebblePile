package com.pebblepile.android.algorithm

import org.junit.Assert.assertEquals
import org.junit.Test

class AlgorithmEngineContractTest {
    private val scene = DefaultDietScenePackage.scenePackage.copy(minIndependentSupports = 3)
    private val query = QueryConstraint("mon", overtime = true, weekend = false, budgetCny = 20)

    @Test fun manifestRecordsActualStagesAndConfiguration() {
        val engine = AlgorithmEngine(
            scene = scene,
            extractorFactory = { pack, port ->
                object : EventExtractor by RuleEventExtractor(pack, port) {
                    override val version = "test-extractor"
                }
            },
            updater = object : PreferenceUpdate by PreferenceUpdater(scene) {
                override val version = "test-updater"
            },
            ranker = object : DinnerRanker {
                override val version = "test-ranker"
                override fun rank(constraint: QueryConstraint, preferences: List<PreferenceCandidate>,
                    candidates: List<MenuItem>, history: List<ExtractedEvent>): RankResult {
                    assertEquals(listOf("source"), history.flatMap { event -> event.evidence.map { it.source.sourceId } }.distinct())
                    return RankResult(emptyList(), emptyList(), emptyList(), candidates.map { it.id })
                }
            },
        )
        val event = engine.extract(EvidenceSource("source", 1, "record"), "周一加班，我吃了十八元的热汤面。")
        val result = engine.dinnerQuery(query, listOf(event), ManualRevisions())
        assertEquals(scene.menu.map { it.id }, result.result.filteredOut)
        assertEquals("test-extractor", result.manifest.extractor)
        assertEquals("test-updater", result.manifest.updater)
        assertEquals("test-ranker", result.manifest.ranker)
        assertEquals(3, result.manifest.minIndependentSupports)
    }

    @Test fun rankingAlreadyExtractedEventsDoesNotChargePastModelCalls() {
        val port = object : InferencePort {
            override val modelId = "test-port"
            override fun suggestExtraction(source: EvidenceSource, text: String, scene: ScenePackage): ExtractedEvent? = null
        }
        val engine = AlgorithmEngine(inference = port)
        val event = engine.extract(EvidenceSource("s", 1, "r"), "今天周一加班，晚饭吃了热汤面。")
        repeat(2) {
            val queryResult = engine.dinnerQuery(query, listOf(event), ManualRevisions())
            assertEquals(0, queryResult.inferenceCalls)
            assertEquals(0, queryResult.manifest.inferenceCalls)
        }
        assertEquals(1, engine.manifest().inferenceCalls)
        assertEquals(1, engine.inferenceCalls)
    }
}
