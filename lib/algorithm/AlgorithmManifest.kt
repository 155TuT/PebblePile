package com.pebblepile.android.algorithm

/** 观察类型：本人实际经历、本人计划、他人转述、其他（收藏等）。 */
enum class ObservationType {
    SELF_ACTUAL,
    SELF_PLAN,
    REPORTED,
    OTHER;
}

/**
 * 偏好状态：
 * - [EXPLICIT] 本人明确设置，优先于一切推断；
 * - [INFERRED] 达到最少独立经历数的推断偏好，可参与低风险排序；
 * - [WEAK] 证据不足，仅保留待核对；
 * - [EXCLUDED] 被人工排除，重新整理也不复现。
 */
enum class PreferenceStatus { EXPLICIT, INFERRED, WEAK, EXCLUDED }

/**
 * 算法能力清单：各函数实现、配置与场景包版本，随每次建议发布，
 * 供 #8 调度记录快照、#10 回归比对新旧实现。
 */
data class AlgorithmManifest(
    val algorithmVersion: String,
    val scenePackVersion: String,
    val datasetVersion: String,
    val extractor: String,
    val updater: String,
    val ranker: String,
    /** 本次实际使用的模型标识；本地规则为 `local-rule-only`。 */
    val inferenceModel: String,
    /** 本次处理的远程模型调用次数；离线规则恒为 0。 */
    val inferenceCalls: Int,
    val description: String,
    val minIndependentSupports: Int,
)

/**
 * 模型适配层。本地规则与经授权的远程模型返回同构的 [ExtractedEvent]；
 * 远程不可用时返回 `null`，算法仍可只凭离线规则走完流程。
 */
interface InferencePort {
    val modelId: String

    /** 让模型提出抽取候选；实现必须在写事务外调用，不携带授权范围外的材料。 */
    fun suggestExtraction(source: EvidenceSource, text: String, scene: ScenePackage): ExtractedEvent?
}

/** 离线占位端口：不发起任何网络请求。 */
object OfflineInferencePort : InferencePort {
    override val modelId: String = "local-rule-only"
    override fun suggestExtraction(
        source: EvidenceSource,
        text: String,
        scene: ScenePackage,
    ): ExtractedEvent? = null
}
