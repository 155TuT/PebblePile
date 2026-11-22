package com.pebblepile.android.algorithm

/**
 * #9 饮食场景算法的领域模型。
 *
 * 算法在写事务外计算，只传普通数据类；Android 生命周期、SQLite 表结构和模型 SDK
 * 分别留在各自模块。证据定位沿用 #6 契约：`sourceId`、`sourceVersion` 与按
 * Unicode 码点计数的半开区间 `[start, end)`。
 */

/** 一次来源版本的定位信息。 */
data class EvidenceSource(
    val sourceId: String,
    val sourceVersion: Long,
    val recordId: String,
)

/** Unicode 码点半开区间 `[start, end)`。 */
data class CodePointSpan(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "span start must be >= 0: $start" }
        require(end >= start) { "span end must be >= start: $end" }
    }

    /** 按码点区间从原文取出引文；Kotlin 内部先换算成 UTF-16 下标再 substring。 */
    fun quoteIn(text: String): String {
        val utf16Start = text.offsetByCodePoints(0, start)
        val utf16End = text.offsetByCodePoints(0, end)
        return text.substring(utf16Start, utf16End)
    }
}

/** 一条证据：来源、版本、原话区间与该区间支持的事实角色。 */
data class EvidenceRef(
    val source: EvidenceSource,
    val span: CodePointSpan,
    val role: String,
)

/**
 * 饮食场景抽取事实。`null` 表示原文未交代该信息，不补造。
 *
 * 字段口径对齐 `docs/testing/issue10/fixtures.json` 的 `expectedFacts`。
 */
data class DietFacts(
    val scenario: String = "diet",
    val overtime: Boolean? = null,
    val weekend: Boolean? = null,
    val budgetCny: Int? = null,
    val priceCny: Int? = null,
    val meal: String? = null,
    val durationMinutes: Int? = null,
    val prepNeeded: Boolean? = null,
    val cleanupNeeded: Boolean? = null,
    val convenienceMentioned: Boolean = false,
    val willingCookMinutes: Int? = null,
    val willingToCleanUp: Boolean? = null,
    val satisfaction: String? = null,
    val exceptionFor: String? = null,
)

/** [RuleEventExtractor.extract] 的输出。 */
data class ExtractedEvent(
    /** 归并键：同一真实经历的重复分享归并到同一键，计数时只算一次。 */
    val eventKey: String,
    val observationType: ObservationType,
    val tags: List<String>,
    val facts: DietFacts,
    val evidence: List<EvidenceRef>,
    val rawText: String,
    /** 转述场景下的经历者，本人经历留空。 */
    val subject: String? = null,
    /** 事件发生的星期（mon…sun），无法识别时留空。 */
    val weekday: String? = null,
)

/** 条件范围：工作日集合、是否加班、是否周末。 */
data class ConditionScope(
    val weekdays: Set<String> = emptySet(),
    val overtime: Boolean? = null,
    val weekend: Boolean? = null,
) {
    /** 判断当前提问约束是否落入该范围。空集合表示该维度不限定。 */
    fun matches(weekday: String, queryOvertime: Boolean, queryWeekend: Boolean): Boolean {
        if (overtime != null && overtime != queryOvertime) return false
        if (weekend != null && weekend != queryWeekend) return false
        if (weekdays.isNotEmpty() && weekday !in weekdays) return false
        return true
    }
}

/** 一条条件偏好候选。 */
data class PreferenceCandidate(
    val preferenceId: String,
    val preferenceKey: String,
    /** `convenience`（优先省事）或 `leisurely`（愿意慢慢做饭）。 */
    val direction: String,
    val scope: ConditionScope,
    val status: PreferenceStatus,
    val support: List<EvidenceRef>,
    val counterExamples: List<EvidenceRef>,
    val supportEventKeys: List<String>,
    val note: String = "",
)

/** [CandidateRanker.rank] 输出的单个排序候选。 */
data class RankedCandidate(
    val candidateId: String,
    val score: Double,
    val matchedConditions: List<String>,
    val evidence: List<EvidenceRef>,
    val reason: String,
)

/** rank 输入的当前提问约束。 */
data class QueryConstraint(
    val weekday: String,
    val overtime: Boolean,
    val weekend: Boolean,
    val budgetCny: Int?,
    val cookMinutes: Int? = null,
)

/** 离线随包的一道晚餐候选。 */
data class MenuItem(
    val id: String,
    val name: String,
    val priceCny: Int,
    val prepMinutes: Int,
    val cleanupNeeded: Boolean,
    /** 0..10，省事程度；越高越省时、免备菜、免洗锅。 */
    val convenienceScore: Int,
    /** 与历史经历菜名归一后的名称，用于证据关联加分。 */
    val matchedMeal: String? = null,
)

/** 饮食词表条目：规范词、别名与标签角色。 */
data class VocabEntry(val canonical: String, val aliases: List<String>, val role: String)

/** 场景包：词表、候选、特征与规则配置，版本随 [AlgorithmManifest] 发布。 */
data class ScenePackage(
    val sceneId: String,
    val version: String,
    val labels: List<VocabEntry>,
    val menu: List<MenuItem>,
    /** 形成一条推断偏好所需的最少独立实际经历数。 */
    val minIndependentSupports: Int,
    val currency: String = "CNY",
)

/** rank 的完整结果。 */
data class RankResult(
    val items: List<RankedCandidate>,
    /** 本次排序实际采用的偏好标识。 */
    val appliedPreferences: List<String>,
    /** 范围未命中、本次未参与加权的偏好标识。 */
    val unmatchedPreferences: List<String>,
    /** 被硬约束（如预算）筛掉的候选标识。 */
    val filteredOut: List<String>,
)

/** 本人明确设置的长期条件（对应「以后按此条件」）。 */
data class ExplicitPreference(
    val preferenceKey: String,
    val direction: String,
    val scope: ConditionScope,
    val evidence: EvidenceRef?,
)

/** 人工修订：被排除的标签与本人明确设置，优先于自动推断。 */
data class ManualRevisions(
    val excludedTags: Set<String> = emptySet(),
    val explicit: List<ExplicitPreference> = emptyList(),
)

/** 上一版有效快照，update 时沿用其状态。 */
data class PreferenceSnapshot(val preferences: List<PreferenceCandidate> = emptyList())

/** 一次晚餐查询的完整产物：排序结果、版本清单、耗时与模型调用量。 */
data class DinnerQueryResult(
    val result: RankResult,
    val manifest: AlgorithmManifest,
    val elapsedMillis: Long,
    val inferenceCalls: Int,
)
