package com.pebblepile.android.data

data class RecordTag(val id: String, val name: String, val sourceQuote: String, val algorithmVersion: String, val excluded: Boolean = false)
data class EvidenceItem(val id: String, val title: String, val quote: String, val recordDate: String)
data class SuggestionItem(val id: String, val title: String, val reason: String, val evidence: List<EvidenceItem>)
data class OutcomeItem(val id: String, val choice: String, val experience: String, val createdAt: String)

data class PebbleRecord(
    val id: String,
    val rawText: String,
    val capturedAt: String,
    val captureMethod: String,
    val revision: Long = 1,
    val tags: List<RecordTag> = emptyList(),
    val suggestions: List<SuggestionItem> = emptyList(),
    val outcomes: List<OutcomeItem> = emptyList(),
    val currentCondition: String = "",
    val status: String = "已保存"
)

enum class CorrectionScope { THIS_TIME, FUTURE }

data class ApiReceipt(
    val success: Boolean,
    val recordId: String? = null,
    val revision: Long? = null,
    val message: String = "",
    val conflict: Boolean = false,
    val currentRevision: Long? = null
)
