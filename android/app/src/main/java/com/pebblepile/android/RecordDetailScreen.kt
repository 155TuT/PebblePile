package com.pebblepile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class DetailNotice(
    val message: String,
    val state: PebbleFeedbackState,
    val retry: (() -> Unit)? = null,
)

@Composable
internal fun RecordDetailScreen(
    api: CoreApi,
    recordId: String,
    onChanged: () -> Unit,
    onMessage: (String, PebbleFeedbackState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var record by remember(recordId) { mutableStateOf<PebbleRecord?>(null) }
    var futureCondition by remember(recordId) { mutableStateOf<ConditionSetting?>(null) }
    var loading by remember(recordId) { mutableStateOf(true) }
    var choice by rememberSaveable(recordId) { mutableStateOf("") }
    var experience by rememberSaveable(recordId) { mutableStateOf("") }
    var conditionDraft by rememberSaveable(recordId) { mutableStateOf("") }
    var correctionScope by rememberSaveable(recordId) { mutableStateOf(CorrectionScope.THIS_TIME) }
    var editingRecordRevision by rememberSaveable(recordId) { mutableLongStateOf(0L) }
    var editingPreferenceRevision by rememberSaveable(recordId) { mutableLongStateOf(0L) }
    var showCorrection by rememberSaveable(recordId) { mutableStateOf(false) }
    var feedbackSaving by remember(recordId) { mutableStateOf(false) }
    var correctionSaving by remember(recordId) { mutableStateOf(false) }
    var busyTagId by remember(recordId) { mutableStateOf<String?>(null) }
    var feedbackOperation by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    var correctionOperation by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    val tagOperations = remember(recordId) { mutableMapOf<String, String>() }
    var conflictMessage by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    var evidenceId by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    var evidenceSource by remember(recordId) { mutableStateOf<PebbleRecord?>(null) }
    var notice by remember(recordId) { mutableStateOf<DetailNotice?>(null) }

    suspend fun reload() {
        loading = true
        val detail = withContext(Dispatchers.IO) { api.getRecordDetail(recordId) }
        record = detail?.record
        futureCondition = detail?.futureCondition
        editingRecordRevision = detail?.record?.revision ?: 0L
        editingPreferenceRevision = detail?.futureCondition?.revision ?: 0L
        loading = false
    }

    LaunchedEffect(recordId) { reload() }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PebbleFeedback(PebbleFeedbackState.PROCESSING, detail = "正在读取记录")
        }
        return
    }
    val item = record
    if (item == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            PebbleCard {
                PebbleText("没有找到这条记录")
                PebbleText("返回记录库后重试。", color = PebbleVisuals.Muted)
            }
        }
        return
    }

    fun openCorrection() {
        conditionDraft = item.currentCondition
        correctionScope = item.conditionScope ?: CorrectionScope.THIS_TIME
        editingRecordRevision = item.revision
        editingPreferenceRevision = futureCondition?.revision ?: 0L
        correctionOperation = null
        showCorrection = true
    }

    fun excludeTag(tagId: String) {
        if (busyTagId != null) return
        busyTagId = tagId
        val operationId = tagOperations[tagId] ?: UUID.randomUUID().toString().also {
            tagOperations[tagId] = it
        }
        scope.launch {
            val receipt = api.reviseTags(item.id, item.revision, tagId, operationId)
            busyTagId = null
            if (receipt.success) {
                tagOperations.remove(tagId)
                notice = DetailNotice(receipt.message, PebbleFeedbackState.UPDATED)
                reload()
                onChanged()
            } else if (receipt.conflict) {
                conflictMessage = receipt.message
            } else {
                notice = DetailNotice(receipt.message, PebbleFeedbackState.FAILED) { excludeTag(tagId) }
            }
        }
    }

    fun submitFeedback() {
        if (feedbackSaving) return
        feedbackSaving = true
        val operationId = feedbackOperation ?: UUID.randomUUID().toString().also { feedbackOperation = it }
        scope.launch {
            val receipt = api.recordOutcome(item.id, item.revision, choice, experience, operationId)
            feedbackSaving = false
            if (receipt.success) {
                feedbackOperation = null
                choice = ""
                experience = ""
                notice = DetailNotice(receipt.message, PebbleFeedbackState.SAVED)
                reload()
                onChanged()
            } else if (receipt.conflict) {
                conflictMessage = receipt.message
            } else {
                notice = DetailNotice(receipt.message, PebbleFeedbackState.FAILED, ::submitFeedback)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        SectionTitle("原始记录")
        PebbleCard {
            PebbleQuote(
                item.rawText,
                source = "${item.capturedAt} · ${if (item.captureMethod == "android_share") "来自系统分享" else "应用内记录"}",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PebbleStatusBadge(PebbleFeedbackState.SAVED, label = item.status)
                if (item.tags.any { it.excluded }) {
                    PebbleStatusBadge(PebbleFeedbackState.REVIEW, label = "有已排除标签")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("语义标签")
        PebbleCard {
            if (item.tags.isEmpty()) {
                PebbleText("暂时没有标签", color = PebbleVisuals.Muted)
            }
            item.tags.forEach { tag ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        PebbleTag(
                            label = if (tag.excluded) "${tag.name} · 已排除" else tag.name,
                            origin = "自动整理 · ${tag.algorithmVersion}",
                            needsReview = false,
                        )
                    }
                    if (!tag.excluded) {
                        PebbleLink(
                            label = if (busyTagId == tag.id) "排除中…" else "排除标签",
                            onClick = { excludeTag(tag.id) },
                            enabled = busyTagId == null,
                        )
                    }
                }
                PebbleText("依据：${tag.sourceQuote}", color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("建议与依据")
        if (item.suggestions.isEmpty()) {
            PebbleCard { PebbleText("暂时没有建议", color = PebbleVisuals.Muted) }
        }
        item.suggestions.forEach { suggestion ->
            PebbleEvidenceCard(
                condition = suggestion.title,
                onCorrect = ::openCorrection,
                origin = suggestion.reason,
            ) {
                suggestion.evidence.forEach { evidence ->
                    PebbleEvidenceSource(
                        quote = "“${evidence.quote}”",
                        sourceLabel = "${evidence.title} · ${evidence.recordDate}",
                        onOpenSource = {
                            evidenceId = evidence.id
                            evidenceSource = null
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("实际反馈")
        PebbleCard {
            if (item.outcomes.isEmpty()) {
                PebbleText("还没有补记实际选择和体验。", color = PebbleVisuals.Muted)
            }
            item.outcomes.forEach { outcome ->
                PebbleQuote(
                    "实际选择：${outcome.choice}\n实际体验：${outcome.experience}",
                    source = outcome.createdAt,
                )
            }
            PebbleInput(
                value = choice,
                onValueChange = {
                    choice = it
                    feedbackOperation = null
                },
                label = "实际选择",
            )
            PebbleInput(
                value = experience,
                onValueChange = {
                    experience = it
                    feedbackOperation = null
                },
                label = "实际体验／感受",
            )
            PebbleButton(
                label = "保存实际反馈",
                onClick = ::submitFeedback,
                style = PebbleButtonStyle.Primary,
                enabled = choice.isNotBlank() || experience.isNotBlank(),
                busy = feedbackSaving,
                busyLabel = "保存中",
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("纠正适用条件")
        PebbleCard {
            PebbleText(
                if (item.currentCondition.isBlank()) "尚未设置当前事项条件" else "当前条件：${item.currentCondition}",
            )
            item.conditionScope?.let {
                PebbleText("适用范围：${it.label}", color = PebbleVisuals.Moss)
            }
            futureCondition?.let {
                PebbleText("长期条件：${it.text}", color = PebbleVisuals.Info)
            }
            PebbleText(
                "可以选择只调整当前事项，或将条件保存为以后适用的明确偏好。",
                color = PebbleVisuals.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            PebbleButton(
                "修改条件",
                ::openCorrection,
                style = PebbleButtonStyle.Secondary,
                icon = PebbleGlyph.Edit,
            )
        }

        notice?.let {
            Spacer(Modifier.height(12.dp))
            PebbleFeedback(it.state, detail = it.message, onRetry = it.retry)
        }
        Spacer(Modifier.height(28.dp))
    }

    val evidence = item.suggestions.flatMap { it.evidence }.firstOrNull { it.id == evidenceId }
    if (evidence != null) {
        LaunchedEffect(evidence.id) {
            evidenceSource = withContext(Dispatchers.IO) { api.getRecord(evidence.sourceRecordId) }
        }
        PebbleDialog(
            title = "来源与原话",
            onDismiss = { evidenceId = null },
            confirmLabel = "关闭",
            cancelLabel = null,
            onConfirm = { evidenceId = null },
        ) {
            PebbleText("${evidence.title} · ${evidence.recordDate}", color = PebbleVisuals.Moss)
            PebbleQuote("“${evidence.quote}”", source = "引用片段")
            evidenceSource?.let {
                if (it.rawText != evidence.quote) {
                    PebbleText("来源记录", style = MaterialTheme.typography.labelLarge)
                    PebbleQuote(it.rawText, source = it.capturedAt)
                }
            }
        }
    }

    if (showCorrection && conflictMessage == null) {
        PebbleDialog(
            title = "纠正条件",
            onDismiss = { if (!correctionSaving) showCorrection = false },
            confirmLabel = if (correctionSaving) "处理中…" else "确认修改",
            confirmEnabled = conditionDraft.isNotBlank() && !correctionSaving,
            onConfirm = {
                if (correctionSaving || conditionDraft.isBlank()) return@PebbleDialog
                correctionSaving = true
                val operationId = correctionOperation ?: UUID.randomUUID().toString().also {
                    correctionOperation = it
                }
                scope.launch {
                    val receipt = api.reviseMemory(
                        item.id,
                        editingRecordRevision,
                        conditionDraft,
                        correctionScope,
                        operationId,
                        basePreferenceRevision = editingPreferenceRevision.takeIf {
                            correctionScope == CorrectionScope.FUTURE
                        },
                    )
                    correctionSaving = false
                    if (receipt.success) {
                        correctionOperation = null
                        showCorrection = false
                        notice = DetailNotice(receipt.message, PebbleFeedbackState.UPDATED)
                        reload()
                        onChanged()
                    } else if (receipt.conflict) {
                        conflictMessage = receipt.message
                    } else {
                        notice = DetailNotice(receipt.message, PebbleFeedbackState.FAILED)
                    }
                }
            },
        ) {
            if (item.currentCondition.isNotBlank()) {
                PebbleText("当前条件：${item.currentCondition}", color = PebbleVisuals.Muted)
            }
            if (correctionScope == CorrectionScope.FUTURE) {
                futureCondition?.let { PebbleText("当前长期条件：${it.text}", color = PebbleVisuals.Muted) }
            }
            PebbleInput(
                value = conditionDraft,
                onValueChange = {
                    conditionDraft = it
                    correctionOperation = null
                },
                label = "新的适用条件",
            )
            PebbleText("影响范围", style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CorrectionScope.entries.forEach { option ->
                    PebbleChoiceTile(
                        label = option.label,
                        selected = correctionScope == option,
                        onSelect = {
                            correctionScope = option
                            correctionOperation = null
                        },
                    )
                }
            }
        }
    }

    conflictMessage?.let { message ->
        PebbleDialog(
            title = "检测到修订冲突",
            onDismiss = { conflictMessage = null },
            confirmLabel = "查看最新版本",
            cancelLabel = null,
            onConfirm = {
                scope.launch {
                    conflictMessage = null
                    reload()
                    onChanged()
                }
            },
        ) {
            PebbleText(message)
            PebbleText("当前输入草稿仍保留，请核对最新内容后再次提交。", color = PebbleVisuals.Muted)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    PebbleText(text, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
}
